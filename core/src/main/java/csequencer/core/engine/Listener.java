package csequencer.core.engine;

import csequencer.core.logic.CNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Event listener registered by an "event.listen" / "event.wait" / "state.machine" piece.
 * Owned by the call that registered it; removed when that call is cancelled or the task ends.
 */
final class Listener {
    final int id;
    final CallHandle owner;
    final String pieceId;
    final String eventType;
    final String signal;
    final CNode filter;
    final String function;
    final boolean once;
    final int maxFires;
    final int cooldown;
    final boolean perPlayer;
    final boolean keepAlive;
    /** For event.wait: the branch waiting on this listener. */
    final Fiber waiter;
    /** For state.machine: state name → function. */
    final Map<String, String> stateTargets;
    final String machine;
    final Map<String, Integer> lastFire = new HashMap<>();
    int fires;
    GameEvent firedEvent;
    /** Player filter for join/leave pieces: null (none), "any", "tracked" or "named". */
    String whoMode;
    String whoName = "";
    /** Make the matching player the sequence's tracked player. */
    boolean trackSubject;

    Listener(int id, CallHandle owner, String pieceId, String eventType, String signal, CNode filter, String function,
             boolean once, int maxFires, int cooldown, boolean perPlayer, boolean keepAlive, Fiber waiter,
             Map<String, String> stateTargets, String machine) {
        this.id = id;
        this.owner = owner;
        this.pieceId = pieceId;
        this.eventType = eventType;
        this.signal = signal;
        this.filter = filter;
        this.function = function;
        this.once = once;
        this.maxFires = maxFires;
        this.cooldown = cooldown;
        this.perPlayer = perPlayer;
        this.keepAlive = keepAlive;
        this.waiter = waiter;
        this.stateTargets = stateTargets;
        this.machine = machine;
    }

    boolean exhausted() { return (once && fires > 0) || (maxFires > 0 && fires >= maxFires); }
}
