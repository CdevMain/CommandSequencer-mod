package csequencer.core.validate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.logic.ConditionCompiler;
import csequencer.core.model.*;
import csequencer.core.pieces.ParamSpec;
import csequencer.core.pieces.ParamType;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;
import csequencer.core.platform.PlatformInfo;
import csequencer.core.security.CommandFilter;
import csequencer.core.security.Limits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Validates a project: structure, references, parameter types and ranges, condition graphs,
 * missing resources (audio, functions, reusable conditions), limits, and compatibility with the
 * target platform. Never mutates the project.
 */
public final class Validator {
    public enum Severity { ERROR, WARNING, INFO }

    public record Issue(Severity severity, String code, String message, String timelineId, String pieceId) {
        @Override public String toString() { return severity + " [" + code + "] " + message; }
    }

    public static final class Report {
        public final List<Issue> issues = new ArrayList<>();

        void add(Severity s, String code, String msg, String tl, String piece) { issues.add(new Issue(s, code, msg, tl, piece)); }

        public boolean hasErrors() { return issues.stream().anyMatch(i -> i.severity == Severity.ERROR); }
        public long count(Severity s) { return issues.stream().filter(i -> i.severity == s).count(); }
        public List<Issue> forPiece(String id) { return issues.stream().filter(i -> id.equals(i.pieceId)).toList(); }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (Issue i : issues) {
                JsonObject x = new JsonObject();
                x.addProperty("severity", i.severity.name());
                x.addProperty("code", i.code);
                x.addProperty("message", i.message);
                if (i.timelineId != null) x.addProperty("timeline", i.timelineId);
                if (i.pieceId != null) x.addProperty("piece", i.pieceId);
                a.add(x);
            }
            o.add("issues", a);
            o.addProperty("errors", count(Severity.ERROR));
            o.addProperty("warnings", count(Severity.WARNING));
            return o;
        }
    }

    private Validator() {}

    /**
     * @param platform     target platform (null = skip capability checks)
     * @param audioPresent returns true when the audio hash is available (null = skip)
     * @param projectExists returns true when a referenced project exists (null = skip)
     */
    public static Report validate(Project p, PlatformInfo platform, Limits limits, Predicate<String> audioPresent, Predicate<String> projectExists) {
        Report r = new Report();
        if (p.schemaVersion > Project.SCHEMA_VERSION)
            r.add(Severity.WARNING, "schema.newer", "Project schema " + p.schemaVersion + " is newer than this version supports (" + Project.SCHEMA_VERSION + ")", null, null);
        if (p.name == null || p.name.isBlank()) r.add(Severity.ERROR, "name.empty", "Project needs a name", null, null);
        if (limits != null && p.pieceCount() > limits.maxPiecesPerProject)
            r.add(Severity.ERROR, "limit.pieces", "Project has " + p.pieceCount() + " pieces (limit " + limits.maxPiecesPerProject + ")", null, null);
        if (limits != null && p.mainLength() > limits.maxTimelineTicks)
            r.add(Severity.ERROR, "limit.length", "Main timeline is " + p.mainLength() + " ticks (limit " + limits.maxTimelineTicks + ")", null, null);

        Set<String> ids = new HashSet<>();
        for (Timeline t : p.allTimelines()) {
            if (!ids.add(t.id)) r.add(Severity.ERROR, "id.duplicate", "Duplicate timeline id " + t.id, t.id, null);
            Set<String> trackIds = new HashSet<>();
            for (Track tr : t.tracks) if (!trackIds.add(tr.id)) r.add(Severity.ERROR, "id.duplicate", "Duplicate track id " + tr.id + " in " + t.name, t.id, null);
            Set<String> pieceIds = new HashSet<>();
            for (Piece piece : t.pieces) {
                if (!pieceIds.add(piece.id)) r.add(Severity.ERROR, "id.duplicate", "Duplicate piece id " + piece.id, t.id, piece.id);
                if (t.track(piece.trackId) == null) r.add(Severity.ERROR, "track.missing", "Piece '" + piece.displayName() + "' is on a missing track", t.id, piece.id);
                if (!piece.groupId.isEmpty() && t.group(piece.groupId) == null)
                    r.add(Severity.WARNING, "group.missing", "Piece '" + piece.displayName() + "' references a missing group", t.id, piece.id);
                validatePiece(p, t, piece, platform, limits, audioPresent, projectExists, r);
            }
        }
        Set<String> fnNames = new HashSet<>();
        for (Timeline f : p.functions) if (!fnNames.add(f.name.toLowerCase())) r.add(Severity.WARNING, "fn.name", "Two functions are named '" + f.name + "'", f.id, null);
        for (ConditionGraph g : p.conditions) checkCondition(p, g.toJson(), "Reusable condition '" + g.name + "'", null, null, limits, r);
        for (Trigger tr : p.triggers) {
            if (tr.kind == Policies.TriggerKind.EVENT && PieceRegistry.event(tr.eventType) == null)
                r.add(Severity.ERROR, "trigger.event", "Trigger '" + tr.name + "' uses unknown event '" + tr.eventType + "'", null, null);
            if (tr.kind == Policies.TriggerKind.CONDITION && (tr.condition == null || tr.condition.isEmpty()))
                r.add(Severity.ERROR, "trigger.condition", "Condition trigger '" + tr.name + "' has no condition", null, null);
            if (tr.kind == Policies.TriggerKind.AREA && !tr.hasArea())
                r.add(Severity.ERROR, "trigger.area", "Area trigger '" + tr.name + "' has no area (set both corners)", null, null);
            if (tr.kind == Policies.TriggerKind.AREA && tr.hasArea()) {
                int[] lo = tr.areaMin(), hi = tr.areaMax();
                long vol = (long) (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1);
                if (hi[0] - lo[0] > 4096 || hi[2] - lo[2] > 4096)
                    r.add(Severity.WARNING, "trigger.area.size", "Area trigger '" + tr.name + "' is very large (" + vol + " blocks)", null, null);
            }
            if (tr.condition != null) checkCondition(p, tr.condition.toJson(), "Trigger '" + tr.name + "'", null, null, limits, r);
        }
        for (AudioRef a : p.audio) {
            if (!AudioRef.isValidHash(a.hash)) r.add(Severity.ERROR, "audio.hash", "Audio '" + a.name + "' has an invalid hash", null, null);
            else if (audioPresent != null && !audioPresent.test(a.hash)) r.add(Severity.ERROR, "audio.missing", "Audio file for '" + a.name + "' is missing on this server", null, null);
            if (!a.format.equals("wav") && !a.format.equals("mp3")) r.add(Severity.WARNING, "audio.format", "Audio '" + a.name + "' has unsupported format " + a.format, null, null);
        }
        if (p.settings.timing == Policies.TimingMode.TICK && usesAudio(p))
            r.add(Severity.INFO, "audio.timing", "Audio sequences stay in sync best with REALTIME timing", null, null);
        return r;
    }

    private static boolean usesAudio(Project p) {
        for (Timeline t : p.allTimelines()) for (Piece x : t.pieces) if (x.type.equals("audio.play")) return true;
        return false;
    }

    private static void validatePiece(Project p, Timeline t, Piece piece, PlatformInfo platform, Limits limits,
                                      Predicate<String> audioPresent, Predicate<String> projectExists, Report r) {
        PieceType type = PieceRegistry.piece(piece.type);
        if (type == null) {
            r.add(Severity.ERROR, "piece.unknown", "Unknown piece type '" + piece.type + "' (kept, but it will not run)", t.id, piece.id);
            return;
        }
        String where = "'" + piece.displayName() + "'";
        if (platform != null && !platform.supports(type.capability()))
            r.add(Severity.ERROR, "piece.unsupported", type.name() + " " + where + " is not supported on " + platform.loader() + " " + platform.minecraftVersion(), t.id, piece.id);
        for (ParamSpec spec : type.params()) {
            JsonElement v = piece.params.get(spec.key);
            if (v == null || v.isJsonNull()) {
                if (spec.required) r.add(Severity.ERROR, "param.missing", where + ": '" + spec.label + "' is required", t.id, piece.id);
                continue;
            }
            switch (spec.type) {
                case INT, NUMBER -> {
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                        double d = v.getAsDouble();
                        if (d < spec.min || d > spec.max) r.add(Severity.WARNING, "param.range", where + ": '" + spec.label + "' is out of range and will be clamped", t.id, piece.id);
                    } else if (v.isJsonPrimitive() && v.getAsString().indexOf('{') < 0) {
                        try { Double.parseDouble(v.getAsString()); } catch (NumberFormatException e) {
                            r.add(Severity.ERROR, "param.type", where + ": '" + spec.label + "' must be a number", t.id, piece.id);
                        }
                    }
                }
                case FUNCTION -> {
                    String ref = v.isJsonPrimitive() ? v.getAsString() : "";
                    if (!ref.isEmpty() && p.function(ref) == null) r.add(Severity.ERROR, "fn.missing", where + ": function '" + ref + "' does not exist", t.id, piece.id);
                }
                case FUNCTIONS -> {
                    if (v.isJsonArray()) for (JsonElement e : v.getAsJsonArray()) {
                        String ref = e.isJsonPrimitive() ? e.getAsString() : (e.isJsonObject() ? J.str(e.getAsJsonObject(), "target", "") : "");
                        if (!ref.isEmpty() && p.function(ref) == null) r.add(Severity.ERROR, "fn.missing", where + ": function '" + ref + "' does not exist", t.id, piece.id);
                    }
                }
                case AUDIO -> {
                    String ref = v.isJsonPrimitive() ? v.getAsString() : "";
                    if (ref.isEmpty()) r.add(Severity.ERROR, "audio.unset", where + ": no audio selected", t.id, piece.id);
                    else if (p.audio(ref) == null) r.add(Severity.ERROR, "audio.missing", where + ": audio '" + ref + "' is not in the project", t.id, piece.id);
                }
                case PROJECT -> {
                    String ref = v.isJsonPrimitive() ? v.getAsString() : "";
                    if (!ref.isEmpty() && projectExists != null && !ref.equalsIgnoreCase(p.name) && !projectExists.test(ref))
                        r.add(Severity.WARNING, "project.missing", where + ": sequence '" + ref + "' was not found", t.id, piece.id);
                }
                case CONDITION -> {
                    if (v.isJsonObject()) checkCondition(p, v.getAsJsonObject(), where, t.id, piece.id, limits, r);
                }
                case BRANCHES -> {
                    if (v.isJsonArray()) {
                        int i = 0;
                        for (JsonElement e : v.getAsJsonArray()) {
                            i++;
                            if (!e.isJsonObject()) continue;
                            JsonObject b = e.getAsJsonObject();
                            if (b.has("condition") && b.get("condition").isJsonObject()) checkCondition(p, b.getAsJsonObject("condition"), where + " branch " + i, t.id, piece.id, limits, r);
                            String target = J.str(b, "target", "");
                            if (!target.isEmpty() && p.function(target) == null) r.add(Severity.ERROR, "fn.missing", where + " branch " + i + ": function '" + target + "' does not exist", t.id, piece.id);
                        }
                    }
                }
                case CASES, OUTCOMES -> {
                    if (v.isJsonArray()) for (JsonElement e : v.getAsJsonArray()) {
                        if (!e.isJsonObject()) continue;
                        String target = J.str(e.getAsJsonObject(), "target", "");
                        if (!target.isEmpty() && p.function(target) == null) r.add(Severity.ERROR, "fn.missing", where + ": function '" + target + "' does not exist", t.id, piece.id);
                    }
                }
                case EVENT_TYPE -> {
                    String id = v.isJsonPrimitive() ? v.getAsString() : "";
                    if (PieceRegistry.event(id) == null) r.add(Severity.ERROR, "event.unknown", where + ": unknown event '" + id + "'", t.id, piece.id);
                }
                case ENUM -> {
                    String s = v.isJsonPrimitive() ? v.getAsString() : "";
                    if (!spec.options.isEmpty() && !spec.options.contains(s))
                        r.add(Severity.WARNING, "param.enum", where + ": '" + s + "' is not a valid " + spec.label, t.id, piece.id);
                }
                case TARGET -> {
                    if (v.isJsonObject() && "filter".equals(J.str(v.getAsJsonObject(), "mode", "")) && v.getAsJsonObject().has("filter"))
                        checkCondition(p, J.obj(v.getAsJsonObject(), "filter"), where + " target filter", t.id, piece.id, limits, r);
                }
                default -> {}
            }
        }
        if (piece.type.equals("action.command")) {
            String cmd = J.str(piece.params, "command", "");
            String reason = CommandFilter.check(cmd.replaceAll("\\{[^}]*}", "x"), java.util.List.of());
            if (reason != null) r.add(Severity.ERROR, "command.invalid", where + ": " + reason, t.id, piece.id);
        }
        if (piece.type.equals("flow.while") || piece.type.equals("flow.repeat")) {
            String fn = J.str(piece.params, "function", "");
            if (fn.isEmpty()) r.add(Severity.ERROR, "fn.unset", where + ": no function selected for the loop body", t.id, piece.id);
        }
    }

    private static void checkCondition(Project p, JsonObject g, String where, String tl, String piece, Limits limits, Report r) {
        if (g == null || g.size() == 0) return;
        ConditionGraph graph = ConditionGraph.fromJson(g);
        if (graph.isEmpty()) return;
        ConditionCompiler.Result res = ConditionCompiler.compile(graph, p::condition, limits == null ? 48 : limits.maxConditionDepth,
                limits == null ? 2048 : limits.maxNodesPerEvaluation);
        for (String e : res.errors()) r.add(Severity.ERROR, "condition.invalid", where + ": " + e, tl, piece);
        for (String w : res.warnings()) r.add(Severity.WARNING, "condition.warning", where + ": " + w, tl, piece);
        for (ConditionGraph.Node n : graph.nodes) {
            if (!"cmp".equals(n.type)) continue;
            JsonElement left = n.params.get("left");
            if (left != null && left.isJsonObject() && left.getAsJsonObject().has("q")) {
                String q = J.str(left.getAsJsonObject(), "q", "");
                if (!q.equals("literal") && PieceRegistry.query(q) == null)
                    r.add(Severity.ERROR, "query.unknown", where + ": unknown value '" + q + "'", tl, piece);
            }
        }
    }

    /** True for param types whose value is a function reference. */
    public static boolean isFunctionRef(ParamType t) { return t == ParamType.FUNCTION || t == ParamType.FUNCTIONS; }
}
