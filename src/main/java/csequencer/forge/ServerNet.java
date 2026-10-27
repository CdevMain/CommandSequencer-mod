package csequencer.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.CommandSequencerMod;
import csequencer.core.engine.ProjectCatalog;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.StartResult;
import csequencer.core.engine.Task;
import csequencer.core.json.J;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.Ids;
import csequencer.core.model.Project;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.platform.ClientChannel;
import csequencer.core.project.ProjectMeta;
import csequencer.core.security.AccessRules;
import csequencer.core.security.Permission;
import csequencer.core.security.ServerSettings;
import csequencer.core.validate.Validator;
import csequencer.net.CSQNetwork;
import csequencer.net.ChunkAssembler;
import csequencer.net.Payloads;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Server side of the protocol. Every request is: rate limited, size limited, parsed with limits,
 * permission checked on the server, and answered with a "result" message carrying the request id.
 * Clients are never trusted for ownership, trust level, permissions or identifiers.
 */
public final class ServerNet {
    private static final int MAX_ACTION_JSON = 64 * 1024;
    private static final Map<UUID, ChunkAssembler> uploads = new HashMap<>();
    private static final Map<UUID, Deque<CSQNetwork.S2CChunk>> outgoing = new HashMap<>();
    private static final Map<UUID, long[]> buckets = new HashMap<>();
    private static final Map<UUID, Deque<Long>> uploadTimes = new HashMap<>();
    private static final Set<String> pendingAudio = new HashSet<>();
    private static int nextTransfer = 1;

    private ServerNet() {}

    static void reset() {
        uploads.clear();
        outgoing.clear();
        buckets.clear();
        uploadTimes.clear();
        pendingAudio.clear();
    }

    static void onLogout(UUID player) {
        uploads.remove(player);
        outgoing.remove(player);
        buckets.remove(player);
        uploadTimes.remove(player);
    }

    /** Token bucket: 40 requests burst, refilled at 20/s. */
    private static boolean allow(ServerPlayer p) {
        long now = System.currentTimeMillis();
        long[] b = buckets.computeIfAbsent(p.getUUID(), k -> new long[]{40, now});
        b[0] = Math.min(40, b[0] + (now - b[1]) / 50);
        b[1] = now;
        if (b[0] <= 0) return false;
        b[0]--;
        return true;
    }

    private static boolean allowUpload(ServerPlayer p, ServerSettings s) {
        long now = System.currentTimeMillis();
        Deque<Long> q = uploadTimes.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
        if (q.size() >= s.uploadsPerMinute) return false;
        q.addLast(now);
        return true;
    }

    // ── Entry points ───────────────────────────────────────────────────────

    public static void onAction(ServerPlayer p, String op, byte[] data) {
        CSQServer s = CSQServer.get();
        if (s == null) return;
        if (!allow(p)) return;
        JsonObject req;
        try {
            req = Payloads.json(data, MAX_ACTION_JSON);
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Malformed request '{}' from {}", op, p.getGameProfile().getName());
            return;
        }
        String reqId = J.str(req, "req", "");
        if (reqId.length() > 32) reqId = reqId.substring(0, 32);
        try {
            handle(s, p, op, req, reqId);
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.error("[CSQ] Request '" + op + "' failed", e);
            reply(p, reqId, false, "Server error: " + e.getMessage(), null);
        }
    }

