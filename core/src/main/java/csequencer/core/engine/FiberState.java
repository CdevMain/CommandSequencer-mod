package csequencer.core.engine;

/** State of one execution branch. */
public enum FiberState {
    RUNNING, WAITING, DONE, FAILED, CANCELLED;

    public boolean ended() { return this == DONE || this == FAILED || this == CANCELLED; }
}
