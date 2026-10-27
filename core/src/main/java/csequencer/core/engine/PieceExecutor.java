package csequencer.core.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.logic.CNode;
import csequencer.core.model.AudioRef;
import csequencer.core.model.Piece;
import csequencer.core.model.Policies;
import csequencer.core.model.Timeline;
import csequencer.core.pieces.ParamSpec;
import csequencer.core.pieces.ParamType;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;
import csequencer.core.platform.ActionAdapter;
import csequencer.core.security.CommandFilter;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Interprets pieces. Flow/logic/data/event/audio/debug pieces run here; actions go to the adapter. */
final class PieceExecutor {
    private final Engine engine;
    private final Evaluator ev;

    PieceExecutor(Engine engine, Evaluator ev) {
        this.engine = engine;
        this.ev = ev;
    }

    // ── Entry point ────────────────────────────────────────────────────────

    void execute(Fiber f, Piece p) {
        Task task = f.task;
        PieceType type = PieceRegistry.piece(p.type);
        if (type == null) {
            task.logOnce("unknown:" + p.type, "warn", "Unsupported piece type '" + p.type + "' skipped");
            task.actionsFailed++;
            return;
        }
        if (!engine.platform.info().supports(type.capability())) {
            failPiece(f, p, "'" + type.name() + "' is not supported on " + engine.platform.info().loader() + " " + engine.platform.info().minecraftVersion());
            return;
        }
        try {
            if (type.dispatched()) {
                dispatch(f, p, type);
                return;
            }
            runCore(f, p, type);
        } catch (RuntimeException e) {
            failPiece(f, p, type.name() + " failed: " + e);
        }
    }

