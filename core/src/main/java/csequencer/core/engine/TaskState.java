package csequencer.core.engine;

/** Lifecycle of a sequence instance. WAITING is reported when every live branch is waiting. */
public enum TaskState {
    QUEUED, RUNNING, WAITING, PAUSED, COMPLETING, CANCELLING, COMPLETED, FAILED, CANCELLED;

    public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }

    public boolean active() { return this == RUNNING || this == WAITING || this == PAUSED || this == QUEUED; }
}
