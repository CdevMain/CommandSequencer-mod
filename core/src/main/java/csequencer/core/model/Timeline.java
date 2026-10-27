package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A timeline: tracks of timed pieces. The project's main sequence is a timeline, and every
 * reusable function / subsequence is a timeline with typed inputs and outputs.
 * When a timeline runs, each track becomes an independent parallel branch.
 */
public final class Timeline {
    public String id;
    public String name;
    public String description = "";
    public List<VariableDecl> inputs = new ArrayList<>();
    public List<VariableDecl> outputs = new ArrayList<>();
    public List<Track> tracks = new ArrayList<>();
    public List<Piece> pieces = new ArrayList<>();
    public List<PieceGroup> groups = new ArrayList<>();
    public List<Marker> markers = new ArrayList<>();
    public int color = 0xFF26A69A;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "name", "description", "inputs", "outputs", "tracks", "pieces",
            "groups", "markers", "color");

    public Timeline() {}

    public Timeline(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Creates an empty timeline with one default track. */
    public static Timeline create(String name, String idPrefix) {
        Timeline t = new Timeline(Ids.next(idPrefix), name);
        t.tracks.add(new Track("Track 1"));
        return t;
    }

    public Track track(String id) {
        for (Track t : tracks) if (t.id.equals(id)) return t;
        return null;
    }

    public int trackIndex(String id) {
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id.equals(id)) return i;
        return -1;
    }

    public Piece piece(String id) {
        for (Piece p : pieces) if (p.id.equals(id)) return p;
        return null;
    }

    public PieceGroup group(String id) {
        for (PieceGroup g : groups) if (g.id.equals(id)) return g;
        return null;
    }

    /**
     * Pieces of a track in deterministic execution order: by tick, then by list order.
     * List order is the order pieces were placed / arranged and is persisted.
     */
    public List<Piece> orderedPieces(String trackId) {
        List<Piece> out = new ArrayList<>();
        for (Piece p : pieces) if (trackId.equals(p.trackId)) out.add(p);
        List<Piece> all = pieces;
        out.sort(Comparator.<Piece>comparingInt(p -> p.tick).thenComparingInt(all::indexOf));
        return out;
    }

    /** Last tick touched by any piece (tick + duration), or by any marker. */
    public int contentLength() {
        int max = 0;
        for (Piece p : pieces) max = Math.max(max, p.tick + Math.max(0, p.duration));
        for (Marker m : markers) max = Math.max(max, m.tick);
        return max;
    }

    public List<Marker> phases() {
        List<Marker> out = new ArrayList<>();
        for (Marker m : markers) if (m.kind == Policies.MarkerKind.PHASE) out.add(m);
        out.sort(Comparator.comparingInt(m -> m.tick));
        return out;
    }

    public Timeline copy() { return fromJson(toJson()); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("color", String.format("#%08X", color));
        o.add("inputs", J.write(inputs, VariableDecl::toJson));
        o.add("outputs", J.write(outputs, VariableDecl::toJson));
        o.add("tracks", J.write(tracks, Track::toJson));
        o.add("pieces", J.write(pieces, Piece::toJson));
        o.add("groups", J.write(groups, PieceGroup::toJson));
        o.add("markers", J.write(markers, Marker::toJson));
        J.mergeExtra(o, extra);
        return o;
    }

    public static Timeline fromJson(JsonObject o) {
        Timeline t = new Timeline();
        t.id = J.str(o, "id", Ids.next("tl"));
        t.name = J.str(o, "name", "Timeline");
        t.description = J.str(o, "description", "");
        int c = Piece.parseColor(J.str(o, "color", null));
        if (c != 0) t.color = c;
        t.inputs = J.list(o, "inputs", VariableDecl::fromJson);
        t.outputs = J.list(o, "outputs", VariableDecl::fromJson);
        t.tracks = J.list(o, "tracks", Track::fromJson);
        t.pieces = J.list(o, "pieces", Piece::fromJson);
        t.groups = J.list(o, "groups", PieceGroup::fromJson);
        t.markers = J.list(o, "markers", Marker::fromJson);
        t.extra = J.extra(o, KNOWN);
        return t;
    }
}