    public static void onChunk(ServerPlayer p, CSQNetwork.C2SChunk c) {
        CSQServer s = CSQServer.get();
        if (s == null) return;
        ServerSettings st = s.settings();
        String kind = c.kind();
        int max;
        UUID u = p.getUUID();
        AccessRules.Perms perms = s.engine().sync.perms(u);
        switch (kind) {
            case "project" -> {
                if (!perms.has(Permission.UPLOAD) && !perms.has(Permission.ADMIN)) { if (c.index() == 0) reply(p, meta(c, "req"), false, "You may not upload projects", null); return; }
                max = st.limits.maxProjectBytes;
            }
            case "audio" -> {
                if (!perms.has(Permission.AUDIO_UPLOAD) && !perms.has(Permission.ADMIN)) { if (c.index() == 0) reply(p, meta(c, "req"), false, "You may not upload audio", null); return; }
                max = st.limits.maxAudioBytes;
            }
            default -> { return; }
        }
        if (c.index() == 0 && !allowUpload(p, st)) {
            reply(p, meta(c, "req"), false, "Too many uploads; wait a minute", null);
            return;
        }
        ChunkAssembler.Done done;
        try {
            done = uploads.computeIfAbsent(u, k -> new ChunkAssembler(2, 60_000)).accept(c.transfer(), kind, c.index(), c.total(), c.meta(), c.data(), max);
        } catch (IllegalArgumentException e) {
            reply(p, meta(c, "req"), false, "Upload rejected: " + e.getMessage(), null);
            return;
        }
        if (done == null) return;
        JsonObject meta;
        try {
            meta = J.parseObject(done.meta());
        } catch (Exception e) {
            meta = new JsonObject();
        }
        String reqId = J.str(meta, "req", "");
        try {
            if (kind.equals("project")) saveProject(s, p, meta, Payloads.gunzip(done.data(), max), reqId);
            else saveAudio(s, p, meta, done.data(), reqId);
        } catch (Exception e) {
            reply(p, reqId, false, "Upload failed: " + e.getMessage(), null);
        }
    }

    private static String meta(CSQNetwork.C2SChunk c, String key) {
        try {
            return J.str(J.parseObject(c.meta()), key, "");
        } catch (Exception e) {
            return "";
        }
    }

    // ── Requests ───────────────────────────────────────────────────────────

