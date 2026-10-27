package csequencer.core.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.engine.AudioService;
import csequencer.core.engine.Engine;
import csequencer.core.engine.Fiber;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.model.Piece;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds JSON views of tasks from actual engine state (never predicted). */
public final class TaskSnapshots {
    private TaskSnapshots() {}

    public static JsonObject summary(Task t, Engine e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("run", t.runNumber());
        o.addProperty("seq", t.seq());
        o.addProperty("project", t.project().name);
        o.addProperty("projectId", t.project().id);
        TaskState st = t.state();
        o.addProperty("state", st.name());
        o.addProperty("reason", t.stateReason());
        o.addProperty("owner", t.initiator() == null ? "server" : t.initiator().name());
        o.addProperty("ownerUuid", t.initiator() == null ? "" : t.initiator().uuid().toString());
        o.addProperty("tracked", t.tracked() == null ? "" : t.tracked().name());
        o.addProperty("scope", t.project().settings.scope.name());
        o.addProperty("scopeKey", t.scopeKey());
        JsonArray parts = new JsonArray();
        int i = 0;
        for (String n : t.participantNames().values()) { if (i++ >= 16) break; parts.add(n); }
        o.add("participants", parts);
        o.addProperty("participantCount", t.participants().size());
        o.addProperty("position", t.mainPosition());
        o.addProperty("length", t.totalLength());
        o.addProperty("progress", t.progress());
        o.addProperty("phase", t.currentPhase());
        o.addProperty("nextPhase", t.nextPhase());
        o.addProperty("ticksToNextPhase", t.ticksToNextPhase());
        o.addProperty("branches", t.liveFiberCount());
        Fiber main = null;
        for (Fiber f : t.fibers()) if (!f.state().ended()) { main = f; break; }
        o.addProperty("branch", main == null ? "" : main.name());
        Fiber next = t.nextFiber();
        JsonObject na = new JsonObject();
        if (next != null && next.nextPiece() != null) {
            Piece p = next.nextPiece();
            PieceType type = PieceRegistry.piece(p.type);
            na.addProperty("label", p.displayName());
            na.addProperty("type", type == null ? p.type : type.name());
            na.addProperty("ticks", next.ticksToNext());
            na.addProperty("branch", next.name());
            na.addProperty("waiting", next.waitDescription());
        }
        o.add("nextAction", na);
        o.addProperty("startedAt", t.startedAtMs());
        o.addProperty("elapsedMs", t.elapsedMs());
        o.addProperty("tick", t.tickCount());
        o.addProperty("ok", t.actionsOk());
        o.addProperty("failed", t.actionsFailed());
        o.addProperty("pending", t.pendingActions());
        o.addProperty("lastResult", t.lastResult());
        o.addProperty("lastError", t.lastError());
        JsonObject cost = new JsonObject();
        cost.addProperty("avgUs", Math.round(t.avgTickMicros()));
        cost.addProperty("maxUs", Math.round(t.maxTickMicros()));
        cost.addProperty("evals", t.evalNodesTotal());
        cost.addProperty("deferred", t.deferredTicks());
        cost.addProperty("listeners", t.listenerCount());
        o.add("cost", cost);
        JsonArray warn = new JsonArray();
        for (String w : t.warnings()) warn.add(w);
        o.add("warnings", warn);
        String audioName = "";
        for (AudioService.Session s : e.audio.sessionsOf(t.id())) { audioName = s.audio.name; break; }
        o.addProperty("audio", audioName);
        o.addProperty("dimension", t.request().dimension);
        o.addProperty("priority", t.project().settings.priority);
        o.addProperty("debug", t.debug());
        o.addProperty("timing", t.project().settings.timing.name());
        o.addProperty("trigger", t.request().reason);
        JsonObject caps = new JsonObject();
        caps.addProperty("pause", t.canPause());
        caps.addProperty("resume", t.canResume());
        caps.addProperty("restart", t.canRestart());
        caps.addProperty("cancel", t.canCancel());
        caps.addProperty("step", t.canStep());
        o.add("caps", caps);
        return o;
    }

    public static JsonObject detail(Task t, Engine e) {
        JsonObject o = summary(t, e);
        JsonArray branches = new JsonArray();
        for (Fiber f : t.fibers()) {
            JsonObject b = new JsonObject();
            b.addProperty("id", f.id());
            b.addProperty("name", f.name());
            b.addProperty("track", f.track() == null ? "" : f.track().id);
            b.addProperty("timeline", f.handle().timeline().id);
            b.addProperty("state", f.state().name());
            b.addProperty("clock", f.clock());
            b.addProperty("wait", f.waitDescription());
            Piece p = f.nextPiece();
            b.addProperty("next", p == null ? "" : p.displayName());
            b.addProperty("nextId", p == null ? "" : p.id);
            b.addProperty("nextTicks", f.ticksToNext());
            b.addProperty("subject", f.subject() == null ? "" : f.subject().name());
            branches.add(b);
        }
        o.add("branchList", branches);
        JsonArray log = new JsonArray();
        for (String l : t.log()) log.add(l);
        o.add("log", log);
        JsonObject vars = new JsonObject();
        int n = 0;
        for (var v : t.vars().asMap().entrySet()) {
            if (n++ >= 100) break;
            vars.addProperty(v.getKey(), v.getValue().asText());
        }
        o.add("vars", vars);
        return o;
    }

    public static JsonObject history(Task t, Engine e, int logLines) {
        JsonObject o = new JsonObject();
        o.addProperty("taskId", t.id());
        o.addProperty("run", t.runNumber());
        o.addProperty("project", t.project().name);
        o.addProperty("projectId", t.project().id);
        o.addProperty("owner", t.initiator() == null ? "server" : t.initiator().name());
        o.addProperty("ownerUuid", t.initiator() == null ? "" : t.initiator().uuid().toString());
        o.addProperty("startedAt", t.startedAtMs());
        o.addProperty("endedAt", t.endedAtMs() == 0 ? e.platform.millis() : t.endedAtMs());
        o.addProperty("result", t.rawState().name());
        o.addProperty("message", t.resultMessage());
        o.addProperty("ticks", t.tickCount());
        o.addProperty("position", t.mainPosition());
        o.addProperty("length", t.totalLength());
        o.addProperty("ok", t.actionsOk());
        o.addProperty("failed", t.actionsFailed());
        o.addProperty("error", t.lastError());
        o.addProperty("trigger", t.request().reason);
        o.addProperty("scope", t.project().settings.scope.name());
        JsonArray parts = new JsonArray();
        for (String n : t.participantNames().values()) { if (parts.size() >= 32) break; parts.add(n); }
        o.add("participants", parts);
        JsonArray warn = new JsonArray();
        for (String w : t.warnings()) warn.add(w);
        o.add("warnings", warn);
        JsonArray log = new JsonArray();
        List<String> lines = t.log();
        for (int i = Math.max(0, lines.size() - logLines); i < lines.size(); i++) log.add(lines.get(i));
        o.add("log", log);
        o.addProperty("avgUs", Math.round(t.avgTickMicros()));
        return o;
    }

    static boolean involves(Task t, UUID viewer) {
        return (t.initiator() != null && t.initiator().uuid().equals(viewer)) || t.participants().contains(viewer);
    }

    static String lower(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }
}
