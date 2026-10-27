package csequencer.core.pieces;

/**
 * Visual and semantic family of a piece. The editor draws each kind with a distinct shape so
 * scheduled actions, logical conditions, events and flow control are easy to tell apart.
 */
public enum PieceKind {
    /** Changes the world or players; executed by the platform adapter. */
    ACTION,
    /** Branches or waits based on a condition graph. */
    LOGIC,
    /** Listens for events / transitions and spawns branches. */
    EVENT,
    /** Timing and structure: delays, loops, calls, parallel branches. */
    FLOW,
    /** Audio playback and synchronization. */
    AUDIO,
    /** Variables, counters, state machines, signals. */
    DATA,
    /** Logging, breakpoints, diagnostics. */
    DEBUG
}
