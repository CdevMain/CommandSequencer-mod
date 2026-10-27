package csequencer.core.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.engine.AudioService;
import csequencer.core.engine.Engine;
import csequencer.core.engine.ProjectCatalog;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.json.J;
import csequencer.core.model.Policies;
import csequencer.core.platform.ClientChannel.Topic;
import csequencer.core.project.ProjectMeta;
import csequencer.core.security.AccessRules;
import csequencer.core.security.Permission;
import csequencer.core.security.ServerSettings;
import csequencer.core.value.EntityRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pushes authoritative execution state to authorised clients. Every view is filtered by the
 * viewer's permissions on the server. Data is sent when it changed and on a heartbeat so
 * clients can detect staleness; clients never derive state on their own.
 */
public final class SyncService {
    private final Engine engine;
    private final Map<UUID, Set<Topic>> subs = new HashMap<>();
    private final Map<String, Integer> lastHash = new HashMap<>();
    private final Map<UUID, Long> lastOverlayTick = new HashMap<>();
    private boolean dirty;

    public SyncService(Engine engine) { this.engine = engine; }

    public void markDirty() { dirty = true; }

    public void subscribe(UUID player, Topic topic, boolean on) {
        Set<Topic> s = subs.computeIfAbsent(player, k -> EnumSet.noneOf(Topic.class));
        if (on) {
            s.add(topic);
            lastHash.remove(player + "|" + topic);
            dirty = true;
        } else {
            s.remove(topic);
        }
    }

    public boolean subscribed(UUID player, Topic t) { return subs.getOrDefault(player, Set.of()).contains(t); }

    public void onPlayerJoin(UUID player) {
        lastHash.remove(player + "|" + Topic.OVERLAY);
        lastOverlayTick.remove(player);
        dirty = true;
    }

    public void onPlayerLeave(UUID player) {
        subs.remove(player);
        lastHash.keySet().removeIf(k -> k.startsWith(player + "|"));
        lastOverlayTick.remove(player);
    }

    // ── Permissions ────────────────────────────────────────────────────────

    public boolean has(UUID player, Permission p) {
        return engine.platform.permissions().has(player, p, engine.settings.level(p));
    }

    public AccessRules.Perms perms(UUID player) { return p -> has(player, p); }

    public boolean canView(Task t, UUID viewer) {
        if (has(viewer, Permission.MONITOR_ALL) || has(viewer, Permission.ADMIN)) return true;
        if (!has(viewer, Permission.MONITOR)) return false;
        return switch (engine.settings.monitorVisibility) {
            case ADMINS_ONLY -> false;
            case ALL -> true;
            case OWN_AND_PARTICIPATING -> TaskSnapshots.involves(t, viewer);
        };
    }

