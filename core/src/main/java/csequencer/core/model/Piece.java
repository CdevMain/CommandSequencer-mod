package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.Set;

/**
 * One block on a timeline track. {@code type} is a stable piece type id from the
 * {@link csequencer.core.pieces.PieceRegistry} (e.g. "action.command"); never a Java class name.
 */
public final class Piece {
    public String id;
    public String type;
    public String trackId;
    /** Start tick relative to the start of its timeline. */
    public int tick;
    /** Visual / logical length in ticks (used by audio, windows and display). */
    public int duration;
    public String label = "";
    /** ARGB colour override, 0 = use the type colour. */
    public int color;
    public boolean enabled = true;
    public String groupId = "";
    /** Optional per-piece error policy override (null = project default). */
    public Policies.ErrorPolicy onError;
    public boolean breakpoint;
    public JsonObject params = new JsonObject();
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "type", "track", "tick", "duration", "label", "color", "enabled",
            "group", "onError", "breakpoint", "params");

    public Piece() {}

    public Piece(String type, String trackId, int tick) {
        this.id = Ids.next("p");
        this.type = type;
        this.trackId = trackId;
        this.tick = tick;
    }

    public Piece copy() {
        Piece p = fromJson(toJson());
        return p;
    }

    /** Copy with a fresh id (duplicate / paste). */
    public Piece duplicate() {
        Piece p = copy();
        p.id = Ids.next("p");
        return p;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("track", trackId);
        o.addProperty("tick", tick);
        o.addProperty("duration", duration);
        o.addProperty("label", label);
        if (color != 0) o.addProperty("color", String.format("#%08X", color));
        o.addProperty("enabled", enabled);
        if (groupId != null && !groupId.isEmpty()) o.addProperty("group", groupId);
        if (onError != null) o.addProperty("onError", onError.name());
        if (breakpoint) o.addProperty("breakpoint", true);
        o.add("params", params == null ? new JsonObject() : params.deepCopy());
        J.mergeExtra(o, extra);
        return o;
    }

    public static Piece fromJson(JsonObject o) {
        Piece p = new Piece();
        p.id = J.str(o, "id", Ids.next("p"));
        p.type = J.str(o, "type", "unknown");
        p.trackId = J.str(o, "track", "");
        p.tick = Math.max(0, J.i(o, "tick", 0));
        p.duration = Math.max(0, J.i(o, "duration", 0));
        p.label = J.str(o, "label", "");
        p.color = parseColor(J.str(o, "color", null));
        p.enabled = J.b(o, "enabled", true);
        p.groupId = J.str(o, "group", "");
        p.onError = J.en(o, "onError", Policies.ErrorPolicy.class, null);
        p.breakpoint = J.b(o, "breakpoint", false);
        JsonObject params = J.obj(o, "params");
        p.params = params == null ? new JsonObject() : params.deepCopy();
        p.extra = J.extra(o, KNOWN);
        return p;
    }

    public static int parseColor(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            String h = s.startsWith("#") ? s.substring(1) : s;
            long v = Long.parseLong(h, 16);
            if (h.length() <= 6) v |= 0xFF000000L;
            return (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String displayName() { return label != null && !label.isEmpty() ? label : type; }
}
