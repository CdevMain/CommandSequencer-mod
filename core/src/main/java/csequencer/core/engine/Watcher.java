package csequencer.core.engine;

import csequencer.core.logic.CNode;
import csequencer.core.model.Policies.EdgeMode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Condition trigger registered by "event.on_condition": polls a condition with edge detection. */
final class Watcher {
    final int id;
    final CallHandle owner;
    final String pieceId;
    final CNode condition;
    final EdgeMode edge;
    final String over;
    final String function;
    final int interval;
    final boolean once;
    final boolean fireInitial;
    final boolean keepAlive;
    final Map<String, Boolean> previous = new HashMap<>();
    final Set<String> fired = new HashSet<>();
    int nextCheck;

    Watcher(int id, CallHandle owner, String pieceId, CNode condition, EdgeMode edge, String over, String function,
            int interval, boolean once, boolean fireInitial, boolean keepAlive, int firstCheck) {
        this.id = id;
        this.owner = owner;
        this.pieceId = pieceId;
        this.condition = condition;
        this.edge = edge;
        this.over = over;
        this.function = function;
        this.interval = interval;
        this.once = once;
        this.fireInitial = fireInitial;
        this.keepAlive = keepAlive;
        this.nextCheck = firstCheck;
    }
}