    private void runCore(Fiber f, Piece p, PieceType type) {
        Task task = f.task;
        Scope scope = Scope.of(f);
        Map<String, Value> a = ev.resolveArgs(type.params(), p.params, scope);
        switch (p.type) {
            case "flow.start" -> ok(task, "start");
            case "flow.end" -> {
                String msg = a.get("message").asText();
                boolean fail = "fail".equalsIgnoreCase(a.get("result").asText());
                engine.finish(task, fail ? TaskState.FAILED : TaskState.COMPLETED, msg.isEmpty() ? (fail ? "ended with failure" : "ended") : msg);
            }
            case "flow.stop_branch" -> f.complete();
            case "flow.delay" -> {
                int t = (int) a.get("ticks").asNumber();
                if (t > 0) f.suspend(new DelayWait(task.tickCount + t));
            }
            case "flow.wait_until_tick" -> {
                int t = (int) a.get("tick").asNumber();
                if (task.tickCount < t) f.suspend(new DelayWait(t));
            }
            case "flow.wait_until" -> {
                CNode cond = ev.compile(task, p.id + "#condition", p.params.get("condition"));
                String key = "wait:" + p.id + ":" + f.id;
                if (ev.test(cond, scope, key)) return;
                int timeout = (int) a.get("timeout").asNumber();
                f.suspend(new CondWait(cond, key, timeout > 0 ? task.tickCount + timeout : -1, Math.max(1, (int) a.get("interval").asNumber()),
                        str(p, "onTimeout"), a.get("failOnTimeout").asBool()));
            }
            case "flow.call" -> {
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function '" + str(p, "function") + "' not found"); return; }
                Map<String, Value> inputs = inputs(p.params.get("args"), scope);
                CallHandle h = task.spawnCall(f, tl, inputs, f.subject, f.event);
                if (h == null) { failPiece(f, p, "Could not start function '" + tl.name + "'"); return; }
                String prefix = a.get("resultVar").asText();
                if (a.get("await").asBool()) f.suspend(new JoinWait(f, List.of(h), "all", false, () -> copyOutputs(h, f, prefix)));
            }
            case "flow.parallel" -> {
                List<CallHandle> hs = new ArrayList<>();
                for (JsonElement e : arr(p, "functions")) {
                    Timeline tl = fn(task, e.isJsonPrimitive() ? e.getAsString() : J.str(e.getAsJsonObject(), "target", ""));
                    if (tl == null) { task.log("warn", "Parallel: function '" + e + "' not found"); continue; }
                    CallHandle h = task.spawnCall(f, tl, Map.of(), f.subject, f.event);
                    if (h != null) hs.add(h);
                }
                String join = a.get("join").asText().toLowerCase(Locale.ROOT);
                if (!hs.isEmpty() && !"none".equals(join)) f.suspend(new JoinWait(f, hs, join, a.get("cancelOthers").asBool(), null));
            }
            case "flow.repeat" -> {
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function not found"); return; }
                int count = (int) Math.min(a.get("count").asNumber(), engine.settings.limits.maxLoopIterations);
                String idx = a.get("indexVar").asText();
                int[] i = {0};
                f.suspend(new LoopWait(f, (int) a.get("interval").asNumber(), "repeat", () -> {
                    if (i[0] >= count) return null;
                    Map<String, Value> in = new HashMap<>();
                    if (!idx.isEmpty()) in.put(idx, Value.of(i[0]));
                    i[0]++;
                    return task.spawnCall(f, tl, in, f.subject, f.event);
                }, () -> i[0] + "/" + count));
            }
            case "flow.while" -> {
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function not found"); return; }
                CNode cond = ev.compile(task, p.id + "#condition", p.params.get("condition"));
                String mode = a.get("mode").asText().toLowerCase(Locale.ROOT);
                int max = (int) Math.min(a.get("maxIterations").asNumber(), engine.settings.limits.maxLoopIterations);
                String idx = a.get("indexVar").asText();
                String key = "while:" + p.id + ":" + f.id;
                int[] i = {0};
                f.suspend(new LoopWait(f, 0, "while", () -> {
                    boolean go;
                    if ("do_while".equals(mode) && i[0] == 0) go = true;
                    else {
                        boolean c = ev.test(cond, Scope.of(f), key);
                        go = "until".equals(mode) ? !c : c;
                    }
                    if (!go) return null;
                    if (i[0] >= max) {
                        task.log("warn", "Loop '" + p.displayName() + "' stopped at its iteration limit (" + max + ")");
                        task.warn("loop iteration limit reached");
                        return null;
                    }
                    Map<String, Value> in = new HashMap<>();
                    if (!idx.isEmpty()) in.put(idx, Value.of(i[0]));
                    i[0]++;
                    return task.spawnCall(f, tl, in, f.subject, f.event);
                }, () -> "iteration " + i[0]));
            }
            case "flow.foreach" -> {
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function not found"); return; }
                List<Value> items = a.get("source").asList();
                int max = (int) Math.min(a.get("maxItems").asNumber(), engine.settings.limits.maxLoopIterations);
                if (items.size() > max) {
                    task.log("warn", "For each: list truncated to " + max + " items");
                    items = items.subList(0, max);
                }
                String var = a.get("itemVar").asText();
                boolean asSubject = a.get("asSubject").asBool();
                List<Value> list = List.copyOf(items);
                if ("parallel".equalsIgnoreCase(a.get("mode").asText())) {
                    List<CallHandle> hs = new ArrayList<>();
                    for (int k = 0; k < list.size(); k++) {
                        CallHandle h = spawnItem(f, tl, var, list.get(k), k, asSubject);
                        if (h != null) hs.add(h);
                    }
                    if (!hs.isEmpty()) f.suspend(new JoinWait(f, hs, "all", false, null));
                } else {
                    int[] i = {0};
                    f.suspend(new LoopWait(f, 0, "for each", () -> {
                        if (i[0] >= list.size()) return null;
                        int k = i[0]++;
                        return spawnItem(f, tl, var, list.get(k), k, asSubject);
                    }, () -> i[0] + "/" + list.size()));
                }
            }
            case "flow.jump" -> {
                int max = (int) a.get("maxJumps").asNumber();
                int n = f.jumps.getOrDefault(p.id, 0);
                if (n >= max) {
                    task.logOnce("jump:" + p.id + f.id, "info", "Jump '" + p.displayName() + "' reached its limit (" + max + ")");
                    return;
                }
                f.jumps.put(p.id, n + 1);
                int target = Math.max(0, (int) a.get("tick").asNumber());
                int idx = 0;
                while (idx < f.pieces.size() && f.pieces.get(idx).tick < target) idx++;
                f.cursor = idx;
                f.clock = target;
            }
            case "flow.timeout" -> {
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function not found"); return; }
                CallHandle h = task.spawnCall(f, tl, Map.of(), f.subject, f.event);
                if (h == null) return;
                int until = task.tickCount + Math.max(1, (int) a.get("window").asNumber());
                String onTimeout = str(p, "onTimeout");
                Runnable expire = () -> {
                    if (h.done) return;
                    task.log("info", "Timed window '" + p.displayName() + "' closed; cancelling '" + tl.name + "'");
                    task.cancelCall(h, "window closed");
                    Timeline t2 = fn(task, onTimeout);
                    if (t2 != null) task.spawnCall(f.state.ended() ? null : f, t2, Map.of(), f.subject, f.event);
                };
                if (a.get("await").asBool()) f.suspend(new TimeoutWait(f, h, until, expire));
                else task.timers.add(new Timer("timeout:" + p.id + ":" + h.id, until, expire));
            }
            case "flow.random" -> {
                JsonArray outs = arr(p, "outcomes");
                double total = 0;
                for (JsonElement e : outs) if (e.isJsonObject()) total += Math.max(0, J.d(e.getAsJsonObject(), "weight", 1));
                if (total <= 0) return;
                String seed = a.get("seed").asText();
                Random rng = task.random;
                if (!seed.isEmpty()) {
                    int count = task.cooldowns.merge("rnd:" + p.id, 1, Integer::sum);
                    rng = new Random(seed.hashCode() * 31L + count);
                }
                double roll = rng.nextDouble() * total;
                for (JsonElement e : outs) {
                    if (!e.isJsonObject()) continue;
                    double w = Math.max(0, J.d(e.getAsJsonObject(), "weight", 1));
                    if (roll < w) {
                        callTarget(f, J.str(e.getAsJsonObject(), "target", ""), a.get("await").asBool());
                        return;
                    }
                    roll -= w;
                }
            }
            case "flow.cooldown" -> {
                int ticks = (int) a.get("ticks").asNumber();
                String key = "cd:" + (a.get("key").asText().isEmpty() ? p.id : a.get("key").asText())
                        + (a.get("perPlayer").asBool() && f.subject != null ? ":" + f.subject.uuid() : "");
                Integer last = task.cooldowns.get(key);
                if (last != null && task.tickCount - last < ticks) {
                    task.log("info", "Cooldown '" + p.displayName() + "' active (" + (ticks - (task.tickCount - last)) + "t left)");
                    return;
                }
                task.cooldowns.put(key, task.tickCount);
                callTarget(f, str(p, "function"), false);
            }
            case "flow.debounce" -> {
                String key = "db:" + (a.get("key").asText().isEmpty() ? p.id : a.get("key").asText());
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function not found"); return; }
                task.timers.removeIf(t -> t.key.equals(key));
                EntityRef subj = f.subject;
                GameEvent evt = f.event;
                task.timers.add(new Timer(key, task.tickCount + Math.max(1, (int) a.get("ticks").asNumber()), () -> task.spawnCall(null, tl, Map.of(), subj, evt)));
            }
            case "flow.mutex" -> {
                Timeline tl = fn(task, str(p, "function"));
                if (tl == null) { failPiece(f, p, "Function not found"); return; }
                int timeout = (int) a.get("timeout").asNumber();
                f.suspend(new LockWait(f, a.get("lock").asText().isEmpty() ? "main" : a.get("lock").asText(), tl, timeout > 0 ? task.tickCount + timeout : -1));
            }
            case "seq.control" -> sequenceControl(f, p, a);
            case "seq.phase" -> {
                task.manualPhase = a.get("name").asText();
                task.manualNext = a.get("next").asText();
                task.manualPhasePos = task.mainPosition();
            }
            case "logic.if" -> {
                JsonArray branches = arr(p, "branches");
                for (int i = 0; i < branches.size(); i++) {
                    if (!branches.get(i).isJsonObject()) continue;
                    JsonObject b = branches.get(i).getAsJsonObject();
                    CNode c = ev.compile(task, p.id + "#b" + i, b.get("condition"));
                    if (ev.test(c, scope, "if:" + p.id + ":" + i + ":" + f.id)) {
                        task.lastResult = "if → branch " + (i + 1);
                        callTarget(f, J.str(b, "target", ""), a.get("await").asBool());
                        return;
                    }
                }
                task.lastResult = "if → else";
                callTarget(f, str(p, "else"), a.get("await").asBool());
            }
            case "logic.switch" -> {
                Value v = a.get("value");
                for (JsonElement e : arr(p, "cases")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject c = e.getAsJsonObject();
                    Value match = Value.of(ev.render(J.str(c, "match", ""), scope));
                    if (v.looseEquals(match)) {
                        task.lastResult = "switch → " + match.asText();
                        callTarget(f, J.str(c, "target", ""), a.get("await").asBool());
                        return;
                    }
                }
                task.lastResult = "switch → default";
                callTarget(f, str(p, "default"), a.get("await").asBool());
            }
            case "logic.gate" -> {
                CNode c = ev.compile(task, p.id + "#condition", p.params.get("condition"));
                if (ev.test(c, scope, "gate:" + p.id + ":" + f.id)) return;
                switch (a.get("otherwise").asText()) {
                    case "skip_next" -> f.skipNext = true;
                    case "fail" -> f.fail("Gate '" + p.displayName() + "' closed");
                    default -> f.complete();
                }
            }
            case "event.listen" -> {
                if (!roomForListener(task, p)) return;
                CNode filter = ev.compile(task, p.id + "#filter", p.params.get("filter"));
                task.listeners.add(new Listener(engine.nextListenerId++, f.handle, p.id, a.get("eventType").asText(), a.get("signal").asText(), filter,
                        str(p, "function"), a.get("once").asBool(), (int) a.get("maxFires").asNumber(), (int) a.get("cooldown").asNumber(),
                        a.get("perPlayer").asBool(), J.b(p.params, "keepAlive", false), null, null, null));
            }
            case "event.wait" -> {
                if (!roomForListener(task, p)) return;
                CNode filter = ev.compile(task, p.id + "#filter", p.params.get("filter"));
                Listener l = new Listener(engine.nextListenerId++, f.handle, p.id, a.get("eventType").asText(), a.get("signal").asText(), filter,
                        "", true, 1, 0, false, false, f, null, null);
                task.listeners.add(l);
                int timeout = (int) a.get("timeout").asNumber();
                f.suspend(new EventWait(l, timeout > 0 ? task.tickCount + timeout : -1, str(p, "onTimeout"), a.get("storeAs").asText()));
            }
            case "event.on_condition" -> {
                if (!roomForListener(task, p)) return;
                CNode cond = ev.compile(task, p.id + "#condition", p.params.get("condition"));
                Policies.EdgeMode edge = switch (a.get("edge").asText().toLowerCase(Locale.ROOT)) {
                    case "exited" -> Policies.EdgeMode.EXITED;
                    case "changed" -> Policies.EdgeMode.CHANGED;
                    case "level" -> Policies.EdgeMode.LEVEL;
                    default -> Policies.EdgeMode.ENTERED;
                };
                task.watchers.add(new Watcher(engine.nextListenerId++, f.handle, p.id, cond, edge, a.get("over").asText(), str(p, "function"),
                        Math.max(1, (int) a.get("interval").asNumber()), a.get("once").asBool(), J.b(p.params, "fireInitial", false),
                        J.b(p.params, "keepAlive", false), task.tickCount));
            }
            case "track.player" -> {
                String source = a.get("source").asText();
                EntityRef who = switch (source) {
                    case "initiator" -> task.request.initiator;
                    case "event_player" -> f.event == null ? null : f.event.player;
                    case "subject" -> f.subject;
                    case "random_online" -> {
                        List<EntityRef> on = engine.platform.players().online();
                        yield on.isEmpty() ? null : on.get(task.random.nextInt(on.size()));
                    }
                    case "random_participant" -> {
                        List<EntityRef> ps = new ArrayList<>();
                        for (UUID u : task.participants) { EntityRef r = engine.platform.players().byUuid(u); if (r != null) ps.add(r); }
                        yield ps.isEmpty() ? null : ps.get(task.random.nextInt(ps.size()));
                    }
                    default -> {
                        String name = a.get("player").asText().trim();
                        yield name.isEmpty() ? null : ev.lookupEntity(name);
                    }
                };
                if (who != null && who.player()) who = engine.platform.players().byUuid(who.uuid());
                if (who == null) {
                    failPiece(f, p, "Track Player: " + ("named".equals(source) || source.isEmpty()
                            ? "'" + a.get("player").asText() + "' is not online" : "no player for '" + source + "'"));
                    return;
                }
                task.setTracked(who, !"branch".equals(a.get("scope").asText()), f);
                ok(task, "tracking " + who.name());
            }
            case "event.player_join", "event.player_leave" -> {
                if (!roomForListener(task, p)) return;
                boolean wait = "wait".equals(a.get("mode").asText());
                String evType = p.type.equals("event.player_join") ? "player.join" : "player.leave";
                Listener l = new Listener(engine.nextListenerId++, f.handle, p.id, evType, "", null, wait ? "" : str(p, "function"),
                        wait || a.get("once").asBool(), 0, 0, false, !wait && a.get("keepAlive").asBool(), wait ? f : null, null, null);
                l.whoMode = a.get("who").asText().isEmpty() ? "any" : a.get("who").asText();
                l.whoName = a.get("player").asText().trim();
                l.trackSubject = a.getOrDefault("trackJoined", Value.FALSE).asBool();
                task.listeners.add(l);
                if (wait) {
                    int timeout = (int) a.get("timeout").asNumber();
                    f.suspend(new EventWait(l, timeout > 0 ? task.tickCount + timeout : -1, "", ""));
                }
            }
            case "zone.keep_inside" -> {
                csequencer.core.value.Vec3d c1 = a.get("from").asVec(), c2 = a.get("to").asVec();
                if (c1 == null || c2 == null) { failPiece(f, p, "Keep Inside Area: invalid corners"); return; }
                csequencer.core.value.Vec3d back = a.get("returnTo").asVec();
                if (back == null) back = new csequencer.core.value.Vec3d((c1.x() + c2.x()) / 2, Math.min(c1.y(), c2.y()) + 1, (c1.z() + c2.z()) / 2);
                String key = "zone:" + (a.get("id").asText().isEmpty() ? "arena" : a.get("id").asText());
                task.timers.removeIf(t -> t.key.equals(key));
                int interval = Math.max(1, (int) a.get("interval").asNumber());
                JsonElement spec = p.params.has("targets") ? p.params.get("targets") : type.param("targets").def;
                String msg = a.get("message").asText();
                csequencer.core.value.Vec3d target = back;
                double minX = Math.min(c1.x(), c2.x()), maxX = Math.max(c1.x(), c2.x()) + 1, minY = Math.min(c1.y(), c2.y()), maxY = Math.max(c1.y(), c2.y()) + 1;
                double minZ = Math.min(c1.z(), c2.z()), maxZ = Math.max(c1.z(), c2.z()) + 1;
                Runnable[] check = new Runnable[1];
                check[0] = () -> {
                    if (f.task.state.terminal()) return;
                    for (EntityRef e : resolveTargets(f, spec)) {
                        Value pos = ev.resolve(csequencer.core.logic.QueryExpr.parse(queryJson("player.pos")), new Scope(task, null, null, e));
                        csequencer.core.value.Vec3d v = pos.asVec();
                        if (v == null) continue;
                        boolean inside = v.x() >= minX && v.x() <= maxX && v.y() >= minY && v.y() <= maxY && v.z() >= minZ && v.z() <= maxZ;
                        if (inside) continue;
                        Map<String, Value> tp = new HashMap<>();
                        tp.put("pos", Value.of(target));
                        tp.put("dimension", Value.of(""));
                        tp.put("relative", Value.FALSE);
                        tp.put("keepRotation", Value.TRUE);
                        tp.put("yaw", Value.NULL);
                        tp.put("pitch", Value.NULL);
                        ActionAdapter.ActionContext ctx = new ActionAdapter.ActionContext(task.id, task.project.name, e, task.request.initiator, List.of(e),
                                task.request.dimension, 0, engine.settings.commandBlacklist, engine.settings.limits.fillBlockLimit);
                        engine.platform.actions().execute("action.teleport", tp, ctx);
                        if (!msg.isEmpty()) {
                            Map<String, Value> ab = new HashMap<>();
                            ab.put("message", Value.of(ev.render(msg, new Scope(task, null, null, e))));
                            engine.platform.actions().execute("action.actionbar", ab, ctx);
                        }
                    }
                    task.timers.add(new Timer(key, task.tickCount + interval, check[0], true));
                };
                task.timers.add(new Timer(key, task.tickCount + 1, check[0], true));
                ok(task, "keeping players inside " + key.substring(5));
            }
            case "zone.release" -> {
                String id = a.get("id").asText();
                int before = task.timers.size();
                task.timers.removeIf(t -> t.key.startsWith("zone:") && (id.isEmpty() || t.key.equals("zone:" + id)));
                ok(task, "released " + (before - task.timers.size()) + " area(s)");
            }
            case "var.set" -> setVariable(f, p, a, scope);
            case "counter.step" -> {
                Variables store = ev.variableStore(a.get("scope").asText(), scope);
                if (store == null) { failPiece(f, p, "Player-scoped counter needs a subject"); return; }
                String name = a.get("name").asText();
                double v = store.get(name).asNumber() + a.get("step").asNumber();
                double threshold = a.get("threshold").asNumber();
                if (threshold != 0 && v >= threshold) {
                    if (a.get("reset").asBool()) v = 0;
                    store.set(name, Value.of(v));
                    callTarget(f, str(p, "function"), false);
                } else {
                    store.set(name, Value.of(v));
                }
            }
            case "state.set" -> setState(task, a.get("machine").asText(), a.get("state").asText(), f);
            case "state.machine" -> {
                if (!roomForListener(task, p)) return;
                Map<String, String> targets = new LinkedHashMap<>();
                for (JsonElement e : arr(p, "states")) if (e.isJsonObject()) targets.put(J.str(e.getAsJsonObject(), "match", "").toLowerCase(Locale.ROOT), J.str(e.getAsJsonObject(), "target", ""));
                String machine = a.get("machine").asText();
                task.listeners.add(new Listener(engine.nextListenerId++, f.handle, p.id, "state.changed", "", null, "", false, 0, 0, false,
                        J.b(p.params, "keepAlive", false), null, targets, machine));
                String initial = a.get("initial").asText();
                if (!initial.isEmpty()) setState(task, machine, initial, f);
            }
            case "signal.emit" -> {
                Map<String, Value> data = new HashMap<>();
                data.put("name", Value.of(a.get("name").asText()));
                data.put("payload", a.get("payload"));
                data.put("source", Value.of(task.id));
                GameEvent e = GameEvent.of("signal", f.subject != null && f.subject.player() ? f.subject : null, task.request.dimension, null, data);
                if (a.get("global").asBool()) engine.post(e);
                else deliver(task, e);
            }
            case "audio.play" -> playAudio(f, p, a, 0);
            case "audio.stop", "audio.pause", "audio.resume", "audio.seek" -> {
                AudioService.Session s = engine.audio.find(task.id, a.get("channel").asText());
                if (s == null) { task.log("info", p.type + ": nothing playing on '" + a.get("channel").asText() + "'"); return; }
                switch (p.type) {
                    case "audio.stop" -> engine.audio.stop(s, (long) a.get("fadeMs").asNumber());
                    case "audio.pause" -> engine.audio.pause(s);
                    case "audio.resume" -> engine.audio.resume(s);
                    default -> engine.audio.seek(s, (long) a.get("positionMs").asNumber());
                }
                ok(task, p.type);
            }
            case "audio.sync" -> f.suspend(new AudioSyncWait(a.get("channel").asText(), (long) a.get("atMs").asNumber()));
            case "audio.volume" -> {
                AudioService.Session s = engine.audio.find(task.id, a.get("channel").asText());
                if (s == null) { task.log("info", "Change Volume: nothing playing on '" + a.get("channel").asText() + "'"); return; }
                engine.audio.setVolume(s, a.get("volume").asNumber(), (long) a.get("fadeMs").asNumber());
                ok(task, "volume " + a.get("volume").asText());
            }
            case "hud.popup", "hud.overlay", "hud.marker", "hud.screen", "hud.bossbar", "hud.hide" -> hud(f, p, type, a);
            case "camera.fixed", "camera.path", "camera.orbit", "camera.reset", "camera.look_at", "camera.release_look",
                 "player.controls", "player.release_controls", "screen.effect", "screen.clear_effects",
                 "player.animation", "player.stop_animation" -> cinematic(f, p, type, a);
            case "flow.wait_death" -> {
                JsonObject q = new JsonObject();
                q.addProperty("q", "entity.alive");
                JsonObject args = new JsonObject();
                args.add("entity", p.params.has("entity") ? p.params.get("entity") : PieceRegistry.queryVar("boss"));
                q.add("args", args);
                csequencer.core.logic.QueryExpr alive = csequencer.core.logic.QueryExpr.parse(q);
                if ("run_function".equals(a.get("mode").asText())) {
                    Timeline tl = fn(task, str(p, "function"));
                    EntityRef subj = f.subject;
                    GameEvent evt = f.event;
                    String key = "death:" + p.id + ":" + f.id;
                    Runnable[] check = new Runnable[1];
                    check[0] = () -> {
                        if (ev.resolve(alive, new Scope(task, null, evt, subj)).asBool()) task.timers.add(new Timer(key, task.tickCount + 5, check[0]));
                        else if (tl != null) task.spawnCall(null, tl, Map.of(), subj, evt);
                    };
                    task.timers.add(new Timer(key, task.tickCount + 1, check[0]));
                } else {
                    f.suspend(new DeathWait(alive));
                }
            }
            case "debug.log" -> {
                String level = a.get("level").asText();
                String msg = a.get("message").asText();
                task.log(level.isEmpty() ? "info" : level, msg);
                if (a.get("notify").asBool()) engine.sync.notifyMonitors(task, msg, level);
            }
            case "debug.breakpoint" -> {
                if (a.get("onlyInDebug").asBool() && !task.debug) return;
                CNode c = ev.compile(task, p.id + "#condition", p.params.get("condition"));
                if (ev.test(c, scope, "bp:" + p.id)) {
                    task.log("info", "Breakpoint '" + p.displayName() + "' hit (" + f.name + ", tick " + f.clock + ")");
                    engine.pause(task, Task.PauseReason.BREAKPOINT);
                }
            }
            case "debug.assert" -> {
                CNode c = ev.compile(task, p.id + "#condition", p.params.get("condition"));
                if (ev.test(c, scope, "assert:" + p.id)) return;
                String msg = "Assertion failed: " + (a.get("message").asText().isEmpty() ? c.describe() : a.get("message").asText());
                task.log("error", msg);
                if (a.get("failTask").asBool()) engine.finish(task, TaskState.FAILED, msg);
            }
            default -> task.logOnce("nyi:" + p.type, "warn", "Piece type '" + p.type + "' has no core implementation");
        }
    }

