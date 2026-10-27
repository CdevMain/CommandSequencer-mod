package csequencer.core.platform;

import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Executes world-changing action pieces. Called on the server thread only.
 * Implementations must validate arguments again (never trust project data), respect
 * {@link ActionContext#commandPermissionLevel()}, and never throw: return a failed result instead.
 */
public interface ActionAdapter {
    ActionResult execute(String pieceType, Map<String, Value> args, ActionContext ctx);

    /** Called once when a task terminates so the adapter can release task-owned resources. */
    void onTaskEnded(String taskId, boolean removeSpawned);

    record ActionContext(String taskId, String projectName, EntityRef subject, EntityRef initiator,
                         List<EntityRef> targets, String dimension, int commandPermissionLevel,
                         List<String> commandBlacklist, int fillBlockLimit) {}

    record ActionResult(boolean success, String message, Value produced, int affected) {
        public static ActionResult ok(String msg) { return new ActionResult(true, msg, Value.NULL, 1); }
        public static ActionResult ok(String msg, int affected) { return new ActionResult(true, msg, Value.NULL, affected); }
        public static ActionResult ok(String msg, Value produced) { return new ActionResult(true, msg, produced, 1); }
        public static ActionResult fail(String msg) { return new ActionResult(false, msg, Value.NULL, 0); }
    }
}
