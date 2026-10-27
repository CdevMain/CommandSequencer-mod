package csequencer.core.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.Policies;
import csequencer.core.model.Project;
import csequencer.core.platform.PlatformAdapter;
import csequencer.core.security.ServerSettings;
import csequencer.core.sync.SyncService;
import csequencer.core.sync.TaskSnapshots;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central, server-authoritative execution engine. Driven by {@link #tick()} once per server tick
 * on the server thread. Owns every task, the scheduler, triggers, audio sessions, history and the
 * client synchronization service.
 *
 * <p>Deterministic ordering within a tick: tasks by priority (desc) then start order; branches by
 * creation order; pieces by (tick, placement order). Budgets defer work to the next tick instead of
 * dropping it, preserving that order.
 */
public final class Engine {
    public interface TaskListener {
        default void started(Task t) {}

        default void finished(Task t, JsonObject historyRecord) {}
    }

    private record LockHolder(Object owner, Task task) {}

    public final PlatformAdapter platform;
    public ServerSettings settings;
    ProjectCatalog catalog;
    final Evaluator evaluator;
    final PieceExecutor executor;
    public final AudioService audio;
    public final HudService hud;
    final TriggerService triggers;
    public final HistoryLog history;
    public final SyncService sync;

    final List<Task> tasks = new ArrayList<>();
    long serverTick;
    private long nextSeq = 1;
    int nextListenerId = 1;
    final Variables globalVars = new Variables();
    private final Map<UUID, Variables> playerVars = new LinkedHashMap<>();
    private final Map<String, LockHolder> locks = new HashMap<>();
    private final Map<String, Long> recentStarts = new LinkedHashMap<>();
    private final Deque<GameEvent> pendingEvents = new ArrayDeque<>();
    private final LinkedHashSet<Long> seenEvents = new LinkedHashSet<>();
    private final List<TaskListener> taskListeners = new ArrayList<>();
    int actionsThisTick;
    int evalNodesThisTick;
    private boolean budgetExhausted;
    private int rotation;
    private boolean shuttingDown;
    private int droppedEvents;
    private final String runId = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);

    public Engine(PlatformAdapter platform, ServerSettings settings, ProjectCatalog catalog) {
        this.platform = platform;
        this.settings = settings;
        this.catalog = catalog == null ? ProjectCatalog.EMPTY : catalog;
        this.evaluator = new Evaluator(this);
        this.executor = new PieceExecutor(this, evaluator);
        this.audio = new AudioService(this);
        this.hud = new HudService(this);
        this.triggers = new TriggerService(this);
        this.history = new HistoryLog(settings.historySize);
        this.sync = new SyncService(this);
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public List<Task> tasks() { return List.copyOf(tasks); }

    public Task task(String id) {
        for (Task t : tasks) if (t.id.equals(id)) return t;
        return null;
    }

    public long serverTick() { return serverTick; }
    public ProjectCatalog catalog() { return catalog; }
    public void setCatalog(ProjectCatalog c) { this.catalog = c == null ? ProjectCatalog.EMPTY : c; triggers.refresh(true); }
    public Variables globalVars() { return globalVars; }
    public void addTaskListener(TaskListener l) { taskListeners.add(l); }
    public int droppedEvents() { return droppedEvents; }
    public List<String> triggerDiagnostics(String projectId) { return triggers.diagnostics(projectId); }

    public void applySettings(ServerSettings s) {
        this.settings = s;
        history.setMax(s.historySize);
    }

    public Variables playerVars(UUID player) {
        Variables v = playerVars.get(player);
        if (v == null) {
            v = new Variables();
            if (playerVars.size() < 20_000) playerVars.put(player, v);
        }
        return v;
    }

    boolean hasPlayerVars(UUID player) { return playerVars.containsKey(player); }

    public int activeCount() {
        int n = 0;
        for (Task t : tasks) if (!t.state.terminal() && t.state != TaskState.QUEUED) n++;
        return n;
    }

    // ── Starting ───────────────────────────────────────────────────────────

    public StartResult start(StartRequest req) {
        if (shuttingDown) return StartResult.fail("Server is shutting down");
        if (req.project == null || req.project.main == null) return StartResult.fail("No project");
        Project p = req.project;
        if (p.pieceCount() > settings.limits.maxPiecesPerProject)
            return StartResult.fail("Project has " + p.pieceCount() + " pieces, limit is " + settings.limits.maxPiecesPerProject);
        if (p.mainLength() > settings.limits.maxTimelineTicks)
            return StartResult.fail("Timeline is longer than the server limit (" + settings.limits.maxTimelineTicks + " ticks)");
        if (req.dedupeKey != null) {
            Long t = recentStarts.get(req.dedupeKey);
            if (t != null && serverTick - t < 200) return StartResult.fail("Duplicate start ignored");
            recentStarts.put(req.dedupeKey, serverTick);
            if (recentStarts.size() > 10_000) {
                Iterator<Map.Entry<String, Long>> it = recentStarts.entrySet().iterator();
                while (it.hasNext() && recentStarts.size() > 5_000) { it.next(); it.remove(); }
            }
        }
        String scopeKey = scopeKey(req);
        boolean queue = false;
        if (req.reuseTaskId == null) {
            List<Task> same = new ArrayList<>();
            for (Task t : tasks) if (!t.state.terminal() && t.project.id.equals(p.id) && t.scopeKey.equals(scopeKey)) same.add(t);
            if (!same.isEmpty()) {
                switch (p.settings.concurrency) {
                    case IGNORE_NEW -> { return StartResult.fail("'" + p.name + "' is already running (" + scopeKey + ")"); }
                    case RESTART -> { for (Task t : same) finish(t, TaskState.CANCELLED, "replaced by a new run"); }
                    case QUEUE -> queue = true;
                    case ALLOW_PARALLEL -> {}
                }
            }
        }
        if (!queue && activeCount() >= settings.limits.maxActiveTasks) {
            if (p.settings.concurrency != Policies.ConcurrencyPolicy.QUEUE) return StartResult.fail("Server task limit reached (" + settings.limits.maxActiveTasks + ")");
            queue = true;
        }
        if (req.initiator != null && req.reuseTaskId == null) {
            int mine = 0;
            for (Task t : tasks) if (!t.state.terminal() && t.request.initiator != null && t.request.initiator.uuid().equals(req.initiator.uuid())) mine++;
            if (mine >= settings.limits.maxTasksPerPlayer) return StartResult.fail("You already have " + mine + " active sequences (limit " + settings.limits.maxTasksPerPlayer + ")");
        }
        if (queue) {
            int queued = 0;
            for (Task t : tasks) if (t.state == TaskState.QUEUED) queued++;
            if (queued >= settings.limits.maxQueuedTasks) return StartResult.fail("Queue is full");
        }
        int commandLevel = commandLevelFor(req);
        String id = req.reuseTaskId != null ? req.reuseTaskId : "T" + runId + "-" + nextSeq;
        Task task = new Task(this, id, nextSeq++, req.runNumber, req, p.copy(), scopeKey, commandLevel);
        tasks.add(task);
        if (queue) {
            task.state = TaskState.QUEUED;
            task.stateReason = "waiting for a free slot";
            task.log("info", "Queued (" + req.reason + ")");
            return StartResult.ok(task, "Queued '" + p.name + "'");
        }
        activate(task);
        return StartResult.ok(task, "Started '" + p.name + "' as " + task.id);
    }

    private void activate(Task task) {
        task.state = TaskState.RUNNING;
        task.stateReason = task.request.reason;
        task.log("info", "Started (" + task.request.reason + (task.request.fromTick > 0 ? ", from tick " + task.request.fromTick : "") + ")");
        try {
            task.init();
        } catch (RuntimeException e) {
            platform.logger().error("Failed to initialise task " + task.id, e);
            finish(task, TaskState.FAILED, "initialisation failed: " + e);
            return;
        }
        for (TaskListener l : taskListeners) safe(() -> l.started(task));
        Map<String, Value> data = new HashMap<>();
        data.put("sequence", Value.of(task.project.name));
        data.put("task", Value.of(task.id));
        data.put("initiator", task.request.initiator == null ? Value.NULL : Value.of(task.request.initiator));
        post(GameEvent.of("sequence.started", task.request.initiator, task.request.dimension, null, data));
        sync.markDirty();
    }

    private int commandLevelFor(StartRequest req) {
        if (req.meta == null) return settings.commandPermissionLevel;
        if (req.meta.trustLevel >= settings.commandPermissionLevel || settings.allowUntrustedCommands) return settings.commandPermissionLevel;
        return 0;
    }

    static String scopeKey(StartRequest req) {
        return switch (req.project.settings.scope) {
            case PLAYER -> req.initiator == null ? "player:server" : "player:" + req.initiator.uuid();
            case ENTITY -> req.subject == null ? "entity:none" : "entity:" + req.subject.uuid();
            case DIMENSION -> "dim:" + (!req.project.settings.dimension.isEmpty() ? req.project.settings.dimension : req.dimension);
            case SEQUENCE -> "sequence";
            case SERVER -> "server";
        };
    }

    Task newProbe(ProjectCatalog.Entry e) {
        StartRequest r = new StartRequest(e.project(), e.meta());
        Task t = new Task(this, "trigger-probe:" + e.project().id, 0, 0, r, e.project(), "probe", 0);
        t.initProbe();
        return t;
    }

    // ── Control ────────────────────────────────────────────────────────────

    public boolean pause(Task t, Task.PauseReason reason) {
        if (t.state != TaskState.RUNNING) return false;
        t.state = TaskState.PAUSED;
        t.pauseReason = reason;
        t.pausedAtMs = platform.millis();
        t.stateReason = "paused (" + reason.name().toLowerCase() + ")";
        t.log("info", "Paused: " + reason.name().toLowerCase());
        audio.pauseTask(t);
        sync.markDirty();
        return true;
    }

    public boolean resume(Task t) {
        if (t.state != TaskState.PAUSED) return false;
        t.pausedTotalMs += platform.millis() - t.pausedAtMs;
        t.state = TaskState.RUNNING;
        t.pauseReason = null;
        t.disconnectDeadlineTick = -1;
        t.stateReason = "resumed";
        t.log("info", "Resumed");
        audio.resumeTask(t);
        sync.markDirty();
        return true;
    }

    public boolean step(Task t) {
        if (t.state != TaskState.PAUSED) return false;
        t.stepRequested = true;
        return true;
    }

    public boolean cancel(Task t, String reason) {
        if (t.state.terminal() || t.state == TaskState.CANCELLING) return false;
        finish(t, TaskState.CANCELLED, reason == null ? "cancelled" : reason);
        return true;
    }

    public StartResult restart(Task t) {
        StartRequest r = t.request.copyForRestart();
        r.reuseTaskId = t.id;
        r.runNumber = t.run + 1;
        if (!t.state.terminal()) finish(t, TaskState.CANCELLED, "restarted");
        tasks.remove(t);
        return start(r);
    }

    /** Cancels every active task (e.g. "stop all"). */
    public int cancelAll(String reason) {
        int n = 0;
        for (Task t : new ArrayList<>(tasks)) if (cancel(t, reason)) n++;
        return n;
    }

    void finish(Task t, TaskState terminal, String msg) {
        if (t.state.terminal() || t.state == TaskState.COMPLETING || t.state == TaskState.CANCELLING) return;
        boolean wasQueued = t.state == TaskState.QUEUED;
        t.state = terminal == TaskState.CANCELLED ? TaskState.CANCELLING : TaskState.COMPLETING;
        if (!wasQueued) cleanup(t);
        t.state = terminal;
        t.stateReason = msg;
        t.resultMessage = msg;
        t.endedAtMs = platform.millis();
        t.finishedAtServerTick = serverTick;
        t.log(terminal == TaskState.FAILED ? "error" : "info", terminal.name().toLowerCase() + ": " + msg);
        JsonObject rec = TaskSnapshots.history(t, this, settings.historyLogLines);
        history.add(rec);
        for (TaskListener l : taskListeners) safe(() -> l.finished(t, rec));
        Map<String, Value> data = new HashMap<>();
        data.put("sequence", Value.of(t.project.name));
        data.put("task", Value.of(t.id));
        data.put("result", Value.of(terminal.name().toLowerCase()));
        post(GameEvent.of("sequence.finished", t.request.initiator, t.request.dimension, null, data));
        sync.markDirty();
    }

    /** Releases everything a task owns. Each step is isolated so cleanup always completes. */
    private void cleanup(Task t) {
        for (Fiber f : new ArrayList<>(t.fibers)) safe(() -> f.cancel("task ended"));
        if (t.root != null) safe(t.root::finish);
        t.listeners.clear();
        t.watchers.clear();
        t.timers.clear();
        t.pendingEvents.clear();
        t.edgeStates.clear();
        safe(() -> locks.values().removeIf(h -> h.task == t));
        safe(() -> audio.stopTask(t));
        safe(() -> hud.clearTask(t));
        safe(() -> platform.actions().onTaskEnded(t.id, t.removeSpawnedOnEnd));
    }

    // ── Locks (flow.mutex) ─────────────────────────────────────────────────

    boolean tryLock(String name, Object owner, Task task) {
        LockHolder h = locks.get(name);
        if (h == null || h.task.state.terminal()) {
            locks.put(name, new LockHolder(owner, task));
            return true;
        }
        return h.owner == owner;
    }

    void unlock(String name, Object owner) {
        LockHolder h = locks.get(name);
        if (h != null && h.owner == owner) locks.remove(name);
    }

    public boolean isLocked(String name) { return locks.containsKey(name); }

    // ── Budgets ────────────────────────────────────────────────────────────

    boolean takeActionBudget(Task t) {
        if (actionsThisTick >= settings.limits.maxActionsPerTick) {
            budgetExhausted = true;
            return false;
        }
        if (t.actionsThisTick >= settings.limits.maxActionsPerTaskPerTick) {
            t.warn("per-task action budget reached; work deferred");
            return false;
        }
        actionsThisTick++;
        t.actionsThisTick++;
        return true;
    }

    boolean evalBudgetExhausted() { return evalNodesThisTick >= settings.limits.maxEvalNodesPerTick; }

    // ── Events and players ─────────────────────────────────────────────────

    /** Queues an event for the next tick. Duplicate event ids are ignored. */
    public void post(GameEvent e) {
        if (shuttingDown || e == null) return;
        if (!seenEvents.add(e.id)) return;
        if (seenEvents.size() > 8192) {
            Iterator<Long> it = seenEvents.iterator();
            while (seenEvents.size() > 4096 && it.hasNext()) { it.next(); it.remove(); }
        }
        if (pendingEvents.size() >= 4096) {
            pendingEvents.pollFirst();
            droppedEvents++;
        }
        pendingEvents.addLast(e);
    }

    public void onPlayerJoin(EntityRef player) {
        for (Task t : tasks) {
            if (t.state == TaskState.PAUSED && t.pauseReason == Task.PauseReason.DISCONNECT && t.request.initiator != null
                    && t.request.initiator.uuid().equals(player.uuid())) {
                t.log("info", "Initiator " + player.name() + " returned");
                resume(t);
            }
        }
        audio.onPlayerJoin(player.uuid());
        hud.onPlayerJoin(player.uuid());
        sync.onPlayerJoin(player.uuid());
    }

    public void onPlayerLeave(EntityRef player) {
        for (Task t : new ArrayList<>(tasks)) {
            if (t.state.terminal() || t.request.initiator == null || !t.request.initiator.uuid().equals(player.uuid())) continue;
            if (t.project.settings.scope == Policies.ExecutionScope.SERVER) continue;
            switch (t.project.settings.disconnect) {
                case CANCEL -> cancel(t, "initiator " + player.name() + " disconnected");
                case PAUSE_UNTIL_RETURN -> {
                    if (t.state == TaskState.QUEUED) { cancel(t, "initiator disconnected"); break; }
                    pause(t, Task.PauseReason.DISCONNECT);
                    t.disconnectDeadlineTick = serverTick + t.project.settings.disconnectGraceTicks;
                }
                case CONTINUE -> t.log("info", "Initiator " + player.name() + " disconnected; continuing");
            }
        }
        sync.onPlayerLeave(player.uuid());
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    public void tick() {
        serverTick++;
        actionsThisTick = 0;
        evalNodesThisTick = 0;
        boolean exhaustedLast = budgetExhausted;
        budgetExhausted = false;

        int n = 0;
        while (!pendingEvents.isEmpty() && n++ < 1024) {
            GameEvent e = pendingEvents.pollFirst();
            safe(() -> triggers.onEvent(e));
            for (Task t : tasks) {
                if (t.state.terminal() || t.state == TaskState.QUEUED) continue;
                if (!wantsEvent(t, e)) continue;
                if (t.pendingEvents.size() < 512) t.pendingEvents.addLast(e);
                else t.warn("event queue full; events dropped");
            }
        }
        safe(triggers::tick);
        promoteQueued();

        for (Task t : new ArrayList<>(tasks)) {
            if (t.state == TaskState.PAUSED && t.disconnectDeadlineTick >= 0 && serverTick >= t.disconnectDeadlineTick)
                cancel(t, "initiator did not return in time");
        }

        List<Task> order = new ArrayList<>();
        for (Task t : tasks) if (t.state == TaskState.RUNNING || (t.state == TaskState.PAUSED && t.stepRequested)) order.add(t);
        order.sort(Comparator.<Task>comparingInt(t -> -t.project.settings.priority).thenComparingLong(t -> t.seq));
        if (exhaustedLast && order.size() > 1) {
            rotation = (rotation + 1) % order.size();
            java.util.Collections.rotate(order, -rotation);
        }
        for (Task t : order) {
            try {
                runTask(t);
            } catch (Throwable e) {
                platform.logger().error("Task " + t.id + " crashed; isolating it", e);
                t.log("error", "Internal error: " + e);
                finish(t, TaskState.FAILED, "internal error: " + e);
            }
        }

        int linger = settings.finishedLingerTicks;
        tasks.removeIf(t -> t.state.terminal() && serverTick - t.finishedAtServerTick >= linger);
        safe(() -> audio.tick(serverTick));
        safe(() -> hud.tick(serverTick));
        safe(sync::tick);
    }

    private boolean wantsEvent(Task t, GameEvent e) {
        for (Listener l : t.listeners) if (l.eventType.equalsIgnoreCase(e.type)) return true;
        return false;
    }

    private void runTask(Task t) {
        if (t.state == TaskState.PAUSED && t.stepRequested) {
            t.stepRequested = false;
            t.state = TaskState.RUNNING;
            t.step();
            if (t.state == TaskState.RUNNING) {
                t.state = TaskState.PAUSED;
                t.pauseReason = Task.PauseReason.STEP;
                t.stateReason = "paused (step)";
            }
            return;
        }
        int steps = 1;
        if (t.project.settings.timing == Policies.TimingMode.REALTIME) {
            long elapsed = platform.millis() - t.startedAtMs - t.pausedTotalMs;
            int target = (int) (elapsed / 50) + 1;
            steps = Math.min(t.project.settings.maxCatchUpTicks + 1, target - t.tickCount);
        }
        for (int i = 0; i < steps && t.state == TaskState.RUNNING; i++) t.step();
    }

    private void promoteQueued() {
        for (Task t : new ArrayList<>(tasks)) {
            if (t.state != TaskState.QUEUED) continue;
            if (activeCount() >= settings.limits.maxActiveTasks) return;
            boolean blocked = false;
            if (t.project.settings.concurrency == Policies.ConcurrencyPolicy.QUEUE) {
                for (Task o : tasks) if (o != t && o.state.active() && o.state != TaskState.QUEUED && o.project.id.equals(t.project.id) && o.scopeKey.equals(t.scopeKey)) { blocked = true; break; }
            }
            if (!blocked) activate(t);
        }
    }

    // ── Persistence of runtime state ───────────────────────────────────────

    public JsonObject exportRuntime() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", 1);
        o.addProperty("savedAt", platform.millis());
        o.add("globalVars", globalVars.toJson());
        JsonObject pv = new JsonObject();
        for (var e : playerVars.entrySet()) if (e.getValue().size() > 0) pv.add(e.getKey().toString(), e.getValue().toJson());
        o.add("playerVars", pv);
        o.add("firedOnce", triggers.exportFired());
        JsonArray ts = new JsonArray();
        for (Task t : tasks) if (t.recoverable()) ts.add(t.recoveryState());
        o.add("tasks", ts);
        return o;
    }

    /** Restores variables and trigger state, then recovers tasks according to their policy. */
    public List<String> importRuntime(JsonObject o) {
        List<String> report = new ArrayList<>();
        if (o == null) return report;
        Variables g = Variables.fromJson(J.obj(o, "globalVars"));
        for (var e : g.asMap().entrySet()) globalVars.set(e.getKey(), e.getValue());
        JsonObject pv = J.obj(o, "playerVars");
        if (pv != null) for (var e : pv.entrySet()) {
            try {
                if (e.getValue().isJsonObject()) playerVars.put(UUID.fromString(e.getKey()), Variables.fromJson(e.getValue().getAsJsonObject()));
            } catch (IllegalArgumentException ignored) {
                report.add("Skipped invalid player variable key " + e.getKey());
            }
        }
        triggers.importFired(J.arr(o, "firedOnce"));
        for (JsonElement el : J.arr(o, "tasks")) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            ProjectCatalog.Entry entry = catalog.find(J.str(t, "projectId", ""));
            if (entry == null) entry = catalog.find(J.str(t, "projectName", ""));
            if (entry == null) { report.add("Cannot recover " + J.str(t, "taskId", "?") + ": project missing"); continue; }
            Policies.RecoveryPolicy policy = entry.project().settings.recovery;
            if (policy == Policies.RecoveryPolicy.DISCARD) { report.add("Discarded " + J.str(t, "taskId", "?")); continue; }
            StartRequest req = new StartRequest(entry.project(), entry.meta()).reason("recovery");
            String iu = J.str(t, "initiatorUuid", "");
            if (!iu.isEmpty()) {
                try {
                    req.initiator(EntityRef.player(UUID.fromString(iu), J.str(t, "initiatorName", "")));
                } catch (IllegalArgumentException ignored) {}
            }
            req.dimension(J.str(t, "dimension", ""));
            req.reuseTaskId = J.str(t, "taskId", null);
            req.runNumber = J.i(t, "run", 1);
            JsonObject args = J.obj(t, "args");
            if (args != null) req.args = args;
            if (policy == Policies.RecoveryPolicy.RESUME) {
                req.fromTick(J.i(t, "position", 0));
                for (var e : Variables.fromJson(J.obj(t, "vars")).asMap().entrySet()) req.restoredVars.put(e.getKey(), e.getValue());
            }
            StartResult r = start(req);
            if (!r.ok()) { report.add("Recovery of " + req.reuseTaskId + " failed: " + r.message()); continue; }
            Task task = r.task();
            report.add((policy == Policies.RecoveryPolicy.RESUME ? "Resumed " : "Restarted ") + task.id + " (" + task.project.name + ")"
                    + (policy == Policies.RecoveryPolicy.RESUME ? " at tick " + req.fromTick : ""));
            if (J.b(t, "paused", false)) pause(task, Task.PauseReason.USER);
            else if (req.initiator != null && task.project.settings.disconnect != Policies.DisconnectPolicy.CONTINUE
                    && task.project.settings.scope != Policies.ExecutionScope.SERVER && !platform.players().isOnline(req.initiator.uuid())) {
                pause(task, Task.PauseReason.DISCONNECT);
                task.disconnectDeadlineTick = serverTick + task.project.settings.disconnectGraceTicks;
            }
        }
        return report;
    }

    /** Fires SERVER_START triggers. Call once after recovery. */
    public void serverStarted() { safe(triggers::onServerStart); }

    /**
     * Stops the engine: captures recoverable state, then cancels every task with full cleanup.
     * Returns the runtime state to persist.
     */
    public JsonObject shutdown() {
        JsonObject runtime = exportRuntime();
        for (Task t : new ArrayList<>(tasks)) if (!t.state.terminal()) finish(t, TaskState.CANCELLED, "server shutdown");
        shuttingDown = true;
        pendingEvents.clear();
        tasks.clear();
        locks.clear();
        return runtime;
    }

    public boolean isShuttingDown() { return shuttingDown; }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            platform.logger().error("CommandSequencer engine error", e);
        }
    }
}
