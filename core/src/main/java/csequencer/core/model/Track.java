package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.Set;

/** A named, coloured lane. Every track of a timeline runs as an independent parallel branch. */
public final class Track {
    public String id;
    public String name;
    public int color = 0xFF3A6EA5;
    public boolean muted;
    public boolean collapsed;
    public boolean locked;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "name", "color", "muted", "collapsed", "locked");

    public Track() {}

    public Track(String name) {
        this.id = Ids.next("t");
        this.name = name;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("color", String.format("#%08X", color));
        o.addProperty("muted", muted);
        o.addProperty("collapsed", collapsed);
        o.addProperty("locked", locked);
        J.mergeExtra(o, extra);
        return o;
    }

    public static Track fromJson(JsonObject o) {
        Track t = new Track();
        t.id = J.str(o, "id", Ids.next("t"));
        t.name = J.str(o, "name", "Track");
        int c = Piece.parseColor(J.str(o, "color", null));
        if (c != 0) t.color = c;
        t.muted = J.b(o, "muted", false);
        t.collapsed = J.b(o, "collapsed", false);
        t.locked = J.b(o, "locked", false);
        t.extra = J.extra(o, KNOWN);
        return t;
    }
}
