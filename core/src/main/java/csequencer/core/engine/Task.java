package csequencer.core.engine;

import com.google.gson.JsonObject;
import csequencer.core.logic.ConditionCompiler;
import csequencer.core.model.Marker;
import csequencer.core.model.Piece;
import csequencer.core.model.Policies;
import csequencer.core.model.Project;
import csequencer.core.model.Timeline;
import csequencer.core.model.Track;
import csequencer.core.model.VariableDecl;
import csequencer.core.project.ProjectMeta;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A running instance of a sequence. Owns its branches, listeners, timers, variables and
 * diagnostics. All mutation happens on the server thread through {@link Engine}.
 */
public final class Task {
    public enum PauseReason { USER, BREAKPOINT, STEP, DISCONNECT, SEQUENCE }

    final Engine engine;
    final String id;
    final long seq;
    final int run;
    final Project project;
    final ProjectMeta meta;
    final StartRequest request;
    final String scopeKey;
    final int commandLevel;
    final Random random;
    final boolean debug;

    TaskState state = TaskState.RUNNING;
    String stateReason = "";
    PauseReason pauseReason;
    long disconnectDeadlineTick = -1;
    CallHandle root;
    final List<Fiber> fibers = new ArrayList<>();
    int nextFiberId = 1;
    int nextHandleId = 1;

    int tickCount;
    final long startedAtMs;
    final long startedAtServerTick;
    long endedAtMs;
    long pausedAtMs;
    long pausedTotalMs;
    long finishedAtServerTick = -1;

    final Variables vars = new Variables();
    final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    final Map<UUID, String> participantNames = new LinkedHashMap<>();
    final List<Listener> listeners = new ArrayList<>();
    final List<Watcher> watchers = new ArrayList<>();
    final List<Timer> timers = new ArrayList<>();
    final Map<String, Boolean> edgeStates = new HashMap<>();
    final Map<String, Integer> cooldowns = new HashMap<>();
    final Deque<GameEvent> pendingEvents = new ArrayDeque<>();
    final Map<String, ConditionCompiler.Result> compiled = new HashMap<>();
    final Map<Object, ConditionCompiler.Result> compiledByIdentity = new IdentityHashMap<>();
    final Set<String> loggedOnce = new HashSet<>();

    // Diagnostics
    int actionsOk, actionsFailed, actionsThisTick;
    long evalNodesTotal;
    int evalNodesThisTick;
    long lastTickNanos, maxTickNanos;
    double avgTickNanos;
    int deferredTicks;
    boolean deferredThisTick;
    String lastResult = "";
    String lastError = "";
    final Deque<String> log = new ArrayDeque<>();
    final Set<String> warnings = new LinkedHashSet<>();

    /** Player the pieces act on by default (target mode "tracked"); initially the initiator. */
    EntityRef tracked;
    String manualPhase, manualNext;
    int manualPhasePos = -1;
    boolean stepRequested;
    boolean removeSpawnedOnEnd;
    String resultMessage = "";

    // Scheduling within a tick
    private final Deque<Fiber> runQueue = new ArrayDeque<>();
    private final Set<Fiber> queued = new HashSet<>();
    private boolean stepping;
    int stepsThisTick;

    Task(Engine engine, String id, long seq, int run, StartRequest req, Project project, String scopeKey, int commandLevel) {
        this.engine = engine;
        this.id = id;
        this.seq = seq;
        this.run = run;
        this.project = project;
        this.meta = req.meta;
        this.request = req;
        this.scopeKey = scopeKey;
        this.commandLevel = commandLevel;
        this.debug = req.debug;
        this.random = new Random(id.hashCode() * 31L + run);
        this.startedAtMs = engine.platform.millis();
        this.startedAtServerTick = engine.serverTick;
        if (req.initiator != null) addParticipant(req.initiator);
    }

    // ── Public read API (used by snapshots, tests and adapters) ────────────

