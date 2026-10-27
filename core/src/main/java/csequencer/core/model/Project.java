package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Root of a CommandSequencer project file. Loader and Minecraft-version independent.
 * Serialized by {@link csequencer.core.json.ProjectCodec} with a schema version and migrations.
 */
public final class Project {
    /** Current schema version written by this build. */
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public String id;
    public String name;
    public String description = "";
    public List<String> tags = new ArrayList<>();
    public ProjectSettings settings = new ProjectSettings();
    public Timeline main;
    public List<Timeline> functions = new ArrayList<>();
    /** Reusable named condition graphs referenced by "logic.ref" nodes. */
    public List<ConditionGraph> conditions = new ArrayList<>();
    public List<AudioRef> audio = new ArrayList<>();
    public List<Trigger> triggers = new ArrayList<>();
    public List<VariableDecl> variables = new ArrayList<>();
    /** Monotonic revision, incremented by the server on each accepted save. */
    public long revision;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("schemaVersion", "format", "id", "name", "description", "tags", "settings",
            "main", "functions", "conditions", "audio", "triggers", "variables", "revision");

    /** Top-level keys owned by the codec; everything else is preserved as extra data. */
    public static Set<String> KNOWN_KEYS() { return KNOWN; }

    public static Project create(String name) {
        Project p = new Project();
        p.id = Ids.next("proj");
        p.name = name;
        p.main = Timeline.create("Main", "tl");
        p.main.name = "Main";
        return p;
    }

    public Timeline timeline(String id) {
        if (id == null) return null;
        if (main != null && main.id.equals(id)) return main;
        for (Timeline t : functions) if (t.id.equals(id)) return t;
        return null;
    }

    /** Looks a function up by id, then by (case-insensitive) name. */
    public Timeline function(String idOrName) {
        if (idOrName == null || idOrName.isEmpty()) return null;
        for (Timeline t : functions) if (t.id.equals(idOrName)) return t;
        for (Timeline t : functions) if (t.name.equalsIgnoreCase(idOrName)) return t;
        return null;
    }

    public ConditionGraph condition(String idOrName) {
        for (ConditionGraph g : conditions) if (g.id.equals(idOrName)) return g;
        for (ConditionGraph g : conditions) if (g.name.equalsIgnoreCase(idOrName)) return g;
        return null;
    }

    public AudioRef audio(String idOrName) {
        for (AudioRef a : audio) if (a.id.equals(idOrName)) return a;
        for (AudioRef a : audio) if (a.name.equalsIgnoreCase(idOrName)) return a;
        return null;
    }

    public List<Timeline> allTimelines() {
        List<Timeline> out = new ArrayList<>();
        if (main != null) out.add(main);
        out.addAll(functions);
        return out;
    }

    /** Length of the main timeline used for progress display. */
    public int mainLength() {
        if (settings.lengthTicks > 0) return settings.lengthTicks;
        return main == null ? 0 : main.contentLength();
    }

    public int pieceCount() {
        int n = 0;
        for (Timeline t : allTimelines()) n += t.pieces.size();
        return n;
    }

    public Project copy() { return csequencer.core.json.ProjectCodec.read(csequencer.core.json.ProjectCodec.write(this)).project(); }
}