    public boolean canControl(Task t, UUID viewer) {
        if (has(viewer, Permission.CONTROL_ALL) || has(viewer, Permission.ADMIN)) return true;
        return has(viewer, Permission.CONTROL_OWN) && t.initiator() != null && t.initiator().uuid().equals(viewer);
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    public void tick() {
        ServerSettings s = engine.settings;
        long tick = engine.serverTick();
        boolean due = tick % s.syncIntervalTicks == 0;
        if (!due && !dirty) return;
        dirty = false;
        for (var e : subs.entrySet()) {
            UUID viewer = e.getKey();
            if (!engine.platform.players().isOnline(viewer)) continue;
            if (e.getValue().contains(Topic.TASKS)) sendIfChanged(viewer, Topic.TASKS, tasksFor(viewer), false);
            if (e.getValue().contains(Topic.MONITOR)) sendIfChanged(viewer, Topic.MONITOR, monitorFor(viewer), false);
        }
        for (EntityRef p : engine.platform.players().online()) {
            JsonObject o = overlayFor(p.uuid());
            Long last = lastOverlayTick.get(p.uuid());
            boolean heartbeat = last == null || tick - last >= s.overlayHeartbeatTicks;
            if (sendIfChanged(p.uuid(), Topic.OVERLAY, o, heartbeat)) lastOverlayTick.put(p.uuid(), tick);
        }
    }

    private boolean sendIfChanged(UUID viewer, Topic topic, JsonObject payload, boolean force) {
        int h = payload.toString().hashCode();
        String key = viewer + "|" + topic;
        Integer prev = lastHash.get(key);
        if (!force && prev != null && prev == h) return false;
        lastHash.put(key, h);
        JsonObject out = payload.deepCopy();
        out.addProperty("serverTick", engine.serverTick());
        out.addProperty("serverMs", engine.platform.millis());
        engine.platform.clients().send(viewer, topic, out);
        return true;
    }

    // ── Views ──────────────────────────────────────────────────────────────

    public JsonObject tasksFor(UUID viewer) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Task t : engine.tasks()) {
            if (!canView(t, viewer)) continue;
            JsonObject s = TaskSnapshots.summary(t, engine);
            s.addProperty("canControl", canControl(t, viewer));
            arr.add(s);
        }
        o.add("tasks", arr);
        o.addProperty("active", engine.activeCount());
        o.addProperty("limit", engine.settings.limits.maxActiveTasks);
        return o;
    }

    public JsonObject monitorFor(UUID viewer) {
        AccessRules.Perms perms = perms(viewer);
        JsonArray projects = new JsonArray();
        List<Task> tasks = engine.tasks();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (ProjectCatalog.Entry en : engine.catalog().all()) {
            ProjectMeta m = en.meta();
            if (m != null && !AccessRules.canView(m, viewer.toString(), perms)) continue;
            listed.add(en.project().id);
            projects.add(projectEntry(en.project().id, en.project().name, m, en.project().triggers.size(), tasks, viewer));
        }
        // Sequences running from unsaved editor sessions still appear.
        for (Task t : tasks) {
            if (listed.contains(t.project().id) || !canView(t, viewer)) continue;
            listed.add(t.project().id);
            projects.add(projectEntry(t.project().id, t.project().name, t.meta(), t.project().triggers.size(), tasks, viewer));
        }
        JsonObject o = new JsonObject();
        o.add("projects", projects);
        o.addProperty("active", engine.activeCount());
        return o;
    }

    private JsonObject projectEntry(String id, String name, ProjectMeta m, int triggers, List<Task> tasks, UUID viewer) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("name", name);
        p.addProperty("owner", m == null ? "" : m.ownerName);
        p.addProperty("triggers", triggers);
        p.addProperty("triggersEnabled", m == null || m.triggersEnabled);
        JsonArray inst = new JsonArray();
        TaskState agg = null;
        int hidden = 0;
        for (Task t : tasks) {
            if (!t.project().id.equals(id)) continue;
            if (!canView(t, viewer)) { hidden++; if (agg == null || rank(t.state()) > rank(agg)) agg = t.state(); continue; }
            JsonObject s = TaskSnapshots.summary(t, engine);
            s.addProperty("canControl", canControl(t, viewer));
            inst.add(s);
            if (agg == null || rank(t.state()) > rank(agg)) agg = t.state();
        }
        p.addProperty("status", agg == null ? "AVAILABLE" : agg.name());
        p.add("instances", inst);
        p.addProperty("hiddenInstances", hidden);
        JsonObject last = null;
        for (JsonObject h : engine.history.list()) if (id.equals(J.str(h, "projectId", ""))) { last = h; break; }
        if (last != null && (has(viewer, Permission.MONITOR_ALL) || viewer.toString().equals(J.str(last, "ownerUuid", "")))) {
            p.addProperty("lastResult", J.str(last, "result", ""));
            p.addProperty("lastEnded", J.l(last, "endedAt", 0));
        }
        return p;
    }

    private static int rank(TaskState s) {
        return switch (s) {
            case RUNNING -> 9;
            case WAITING -> 8;
            case PAUSED -> 7;
            case QUEUED -> 6;
            case COMPLETING, CANCELLING -> 5;
            case FAILED -> 4;
            case CANCELLED -> 2;
            case COMPLETED -> 1;
        };
    }

    /** Overlay entries this player is allowed to see, most relevant first. */
    public JsonObject overlayFor(UUID viewer) {
        JsonObject o = new JsonObject();
        JsonArray entries = new JsonArray();
        if (engine.settings.announcementsEnabled && has(viewer, Permission.ANNOUNCEMENTS)) {
            List<Task> visible = new ArrayList<>();
            for (Task t : engine.tasks()) if (t.rawState() != TaskState.QUEUED && announceTo(t, viewer)) visible.add(t);
            visible.sort(Comparator.<Task>comparingInt(t -> TaskSnapshots.involves(t, viewer) ? 0 : 1)
                    .thenComparingInt(t -> -t.project().settings.priority).thenComparingLong(Task::seq));
            for (Task t : visible) {
                if (entries.size() >= 6) break;
                entries.add(overlayEntry(t, viewer));
            }
        }
        o.add("entries", entries);
        return o;
    }

    private boolean announceTo(Task t, UUID viewer) {
        Policies.AnnounceMode mode = t.project().settings.announce;
        return switch (mode) {
            case NONE -> false;
            case ALL -> true;
            case PARTICIPANTS -> TaskSnapshots.involves(t, viewer);
            case PERMISSION -> has(viewer, Permission.MONITOR_ALL) || engine.platform.permissions().hasNode(viewer, Permission.ANNOUNCEMENTS.node() + "." + t.project().id);
            case TEAMS -> {
                List<String> teams = engine.platform.permissions().teams(viewer);
                boolean match = false;
                for (String team : t.project().settings.announceTeams) if (teams.contains(team)) { match = true; break; }
                yield match || TaskSnapshots.involves(t, viewer);
            }
        };
    }

    private JsonObject overlayEntry(Task t, UUID viewer) {
        JsonObject e = new JsonObject();
        TaskState st = t.state();
        e.addProperty("task", t.id());
        e.addProperty("name", t.project().name);
        e.addProperty("state", st.name());
        e.addProperty("phase", t.currentPhase());
        e.addProperty("next", t.nextPhase());
        int ttn = t.ticksToNextPhase();
        e.addProperty("ticksToNext", ttn);
        e.addProperty("preAnnounce", ttn >= 0 && t.project().settings.preAnnounceTicks > 0 && ttn <= t.project().settings.preAnnounceTicks);
        e.addProperty("progress", t.progress());
        e.addProperty("position", t.mainPosition());
        e.addProperty("length", t.totalLength());
        e.addProperty("personal", TaskSnapshots.involves(t, viewer));
        e.addProperty("priority", t.project().settings.priority);
        e.addProperty("keepVisible", t.project().settings.overlayKeepVisible);
        e.addProperty("timing", t.project().settings.timing.name());
        String audio = "";
        for (AudioService.Session s : engine.audio.sessionsOf(t.id())) { audio = s.audio.name; break; }
        e.addProperty("audio", audio);
        String waiting = "";
        if (st == TaskState.WAITING) {
            var f = t.nextFiber();
            if (f != null) waiting = f.waitDescription();
            if (waiting.length() > 48) waiting = waiting.substring(0, 47) + "…";
        }
        e.addProperty("waiting", waiting);
        e.addProperty("paused", t.pauseReason() == null ? "" : t.pauseReason().name());
        e.addProperty("result", st.terminal() ? t.resultMessage() : "");
        return e;
    }

    // ── On-demand sends ────────────────────────────────────────────────────

    public boolean sendDetail(UUID viewer, String taskId) {
        Task t = engine.task(taskId);
        if (t == null || !canView(t, viewer)) return false;
        JsonObject d = TaskSnapshots.detail(t, engine);
        d.addProperty("canControl", canControl(t, viewer));
        d.addProperty("serverTick", engine.serverTick());
        engine.platform.clients().send(viewer, Topic.TASK_DETAIL, d);
        return true;
    }

    public void sendHistory(UUID viewer) {
        boolean all = has(viewer, Permission.MONITOR_ALL) || has(viewer, Permission.ADMIN);
        JsonArray arr = new JsonArray();
        for (JsonObject h : engine.history.list()) {
            if (!all && !viewer.toString().equals(J.str(h, "ownerUuid", ""))) {
                boolean participant = false;
                for (var pe : J.arr(h, "participants")) if (pe.isJsonPrimitive() && engine.platform.players().byUuid(viewer) != null
                        && pe.getAsString().equals(engine.platform.players().byUuid(viewer).name())) participant = true;
                if (!participant) continue;
            }
            arr.add(h);
            if (arr.size() >= 300) break;
        }
        JsonObject o = new JsonObject();
        o.add("records", arr);
        engine.platform.clients().send(viewer, Topic.HISTORY, o);
    }

    public void notify(UUID player, String msg, String level) {
        JsonObject o = new JsonObject();
        o.addProperty("msg", msg.length() > 300 ? msg.substring(0, 300) : msg);
        o.addProperty("level", level == null ? "info" : level);
        engine.platform.clients().send(player, Topic.NOTIFY, o);
    }

    public void notifyMonitors(Task t, String msg, String level) {
        for (EntityRef p : engine.platform.players().online()) if (canView(t, p.uuid()) && has(p.uuid(), Permission.MONITOR_ALL)) notify(p.uuid(), "[" + t.project().name + "] " + msg, level);
    }
}