    public String id() { return id; }
    public long seq() { return seq; }
    public int runNumber() { return run; }
    public Project project() { return project; }
    public ProjectMeta meta() { return meta; }
    public StartRequest request() { return request; }
    public String scopeKey() { return scopeKey; }
    public int tickCount() { return tickCount; }
    public long startedAtMs() { return startedAtMs; }
    public long endedAtMs() { return endedAtMs; }
    public String stateReason() { return stateReason; }
    public PauseReason pauseReason() { return pauseReason; }
    public Variables vars() { return vars; }
    public List<Fiber> fibers() { return List.copyOf(fibers); }
    public Set<UUID> participants() { return java.util.Collections.unmodifiableSet(participants); }
    public Map<UUID, String> participantNames() { return java.util.Collections.unmodifiableMap(participantNames); }
    public int actionsOk() { return actionsOk; }
    public int actionsFailed() { return actionsFailed; }
    public String lastResult() { return lastResult; }
    public String lastError() { return lastError; }
    public List<String> log() { return List.copyOf(log); }
    public Set<String> warnings() { return java.util.Collections.unmodifiableSet(warnings); }
    public boolean debug() { return debug; }
    public double avgTickMicros() { return avgTickNanos / 1000.0; }
    public double maxTickMicros() { return maxTickNanos / 1000.0; }
    public long evalNodesTotal() { return evalNodesTotal; }
    public int deferredTicks() { return deferredTicks; }
    public int listenerCount() { return listeners.size() + watchers.size(); }
    public String resultMessage() { return resultMessage; }
    public EntityRef initiator() { return request.initiator; }
    public EntityRef tracked() { return tracked; }

    /**
     * Changes the tracked player. With {@code sequenceWide}, branches that were following the previous
     * tracked player follow the new one; otherwise only {@code fiber} changes its subject.
     */
    void setTracked(EntityRef player, boolean sequenceWide, Fiber fiber) {
        if (player == null) return;
        addParticipant(player);
        if (!sequenceWide) {
            if (fiber != null) fiber.subject = player;
            return;
        }
        EntityRef old = tracked;
        tracked = player;
        for (Fiber f : fibers) {
            if (f.state.ended()) continue;
            if (f.subject == null || (old != null && f.subject.uuid().equals(old.uuid()))) f.subject = player;
        }
        log("info", "Tracking " + player.name());
    }

    /** Display state: RUNNING is reported as WAITING when every live branch is suspended. */
    public TaskState state() {
        if (state == TaskState.RUNNING) {
            boolean any = false, allWaiting = true;
            for (Fiber f : fibers) {
                if (f.state.ended()) continue;
                any = true;
                if (f.state != FiberState.WAITING) { allWaiting = false; break; }
            }
            if (any && allWaiting) return TaskState.WAITING;
            if (!any && (!listeners.isEmpty() || !watchers.isEmpty() || timers.stream().anyMatch(t -> !t.background))) return TaskState.WAITING;
        }
        return state;
    }

    public TaskState rawState() { return state; }

    public int pendingActions() {
        int n = 0;
        for (Fiber f : fibers) if (!f.state.ended()) n += f.remainingPieces();
        return n;
    }

    public int liveFiberCount() {
        int n = 0;
        for (Fiber f : fibers) if (!f.state.ended()) n++;
        return n;
    }

    /** Position on the main timeline (ticks). */
    public int mainPosition() {
        int pos = -1;
        for (Fiber f : fibers) if (f.isMain() && !f.state.ended()) pos = Math.max(pos, f.clock);
        if (pos < 0) {
            if (state == TaskState.COMPLETED) return totalLength();
            for (Fiber f : fibers) if (f.isMain()) pos = Math.max(pos, f.clock);
        }
        return Math.max(0, pos);
    }

    public int totalLength() { return project.mainLength(); }

    /** 0..1 progress along the main timeline, or -1 when the timeline has no length. */
    public double progress() {
        int len = totalLength();
        if (len <= 0) return state == TaskState.COMPLETED ? 1 : -1;
        if (state == TaskState.COMPLETED) return 1;
        return Math.max(0, Math.min(1, mainPosition() / (double) len));
    }

    public Marker currentPhaseMarker() {
        int pos = mainPosition();
        Marker cur = null;
        for (Marker m : project.main.phases()) if (m.tick <= pos) cur = m;
        return cur;
    }