    private static void handle(CSQServer s, ServerPlayer p, String op, JsonObject req, String reqId) {
        UUID u = p.getUUID();
        var engine = s.engine();
        var sync = engine.sync;
        AccessRules.Perms perms = sync.perms(u);
        switch (op) {
            case "hello" -> sendHello(s, p);
            case "subscribe" -> {
                String topic = J.str(req, "topic", "");
                ClientChannel.Topic t = switch (topic) {
                    case "tasks" -> ClientChannel.Topic.TASKS;
                    case "monitor" -> ClientChannel.Topic.MONITOR;
                    default -> null;
                };
                if (t == null) return;
                if (!perms.has(Permission.MONITOR) && !perms.has(Permission.MONITOR_ALL)) { reply(p, reqId, false, "No monitor permission", null); return; }
                sync.subscribe(u, t, J.b(req, "on", true));
            }
            case "task.detail" -> {
                String id = J.str(req, "task", "");
                if (!sync.sendDetail(u, id)) {
                    // The task ended and left the active list (or is not visible): tell the client to stop polling.
                    JsonObject gone = new JsonObject();
                    gone.addProperty("id", id);
                    gone.addProperty("gone", true);
                    JsonObject h = engine.history.find(id);
                    if (h != null && (sync.has(u, Permission.MONITOR_ALL) || u.toString().equals(J.str(h, "ownerUuid", "")))) {
                        gone.addProperty("state", J.str(h, "result", ""));
                        gone.addProperty("reason", J.str(h, "message", ""));
                    }
                    ForgePlatform.send(p, "task_detail", gone);
                }
            }
            case "task.control" -> {
                Task t = engine.task(J.str(req, "task", ""));
                if (t == null || !sync.canView(t, u)) { reply(p, reqId, false, "Task not found", null); return; }
                if (!sync.canControl(t, u)) { reply(p, reqId, false, "You may not control this task", null); return; }
                String action = J.str(req, "action", "");
                boolean ok = switch (action) {
                    case "pause" -> engine.pause(t, Task.PauseReason.USER);
                    case "resume" -> engine.resume(t);
                    case "step" -> engine.step(t);
                    case "cancel" -> engine.cancel(t, "cancelled by " + p.getGameProfile().getName());
                    case "restart" -> engine.restart(t).ok();
                    default -> false;
                };
                if (ok) CommandSequencerMod.LOGGER.info("[CSQ] {} {} task {}", p.getGameProfile().getName(), action, t.id());
                reply(p, reqId, ok, ok ? action + ": " + t.project().name : "Cannot " + action + " in state " + t.state(), null);
                sync.markDirty();
            }
            case "tasks.stopall" -> {
                if (!perms.has(Permission.CONTROL_ALL) && !perms.has(Permission.ADMIN)) { reply(p, reqId, false, "No permission", null); return; }
                int n = engine.cancelAll("stopped by " + p.getGameProfile().getName());
                reply(p, reqId, true, "Cancelled " + n + " task(s)", null);
            }
            case "history.get" -> sync.sendHistory(u);
            case "projects.list" -> sendProjectList(s, p);
            case "areas.get" -> sendAreas(s, p);
            case "project.get" -> {
                ProjectCatalog.Entry e = s.store().find(J.str(req, "id", ""));
                if (e == null || !AccessRules.canView(e.meta(), u.toString(), perms)) { reply(p, reqId, false, "Project not found", null); return; }
                JsonObject meta = new JsonObject();
                meta.addProperty("req", reqId);
                meta.add("meta", e.meta().toJson());
                meta.addProperty("canEdit", AccessRules.canEdit(e.meta(), u.toString(), perms));
                byte[] data = Payloads.gzip(ProjectCodec.writeCompact(e.project()).getBytes(StandardCharsets.UTF_8));
                sendChunked(p, "project", J.COMPACT.toJson(meta), data);
            }
            case "project.delete" -> {
                ProjectCatalog.Entry e = s.store().find(J.str(req, "id", ""));
                if (e == null) { reply(p, reqId, false, "Project not found", null); return; }
                if (!AccessRules.canDelete(e.meta(), u.toString(), perms)) { reply(p, reqId, false, "Only the owner or an admin can delete", null); return; }
                s.store().delete(e.project().id);
                engine.setCatalog(s.store());
                CommandSequencerMod.LOGGER.info("[CSQ] {} deleted project {}", p.getGameProfile().getName(), e.project().name);
                reply(p, reqId, true, "Deleted '" + e.project().name + "' (kept in trash/)", null);
                sendProjectList(s, p);
            }
            case "project.run" -> {
                ProjectCatalog.Entry e = s.store().find(J.str(req, "id", ""));
                if (e == null) { reply(p, reqId, false, "Project not found", null); return; }
                if (!AccessRules.canRun(e.meta(), u.toString(), perms)) { reply(p, reqId, false, "You may not run this sequence", null); return; }
                boolean debug = J.b(req, "debug", false);
                if (debug && !AccessRules.canEdit(e.meta(), u.toString(), perms)) debug = false;
                StartRequest r = new StartRequest(e.project(), e.meta()).initiator(ForgePlatform.ref(p)).dimension(ForgePlatform.dim(p.level()))
                        .fromTick(Math.max(0, J.i(req, "fromTick", 0))).debug(debug).reason("run by " + p.getGameProfile().getName())
                        .dedupe(u + "|run|" + reqId);
                StartResult res = engine.start(r);
                JsonObject extra = new JsonObject();
                if (res.ok()) extra.addProperty("task", res.task().id());
                reply(p, reqId, res.ok(), res.message(), extra);
            }
            case "project.share" -> {
                ProjectCatalog.Entry e = s.store().find(J.str(req, "id", ""));
                if (e == null) { reply(p, reqId, false, "Project not found", null); return; }
                if (!AccessRules.canDelete(e.meta(), u.toString(), perms)) { reply(p, reqId, false, "Only the owner or an admin can change sharing", null); return; }
                ProjectMeta m = ProjectMeta.fromJson(e.meta().toJson());
                ProjectMeta.Visibility v = J.en(req, "visibility", ProjectMeta.Visibility.class, m.visibility);
                m.visibility = v;
                for (String name : J.strings(req, "addEditors")) {
                    var prof = s.server().getProfileCache() == null ? Optional.<com.mojang.authlib.GameProfile>empty() : s.server().getProfileCache().get(name);
                    prof.ifPresent(gp -> { if (!m.editors.contains(gp.getId().toString()) && m.editors.size() < 64) m.editors.add(gp.getId().toString()); });
                }
                for (String id : J.strings(req, "removeEditors")) m.editors.remove(id);
                s.store().updateMeta(m);
                reply(p, reqId, true, "Sharing updated: " + v.name().toLowerCase(Locale.ROOT), null);
                sendProjectList(s, p);
            }
            case "project.triggers" -> {
                ProjectCatalog.Entry e = s.store().find(J.str(req, "id", ""));
                if (e == null) { reply(p, reqId, false, "Project not found", null); return; }
                if (!AccessRules.canEdit(e.meta(), u.toString(), perms)) { reply(p, reqId, false, "No permission", null); return; }
                ProjectMeta m = ProjectMeta.fromJson(e.meta().toJson());
                m.triggersEnabled = J.b(req, "enabled", true);
                s.store().updateMeta(m);
                engine.setCatalog(s.store());
                reply(p, reqId, true, "Triggers " + (m.triggersEnabled ? "armed" : "disarmed"), null);
                sendProjectList(s, p);
            }
            case "settings.get" -> {
                if (!perms.has(Permission.ADMIN)) { reply(p, reqId, false, "Admins only", null); return; }
                JsonObject o = s.settings().toJson();
                o.addProperty("req", reqId);
                ForgePlatform.send(p, "settings", o);
            }
            case "settings.set" -> {
                if (!perms.has(Permission.ADMIN)) { reply(p, reqId, false, "Admins only", null); return; }
                JsonObject o = J.obj(req, "settings");
                if (o == null) { reply(p, reqId, false, "Missing settings", null); return; }
                s.applySettings(ServerSettings.fromJson(o));
                CommandSequencerMod.LOGGER.info("[CSQ] Server settings changed by {}", p.getGameProfile().getName());
                reply(p, reqId, true, "Server settings saved", null);
                sendHello(s, p);
            }
            case "audio.request" -> {
                String hash = J.str(req, "hash", "").toLowerCase(Locale.ROOT);
                Path f = s.audio().find(hash);
                if (f == null) { reply(p, reqId, false, "Audio not on server", null); return; }
                String key = u + "|" + hash;
                if (!pendingAudio.add(key)) return;
                s.io().execute(() -> {
                    try {
                        byte[] bytes = Files.readAllBytes(f);
                        s.server().execute(() -> {
                            pendingAudio.remove(key);
                            ServerPlayer again = s.server().getPlayerList().getPlayer(u);
                            if (again == null) return;
                            JsonObject meta = new JsonObject();
                            meta.addProperty("hash", hash);
                            meta.addProperty("format", f.getFileName().toString().endsWith(".mp3") ? "mp3" : "wav");
                            sendChunked(again, "audio", J.COMPACT.toJson(meta), bytes);
                        });
                    } catch (Exception ex) {
                        s.server().execute(() -> pendingAudio.remove(key));
                    }
                });
            }
            default -> reply(p, reqId, false, "Unknown request " + op, null);
        }
    }

