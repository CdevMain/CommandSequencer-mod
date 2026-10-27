package csequencer.core.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Snapshot-based undo/redo for the editor. Snapshots are serialized project JSON, so undo works
 * uniformly across every kind of edit (pieces, tracks, groups, conditions, settings).
 */
public final class EditorHistory {
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private final int max;

    public EditorHistory(int max) { this.max = Math.max(1, max); }

    /** Records the state *before* an edit. Identical consecutive snapshots are ignored. */
    public void push(String before) {
        if (!undo.isEmpty() && undo.peek().equals(before)) return;
        undo.push(before);
        redo.clear();
        while (undo.size() > max) undo.pollLast();
    }

    /** @return the state to restore, or null. {@code current} goes onto the redo stack. */
    public String undo(String current) {
        if (undo.isEmpty()) return null;
        redo.push(current);
        return undo.pop();
    }

    public String redo(String current) {
        if (redo.isEmpty()) return null;
        undo.push(current);
        return redo.pop();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public int undoSize() { return undo.size(); }

    public void clear() {
        undo.clear();
        redo.clear();
    }
}
