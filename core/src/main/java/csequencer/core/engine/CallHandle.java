package csequencer.core.engine;

import csequencer.core.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * One invocation of a timeline (the main sequence or a function). Owns the branches (one per
 * track) and the local variable frame. Completes when every branch has ended. Cancelling a
 * handle cancels its branches and, recursively, every call they started.
 */
public final class CallHandle {
    final int id;
    final Task task;
    final Timeline timeline;
    final Variables locals;
    final int depth;
    final CallHandle parent;
    final List<Fiber> fibers = new ArrayList<>();
    final List<Runnable> onDone = new ArrayList<>();
    boolean done;
    boolean failed;
    boolean cancelled;
    String error;

    CallHandle(int id, Task task, Timeline timeline, Variables locals, int depth, CallHandle parent) {
        this.id = id;
        this.task = task;
        this.timeline = timeline;
        this.locals = locals;
        this.depth = depth;
        this.parent = parent;
    }

    public boolean isDone() { return done; }
    public boolean isFailed() { return failed; }
    public Timeline timeline() { return timeline; }
    public Variables locals() { return locals; }

    void fiberEnded(Fiber f) {
        if (done) return;
        if (f.state == FiberState.FAILED) {
            failed = true;
            if (error == null) error = f.failure;
        }
        for (Fiber x : fibers) if (!x.state.ended()) return;
        finish();
    }

    void finish() {
        if (done) return;
        done = true;
        List<Runnable> cbs = new ArrayList<>(onDone);
        onDone.clear();
        for (Runnable r : cbs) r.run();
    }

    void whenDone(Runnable r) {
        if (done) r.run();
        else onDone.add(r);
    }
}