    // ── Saving ─────────────────────────────────────────────────────────────

    private static void saveProject(CSQServer s, ServerPlayer p, JsonObject meta, byte[] json, String reqId) {
        UUID u = p.getUUID();
        AccessRules.Perms perms = s.engine().sync.perms(u);
        ProjectCodec.ReadResult rr = ProjectCodec.read(new String(json, StandardCharsets.UTF_8));
        Project proj = rr.project();
        if (!Ids.isValid(proj.id)) proj.id = Ids.next("proj");
        if (proj.name == null || proj.name.isBlank() || proj.name.length() > 64) { reply(p, reqId, false, "Project name must be 1-64 characters", null); return; }
        ProjectCatalog.Entry existing = s.store().find(proj.id);
        if (existing != null && !existing.project().id.equals(proj.id)) existing = null;
        ProjectMeta m;
        if (existing == null) {
            if (!AccessRules.canCreate(perms)) { reply(p, reqId, false, "You may not create projects", null); return; }
            m = new ProjectMeta();
            m.id = proj.id;
            m.ownerUuid = u.toString();
            m.ownerName = p.getGameProfile().getName();
        } else {
            if (!AccessRules.canEdit(existing.meta(), u.toString(), perms)) { reply(p, reqId, false, "You may not edit '" + existing.project().name + "'", null); return; }
            long base = J.l(meta, "baseRevision", -1);
            if (base >= 0 && base != existing.project().revision && !J.b(meta, "force", false)) {
                reply(p, reqId, false, "Conflict: '" + proj.name + "' was changed by someone else (revision " + existing.project().revision
                        + "). Reload or save with overwrite.", conflictExtra(existing.project().revision));
                return;
            }
            m = ProjectMeta.fromJson(existing.meta().toJson());
        }
        if (s.store().nameTaken(proj.name, proj.id)) { reply(p, reqId, false, "Another project is already named '" + proj.name + "'", null); return; }
        m.trustLevel = s.server().getProfilePermissions(p.getGameProfile());
        m.lastSavedBy = p.getGameProfile().getName();
        Validator.Report report = Validator.validate(proj, s.platform().info(), s.settings().limits, s.audio()::has, n -> s.store().find(n) != null);
        if (proj.pieceCount() > s.settings().limits.maxPiecesPerProject) { reply(p, reqId, false, "Too many pieces (limit " + s.settings().limits.maxPiecesPerProject + ")", null); return; }
        var saved = s.store().save(proj, m);
        s.engine().setCatalog(s.store());
        JsonObject extra = new JsonObject();
        extra.addProperty("id", saved.project().id);
        extra.addProperty("revision", saved.project().revision);
        extra.add("report", report.toJson());
        boolean commandsDisabled = m.trustLevel < s.settings().commandPermissionLevel && !s.settings().allowUntrustedCommands && usesCommands(saved.project());
        String msg = "Saved '" + saved.project().name + "' (rev " + saved.project().revision + ")"
                + (report.hasErrors() ? " with " + report.count(Validator.Severity.ERROR) + " error(s)" : "")
                + (commandsDisabled ? " — command pieces disabled: your permission level is below the server's command trust level" : "");
        CommandSequencerMod.LOGGER.info("[CSQ] {} saved project {} rev {}", p.getGameProfile().getName(), saved.project().name, saved.project().revision);
        reply(p, reqId, true, msg, extra);
    }

