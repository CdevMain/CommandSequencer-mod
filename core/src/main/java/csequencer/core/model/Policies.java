package csequencer.core.model;

/** Execution policies. Enum constant names are part of the file format: never rename. */
public final class Policies {
    private Policies() {}

    /**
     * Execution scope of a sequence instance. Determines the uniqueness key used by the
     * concurrency policy and what "the subject" of the sequence is.
     * <ul>
     *   <li>PLAYER: one instance per initiating player (key = player uuid)</li>
     *   <li>ENTITY: one instance per initiating entity (key = entity uuid)</li>
     *   <li>SEQUENCE: one instance per sequence (key = project id)</li>
     *   <li>DIMENSION: one instance per dimension (key = dimension id)</li>
     *   <li>SERVER: one instance for the whole server; ignores the initiator for uniqueness</li>
     * </ul>
     */
    public enum ExecutionScope { PLAYER, ENTITY, SEQUENCE, DIMENSION, SERVER }

    /** What happens when a sequence is started while an instance with the same scope key is active. */
    public enum ConcurrencyPolicy { ALLOW_PARALLEL, IGNORE_NEW, RESTART, QUEUE }

    /** Behaviour when the initiating player disconnects. */
    public enum DisconnectPolicy { CONTINUE, PAUSE_UNTIL_RETURN, CANCEL }

    /** Behaviour after a server restart. RESUME continues from the saved main-timeline position. */
    public enum RecoveryPolicy { DISCARD, RESTART, RESUME }

    /**
     * TICK: one timeline tick per server tick (deterministic; slows down with low TPS).
     * REALTIME: catches up to wall-clock time (bounded), recommended for audio-synchronized sequences.
     */
    public enum TimingMode { TICK, REALTIME }

    /** What an action failure does unless the piece overrides it. */
    public enum ErrorPolicy { CONTINUE, STOP_BRANCH, FAIL_TASK }

    /** Who sees the top-of-screen announcement overlay. */
    public enum AnnounceMode { NONE, PARTICIPANTS, ALL, PERMISSION, TEAMS }

    public enum MarkerKind { PHASE, CUE, AUDIO }

    /** AREA: a box in the world; fires for players entering it (optionally only when a condition holds). */
    public enum TriggerKind { EVENT, CONDITION, SERVER_START, AREA }

    /** Transition detection modes. LEVEL fires on every evaluation while true (a state check). */
    public enum EdgeMode { ENTERED, EXITED, CHANGED, LEVEL }

    /** Who becomes the initiator of a triggered sequence. */
    public enum TriggerSubject { EVENT_PLAYER, EACH_PLAYER, SERVER }
}
