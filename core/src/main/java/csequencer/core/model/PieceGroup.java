package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.Set;

/** Visual grouping of pieces (collapsible). Membership is stored on {@link Piece#groupId}. */
public final class PieceGroup {
    public String id;
    public String name;
    public int color = 0xFF7E57C2;
    public boolean collapsed;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "name", "color", "collapsed");

    public PieceGroup() {}

    public PieceGroup(String name) {
        this.id = Ids.next("g");
        this.name = name;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("color", String.format("#%08X", color));
        o.addProperty("collapsed", collapsed);
        J.mergeExtra(o, extra);
        return o;
    }

    public static PieceGroup fromJson(JsonObject o) {
        PieceGroup g = new PieceGroup();
        g.id = J.str(o, "id", Ids.next("g"));
        g.name = J.str(o, "name", "Group");
        int c = Piece.parseColor(J.str(o, "color", null));
        if (c != 0) g.color = c;
        g.collapsed = J.b(o, "collapsed", false);
        g.extra = J.extra(o, KNOWN);
        return g;
    }
}
