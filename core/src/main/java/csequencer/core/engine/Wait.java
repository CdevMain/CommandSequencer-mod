package csequencer.core.engine;

/**
 * A suspension of one branch. {@link #poll} is called whenever the branch is scheduled and returns
 * true once the branch may continue. {@link #cancel} releases anything the wait holds (listeners,
 * locks, child calls) and is guaranteed to be called if the branch ends while waiting.
 */
interface Wait {
    boolean poll(Fiber f);

    default void cancel(Fiber f) {}

    /** Short description for the Task Manager ("delay 20t", "condition", "join 2/3"). */
    String describe();

    /** Ticks remaining until a known resume point, or -1 when unknown. */
    default int remaining(Fiber f) { return -1; }

    /** Eager waits are polled right after the piece that created them, in the same tick. */
    default boolean eager() { return false; }
}
