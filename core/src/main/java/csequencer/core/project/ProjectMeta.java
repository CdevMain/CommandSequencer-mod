package csequencer.core.project;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-owned metadata about a stored project. Never taken from the project file itself,
 * so a client cannot forge ownership or trust by editing JSON.
 */
public final class ProjectMeta {
    public enum Visibility { PRIVATE, SHARED, PUBLIC }

    public String id;
    public String name;
    public String ownerUuid = "";
    public String ownerName = "";
    /** Players (uuid strings) allowed to edit besides the owner. */
    public List<String> editors = new ArrayList<>();
    /** PRIVATE: owner/editors/admins. SHARED: anyone with RUN may run & view. PUBLIC: anyone may view, RUN may run. */
    public Visibility visibility = Visibility.SHARED;
    /** Operator level of the player who last saved it; limits command execution. */
    public int trustLevel;
    public long createdAt;
    public long updatedAt;
    public long revision;
    public long sizeBytes;
    /** Whether the project's automatic triggers are armed. */
    public boolean triggersEnabled = true;
    public int pieceCount;
    public String lastSavedBy = "";

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("ownerUuid", ownerUuid);
        o.addProperty("ownerName", ownerName);
        o.add("editors", J.toArray(editors));
        o.addProperty("visibility", visibility.name());
        o.addProperty("trustLevel", trustLevel);
        o.addProperty("createdAt", createdAt);
        o.addProperty("updatedAt", updatedAt);
        o.addProperty("revision", revision);
        o.addProperty("sizeBytes", sizeBytes);
        o.addProperty("triggersEnabled", triggersEnabled);
        o.addProperty("pieceCount", pieceCount);
        o.addProperty("lastSavedBy", lastSavedBy);
        return o;
    }

    public static ProjectMeta fromJson(JsonObject o) {
        ProjectMeta m = new ProjectMeta();
        m.id = J.str(o, "id", "");
        m.name = J.str(o, "name", "");
        m.ownerUuid = J.str(o, "ownerUuid", "");
        m.ownerName = J.str(o, "ownerName", "");
        m.editors = new ArrayList<>(J.strings(o, "editors"));
        m.visibility = J.en(o, "visibility", Visibility.class, Visibility.SHARED);
        m.trustLevel = Math.max(0, Math.min(4, J.i(o, "trustLevel", 0)));
        m.createdAt = J.l(o, "createdAt", 0);
        m.updatedAt = J.l(o, "updatedAt", 0);
        m.revision = J.l(o, "revision", 0);
        m.sizeBytes = J.l(o, "sizeBytes", 0);
        m.triggersEnabled = J.b(o, "triggersEnabled", true);
        m.pieceCount = J.i(o, "pieceCount", 0);
        m.lastSavedBy = J.str(o, "lastSavedBy", "");
        return m;
    }

    public boolean isOwner(String uuid) { return uuid != null && uuid.equals(ownerUuid); }

    public boolean isEditor(String uuid) { return isOwner(uuid) || editors.contains(uuid); }
}