    private static boolean usesCommands(Project p) {
        for (var t : p.allTimelines()) for (var x : t.pieces) {
            var type = PieceRegistry.piece(x.type);
            if (type != null && type.privileged()) return true;
        }
        return false;
    }

    private static JsonObject conflictExtra(long rev) {
        JsonObject o = new JsonObject();
        o.addProperty("conflict", true);
        o.addProperty("revision", rev);
        return o;
    }

    private static void saveAudio(CSQServer s, ServerPlayer p, JsonObject meta, byte[] data, String reqId) {
        String hash = J.str(meta, "hash", "").toLowerCase(Locale.ROOT);
        String fmt = J.str(meta, "format", "");
        UUID u = p.getUUID();
        s.io().execute(() -> {
            String result;
            boolean ok;
            try {
                result = s.audio().save(data, hash, fmt);
                ok = true;
            } catch (Exception e) {
                result = e.getMessage();
                ok = false;
            }
            boolean fok = ok;
            String fres = result;
            s.server().execute(() -> {
                ServerPlayer again = s.server().getPlayerList().getPlayer(u);
                if (again == null) return;
                JsonObject extra = new JsonObject();
                extra.addProperty("hash", hash);
                if (fok) extra.addProperty("format", fres);
                reply(again, reqId, fok, fok ? "Audio uploaded" : "Audio rejected: " + fres, extra);
            });
        });
    }

    // ── Responses ──────────────────────────────────────────────────────────

    public static void reply(ServerPlayer p, String reqId, boolean ok, String msg, JsonObject extra) {
        JsonObject o = extra == null ? new JsonObject() : extra.deepCopy();
        o.addProperty("req", reqId == null ? "" : reqId);
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        ForgePlatform.send(p, "result", o);
    }

    static void sendHello(CSQServer s, ServerPlayer p) {
        JsonObject o = new JsonObject();
        JsonObject perms = new JsonObject();
        for (Permission perm : Permission.values()) perms.addProperty(perm.id, ForgePermissions.check(p, perm));
        o.add("perms", perms);
        o.addProperty("opLevel", s.server().getProfilePermissions(p.getGameProfile()));
        o.addProperty("platform", s.platform().info().loader() + " " + s.platform().info().loaderVersion());
        o.addProperty("minecraft", s.platform().info().minecraftVersion());
        o.addProperty("maxProjectBytes", s.settings().limits.maxProjectBytes);
        o.addProperty("maxAudioBytes", s.settings().limits.maxAudioBytes);
        o.addProperty("commandLevel", s.settings().commandPermissionLevel);
        o.addProperty("announcements", s.settings().announcementsEnabled);
        JsonArray caps = new JsonArray();
        for (var c : s.platform().info().capabilities()) caps.add(c.name());
        o.add("capabilities", caps);
        ForgePlatform.send(p, "hello", o);
    }

