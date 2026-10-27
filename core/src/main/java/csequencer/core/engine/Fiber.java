package csequencer.core.engine;

import csequencer.core.model.Piece;
import csequencer.core.model.Track;
import csequencer.core.value.EntityRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One execution branch: plays the pieces of a single track in tick order with its own clock.
 * Waiting pieces (delay, wait-until, joins) freeze the clock, shifting later pieces.
 */
public final class Fiber {
    final int id;
    final String name;
    final Task task;
    final CallHandle handle;
    final Track track;
    final List<Piece> pieces;
    final int endTick;
    final List<CallHandle> childCalls = new ArrayList<>();
    int cursor;
    int clock;
    FiberState state = FiberState.RUNNING;
    Wait wait;
    EntityRef subject;
    GameEvent event;
    String failure;
    String lastPieceId;
    String lastPieceLabel;
    boolean skipNext;
    boolean skipBreakpointOnce;
    int lastAdvanceTick = -1;
    final Map<String, Integer> jumps = new HashMap<>();

    Fiber(int id, String name, Task task, CallHandle handle, Track track, List<Piece> pieces, int endTick) {
        this.id = id;
        this.name = name;
        this.task = task;
        this.handle = handle;
        this.track = track;
        this.pieces = pieces;
        this.endTick = endTick;
    }

    public int id() { return id; }
    public String name() { return name; }
    public FiberState state() { return state; }
    public int clock() { return clock; }
    public Track track() { return track; }
    public CallHandle handle() { return handle; }
    public EntityRef subject() { return subject; }

    public String waitDescription() { return wait == null ? "" : wait.describe(); }

    /** Next pending piece, or null. */
    public Piece nextPiece() { return cursor < pieces.size() ? pieces.get(cursor) : null; }

    public int remainingPieces() { return Math.max(0, pieces.size() - cursor); }

    /** Ticks until the next piece runs (-1 = unknown because waiting on something open-ended). */
    public int ticksToNext() {
        Piece p = nextPiece();
        if (p == null) return -1;
        int base = Math.max(0, p.tick - clock);
        if (wait != null) {
            int r = wait.remaining(this);
            return r < 0 ? -1 : r + base;
        }
        return base;
    }

    boolean isMain() { return handle == task.root; }

    void suspend(Wait w) {
        this.wait = w;
        this.state = FiberState.WAITING;
    }

    void run() {
        if (state.ended()) return;
        Engine engine = task.engine;
        if (wait != null) {
            boolean ready;
            try {
                ready = wait.poll(this);
            } catch (RuntimeException e) {
                fail("Wait failed: " + e.getMessage());
                return;
            }
            if (state.ended()) return;
            if (!ready) return;
            wait = null;
            state = FiberState.RUNNING;
        }
        while (state == FiberState.RUNNING) {
            if (task.state != TaskState.RUNNING) return;
            if (cursor < pieces.size() && pieces.get(cursor).tick <= clock) {
                if (!engine.takeActionBudget(task)) {
                    task.deferredThisTick = true;
                    return;
                }
                Piece p = pieces.get(cursor);
                if (!skipBreakpointOnce && task.shouldBreak(this, p)) {
                    skipBreakpointOnce = true;
                    task.breakAt(this, p);
                    return;
                }
                skipBreakpointOnce = false;
                cursor++;
                if (skipNext) {
                    skipNext = false;
                    continue;
                }
                if (!p.enabled) continue;
                lastPieceId = p.id;
                lastPieceLabel = p.displayName();
                engine.executor.execute(this, p);
                if (state == FiberState.WAITING && wait != null && wait.eager()) {
                    Wait w = wait;
                    boolean ready;
                    try {
                        ready = w.poll(this);
                    } catch (RuntimeException e) {
                        fail("Wait failed: " + e.getMessage());
                        return;
                    }
                    if (ready && wait == w && state == FiberState.WAITING) {
                        wait = null;
                        state = FiberState.RUNNING;
                    }
                }
                continue;
            }
            if (cursor >= pieces.size() && clock >= endTick) {
                complete();
                return;
            }
            if (lastAdvanceTick != task.tickCount) {
                clock++;
                lastAdvanceTick = task.tickCount;
            }
            return;
        }
    }

    void complete() { end(FiberState.DONE, null); }

    void fail(String reason) {
        task.log("error", "[" + name + "] " + reason);
        end(FiberState.FAILED, reason);
    }

    void cancel(String reason) {
        if (state.ended()) return;
        end(FiberState.CANCELLED, reason);
    }

    private void end(FiberState s, String reason) {
        if (state.ended()) return;
        Wait w = wait;
        wait = null;
        state = s;
        failure = reason;
        if (w != null) {
            try {
                w.cancel(this);
            } catch (RuntimeException ignored) {
                // cleanup must never propagate
            }
        }
        if (s == FiberState.CANCELLED || s == FiberState.FAILED) {
            for (CallHandle c : new ArrayList<>(childCalls)) task.cancelCall(c, reason == null ? "parent ended" : reason);
        }
        handle.fiberEnded(this);
    }
}