    public Marker nextPhaseMarker() {
        int pos = mainPosition();
        for (Marker m : project.main.phases()) if (m.tick > pos) return m;
        return null;
    }

    public String currentPhase() {
        Marker cur = currentPhaseMarker();
        if (manualPhase != null && (cur == null || cur.tick <= manualPhasePos)) return manualPhase;
        return cur == null ? "" : cur.name;
    }

    public String nextPhase() {
        Marker next = nextPhaseMarker();
        Marker cur = currentPhaseMarker();
        if (manualNext != null && !manualNext.isEmpty() && (cur == null || cur.tick <= manualPhasePos)) return manualNext;
        return next == null ? "" : next.name;
    }

    /** Ticks until the next phase marker, or -1. */
    public int ticksToNextPhase() {
        Marker next = nextPhaseMarker();
        if (next == null) return -1;
        if (state() == TaskState.WAITING || state == TaskState.PAUSED) return -1;
        return next.tick - mainPosition();
    }

    /** The branch whose next piece runs soonest. */
    public Fiber nextFiber() {
        Fiber best = null;
        int bestT = Integer.MAX_VALUE;
        for (Fiber f : fibers) {
            if (f.state.ended() || f.nextPiece() == null) continue;
            int t = f.ticksToNext();
            int key = t < 0 ? Integer.MAX_VALUE - 1 : t;
            if (key < bestT) { bestT = key; best = f; }
        }
        return best;
    }

    public long elapsedMs() {
        long end = state.terminal() ? endedAtMs : engine.platform.millis();
        long paused = pausedTotalMs + (state == TaskState.PAUSED ? engine.platform.millis() - pausedAtMs : 0);
        return Math.max(0, end - startedAtMs - paused);
    }

    public boolean canPause() { return state == TaskState.RUNNING; }
    public boolean canResume() { return state == TaskState.PAUSED; }
    public boolean canCancel() { return !state.terminal() && state != TaskState.CANCELLING; }
    public boolean canRestart() { return state != TaskState.QUEUED; }
    public boolean canStep() { return state == TaskState.PAUSED; }

    // ── Participants ───────────────────────────────────────────────────────

    void addParticipant(EntityRef e) {
        if (e == null || !e.player()) return;
        if (participants.size() >= 1024 && !participants.contains(e.uuid())) return;
        participants.add(e.uuid());
        participantNames.put(e.uuid(), e.name());
    }

    // ── Logging ────────────────────────────────────────────────────────────

    void log(String level, String msg) {
        String line = "[t" + tickCount + "][" + level.toUpperCase(java.util.Locale.ROOT) + "] " + msg;
        log.addLast(line);
        while (log.size() > 200) log.removeFirst();
        if ("error".equals(level)) lastError = msg;
    }

    void logOnce(String key, String level, String msg) {
        if (loggedOnce.add(key)) log(level, msg);
    }

