package csequencer.core.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import csequencer.core.logic.CNode;
import csequencer.core.model.Policies;
import csequencer.core.model.Project;
import csequencer.core.model.Trigger;
import csequencer.core.value.EntityRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates the automatic start triggers of stored projects (server side only).
 * Duplicate protection: per-trigger cooldowns, once-per-player/once-per-server sets (persisted),
 * edge detection for condition triggers, and start-request dedupe keys.
 */
final class TriggerService {
    private record Armed(ProjectCatalog.Entry entry, Trigger trigger) {}

    private final Engine engine;
    private List<Armed> armed = new ArrayList<>();
    private long lastRefresh = -1000;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Boolean> previous = new HashMap<>();
    final Set<String> firedOnce = new HashSet<>();
    private final Map<String, Task> probes = new HashMap<>();

    TriggerService(Engine engine) { this.engine = engine; }

    void refresh(boolean force) {
        if (!force && engine.serverTick - lastRefresh < 20) return;
        lastRefresh = engine.serverTick;
        List<Armed> list = new ArrayList<>();
        for (ProjectCatalog.Entry e : engine.catalog.all()) {
            if (e.meta() != null && !e.meta().triggersEnabled) continue;
            for (Trigger t : e.project().triggers) if (t.enabled) list.add(new Armed(e, t));
        }
        armed = list;
        // Drop probes of removed/changed projects so conditions recompile with the new revision.
        Set<String> keep = new HashSet<>();
        for (Armed a : list) keep.add(probeKey(a.entry.project()));
        probes.keySet().retainAll(keep);
    }

    private static String probeKey(Project p) { return p.id + "@" + p.revision + "@" + System.identityHashCode(p); }

    private Task probe(ProjectCatalog.Entry e) {
        return probes.computeIfAbsent(probeKey(e.project()), k -> engine.newProbe(e));
    }

    void onEvent(GameEvent ev) {
        refresh(false);
        for (Armed a : armed) {
            Trigger t = a.trigger;
            if (t.kind != Policies.TriggerKind.EVENT || !t.eventType.equalsIgnoreCase(ev.type)) continue;
            EntityRef subject = ev.subject();
            Task probe = probe(a.entry);
            if (t.condition != null && !t.condition.isEmpty()) {
                CNode c = engine.evaluator.compile(probe, "trigger:" + t.id, t.condition.toJson());
                if (!engine.evaluator.test(c, new Scope(probe, null, ev, subject), "trigger:" + t.id)) continue;
            }
            EntityRef initiator = t.subject == Policies.TriggerSubject.SERVER ? null : subject;
            fire(a, initiator, "e" + ev.id, ev.dimension);
        }
    }

    void tick() {
        refresh(false);
        for (Armed a : armed) {
            Trigger t = a.trigger;
            boolean area = t.kind == Policies.TriggerKind.AREA;
            if ((t.kind != Policies.TriggerKind.CONDITION && !area) || engine.serverTick % Math.max(1, t.intervalTicks) != 0) continue;
            if (area && !t.hasArea()) continue;
            Task probe = probe(a.entry);
            boolean hasCond = t.condition != null && !t.condition.isEmpty();
            CNode c = area && !hasCond ? null : engine.evaluator.compile(probe, "trigger:" + t.id, t.condition == null ? null : t.condition.toJson());
            List<EntityRef> subjects = new ArrayList<>();
            if (t.subject == Policies.TriggerSubject.SERVER && !area) subjects.add(null);
            else subjects.addAll(engine.platform.players().online());
            Set<String> seen = new HashSet<>();
            for (EntityRef s : subjects) {
                String key = t.id + "|" + a.entry.project().id + "|" + (s == null ? "server" : s.key());
                seen.add(key);
                boolean now;
                if (area) {
                    // Inside the box AND (optional) extra conditions, so the area cannot be set off by just anyone/anything.
                    var pos = engine.platform.players().positionOf(s.uuid());
                    now = pos != null && t.inArea(engine.platform.players().dimensionOf(s.uuid()), pos.x(), pos.y(), pos.z())
                            && (c == null || engine.evaluator.test(c, new Scope(probe, null, null, s), "trigger:" + t.id));
                } else {
                    now = engine.evaluator.test(c, new Scope(probe, null, null, s), "trigger:" + t.id);
                }
                Boolean prev = previous.put(key, now);
                boolean fire = switch (t.edge) {
                    case LEVEL -> now;
                    case ENTERED -> prev != null && now && !prev;
                    case EXITED -> prev != null && !now && prev;
                    case CHANGED -> prev != null && now != prev;
                };
                if (fire) fire(a, area && t.subject == Policies.TriggerSubject.SERVER ? null : s, "c" + engine.serverTick, s == null ? "" : engine.platform.players().dimensionOf(s.uuid()));
            }
            previous.keySet().removeIf(k -> k.startsWith(t.id + "|" + a.entry.project().id + "|") && !seen.contains(k));
        }
    }

    void onServerStart() {
        refresh(true);
        for (Armed a : armed) if (a.trigger.kind == Policies.TriggerKind.SERVER_START) fire(a, null, "start", "");
    }

    private void fire(Armed a, EntityRef subject, String nonce, String dimension) {
        Trigger t = a.trigger;
        String subjectKey = subject == null ? "server" : subject.key();
        String base = a.entry.project().id + "|" + t.id;
        if (t.oncePerServer && firedOnce.contains(base + "|*")) return;
        if (t.oncePerPlayer && subject != null && firedOnce.contains(base + "|" + subjectKey)) return;
        String cdKey = base + "|" + subjectKey;
        Long last = cooldowns.get(cdKey);
        if (t.cooldownTicks > 0 && last != null && engine.serverTick - last < t.cooldownTicks) return;
        StartRequest req = new StartRequest(a.entry.project(), a.entry.meta())
                .initiator(subject).dimension(dimension).reason("trigger:" + (t.name.isEmpty() ? t.id : t.name))
                .dedupe("trigger|" + base + "|" + subjectKey + "|" + nonce);
        StartResult r = engine.start(req);
        if (r.ok()) {
            cooldowns.put(cdKey, engine.serverTick);
            if (t.oncePerServer) firedOnce.add(base + "|*");
            if (t.oncePerPlayer && subject != null) firedOnce.add(base + "|" + subjectKey);
        }
        if (cooldowns.size() > 50_000) cooldowns.clear();
    }

    JsonArray exportFired() {
        JsonArray a = new JsonArray();
        for (String s : firedOnce) a.add(s);
        return a;
    }

    void importFired(JsonArray a) {
        if (a == null) return;
        for (JsonElement e : a) if (e.isJsonPrimitive() && firedOnce.size() < 200_000) firedOnce.add(e.getAsString());
    }

    /** Diagnostics logged while evaluating triggers of a project. */
    List<String> diagnostics(String projectId) {
        for (var e : probes.entrySet()) if (e.getKey().startsWith(projectId + "@")) return e.getValue().log();
        return List.of();
    }
}