    // ── Seek replay ────────────────────────────────────────────────────────

    /** Called when starting from a later tick: re-registers long-lived pieces and resumes audio. */
    void replayForSeek(Fiber f, Piece p, int from) {
        switch (p.type) {
            case "event.listen", "event.on_condition", "state.machine" -> execute(f, p);
            case "audio.play" -> {
                PieceType type = PieceRegistry.piece(p.type);
                Map<String, Value> a = ev.resolveArgs(type.params(), p.params, Scope.of(f));
                AudioRef ref = f.task.project.audio(J.str(p.params, "audio", ""));
                long elapsedMs = (from - p.tick) * 50L;
                long len = ref == null ? 0 : ref.durationMs;
                if (ref != null && (a.get("loop").asBool() || elapsedMs + (long) a.get("offsetMs").asNumber() < len)) playAudio(f, p, a, elapsedMs);
            }
            default -> {}
        }
    }

    // ── Event delivery ─────────────────────────────────────────────────────

    void deliver(Task task, GameEvent e) {
        for (Listener l : new ArrayList<>(task.listeners)) {
            if (!task.listeners.contains(l)) continue;
            if (!l.eventType.equalsIgnoreCase(e.type)) continue;
            if ("signal".equals(e.type) && !l.signal.isEmpty() && !l.signal.equalsIgnoreCase(e.field("name").asText())) continue;
            if (l.machine != null && !l.machine.equalsIgnoreCase(e.field("machine").asText())) continue;
            EntityRef subject = e.subject();
            if (l.whoMode != null && !"any".equals(l.whoMode)) {
                if (e.player == null) continue;
                if ("tracked".equals(l.whoMode) && (task.tracked == null || !task.tracked.uuid().equals(e.player.uuid()))) continue;
                if ("named".equals(l.whoMode) && !e.player.name().equalsIgnoreCase(l.whoName)) continue;
            }
            Scope scope = new Scope(task, null, e, subject);
            if (l.filter != null && !ev.test(l.filter, scope, "listen:" + l.id)) continue;
            if (l.trackSubject && e.player != null) task.setTracked(e.player, true, null);
            if (l.cooldown > 0) {
                String key = l.perPlayer && subject != null ? subject.key() : "*";
                Integer last = l.lastFire.get(key);
                if (last != null && task.tickCount - last < l.cooldown) continue;
                l.lastFire.put(key, task.tickCount);
            }
            l.fires++;
            if (subject != null) task.addParticipant(subject);
            if (l.waiter != null) {
                l.firedEvent = e;
                task.listeners.remove(l);
                task.wake(l.waiter);
                continue;
            }
            if (l.stateTargets != null) {
                String fn = l.stateTargets.get(e.field("to").asText().toLowerCase(Locale.ROOT));
                if (fn != null && !fn.isEmpty()) spawnDetached(task, fn, null, e);
            } else {
                spawnDetached(task, l.function, subject, e);
            }
            if (l.exhausted()) task.listeners.remove(l);
        }
    }