    /** Area triggers of the projects this player may edit (for the in-world area viewer). */
    static void sendAreas(CSQServer s, ServerPlayer p) {
        UUID u = p.getUUID();
        AccessRules.Perms perms = s.engine().sync.perms(u);
        JsonArray arr = new JsonArray();
        if (perms.has(Permission.EDIT)) {
            for (ProjectCatalog.Entry e : s.store().all()) {
                if (!AccessRules.canEdit(e.meta(), u.toString(), perms)) continue;
                for (csequencer.core.model.Trigger t : e.project().triggers) {
                    if (t.kind != csequencer.core.model.Policies.TriggerKind.AREA || !t.hasArea()) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("project", e.project().name);
                    o.addProperty("name", t.name);
                    o.addProperty("armed", t.enabled && e.meta().triggersEnabled);
                    o.addProperty("dim", t.areaDim == null ? "" : t.areaDim);
                    o.add("from", J.toIntArray(t.areaFrom));
                    o.add("to", J.toIntArray(t.areaTo));
                    arr.add(o);
                    if (arr.size() >= 256) break;
                }
            }
        }
        JsonObject out = new JsonObject();
        out.add("areas", arr);
        ForgePlatform.send(p, "areas", out);
    }

    static void sendProjectList(CSQServer s, ServerPlayer p) {
        UUID u = p.getUUID();
        AccessRules.Perms perms = s.engine().sync.perms(u);
        JsonArray arr = new JsonArray();
        for (ProjectCatalog.Entry e : s.store().all()) {
            if (!AccessRules.canView(e.meta(), u.toString(), perms)) continue;
            JsonObject o = e.meta().toJson();
            o.addProperty("canEdit", AccessRules.canEdit(e.meta(), u.toString(), perms));
            o.addProperty("canRun", AccessRules.canRun(e.meta(), u.toString(), perms));
            o.addProperty("canDelete", AccessRules.canDelete(e.meta(), u.toString(), perms));
            o.addProperty("triggers", e.project().triggers.size());
            o.addProperty("description", e.project().description);
            int running = 0;
            for (Task t : s.engine().tasks()) if (t.project().id.equals(e.project().id) && !t.state().terminal()) running++;
            o.addProperty("running", running);
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("projects", arr);
        out.addProperty("canCreate", AccessRules.canCreate(perms));
        ForgePlatform.send(p, "projects", out);
    }

    // ── Chunked downloads (rate limited per player per tick) ───────────────

    static void sendChunked(ServerPlayer p, String kind, String meta, byte[] data) {
        int id = nextTransfer++;
        int total = Math.max(1, (data.length + CSQNetwork.CHUNK_S2C - 1) / CSQNetwork.CHUNK_S2C);
        Deque<CSQNetwork.S2CChunk> q = outgoing.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        for (int i = 0; i < total; i++) {
            int from = i * CSQNetwork.CHUNK_S2C, to = Math.min(data.length, from + CSQNetwork.CHUNK_S2C);
            q.addLast(new CSQNetwork.S2CChunk(id, kind, i, total, meta, Arrays.copyOfRange(data, from, to)));
        }
    }

    static void tick(CSQServer s) {
        int budget = s.settings().audioBytesPerTickPerPlayer;
        for (Iterator<Map.Entry<UUID, Deque<CSQNetwork.S2CChunk>>> it = outgoing.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ServerPlayer p = s.server().getPlayerList().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            int sent = 0;
            while (!e.getValue().isEmpty() && (sent == 0 || sent + e.getValue().peekFirst().data().length <= budget)) {
                CSQNetwork.S2CChunk c = e.getValue().pollFirst();
                CSQNetwork.toPlayer(p, c);
                sent += c.data().length;
            }
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    @SuppressWarnings("unused")
    private static JsonElement nothing() { return null; }
}
