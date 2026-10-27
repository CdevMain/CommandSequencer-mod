package csequencer.core.security;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

/** Configurable safety limits. All are enforced by the engine on the server. */
public final class Limits {
    public int maxActiveTasks = 64;
    public int maxTasksPerPlayer = 8;
    public int maxQueuedTasks = 64;
    public int maxFibersPerTask = 256;
    public int maxActionsPerTick = 2000;
    public int maxActionsPerTaskPerTick = 500;
    public int maxEvalNodesPerTick = 50000;
    public int maxNodesPerEvaluation = 2048;
    public int maxConditionDepth = 48;
    public int maxCallDepth = 32;
    public int maxLoopIterations = 10000;
    public int maxFiberStepsPerTick = 5000;
    public int maxListenersPerTask = 64;
    public int maxPiecesPerProject = 5000;
    public int maxChainDepth = 8;
    public int maxTimelineTicks = 20 * 60 * 60 * 6;
    public int fillBlockLimit = 32768;
    public int maxProjectBytes = 2 * 1024 * 1024;
    public int maxAudioBytes = 32 * 1024 * 1024;
    /** Warn in the Task Manager when a task's tick takes longer than this (microseconds). */
    public int slowTaskMicros = 3000;

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("maxActiveTasks", maxActiveTasks);
        o.addProperty("maxTasksPerPlayer", maxTasksPerPlayer);
        o.addProperty("maxQueuedTasks", maxQueuedTasks);
        o.addProperty("maxFibersPerTask", maxFibersPerTask);
        o.addProperty("maxActionsPerTick", maxActionsPerTick);
        o.addProperty("maxActionsPerTaskPerTick", maxActionsPerTaskPerTick);
        o.addProperty("maxEvalNodesPerTick", maxEvalNodesPerTick);
        o.addProperty("maxNodesPerEvaluation", maxNodesPerEvaluation);
        o.addProperty("maxConditionDepth", maxConditionDepth);
        o.addProperty("maxCallDepth", maxCallDepth);
        o.addProperty("maxLoopIterations", maxLoopIterations);
        o.addProperty("maxFiberStepsPerTick", maxFiberStepsPerTick);
        o.addProperty("maxListenersPerTask", maxListenersPerTask);
        o.addProperty("maxPiecesPerProject", maxPiecesPerProject);
        o.addProperty("maxChainDepth", maxChainDepth);
        o.addProperty("maxTimelineTicks", maxTimelineTicks);
        o.addProperty("fillBlockLimit", fillBlockLimit);
        o.addProperty("maxProjectBytes", maxProjectBytes);
        o.addProperty("maxAudioBytes", maxAudioBytes);
        o.addProperty("slowTaskMicros", slowTaskMicros);
        return o;
    }

    public static Limits fromJson(JsonObject o) {
        Limits l = new Limits();
        if (o == null) return l;
        l.maxActiveTasks = clamp(J.i(o, "maxActiveTasks", l.maxActiveTasks), 1, 10000);
        l.maxTasksPerPlayer = clamp(J.i(o, "maxTasksPerPlayer", l.maxTasksPerPlayer), 1, 10000);
        l.maxQueuedTasks = clamp(J.i(o, "maxQueuedTasks", l.maxQueuedTasks), 0, 10000);
        l.maxFibersPerTask = clamp(J.i(o, "maxFibersPerTask", l.maxFibersPerTask), 4, 100000);
        l.maxActionsPerTick = clamp(J.i(o, "maxActionsPerTick", l.maxActionsPerTick), 1, 1000000);
        l.maxActionsPerTaskPerTick = clamp(J.i(o, "maxActionsPerTaskPerTick", l.maxActionsPerTaskPerTick), 1, 1000000);
        l.maxEvalNodesPerTick = clamp(J.i(o, "maxEvalNodesPerTick", l.maxEvalNodesPerTick), 100, 10000000);
        l.maxNodesPerEvaluation = clamp(J.i(o, "maxNodesPerEvaluation", l.maxNodesPerEvaluation), 16, 1000000);
        l.maxConditionDepth = clamp(J.i(o, "maxConditionDepth", l.maxConditionDepth), 2, 1000);
        l.maxCallDepth = clamp(J.i(o, "maxCallDepth", l.maxCallDepth), 1, 1000);
        l.maxLoopIterations = clamp(J.i(o, "maxLoopIterations", l.maxLoopIterations), 1, 10000000);
        l.maxFiberStepsPerTick = clamp(J.i(o, "maxFiberStepsPerTick", l.maxFiberStepsPerTick), 10, 10000000);
        l.maxListenersPerTask = clamp(J.i(o, "maxListenersPerTask", l.maxListenersPerTask), 1, 100000);
        l.maxPiecesPerProject = clamp(J.i(o, "maxPiecesPerProject", l.maxPiecesPerProject), 1, 1000000);
        l.maxChainDepth = clamp(J.i(o, "maxChainDepth", l.maxChainDepth), 0, 1000);
        l.maxTimelineTicks = clamp(J.i(o, "maxTimelineTicks", l.maxTimelineTicks), 20, Integer.MAX_VALUE);
        l.fillBlockLimit = clamp(J.i(o, "fillBlockLimit", l.fillBlockLimit), 1, 10000000);
        l.maxProjectBytes = clamp(J.i(o, "maxProjectBytes", l.maxProjectBytes), 1024, 64 * 1024 * 1024);
        l.maxAudioBytes = clamp(J.i(o, "maxAudioBytes", l.maxAudioBytes), 0, 512 * 1024 * 1024);
        l.slowTaskMicros = clamp(J.i(o, "slowTaskMicros", l.slowTaskMicros), 10, 10000000);
        return l;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
