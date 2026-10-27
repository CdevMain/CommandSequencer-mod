package csequencer.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import csequencer.core.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes versioned project JSON.
 *
 * <p>Schema history:
 * <ul>
 *   <li>0 — legacy CommandSequencer 1.0 files: {@code {"name", "events":[{"id","tick","command","label"}], "triggers"?}}</li>
 *   <li>1 — current format (tracks, typed pieces, functions, conditions, audio, triggers, settings)</li>
 * </ul>
 * Files newer than {@link Project#SCHEMA_VERSION} are loaded best-effort with unknown data preserved
 * and {@link ReadResult#newerSchema()} set so the UI can warn before overwriting.
 */
public final class ProjectCodec {
    public static final String FORMAT_ID = "commandsequencer.project";

    private ProjectCodec() {}

    public record ReadResult(Project project, int sourceVersion, boolean migrated, boolean newerSchema, List<String> warnings) {}

    public static String write(Project p) { return J.PRETTY.toJson(toJson(p)); }

    public static String writeCompact(Project p) { return J.COMPACT.toJson(toJson(p)); }

    public static JsonObject toJson(Project p) {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT_ID);
        o.addProperty("schemaVersion", Math.max(p.schemaVersion, Project.SCHEMA_VERSION));
        o.addProperty("id", p.id);
        o.addProperty("name", p.name);
        o.addProperty("description", p.description);
        o.add("tags", J.toArray(p.tags));
        o.addProperty("revision", p.revision);
        o.add("settings", p.settings.toJson());
        o.add("main", p.main.toJson());
        o.add("functions", J.write(p.functions, Timeline::toJson));
        o.add("conditions", J.write(p.conditions, ConditionGraph::toJson));
        o.add("audio", J.write(p.audio, AudioRef::toJson));
        o.add("triggers", J.write(p.triggers, Trigger::toJson));
        o.add("variables", J.write(p.variables, VariableDecl::toJson));
        J.mergeExtra(o, p.extra);
        return o;
    }

    public static ReadResult read(String json) { return read(J.parseObject(json)); }

    public static ReadResult read(JsonObject root) {
        List<String> warnings = new ArrayList<>();
        int version = detectVersion(root);
        JsonObject o = root.deepCopy();
        boolean migrated = false;
        if (version < 1) {
            o = migrate0to1(o, warnings);
            migrated = true;
        }
        boolean newer = version > Project.SCHEMA_VERSION;
        if (newer) warnings.add("Project uses schema " + version + ", newer than supported " + Project.SCHEMA_VERSION
                + ". Unknown data is preserved but some features may be missing.");

        Project p = new Project();
        p.schemaVersion = Math.max(version, Project.SCHEMA_VERSION);
        p.id = J.str(o, "id", Ids.next("proj"));
        p.name = J.str(o, "name", "Untitled");
        p.description = J.str(o, "description", "");
        p.tags = J.strings(o, "tags");
        p.revision = J.l(o, "revision", 0);
        p.settings = ProjectSettings.fromJson(J.obj(o, "settings"));
        JsonObject main = J.obj(o, "main");
        if (main == null) {
            warnings.add("Project has no main timeline; an empty one was created.");
            p.main = Timeline.create("Main", "tl");
        } else {
            p.main = Timeline.fromJson(main);
        }
        p.functions = J.list(o, "functions", Timeline::fromJson);
        p.conditions = J.list(o, "conditions", ConditionGraph::fromJson);
        p.audio = J.list(o, "audio", AudioRef::fromJson);
        p.triggers = J.list(o, "triggers", Trigger::fromJson);
        p.variables = J.list(o, "variables", VariableDecl::fromJson);
        JsonObject extra = J.extra(o, Project.KNOWN_KEYS());
        p.extra = extra;
        repair(p, warnings);
        return new ReadResult(p, version, migrated, newer, warnings);
    }

    static int detectVersion(JsonObject o) {
        if (o.has("schemaVersion")) return J.i(o, "schemaVersion", 0);
        return 0;
    }

    /** Ensures structural invariants (every timeline has a track; pieces reference existing tracks). */
    static void repair(Project p, List<String> warnings) {
        for (Timeline t : p.allTimelines()) {
            if (t.tracks.isEmpty()) t.tracks.add(new Track("Track 1"));
            for (Piece piece : t.pieces) {
                if (t.track(piece.trackId) == null) {
                    warnings.add("Piece " + piece.id + " referenced missing track '" + piece.trackId + "'; moved to first track.");
                    piece.trackId = t.tracks.get(0).id;
                }
            }
        }
    }

    // ── Migrations ───────────────────────────────────────────────────────────

    /** Legacy v0 → v1: each legacy event becomes an "action.command" piece on a single track. */
    static JsonObject migrate0to1(JsonObject legacy, List<String> warnings) {
        JsonObject o = new JsonObject();
        String name = J.str(legacy, "name", "Imported");
        o.addProperty("schemaVersion", 1);
        o.addProperty("id", Ids.next("proj"));
        o.addProperty("name", name);
        o.addProperty("description", "Migrated from CommandSequencer 1.0 format");
        Timeline main = Timeline.create("Main", "tl");
        Track track = main.tracks.get(0);
        track.name = "Commands";
        String chain = J.str(legacy, "chain", "");
        int count = 0;
        for (JsonElement e : J.arr(legacy, "events")) {
            if (!e.isJsonObject()) continue;
            JsonObject ev = e.getAsJsonObject();
            Piece p = new Piece("action.command", track.id, Math.max(0, J.i(ev, "tick", 0)));
            String id = J.str(ev, "id", null);
            if (id != null && Ids.isValid(id.replace("-", ""))) p.id = "p_" + id.replace("-", "").substring(0, Math.min(16, id.replace("-", "").length()));
            p.label = J.str(ev, "label", "");
            String cmd = J.str(ev, "command", "");
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            p.params.addProperty("command", cmd);
            main.pieces.add(p);
            count++;
        }
        if (!chain.isEmpty()) {
            Piece c = new Piece("seq.control", track.id, main.contentLength() + 1);
            c.params.addProperty("action", "start");
            c.params.addProperty("project", chain);
            c.label = "Chain → " + chain;
            main.pieces.add(c);
        }
        o.add("main", main.toJson());
        // Legacy region triggers become CONDITION triggers with a region comparison, preserved verbatim too.
        JsonArray triggers = new JsonArray();
        for (JsonElement e : J.arr(legacy, "triggers")) {
            if (!e.isJsonObject()) continue;
            JsonObject lt = e.getAsJsonObject();
            Trigger t = new Trigger();
            t.id = Ids.next("tr");
            t.name = J.str(lt, "name", "region");
            t.kind = Policies.TriggerKind.CONDITION;
            t.subject = Policies.TriggerSubject.EACH_PLAYER;
            t.edge = Policies.EdgeMode.ENTERED;
            t.cooldownTicks = J.i(lt, "cooldownTicks", 200);
            t.enabled = J.b(lt, "enabled", true);
            ConditionGraph.Node cmp = new ConditionGraph.Node("cmp");
            JsonObject left = new JsonObject();
            left.addProperty("q", "player.in_region");
            JsonObject args = new JsonObject();
            args.addProperty("from", J.d(lt, "x1", 0) + " " + J.d(lt, "y1", 0) + " " + J.d(lt, "z1", 0));
            args.addProperty("to", J.d(lt, "x2", 0) + " " + J.d(lt, "y2", 0) + " " + J.d(lt, "z2", 0));
            left.add("args", args);
            cmp.params.add("left", left);
            cmp.params.addProperty("op", "is_true");
            t.condition = ConditionGraph.single(cmp);
            t.extra.add("legacy", lt.deepCopy());
            triggers.add(t.toJson());
        }
        o.add("triggers", triggers);
        JsonObject extra = J.extra(legacy, java.util.Set.of("name", "events", "savedAt", "triggers", "chain"));
        if (extra.size() > 0) o.add("legacyExtra", extra);
        if (legacy.has("savedAt")) o.add("legacySavedAt", new JsonPrimitive(J.l(legacy, "savedAt", 0)));
        warnings.add("Migrated legacy project '" + name + "' (" + count + " commands) to schema 1.");
        return o;
    }
}
