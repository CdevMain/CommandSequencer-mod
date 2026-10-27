package csequencer.core.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.Ids;
import csequencer.core.model.Marker;
import csequencer.core.model.Piece;
import csequencer.core.model.PieceGroup;
import csequencer.core.model.Timeline;
import csequencer.core.model.Track;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure editing operations used by the in-game timeline editor (and unit tests). */
public final class TimelineOps {
    private TimelineOps() {}

    /** Snaps a tick to the grid (and to nearby markers when {@code markers} is non-null). */
    public static int snap(int tick, int grid, List<Marker> markers, int markerRadius) {
        int best = grid > 0 ? Math.round(tick / (float) grid) * grid : tick;
        if (markers != null) {
            for (Marker m : markers) if (Math.abs(m.tick - tick) <= markerRadius && Math.abs(m.tick - tick) < Math.abs(best - tick)) best = m.tick;
        }
        return Math.max(0, best);
    }

    public static Piece create(Timeline tl, String type, String trackId, int tick) {
        PieceType t = PieceRegistry.piece(type);
        Piece p = new Piece(type, trackId, Math.max(0, tick));
        if (t != null) {
            p.params = PieceRegistry.defaults(t);
            p.label = t.name();
            p.duration = t.defaultDuration();
        }
        tl.pieces.add(p);
        return p;
    }

    /** Moves pieces by a delta (ticks / track index), clamping at 0 and to existing tracks. */
    public static void move(Timeline tl, Collection<Piece> pieces, int deltaTicks, int deltaTrack) {
        int minTick = Integer.MAX_VALUE;
        for (Piece p : pieces) minTick = Math.min(minTick, p.tick);
        if (minTick + deltaTicks < 0) deltaTicks = -minTick;
        for (Piece p : pieces) {
            Track tr = tl.track(p.trackId);
            if (tr != null && tr.locked) continue;
            p.tick += deltaTicks;
            if (deltaTrack != 0) {
                int idx = tl.trackIndex(p.trackId) + deltaTrack;
                idx = Math.max(0, Math.min(tl.tracks.size() - 1, idx));
                p.trackId = tl.tracks.get(idx).id;
            }
        }
    }

    /** Serializes a selection for the clipboard (ticks relative to the earliest piece). */
    public static String copy(Timeline tl, Collection<Piece> pieces) {
        JsonObject o = new JsonObject();
        o.addProperty("format", "commandsequencer.clipboard");
        int min = Integer.MAX_VALUE;
        for (Piece p : pieces) min = Math.min(min, p.tick);
        JsonArray arr = new JsonArray();
        for (Piece p : pieces) {
            JsonObject j = p.toJson();
            j.addProperty("tick", p.tick - min);
            j.addProperty("trackIndex", tl.trackIndex(p.trackId));
            arr.add(j);
        }
        o.add("pieces", arr);
        return J.COMPACT.toJson(o);
    }

    /** Pastes clipboard content at {@code tick}, relative to {@code baseTrack}. Returns the new pieces. */
    public static List<Piece> paste(Timeline tl, String clipboard, int tick, int baseTrack) {
        List<Piece> out = new ArrayList<>();
        if (clipboard == null || clipboard.isEmpty()) return out;
        JsonObject o;
        try {
            o = J.parseObject(clipboard);
        } catch (RuntimeException e) {
            return out;
        }
        if (!"commandsequencer.clipboard".equals(J.str(o, "format", ""))) return out;
        int minIdx = Integer.MAX_VALUE;
        for (JsonElement e : J.arr(o, "pieces")) if (e.isJsonObject()) minIdx = Math.min(minIdx, J.i(e.getAsJsonObject(), "trackIndex", 0));
        Map<String, String> groupMap = new HashMap<>();
        for (JsonElement e : J.arr(o, "pieces")) {
            if (!e.isJsonObject() || out.size() >= 2000) continue;
            JsonObject j = e.getAsJsonObject();
            Piece p = Piece.fromJson(j);
            p.id = Ids.next("p");
            int idx = baseTrack + J.i(j, "trackIndex", 0) - (minIdx == Integer.MAX_VALUE ? 0 : minIdx);
            while (idx >= tl.tracks.size()) tl.tracks.add(new Track("Track " + (tl.tracks.size() + 1)));
            p.trackId = tl.tracks.get(Math.max(0, idx)).id;
            p.tick = Math.max(0, tick + p.tick);
            if (!p.groupId.isEmpty() && tl.group(p.groupId) == null) p.groupId = groupMap.computeIfAbsent(p.groupId, g -> "");
            tl.pieces.add(p);
            out.add(p);
        }
        return out;
    }

    public static List<Piece> duplicate(Timeline tl, Collection<Piece> pieces, int offset) {
        List<Piece> out = new ArrayList<>();
        for (Piece p : pieces) {
            Piece c = p.duplicate();
            c.tick = Math.max(0, p.tick + offset);
            tl.pieces.add(c);
            out.add(c);
        }
        return out;
    }

    public static PieceGroup group(Timeline tl, Collection<Piece> pieces, String name) {
        PieceGroup g = new PieceGroup(name == null || name.isEmpty() ? "Group " + (tl.groups.size() + 1) : name);
        tl.groups.add(g);
        for (Piece p : pieces) p.groupId = g.id;
        return g;
    }

    public static void ungroup(Timeline tl, String groupId) {
        for (Piece p : tl.pieces) if (groupId.equals(p.groupId)) p.groupId = "";
        tl.groups.removeIf(g -> g.id.equals(groupId));
    }

    public static void delete(Timeline tl, Collection<Piece> pieces) {
        tl.pieces.removeAll(pieces);
    }

    /** Removes a track; its pieces move to a neighbouring track (never silently deleted). */
    public static boolean removeTrack(Timeline tl, String trackId) {
        if (tl.tracks.size() <= 1) return false;
        int idx = tl.trackIndex(trackId);
        if (idx < 0) return false;
        Track target = tl.tracks.get(idx == 0 ? 1 : idx - 1);
        for (Piece p : tl.pieces) if (p.trackId.equals(trackId)) p.trackId = target.id;
        tl.tracks.remove(idx);
        return true;
    }
}