    void warn(String w) {
        if (warnings.size() < 32) warnings.add(w);
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    /** Minimal initialisation for trigger-evaluation probes (never scheduled). */
    void initProbe() {
        root = new CallHandle(nextHandleId++, this, project.main, new Variables(), 0, null);
        root.finish();
        state = TaskState.COMPLETED;
    }

    void init() {
        for (var e : request.restoredVars.entrySet()) vars.set(e.getKey(), e.getValue());
        for (VariableDecl v : project.variables) {
            if (!vars.has(v.name) && v.defaultValue != null && !v.defaultValue.isJsonNull()) vars.set(v.name, Value.fromJson(v.defaultValue));
        }
        tracked = request.initiator;
        String named = project.settings.trackedPlayer;
        if (named != null && !named.isBlank()) {
            EntityRef r = engine.platform.players().byName(named.trim());
            if (r != null) tracked = r;
            else log("warn", "Tracked player '" + named + "' is not online; tracking " + (tracked == null ? "nobody" : tracked.name()) + " instead");
        }
        if (tracked != null) addParticipant(tracked);
        EntityRef firstSubject = request.subject == null || (request.initiator != null && request.subject.uuid().equals(request.initiator.uuid())) ? tracked : request.subject;
        Variables locals = new Variables();
        root = new CallHandle(nextHandleId++, this, project.main, locals, 0, null);
        int end = Math.max(project.mainLength(), 0);
        int from = request.fromTick;
        for (Track t : project.main.tracks) {
            if (t.muted) continue;
            List<Piece> ordered = project.main.orderedPieces(t.id);
            int trackEnd = end;
            Fiber f = new Fiber(nextFiberId++, t.name, this, root, t, ordered, trackEnd);
            f.subject = firstSubject;
            if (from > 0) seek(f, from);
            root.fibers.add(f);
            fibers.add(f);
        }
        if (root.fibers.isEmpty()) root.finish();
    }

    /**
     * Starts a branch at {@code from}: earlier pieces are skipped, except long-lived setup pieces
     * (listeners, watchers, state machines) which are re-registered, and audio that is still
     * playing at {@code from}, which is started at the matching offset.
     */
    private void seek(Fiber f, int from) {
        int idx = 0;
        while (idx < f.pieces.size() && f.pieces.get(idx).tick < from) {
            Piece p = f.pieces.get(idx);
            if (p.enabled) engine.executor.replayForSeek(f, p, from);
            idx++;
        }
        f.cursor = idx;
        f.clock = from;
    }

    // ── Calls ──────────────────────────────────────────────────────────────

    /**
     * Starts a call of {@code tl} from {@code parent}. Returns null (and logs) if limits prevent it.
     * Newly created branches run in the current tick if the task is stepping.
     */
    CallHandle spawnCall(Fiber parent, Timeline tl, Map<String, Value> inputs, EntityRef subject, GameEvent event) {
        CallHandle parentHandle = parent == null ? root : parent.handle;
        int depth = parentHandle.depth + 1;
        if (depth > engine.settings.limits.maxCallDepth) {
            log("error", "Call depth limit (" + engine.settings.limits.maxCallDepth + ") reached calling '" + tl.name + "'");
            warn("call depth limit reached");
            return null;
        }
        int alive = liveFiberCount();
        int tracks = 0;
        for (Track t : tl.tracks) if (!t.muted) tracks++;
        if (alive + tracks > engine.settings.limits.maxFibersPerTask) {
            log("error", "Branch limit (" + engine.settings.limits.maxFibersPerTask + ") reached calling '" + tl.name + "'");
            warn("branch limit reached");
            return null;
        }
        Variables locals = new Variables();
        for (VariableDecl in : tl.inputs) {
            Value v = inputs.get(in.name);
            if (v == null && in.defaultValue != null && !in.defaultValue.isJsonNull()) v = Value.fromJson(in.defaultValue);
            if (v != null) locals.set(in.name, v);
        }
        for (var e : inputs.entrySet()) if (!locals.has(e.getKey())) locals.set(e.getKey(), e.getValue());
        CallHandle h = new CallHandle(nextHandleId++, this, tl, locals, depth, parentHandle);
        int len = tl.contentLength();
        for (Track t : tl.tracks) {
            if (t.muted) continue;
            Fiber f = new Fiber(nextFiberId++, tl.name + "/" + t.name, this, h, t, tl.orderedPieces(t.id), len);
            f.subject = subject;
            f.event = event;
            h.fibers.add(f);
            fibers.add(f);
            wake(f);
        }
        if (parent != null) parent.childCalls.add(h);
        if (h.fibers.isEmpty()) h.finish();
        return h;
    }

    void cancelCall(CallHandle h, String reason) {
        if (h == null || h.done) return;
        h.cancelled = true;
        // Listeners/watchers registered by a cancelled call are removed with it.
        listeners.removeIf(l -> l.owner == h);
        watchers.removeIf(w -> w.owner == h);
        for (Fiber f : new ArrayList<>(h.fibers)) f.cancel(reason);
        h.finish();
    }

    void wake(Fiber f) {
        if (!stepping || f.state.ended()) return;
        if (queued.add(f)) runQueue.addLast(f);
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    /** Runs one task tick. Only called by the engine while state == RUNNING. */
    void step() {
        long t0 = System.nanoTime();
        tickCount++;
        actionsThisTick = 0;
        evalNodesThisTick = 0;
        deferredThisTick = false;
        stepsThisTick = 0;
        stepping = true;
        try {
            runQueue.clear();
            queued.clear();
            for (Fiber f : fibers) if (!f.state.ended()) wake(f);
            // Timers (debounce etc.) fire before branches so they see a consistent tick.
            if (!timers.isEmpty()) {
                List<Timer> due = new ArrayList<>();
                timers.removeIf(t -> { if (t.dueTick <= tickCount) { due.add(t); return true; } return false; });
                for (Timer t : due) {
                    try {
                        t.action.run();
                    } catch (RuntimeException e) {
                        log("error", "Timer failed: " + e.getMessage());
                    }
                }
            }
            // Events received since the last tick.
            int delivered = 0;
            while (!pendingEvents.isEmpty() && delivered < 256) {
                engine.executor.deliver(this, pendingEvents.pollFirst());
                delivered++;
            }
            if (!watchers.isEmpty()) engine.executor.pollWatchers(this);
            int limit = engine.settings.limits.maxFiberStepsPerTick;
            while (!runQueue.isEmpty() && state == TaskState.RUNNING) {
                if (++stepsThisTick > limit) {
                    warn("branch step limit per tick reached (zero-time loop?) — continuing next tick");
                    deferredThisTick = true;
                    break;
                }
                Fiber f = runQueue.pollFirst();
                queued.remove(f);
                f.run();
            }
        } finally {
            stepping = false;
            runQueue.clear();
            queued.clear();
        }
        if (deferredThisTick) deferredTicks++;
        fibers.removeIf(f -> f.state.ended() && f.handle != root);
        if (state == TaskState.RUNNING && isFinished()) engine.finish(this, TaskState.COMPLETED, resultMessage.isEmpty() ? "completed" : resultMessage);
        long dt = System.nanoTime() - t0;
        lastTickNanos = dt;
        maxTickNanos = Math.max(maxTickNanos, dt);
        avgTickNanos = avgTickNanos == 0 ? dt : avgTickNanos * 0.95 + dt * 0.05;
        if (dt / 1000 > engine.settings.limits.slowTaskMicros) warn("slow tick: " + (dt / 1000) + "µs");
    }

    boolean isFinished() {
        for (Fiber f : fibers) if (!f.state.ended()) return false;
        if (!root.done) return false;
        for (Listener l : listeners) if (l.keepAlive) return false;
        for (Watcher w : watchers) if (w.keepAlive) return false;
        for (Timer t : timers) if (!t.background) return false;
        return true;
    }

    // ── Debugging ──────────────────────────────────────────────────────────

    boolean shouldBreak(Fiber f, Piece p) { return debug && p.breakpoint; }

    void breakAt(Fiber f, Piece p) {
        log("info", "Breakpoint at '" + p.displayName() + "' (" + f.name + ", tick " + f.clock + ")");
        engine.pause(this, PauseReason.BREAKPOINT);
    }

    // ── Recovery ───────────────────────────────────────────────────────────

    /** Minimal persistent state used by the RESUME / RESTART recovery policies. */
    public JsonObject recoveryState() {
        JsonObject o = new JsonObject();
        o.addProperty("taskId", id);
        o.addProperty("run", run);
        o.addProperty("projectId", project.id);
        o.addProperty("projectName", project.name);
        o.addProperty("position", mainPosition());
        o.addProperty("recovery", project.settings.recovery.name());
        o.addProperty("paused", state == TaskState.PAUSED);
        if (request.initiator != null) {
            o.addProperty("initiatorUuid", request.initiator.uuid().toString());
            o.addProperty("initiatorName", request.initiator.name());
        }
        o.addProperty("dimension", request.dimension);
        o.add("vars", vars.toJson());
        o.add("args", request.args.deepCopy());
        return o;
    }

    boolean recoverable() {
        return project.settings.recovery != Policies.RecoveryPolicy.DISCARD && !state.terminal();
    }
}
