package csequencer.core.engine;

/** Outcome of {@link Engine#start(StartRequest)}. */
public record StartResult(boolean ok, Task task, String message) {
    public static StartResult ok(Task t, String msg) { return new StartResult(true, t, msg); }
    public static StartResult fail(String msg) { return new StartResult(false, null, msg); }
}
