package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.Set;

/**
 * Reference to an audio resource used by the project. Audio data is stored separately
 * (content addressed by {@link #hash}) and is never embedded in the project file.
 */
public final class AudioRef {
    public String id;
    public String name = "";
    /** Lowercase hex SHA-1 of the original file bytes. */
    public String hash = "";
    /** "wav" or "mp3". */
    public String format = "wav";
    public long durationMs;
    public long sizeBytes;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "name", "hash", "format", "durationMs", "sizeBytes");

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("hash", hash);
        o.addProperty("format", format);
        o.addProperty("durationMs", durationMs);
        o.addProperty("sizeBytes", sizeBytes);
        J.mergeExtra(o, extra);
        return o;
    }

    public static AudioRef fromJson(JsonObject o) {
        AudioRef a = new AudioRef();
        a.id = J.str(o, "id", Ids.next("a"));
        a.name = J.str(o, "name", "");
        a.hash = J.str(o, "hash", "").toLowerCase(java.util.Locale.ROOT);
        a.format = J.str(o, "format", "wav").toLowerCase(java.util.Locale.ROOT);
        a.durationMs = Math.max(0, J.l(o, "durationMs", 0));
        a.sizeBytes = Math.max(0, J.l(o, "sizeBytes", 0));
        a.extra = J.extra(o, KNOWN);
        return a;
    }

    public int durationTicks() { return (int) Math.ceil(durationMs / 50.0); }

    public static boolean isValidHash(String h) { return h != null && h.matches("[0-9a-f]{40}"); }
}