    void pollWatchers(Task task) {
        for (Watcher w : new ArrayList<>(task.watchers)) {
            if (!task.watchers.contains(w) || task.tickCount < w.nextCheck) continue;
            w.nextCheck = task.tickCount + w.interval;
            List<EntityRef> subjects = new ArrayList<>();
            switch (w.over.toLowerCase(Locale.ROOT)) {
                case "global" -> subjects.add(null);
                case "participants" -> {
                    for (UUID u : task.participants) {
                        EntityRef r = engine.platform.players().byUuid(u);
                        if (r != null) subjects.add(r);
                    }
                }
                default -> subjects.addAll(engine.platform.players().online());
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (EntityRef s : subjects) {
                String key = s == null ? "global" : s.key();
                seen.add(key);
                boolean now = ev.test(w.condition, new Scope(task, null, null, s), "watch:" + w.id);
                Boolean prev = w.previous.put(key, now);
                boolean fire = switch (w.edge) {
                    case LEVEL -> now;
                    case ENTERED -> prev == null ? (w.fireInitial && now) : (now && !prev);
                    case EXITED -> prev != null && !now && prev;
                    case CHANGED -> prev != null && now != prev;
                };
                if (!fire) continue;
                if (w.once && !w.fired.add(key)) continue;
                if (s != null) task.addParticipant(s);
                spawnDetached(task, w.function, s, null);
            }
            // Forget subjects that disappeared (logged out) so a re-join starts from a fresh baseline.
            w.previous.keySet().retainAll(seen);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void spawnDetached(Task task, String fnRef, EntityRef subject, GameEvent e) {
        Timeline tl = fn(task, fnRef);
        if (tl == null) {
            if (fnRef != null && !fnRef.isEmpty()) task.logOnce("nofn:" + fnRef, "warn", "Function '" + fnRef + "' not found");
            return;
        }
        task.spawnCall(null, tl, Map.of(), subject, e);
    }

    private void callTarget(Fiber f, String fnRef, boolean await) {
        if (fnRef == null || fnRef.isEmpty()) return;
        Timeline tl = fn(f.task, fnRef);
        if (tl == null) {
            f.task.log("warn", "Function '" + fnRef + "' not found");
            return;
        }
        CallHandle h = f.task.spawnCall(f, tl, Map.of(), f.subject, f.event);
        if (h != null && await) f.suspend(new JoinWait(f, List.of(h), "all", false, null));
    }

    private CallHandle spawnItem(Fiber f, Timeline tl, String var, Value item, int index, boolean asSubject) {
        Map<String, Value> in = new HashMap<>();
        if (!var.isEmpty()) in.put(var, item);
        in.put("index", Value.of(index));
        EntityRef subject = asSubject && item.type() == ValueType.ENTITY ? item.asEntity() : f.subject;
        if (subject != null && subject.player()) f.task.addParticipant(subject);
        return f.task.spawnCall(f, tl, in, subject, f.event);
    }

    private void copyOutputs(CallHandle h, Fiber f, String prefix) {
        for (var out : h.timeline.outputs) {
            Value v = h.locals.get(out.name);
            f.handle.locals.set(prefix == null || prefix.isEmpty() ? out.name : prefix + "." + out.name, v);
        }
    }

    private Map<String, Value> inputs(JsonElement args, Scope scope) {
        Map<String, Value> in = new HashMap<>();
        if (args != null && args.isJsonObject()) {
            for (var e : args.getAsJsonObject().entrySet()) in.put(e.getKey(), ev.value(e.getValue(), scope));
        }
        return in;
    }

    private boolean roomForListener(Task task, Piece p) {
        if (task.listeners.size() + task.watchers.size() >= engine.settings.limits.maxListenersPerTask) {
            task.log("error", "Listener limit reached; '" + p.displayName() + "' not registered");
            task.warn("listener limit reached");
            return false;
        }
        return true;
    }

    private void setVariable(Fiber f, Piece p, Map<String, Value> a, Scope scope) {
        Variables store = ev.variableStore(a.get("scope").asText(), scope);
        if (store == null) { failPiece(f, p, "Player-scoped variable needs a subject"); return; }
        String name = a.get("name").asText();
        Value cur = store.get(name);
        Value v = a.get("value");
        Value next = switch (a.get("op").asText().toLowerCase(Locale.ROOT)) {
            case "add" -> Value.of(cur.asNumber() + v.asNumber());
            case "subtract" -> Value.of(cur.asNumber() - v.asNumber());
            case "multiply" -> Value.of(cur.asNumber() * v.asNumber());
            case "divide" -> Value.of(v.asNumber() == 0 ? 0 : cur.asNumber() / v.asNumber());
            case "append" -> {
                List<Value> l = new ArrayList<>(cur.asList());
                if (l.size() < 10000) l.add(v);
                yield Value.list(l);
            }
            case "remove" -> {
                List<Value> l = new ArrayList<>(cur.asList());
                l.removeIf(x -> x.looseEquals(v));
                yield Value.list(l);
            }
            case "toggle" -> Value.of(!cur.asBool());
            case "clear" -> Value.NULL;
            default -> v;
        };
        if (!store.set(name, next)) failPiece(f, p, "Variable store full or invalid name '" + name + "'");
    }

    private void setState(Task task, String machine, String state, Fiber f) {
        String key = "state:" + machine;
        Value prev = task.vars.get(key);
        if (prev.asText().equalsIgnoreCase(state)) return;
        task.vars.set(key, Value.of(state));
        Map<String, Value> data = new HashMap<>();
        data.put("machine", Value.of(machine));
        data.put("from", prev);
        data.put("to", Value.of(state));
        deliver(task, GameEvent.of("state.changed", null, task.request.dimension, null, data));
    }

    private void playAudio(Fiber f, Piece p, Map<String, Value> a, long extraOffsetMs) {
        Task task = f.task;
        String ref = J.str(p.params, "audio", "");
        AudioRef audio = task.project.audio(ref);
        if (audio == null) { failPiece(f, p, "Audio '" + ref + "' is not part of this project"); return; }
        JsonElement tspec = p.params.has("targets") ? p.params.get("targets") : PieceRegistry.targetMode("all");
        boolean all = tspec.isJsonObject() && "all".equalsIgnoreCase(J.str(tspec.getAsJsonObject(), "mode", "all"));
        List<EntityRef> targets = all ? List.of() : resolveTargets(f, tspec);
        long base = (long) a.get("offsetMs").asNumber();
        // A piece with a length cuts the audio at its end (trimmed clip); 0 = play the whole file.
        long endMs = p.duration > 0 && !a.get("loop").asBool() ? base + p.duration * 50L : -1;
        if (endMs >= 0 && base + extraOffsetMs >= endMs) return;
        engine.audio.play(task, a.get("channel").asText().isEmpty() ? "main" : a.get("channel").asText(), audio, targets, all,
                a.get("volume").asNumber(), base + extraOffsetMs, a.get("loop").asBool(),
                (long) a.getOrDefault("fadeInMs", Value.ZERO).asNumber(), endMs, (long) a.getOrDefault("fadeOutMs", Value.ZERO).asNumber());
        ok(task, "audio " + audio.name);
    }

    private void sequenceControl(Fiber f, Piece p, Map<String, Value> a) {
        Task task = f.task;
        String action = a.get("action").asText().toLowerCase(Locale.ROOT);
        String projectRef = a.get("project").asText();
        boolean self = projectRef.isEmpty() || projectRef.equalsIgnoreCase(task.project.name) || projectRef.equals(task.project.id);
        String which = a.get("which").asText().toLowerCase(Locale.ROOT);
        if ("start".equals(action)) {
            ProjectCatalog.Entry entry = self ? new ProjectCatalog.Entry(task.project, task.meta) : engine.catalog.find(projectRef);
            if (entry == null) { failPiece(f, p, "Sequence '" + projectRef + "' not found"); return; }
            if (task.request.chainDepth + 1 > engine.settings.limits.maxChainDepth) {
                failPiece(f, p, "Chain depth limit (" + engine.settings.limits.maxChainDepth + ") reached starting '" + entry.project().name + "'");
                return;
            }
            StartRequest req = new StartRequest(entry.project(), entry.meta()).initiator(task.request.initiator).dimension(task.request.dimension).reason("chain:" + task.project.name);
            req.chainDepth = task.request.chainDepth + 1;
            StartResult r = engine.start(req);
            if (r.ok()) ok(task, "started " + entry.project().name);
            else failPiece(f, p, "Could not start '" + entry.project().name + "': " + r.message());
            return;
        }
        List<Task> targets = new ArrayList<>();
        if (self && "self".equals(which)) targets.add(task);
        else for (Task t : engine.tasks()) {
            if (t.state.terminal()) continue;
            boolean sameProject = self ? t.project.id.equals(task.project.id) : (t.project.name.equalsIgnoreCase(projectRef) || t.project.id.equals(projectRef));
            if (!sameProject) continue;
            if ("same_scope".equals(which) && !t.scopeKey.equals(task.scopeKey)) continue;
            targets.add(t);
        }
        for (Task t : targets) {
            switch (action) {
                case "pause" -> engine.pause(t, Task.PauseReason.SEQUENCE);
                case "resume" -> engine.resume(t);
                case "cancel" -> engine.cancel(t, "cancelled by " + task.project.name);
                case "restart" -> engine.restart(t);
                default -> task.log("warn", "Unknown sequence action '" + action + "'");
            }
        }
        ok(task, action + " " + targets.size() + " task(s)");
    }

    // ── Dispatch to the platform ───────────────────────────────────────────

    private void dispatch(Fiber f, Piece p, PieceType type) {
        Task task = f.task;
        Scope scope = Scope.of(f);
        Map<String, Value> args = ev.resolveArgs(type.params(), p.params, scope);
        List<EntityRef> targets = List.of();
        if (type.param("targets") != null) {
            JsonElement spec = p.params.has("targets") ? p.params.get("targets") : type.param("targets").def;
            targets = resolveTargets(f, spec);
        }
        if (type.privileged()) {
            if (task.commandLevel <= 0) {
                failPiece(f, p, "Commands are disabled for this project (saved by a player below the server's command trust level)");
                return;
            }
            String reason = CommandFilter.check(args.getOrDefault("command", Value.NULL).asText(), engine.settings.commandBlacklist);
            if (reason != null) { failPiece(f, p, reason); return; }
        }
        if (args.getOrDefault("removeOnEnd", Value.FALSE).asBool()) task.removeSpawnedOnEnd = true;
        String dim = f.subject != null && f.subject.player() ? engine.platform.players().dimensionOf(f.subject.uuid()) : null;
        if (dim == null || dim.isEmpty()) dim = task.request.dimension;
        ActionAdapter.ActionContext ctx = new ActionAdapter.ActionContext(task.id, task.project.name, f.subject, task.request.initiator, targets,
                dim, task.commandLevel, engine.settings.commandBlacklist, engine.settings.limits.fillBlockLimit);
        ActionAdapter.ActionResult r;
        try {
            r = engine.platform.actions().execute(p.type, args, ctx);
        } catch (RuntimeException e) {
            r = ActionAdapter.ActionResult.fail("adapter error: " + e);
        }
        if (r == null) r = ActionAdapter.ActionResult.fail("adapter returned no result");
        if (r.success()) {
            ok(task, type.name() + (r.message() == null || r.message().isEmpty() ? "" : ": " + r.message()));
            String storeAs = args.getOrDefault("storeAs", Value.NULL).asText();
            // Stored in the sequence scope so functions, listeners and other tracks can use it (e.g. the spawned boss).
            if (!storeAs.isEmpty() && r.produced() != null && !r.produced().isNull()) task.vars.set(storeAs, r.produced());
        } else {
            failPiece(f, p, type.name() + ": " + r.message());
        }
    }

    /** Resolves a TARGET spec into concrete players/entities. */
    List<EntityRef> resolveTargets(Fiber f, JsonElement specEl) {
        Task task = f.task;
        JsonObject spec = specEl != null && specEl.isJsonObject() ? specEl.getAsJsonObject() : PieceRegistry.targetMode("initiator");
        String mode = J.str(spec, "mode", "initiator").toLowerCase(Locale.ROOT);
        Scope scope = Scope.of(f);
        List<EntityRef> out = new ArrayList<>();
        switch (mode) {
            case "none" -> {}
            case "tracked" -> {
                EntityRef t = task.tracked;
                if (t != null && engine.platform.players().isOnline(t.uuid())) out.add(engine.platform.players().byUuid(t.uuid()));
            }
            case "initiator" -> {
                EntityRef i = task.request.initiator;
                if (i != null && engine.platform.players().isOnline(i.uuid())) out.add(engine.platform.players().byUuid(i.uuid()));
            }
            case "context", "subject" -> {
                if (f.subject != null) {
                    if (!f.subject.player()) out.add(f.subject);
                    else if (engine.platform.players().isOnline(f.subject.uuid())) out.add(engine.platform.players().byUuid(f.subject.uuid()));
                }
            }
            case "all" -> out.addAll(engine.platform.players().online());
            case "participants" -> {
                for (UUID u : task.participants) {
                    EntityRef e = engine.platform.players().byUuid(u);
                    if (e != null) out.add(e);
                }
            }
            case "player" -> {
                for (String name : ev.render(J.str(spec, "name", ""), scope).split("[,\\s]+")) {
                    if (name.isEmpty()) continue;
                    EntityRef e = ev.lookupEntity(name);
                    if (e != null && !out.contains(e)) out.add(e);
                }
            }
            case "team" -> out.addAll(engine.platform.players().inTeam(ev.render(J.str(spec, "team", ""), scope)));
            case "filter" -> {
                CNode c = ev.compile(task, "target#" + System.identityHashCode(spec), spec.get("filter"));
                for (EntityRef e : engine.platform.players().online()) if (ev.test(c, scope.withSubject(e), "target")) out.add(e);
            }
            case "selector" -> out.addAll(engine.platform.players().select(ev.render(J.str(spec, "selector", "@a"), scope), f.subject, J.b(spec, "playersOnly", true)));
            case "variable" -> {
                Value v = ev.variable("auto", J.str(spec, "var", ""), scope);
                for (Value x : v.asList()) if (x.type() == ValueType.ENTITY) out.add(x.asEntity());
            }
            default -> task.logOnce("tm:" + mode, "warn", "Unknown target mode '" + mode + "'");
        }
        if (out.size() > 1000) out = out.subList(0, 1000);
        for (EntityRef e : out) task.addParticipant(e);
        return out;
    }

    private void ok(Task task, String msg) {
        task.actionsOk++;
        task.lastResult = msg;
    }

    private void failPiece(Fiber f, Piece p, String msg) {
        Task task = f.task;
        task.actionsFailed++;
        task.lastError = msg;
        task.log("error", "[" + p.displayName() + "] " + msg);
        Policies.ErrorPolicy policy = p.onError != null ? p.onError : task.project.settings.errorPolicy;
        switch (policy) {
            case STOP_BRANCH -> f.fail(msg);
            case FAIL_TASK -> engine.finish(task, TaskState.FAILED, msg);
            default -> {}
        }
    }

    private static Timeline fn(Task task, String ref) { return ref == null || ref.isEmpty() ? null : task.project.function(ref); }

    private static String str(Piece p, String key) {
        PieceType t = PieceRegistry.piece(p.type);
        JsonElement e = p.params.has(key) ? p.params.get(key) : (t != null && t.param(key) != null ? t.param(key).def : null);
        return e == null || e.isJsonNull() ? "" : (e.isJsonPrimitive() ? e.getAsString() : e.toString());
    }

    private static JsonArray arr(Piece p, String key) {
        JsonElement e = p.params.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    @SuppressWarnings("unused")
    private static boolean isType(ParamSpec s, ParamType t) { return s != null && s.type == t; }

    // ── Wait implementations ───────────────────────────────────────────────

    static final class DelayWait implements Wait {
        final int until;

        DelayWait(int until) { this.until = until; }

        @Override public boolean poll(Fiber f) { return f.task.tickCount >= until; }

        @Override public int remaining(Fiber f) { return Math.max(0, until - f.task.tickCount); }

        @Override public String describe() { return "delay"; }
    }

    final class CondWait implements Wait {
        final CNode cond;
        final String key;
        final int timeoutAt;
        final int interval;
        final String onTimeout;
        final boolean failOnTimeout;
        int nextCheck;

        CondWait(CNode cond, String key, int timeoutAt, int interval, String onTimeout, boolean failOnTimeout) {
            this.cond = cond;
            this.key = key;
            this.timeoutAt = timeoutAt;
            this.interval = interval;
            this.onTimeout = onTimeout;
            this.failOnTimeout = failOnTimeout;
        }

        @Override public boolean poll(Fiber f) {
            Task t = f.task;
            if (t.tickCount >= nextCheck) {
                if (engine.evalBudgetExhausted()) {
                    t.deferredThisTick = true;
                } else {
                    nextCheck = t.tickCount + interval;
                    if (ev.test(cond, Scope.of(f), key)) return true;
                }
            }
            if (timeoutAt >= 0 && t.tickCount >= timeoutAt) {
                t.log("info", "[" + f.name + "] wait-until timed out");
                if (failOnTimeout) {
                    f.fail("wait-until timed out");
                    return false;
                }
                Timeline tl = fn(t, onTimeout);
                CallHandle h = tl == null ? null : t.spawnCall(f, tl, Map.of(), f.subject, f.event);
                if (h != null) {
                    // Run the timeout handler to completion before the branch continues.
                    f.suspend(new JoinWait(f, List.of(h), "all", false, null));
                    return false;
                }
                return true;
            }
            return false;
        }

        @Override public int remaining(Fiber f) { return -1; }

        @Override public String describe() { return "condition: " + cond.describe(); }
    }

    static final class JoinWait implements Wait {
        final Fiber fiber;
        final List<CallHandle> handles;
        final String mode;
        final boolean cancelOthers;
        final Runnable after;

        JoinWait(Fiber fiber, List<CallHandle> handles, String mode, boolean cancelOthers, Runnable after) {
            this.fiber = fiber;
            this.handles = handles;
            this.mode = mode;
            this.cancelOthers = cancelOthers;
            this.after = after;
            for (CallHandle h : handles) h.whenDone(() -> fiber.task.wake(fiber));
        }

        @Override public boolean poll(Fiber f) {
            int done = 0;
            for (CallHandle h : handles) if (h.done) done++;
            boolean ready = "any".equals(mode) ? done > 0 : done == handles.size();
            if (!ready) return false;
            if ("any".equals(mode) && cancelOthers) for (CallHandle h : handles) if (!h.done) f.task.cancelCall(h, "another branch finished first");
            if (after != null) after.run();
            return true;
        }

        @Override public void cancel(Fiber f) {
            for (CallHandle h : handles) if (!h.done) f.task.cancelCall(h, "waiting branch ended");
        }

        @Override public String describe() {
            int done = 0;
            for (CallHandle h : handles) if (h.done) done++;
            return "join " + done + "/" + handles.size() + ("any".equals(mode) ? " (any)" : "");
        }
    }

    interface LoopDriver { CallHandle next(); }

    static final class LoopWait implements Wait {
        final Fiber fiber;
        final int interval;
        final String name;
        final LoopDriver driver;
        final java.util.function.Supplier<String> progress;
        CallHandle current;
        int delayUntil = -1;
        boolean started;

        LoopWait(Fiber fiber, int interval, String name, LoopDriver driver, java.util.function.Supplier<String> progress) {
            this.fiber = fiber;
            this.interval = Math.max(0, interval);
            this.name = name;
            this.driver = driver;
            this.progress = progress;
        }

        @Override public boolean poll(Fiber f) {
            Task t = f.task;
            int guard = 0;
            while (true) {
                if (current != null && !current.done) return false;
                if (current != null) {
                    current = null;
                    if (interval > 0) delayUntil = t.tickCount + interval;
                }
                if (delayUntil > t.tickCount) return false;
                if (++guard > 256) return false;
                CallHandle h = driver.next();
                if (h == null) return true;
                current = h;
                h.whenDone(() -> t.wake(fiber));
            }
        }

        @Override public void cancel(Fiber f) {
            if (current != null && !current.done) f.task.cancelCall(current, "loop cancelled");
        }

        @Override public int remaining(Fiber f) { return -1; }

        @Override public boolean eager() { return true; }

        @Override public String describe() { return name + " " + progress.get(); }
    }

    static final class TimeoutWait implements Wait {
        final CallHandle handle;
        final int until;
        final Runnable expire;

        TimeoutWait(Fiber f, CallHandle handle, int until, Runnable expire) {
            this.handle = handle;
            this.until = until;
            this.expire = expire;
            handle.whenDone(() -> f.task.wake(f));
        }

        @Override public boolean poll(Fiber f) {
            if (handle.done) return true;
            if (f.task.tickCount >= until) {
                expire.run();
                return true;
            }
            return false;
        }

        @Override public void cancel(Fiber f) { if (!handle.done) f.task.cancelCall(handle, "window cancelled"); }

        @Override public int remaining(Fiber f) { return Math.max(0, until - f.task.tickCount); }

        @Override public String describe() { return "timed window"; }
    }

    final class EventWait implements Wait {
        final Listener listener;
        final int timeoutAt;
        final String onTimeout;
        final String storeAs;

        EventWait(Listener listener, int timeoutAt, String onTimeout, String storeAs) {
            this.listener = listener;
            this.timeoutAt = timeoutAt;
            this.onTimeout = onTimeout;
            this.storeAs = storeAs;
        }

        @Override public boolean poll(Fiber f) {
            if (listener.firedEvent != null) {
                GameEvent e = listener.firedEvent;
                if (!storeAs.isEmpty() && e.subject() != null) f.task.vars.set(storeAs, Value.of(e.subject()));
                f.event = e;
                return true;
            }
            if (timeoutAt >= 0 && f.task.tickCount >= timeoutAt) {
                f.task.listeners.remove(listener);
                Timeline tl = fn(f.task, onTimeout);
                CallHandle h = tl == null ? null : f.task.spawnCall(f, tl, Map.of(), f.subject, f.event);
                if (h != null) {
                    f.suspend(new JoinWait(f, List.of(h), "all", false, null));
                    return false;
                }
                return true;
            }
            return false;
        }

        @Override public void cancel(Fiber f) { f.task.listeners.remove(listener); }

        @Override public int remaining(Fiber f) { return -1; }

        @Override public String describe() { return "event " + listener.eventType; }
    }

    // ── Screen & HUD ───────────────────────────────────────────────────────

    private static final Map<String, Integer> STYLE_COLORS = Map.of(
            "info", 0xFF4FC3F7, "success", 0xFF66BB6A, "warning", 0xFFFFC857, "danger", 0xFFEF5350, "boss", 0xFFB388FF, "gold", 0xFFFFD54F);

    static int color(String style, String custom) {
        if (custom != null && !custom.isBlank()) {
            String h = custom.trim().startsWith("#") ? custom.trim().substring(1) : custom.trim();
            try {
                return (int) (Long.parseLong(h, 16) | (h.length() <= 6 ? 0xFF000000L : 0));
            } catch (NumberFormatException ignored) {}
        }
        return STYLE_COLORS.getOrDefault(style, 0xFF4FC3F7);
    }

    private boolean allTargets(Piece p, PieceType type) {
        JsonElement spec = p.params.has("targets") ? p.params.get("targets") : (type.param("targets") == null ? null : type.param("targets").def);
        return spec != null && spec.isJsonObject() && "all".equals(J.str(spec.getAsJsonObject(), "mode", ""));
    }

    private void hud(Fiber f, Piece p, PieceType type, Map<String, Value> a) {
        Task task = f.task;
        boolean all = allTargets(p, type);
        List<EntityRef> targets = all || type.param("targets") == null ? List.of() : resolveTargets(f, p.params.has("targets") ? p.params.get("targets") : type.param("targets").def);
        if (!all && type.param("targets") != null && targets.isEmpty()) task.log("info", type.name() + ": no online targets");
        EntityRef subj = f.subject;
        GameEvent evt = f.event;
        java.util.function.Supplier<Map<String, Value>> args = () -> ev.resolveArgs(type.params(), p.params, new Scope(task, null, evt, subj));
        switch (p.type) {
            case "hud.popup" -> {
                JsonObject o = new JsonObject();
                o.addProperty("title", a.get("title").asText());
                o.addProperty("message", a.get("message").asText());
                o.addProperty("icon", a.get("icon").asText());
                o.addProperty("color", color(a.get("style").asText(), a.get("color").asText()));
                o.addProperty("position", a.get("position").asText());
                o.addProperty("durationMs", (long) a.get("duration").asNumber() * 50);
                o.addProperty("sound", a.get("sound").asBool());
                engine.hud.flash("popup", o, targets, all);
            }
            case "hud.overlay" -> {
                long countdown = (long) a.get("countdown").asNumber() * 50L;
                long endsAt = countdown > 0 ? engine.platform.millis() + countdown : -1;
                java.util.function.Function<Map<String, Value>, JsonObject> build = r -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("style", r.get("style").asText());
                    o.addProperty("title", r.get("title").asText());
                    o.addProperty("subtitle", r.get("subtitle").asText());
                    Value v = r.get("value");
                    if (v.exists() && v.isNumeric()) {
                        double max = r.get("max").asNumber();
                        o.addProperty("value", v.asNumber());
                        o.addProperty("max", max <= 0 ? 100 : max);
                    }
                    if (endsAt > 0) o.addProperty("countdownMs", Math.max(0, endsAt - engine.platform.millis()));
                    o.addProperty("color", color(r.get("color").asText(), r.get("customColor").asText()));
                    o.addProperty("position", r.get("position").asText());
                    return o;
                };
                boolean live = a.get("live").asBool() || endsAt > 0;
                engine.hud.show(task, "overlay", a.get("id").asText(), build.apply(a), targets, all, (int) a.get("duration").asNumber(),
                        live ? () -> build.apply(args.get()) : null, true);
            }
            case "hud.marker" -> {
                java.util.function.Function<Map<String, Value>, JsonObject> build = r -> {
                    JsonObject o = new JsonObject();
                    csequencer.core.value.Vec3d pos = r.get("pos").asVec();
                    Value follow = r.get("follow");
                    if (follow.asEntity() != null || (follow.exists() && follow.type() == csequencer.core.value.ValueType.TEXT && follow.asText().length() > 30)) {
                        JsonObject q = new JsonObject();
                        q.addProperty("q", "entity.pos");
                        JsonObject qa = new JsonObject();
                        qa.add("entity", follow.toJson());
                        q.add("args", qa);
                        Value fp = ev.resolve(csequencer.core.logic.QueryExpr.parse(q), new Scope(task, null, evt, subj));
                        if (fp.asVec() == null) return null; // followed entity is gone
                        pos = fp.asVec();
                    }
                    if (pos == null) return null;
                    JsonObject po = new JsonObject();
                    po.addProperty("x", pos.x());
                    po.addProperty("y", pos.y());
                    po.addProperty("z", pos.z());
                    o.add("pos", po);
                    o.addProperty("dimension", r.get("dimension").asText());
                    o.addProperty("label", r.get("label").asText());
                    o.addProperty("icon", r.get("icon").asText());
                    o.addProperty("color", color(r.get("color").asText(), r.get("customColor").asText()));
                    o.addProperty("showDistance", r.get("showDistance").asBool());
                    o.addProperty("hideWithin", r.get("hideWithin").asNumber());
                    return o;
                };
                JsonObject first = build.apply(a);
                if (first == null) { failPiece(f, p, "Waypoint Marker: no valid position (or the followed entity is gone)"); return; }
                engine.hud.show(task, "marker", a.get("id").asText(), first, targets, all, (int) a.get("duration").asNumber(),
                        () -> build.apply(args.get()), true);
            }
            case "hud.screen" -> {
                String effect = a.get("effect").asText();
                JsonObject o = new JsonObject();
                o.addProperty("effect", effect);
                o.addProperty("color", color("", a.get("color").asText().isEmpty() ? "#000000" : a.get("color").asText()));
                o.addProperty("transitionMs", (long) a.get("duration").asNumber() * 50);
                o.addProperty("intensity", a.get("intensity").asNumber());
                String group = effect.startsWith("fade") ? "fade" : effect.startsWith("letterbox") ? "letterbox" : effect.startsWith("vignette") ? "vignette" : null;
                boolean on = effect.equals("fade_out") || effect.endsWith("_on");
                if (group == null) {
                    engine.hud.flash("screen", o, targets, all);
                } else if (on) {
                    engine.hud.show(task, "screen", group, o, targets, all, 0, null, true);
                } else {
                    // Release the held effect with the given transition time.
                    for (HudService.Element el : engine.hud.elementsOf(task.id)) {
                        if (el.id.equals(HudService.elementId(task, "screen", group)) && el.payload != null) el.payload.addProperty("releaseMs", (long) a.get("duration").asNumber() * 50);
                    }
                    if (engine.hud.hide(task, "screen", group) == 0 && effect.equals("fade_in")) engine.hud.flash("screen", o, targets, all);
                }
            }
            case "hud.bossbar" -> bossbar(f, p, type, a, targets, all);
            case "hud.hide" -> {
                String kind = a.get("kind").asText().isEmpty() ? "any" : a.get("kind").asText();
                int n = engine.hud.hide(task, kind, a.get("id").asText());
                ok(task, "hid " + n + " element(s)");
                return;
            }
            default -> {}
        }
        ok(task, type.name());
    }

    // ── Camera, player controls, forced look and screen effects ────────────

    private static void putVec(JsonObject o, String key, csequencer.core.value.Vec3d v) {
        if (v == null) return;
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(v.x());
        arr.add(v.y());
        arr.add(v.z());
        o.add(key, arr);
    }

    private static String entityUuid(Value v) {
        EntityRef r = v == null ? null : v.asEntity();
        return r == null ? "" : r.uuid().toString();
    }

    /**
     * Camera pieces become persistent HUD elements (kinds camera / look / controls / effect) so they reach late
     * joiners and are undone automatically when the sequence ends, is cancelled or fails.
     */
    private void cinematic(Fiber f, Piece p, PieceType type, Map<String, Value> a) {
        Task task = f.task;
        boolean all = allTargets(p, type);
        boolean hasTargets = type.param("targets") != null;
        List<EntityRef> targets = all || !hasTargets ? List.of() : resolveTargets(f, p.params.has("targets") ? p.params.get("targets") : type.param("targets").def);
        if (hasTargets && !all && targets.isEmpty()) task.log("info", type.name() + ": no online targets");
        Scope sc = new Scope(task, null, f.event, f.subject);
        switch (p.type) {
            case "camera.fixed", "camera.path", "camera.orbit" -> {
                JsonObject o = new JsonObject();
                String mode = p.type.substring("camera.".length());
                o.addProperty("mode", mode);
                o.addProperty("blendMs", (long) a.get("blend").asNumber() * 50);
                o.addProperty("releaseMs", (long) a.get("returnBlend").asNumber() * 50);
                o.addProperty("fov", a.get("fov").asNumber());
                o.addProperty("lockPlayer", a.get("lockPlayer").asBool());
                o.addProperty("hideHud", a.get("hideHud").asBool());
                int elementTicks;
                if (mode.equals("orbit")) {
                    o.addProperty("look", a.get("center").asText().equals("entity") ? "at_entity" : a.get("center").asText().equals("point") ? "at_point" : "at_player");
                    putVec(o, "lookAt", a.get("pos").asVec());
                    o.addProperty("entity", entityUuid(a.get("entity")));
                    o.addProperty("radius", a.get("radius").asNumber());
                    o.addProperty("height", a.get("height").asNumber());
                    o.addProperty("startAngle", a.get("startAngle").asNumber());
                    o.addProperty("speed", a.get("speed").asNumber());
                    elementTicks = (int) a.get("duration").asNumber();
                    if (o.get("look").getAsString().equals("at_point") && a.get("pos").asVec() == null) { failPiece(f, p, "Camera: Orbit needs a valid point"); return; }
                } else {
                    o.addProperty("look", a.get("look").asText());
                    putVec(o, "lookAt", a.get("lookAt").asVec());
                    o.addProperty("entity", entityUuid(a.get("entity")));
                    if (mode.equals("fixed")) {
                        csequencer.core.value.Vec3d pos = a.get("pos").asVec();
                        if (pos == null) { failPiece(f, p, "Camera: Fixed Shot needs a valid position (is the tracked player online?)"); return; }
                        putVec(o, "pos", pos);
                        o.addProperty("yaw", a.get("yaw").asNumber());
                        o.addProperty("pitch", a.get("pitch").asNumber());
                        elementTicks = (int) a.get("duration").asNumber();
                    } else {
                        com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
                        for (String line : a.get("points").asText().split("[\\n;|]+")) {
                            String[] tok = line.trim().split("[,\\s]+");
                            if (tok.length < 3 || tok[0].isEmpty()) continue;
                            csequencer.core.value.Vec3d v = ev.vecOf(tok[0] + " " + tok[1] + " " + tok[2], sc);
                            if (v == null) { failPiece(f, p, "Camera: Cinematic Path has an invalid keyframe: '" + line.trim() + "'"); return; }
                            com.google.gson.JsonArray k = new com.google.gson.JsonArray();
                            k.add(v.x());
                            k.add(v.y());
                            k.add(v.z());
                            try {
                                if (tok.length >= 5) { k.add(Double.parseDouble(tok[3])); k.add(Double.parseDouble(tok[4])); }
                            } catch (NumberFormatException ignored) {
                                // keyframe without rotation
                            }
                            pts.add(k);
                            if (pts.size() >= 64) break;
                        }
                        if (pts.size() < 2) { failPiece(f, p, "Camera: Cinematic Path needs at least 2 keyframes"); return; }
                        o.add("points", pts);
                        int dur = Math.max(1, (int) a.get("duration").asNumber());
                        o.addProperty("durationMs", dur * 50L);
                        o.addProperty("easing", a.get("easing").asText());
                        o.addProperty("smooth", a.get("smooth").asBool());
                        o.addProperty("loop", a.get("loop").asBool());
                        boolean hold = a.get("loop").asBool() || "hold".equals(a.get("afterEnd").asText());
                        elementTicks = hold ? 0 : dur + (int) (a.get("blend").asNumber());
                        if (a.get("wait").asBool()) f.suspend(new DelayWait(task.tickCount + dur + (int) a.get("blend").asNumber()));
                    }
                }
                engine.hud.show(task, "camera", "main", o, targets, all, elementTicks, null, true);
            }
            case "camera.reset" -> release(task, "camera", "main", (long) a.get("blend").asNumber() * 50);
            case "camera.look_at" -> {
                JsonObject o = new JsonObject();
                String target = a.get("target").asText();
                o.addProperty("target", target);
                if (target.equals("entity")) {
                    String u = entityUuid(a.get("entity"));
                    if (u.isEmpty()) { failPiece(f, p, "Force Look At: the entity is not set or is gone"); return; }
                    o.addProperty("entity", u);
                } else {
                    csequencer.core.value.Vec3d v = a.get("pos").asVec();
                    if (v == null) { failPiece(f, p, "Force Look At: invalid position"); return; }
                    if (target.equals("block")) v = new csequencer.core.value.Vec3d(Math.floor(v.x()) + 0.5, Math.floor(v.y()) + 0.5, Math.floor(v.z()) + 0.5);
                    putVec(o, "pos", v);
                }
                o.addProperty("strength", a.get("strength").asNumber());
                engine.hud.show(task, "look", "main", o, targets, all, (int) a.get("duration").asNumber(), null, true);
            }
            case "camera.release_look" -> engine.hud.hide(task, "look", "main");
            case "player.animation" -> {
                // Shown to everyone so other players see the animated bodies too.
                com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
                for (EntityRef r : all ? engine.platform.players().online() : targets) if (r != null && r.player()) ids.add(r.uuid().toString());
                JsonObject o = new JsonObject();
                o.addProperty("anim", a.get("animation").asText());
                o.addProperty("intensity", a.get("intensity").asNumber());
                o.add("players", ids);
                engine.hud.show(task, "anim", a.get("animation").asText(), o, List.of(), true, (int) a.get("duration").asNumber(), null, true);
            }
            case "player.stop_animation" -> {
                String an = a.get("animation").asText();
                engine.hud.hide(task, "anim", an.equals("all") ? "" : an);
            }
            case "player.controls" -> {
                JsonObject o = new JsonObject();
                for (String k : List.of("movement", "jump", "look", "actions", "inventory", "screens", "hideHud", "hideHand")) o.addProperty(k, a.get(k).asBool());
                engine.hud.show(task, "controls", "main", o, targets, all, (int) a.get("duration").asNumber(), null, true);
            }
            case "player.release_controls" -> engine.hud.hide(task, "controls", "main");
            case "screen.effect" -> {
                JsonObject o = new JsonObject();
                String effect = a.get("effect").asText();
                o.addProperty("effect", effect);
                o.addProperty("intensity", a.get("intensity").asNumber());
                o.addProperty("words", a.get("words").asText());
                o.addProperty("fadeInMs", (long) a.get("fadeIn").asNumber() * 50);
                o.addProperty("releaseMs", (long) a.get("fadeOut").asNumber() * 50);
                int d = (int) a.get("duration").asNumber();
                engine.hud.show(task, "effect", effect, o, targets, all, d > 0 ? d + (int) a.get("fadeIn").asNumber() : 0, null, true);
            }
            case "screen.clear_effects" -> {
                String effect = a.get("effect").asText();
                release(task, "effect", effect.equals("all") ? "" : effect, (long) a.get("fadeOut").asNumber() * 50);
            }
            default -> {}
        }
        ok(task, type.name());
    }

    /** Hides elements, telling clients how long to fade them out. {@code key} empty = all of that kind. */
    private void release(Task task, String kind, String key, long releaseMs) {
        for (HudService.Element el : engine.hud.elementsOf(task.id)) {
            if (!el.kind.equals(kind) || el.payload == null) continue;
            if (!key.isEmpty() && !el.id.equals(HudService.elementId(task, kind, key))) continue;
            el.payload.addProperty("releaseMs", releaseMs);
        }
        engine.hud.hide(task, kind, key);
    }

    private void bossbar(Fiber f, Piece p, PieceType type, Map<String, Value> a, List<EntityRef> targets, boolean all) {
        Task task = f.task;
        String key = a.get("id").asText().isEmpty() ? "boss" : a.get("id").asText();
        EntityRef subj = f.subject;
        GameEvent evt = f.event;
        String dim = task.request.dimension;
        java.util.function.Function<Map<String, Value>, Map<String, Value>> build = r -> {
            Map<String, Value> out = new HashMap<>();
            double progress;
            Value ent = r.get("entity");
            if (ent.exists()) {
                Scope sc = new Scope(task, null, evt, subj);
                Value alive = queryOf("entity.alive", ent, sc), hp = queryOf("entity.health", ent, sc), max = queryOf("entity.max_health", ent, sc);
                if (!alive.asBool() && r.get("hideWhenDead").asBool()) return null;
                progress = max.asNumber() <= 0 ? 0 : hp.asNumber() / max.asNumber();
            } else {
                double max = r.get("max").asNumber();
                progress = max <= 0 ? 0 : r.get("value").asNumber() / max;
            }
            out.put("op", Value.of("show"));
            out.put("key", Value.of(key));
            out.put("name", r.get("name"));
            out.put("progress", Value.of(Math.max(0, Math.min(1, progress))));
            out.put("color", r.get("color"));
            out.put("style", r.get("style"));
            out.put("darkenSky", r.get("darkenSky"));
            out.put("all", Value.of(all));
            return out;
        };
        Map<String, Value> first = build.apply(a);
        if (first == null) { task.log("info", "Boss Bar: entity already dead"); return; }
        java.util.function.Consumer<Map<String, Value>> send = args -> {
            List<EntityRef> tg = all ? engine.platform.players().online() : targets;
            engine.platform.actions().execute("hud.bossbar", args, new ActionAdapter.ActionContext(task.id, task.project.name, subj, task.request.initiator,
                    tg, dim, 0, engine.settings.commandBlacklist, engine.settings.limits.fillBlockLimit));
        };
        send.accept(first);
        HudService.Element el = engine.hud.show(task, "bossbar", key, new JsonObject(), targets, all, 0, () -> {
            Map<String, Value> next = build.apply(ev.resolveArgs(type.params(), p.params, new Scope(task, null, evt, subj)));
            if (next == null) return null;
            send.accept(next);
            JsonObject o = new JsonObject();
            o.addProperty("p", Math.round(next.get("progress").asNumber() * 1000));
            o.addProperty("n", next.get("name").asText());
            return o;
        }, false);
        el.onRemove = () -> {
            Map<String, Value> hide = new HashMap<>();
            hide.put("op", Value.of("hide"));
            hide.put("key", Value.of(key));
            send.accept(hide);
        };
    }

    private static JsonObject queryJson(String id) {
        JsonObject q = new JsonObject();
        q.addProperty("q", id);
        q.add("args", new JsonObject());
        return q;
    }

    private Value queryOf(String id, Value entity, Scope scope) {
        JsonObject q = new JsonObject();
        q.addProperty("q", id);
        JsonObject args = new JsonObject();
        args.add("entity", entity.toJson());
        q.add("args", args);
        return ev.resolve(csequencer.core.logic.QueryExpr.parse(q), scope);
    }

    final class DeathWait implements Wait {
        final csequencer.core.logic.QueryExpr alive;
        int nextCheck;

        DeathWait(csequencer.core.logic.QueryExpr alive) { this.alive = alive; }

        @Override public boolean poll(Fiber f) {
            if (f.task.tickCount < nextCheck) return false;
            nextCheck = f.task.tickCount + 5;
            return !ev.resolve(alive, Scope.of(f)).asBool();
        }

        @Override public String describe() { return "entity death"; }
    }

    final class AudioSyncWait implements Wait {
        final String channel;
        final long atMs;

        AudioSyncWait(String channel, long atMs) {
            this.channel = channel;
            this.atMs = atMs;
        }

        @Override public boolean poll(Fiber f) {
            AudioService.Session s = engine.audio.find(f.task.id, channel);
            return s == null || engine.audio.positionMs(s) >= atMs;
        }

        @Override public int remaining(Fiber f) {
            AudioService.Session s = engine.audio.find(f.task.id, channel);
            return s == null ? 0 : (int) Math.max(0, (atMs - engine.audio.positionMs(s)) / 50);
        }

        @Override public String describe() { return "audio @" + atMs + "ms"; }
    }

    final class LockWait implements Wait {
        final Fiber fiber;
        final String lock;
        final Timeline tl;
        final int timeoutAt;
        CallHandle call;

        LockWait(Fiber fiber, String lock, Timeline tl, int timeoutAt) {
            this.fiber = fiber;
            this.lock = lock;
            this.tl = tl;
            this.timeoutAt = timeoutAt;
        }

        @Override public boolean poll(Fiber f) {
            if (call == null) {
                if (engine.tryLock(lock, this, f.task)) {
                    call = f.task.spawnCall(f, tl, Map.of(), f.subject, f.event);
                    if (call == null) { engine.unlock(lock, this); return true; }
                    CallHandle c = call;
                    c.whenDone(() -> { engine.unlock(lock, this); f.task.wake(fiber); });
                    return c.done;
                }
                if (timeoutAt >= 0 && f.task.tickCount >= timeoutAt) {
                    f.task.log("warn", "Exclusive section '" + lock + "' timed out waiting for the lock");
                    return true;
                }
                return false;
            }
            return call.done;
        }

        @Override public void cancel(Fiber f) {
            if (call != null && !call.done) f.task.cancelCall(call, "exclusive section cancelled");
            if (call != null) engine.unlock(lock, this);
        }

        @Override public boolean eager() { return true; }

        @Override public String describe() { return call == null ? "lock '" + lock + "'" : "exclusive '" + lock + "'"; }
    }
}
