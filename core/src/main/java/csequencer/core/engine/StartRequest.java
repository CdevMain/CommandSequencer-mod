package csequencer.core.engine;

import com.google.gson.JsonObject;
import csequencer.core.model.Project;
import csequencer.core.project.ProjectMeta;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;

import java.util.HashMap;
import java.util.Map;

/** Everything needed to start a sequence instance. */
public final class StartRequest {
    public final Project project;
    public final ProjectMeta meta;
    public EntityRef initiator;
    public EntityRef subject;
    public String dimension = "";
    public int fromTick;
    public boolean debug;
    /** Human readable cause: "manual", "command", "trigger:&lt;id&gt;", "chain", "recovery". */
    public String reason = "manual";
    /** Identical keys within the dedupe window are ignored (prevents duplicate starts). */
    public String dedupeKey;
    public int chainDepth;
    public JsonObject args = new JsonObject();
    /** Sequence variables restored by recovery. */
    public Map<String, Value> restoredVars = new HashMap<>();
    /** Keep the given task id (restart/recovery) instead of allocating a new one. */
    public String reuseTaskId;
    public int runNumber = 1;

    public StartRequest(Project project, ProjectMeta meta) {
        this.project = project;
        this.meta = meta;
    }

    public StartRequest initiator(EntityRef e) { this.initiator = e; if (subject == null) subject = e; return this; }
    public StartRequest subject(EntityRef e) { this.subject = e; return this; }
    public StartRequest dimension(String d) { this.dimension = d == null ? "" : d; return this; }
    public StartRequest fromTick(int t) { this.fromTick = Math.max(0, t); return this; }
    public StartRequest debug(boolean d) { this.debug = d; return this; }
    public StartRequest reason(String r) { this.reason = r; return this; }
    public StartRequest dedupe(String k) { this.dedupeKey = k; return this; }

    public StartRequest copyForRestart() {
        StartRequest r = new StartRequest(project, meta);
        r.initiator = initiator;
        r.subject = subject;
        r.dimension = dimension;
        r.fromTick = 0;
        r.debug = debug;
        r.reason = "restart";
        r.chainDepth = chainDepth;
        r.args = args.deepCopy();
        return r;
    }
}
