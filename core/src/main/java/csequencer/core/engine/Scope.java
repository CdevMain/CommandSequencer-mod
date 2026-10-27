package csequencer.core.engine;

import csequencer.core.value.EntityRef;

/** Execution scope used while evaluating values: the task, the branch (may be null) and the event. */
record Scope(Task task, Fiber fiber, GameEvent event, EntityRef subject) {
    Variables locals() { return fiber != null ? fiber.handle.locals : task.root.locals; }

    static Scope of(Fiber f) { return new Scope(f.task, f, f.event, f.subject); }

    Scope withSubject(EntityRef s) { return new Scope(task, fiber, event, s); }
}
