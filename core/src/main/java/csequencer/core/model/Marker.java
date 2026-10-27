package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.Policies.MarkerKind;

import java.util.Set;

/**
 * Timeline marker. PHASE markers define the named phases shown in the overlay and monitor.
 * CUE markers are snapping/visual aids. AUDIO markers are cue points tied to audio time.
 */
public final class Marker {
    public String id;
    public int tick;
    public String name = "";
    public MarkerKind kind = MarkerKind.PHASE;
    public int color = 0xFFFFC107;
    /** Whether this phase is announced in the overlay. */
    public boolean announce = true;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "tick", "name", "kind", "color", "announce");

    public Marker() {}

    public Marker(String name, int tick, MarkerKind kind) {
        this.id = Ids.next("m");
        this.name = name;
        this.tick = tick;
        this.kind = kind;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("tick", tick);
        o.addProperty("name", name);
        o.addProperty("kind", kind.name());
        o.addProperty("color", String.format("#%08X", color));
        o.addProperty("announce", announce);
        J.mergeExtra(o, extra);
        return o;
    }

    public static Marker fromJson(JsonObject o) {
        Marker m = new Marker();
        m.id = J.str(o, "id", Ids.next("m"));
        m.tick = Math.max(0, J.i(o, "tick", 0));
        m.name = J.str(o, "name", "");
        m.kind = J.en(o, "kind", MarkerKind.class, MarkerKind.PHASE);
        int c = Piece.parseColor(J.str(o, "color", null));
        if (c != 0) m.color = c;
        m.announce = J.b(o, "announce", true);
        m.extra = J.extra(o, KNOWN);
        return m;
    }
}
