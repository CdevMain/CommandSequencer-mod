package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.Policies.EdgeMode;
import csequencer.core.model.Policies.TriggerKind;
import csequencer.core.model.Policies.TriggerSubject;

import java.util.Set;

/**
 * Automatic start rule for a deployed project. Evaluated by the server only.
 * EVENT triggers react to game events; CONDITION triggers poll a condition graph with
 * edge detection (so "entered the arena" fires once per entry, not every tick).
 */
public final class Trigger {
    public String id;
    public String name = "";
    public boolean enabled = true;
    public TriggerKind kind = TriggerKind.EVENT;
    /** Event type id for EVENT triggers (e.g. "player.join"). */
    public String eventType = "player.join";
    /** Optional filter (EVENT) or watched condition (CONDITION). */
    public ConditionGraph condition;
    public EdgeMode edge = EdgeMode.ENTERED;
    public TriggerSubject subject = TriggerSubject.EVENT_PLAYER;
    public int cooldownTicks = 0;
    public int intervalTicks = 10;
    public boolean oncePerPlayer;
    public boolean oncePerServer;
    /** AREA triggers: dimension id ("" = any) and two opposite block corners (inclusive). */
    public String areaDim = "";
    public int[] areaFrom;
    public int[] areaTo;
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "name", "enabled", "kind", "eventType", "condition", "edge",
            "subject", "cooldownTicks", "intervalTicks", "oncePerPlayer", "oncePerServer", "area");

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("enabled", enabled);
        o.addProperty("kind", kind.name());
        o.addProperty("eventType", eventType);
        if (condition != null) o.add("condition", condition.toJson());
        o.addProperty("edge", edge.name());
        o.addProperty("subject", subject.name());
        o.addProperty("cooldownTicks", cooldownTicks);
        o.addProperty("intervalTicks", intervalTicks);
        o.addProperty("oncePerPlayer", oncePerPlayer);
        o.addProperty("oncePerServer", oncePerServer);
        if (hasArea()) {
            JsonObject a = new JsonObject();
            a.addProperty("dim", areaDim == null ? "" : areaDim);
            a.add("from", J.toIntArray(areaFrom));
            a.add("to", J.toIntArray(areaTo));
            o.add("area", a);
        }
        J.mergeExtra(o, extra);
        return o;
    }

    public boolean hasArea() { return areaFrom != null && areaTo != null && areaFrom.length == 3 && areaTo.length == 3; }

    /** Lowest corner of the area box (block coordinates). */
    public int[] areaMin() { return new int[]{Math.min(areaFrom[0], areaTo[0]), Math.min(areaFrom[1], areaTo[1]), Math.min(areaFrom[2], areaTo[2])}; }

    /** Highest corner of the area box (block coordinates, inclusive). */
    public int[] areaMax() { return new int[]{Math.max(areaFrom[0], areaTo[0]), Math.max(areaFrom[1], areaTo[1]), Math.max(areaFrom[2], areaTo[2])}; }

    /** True when the position (feet) is inside the box in the given dimension. */
    public boolean inArea(String dim, double x, double y, double z) {
        if (!hasArea()) return false;
        if (areaDim != null && !areaDim.isEmpty() && dim != null && !areaDim.equals(dim)) return false;
        int[] lo = areaMin(), hi = areaMax();
        return x >= lo[0] && x < hi[0] + 1 && y >= lo[1] && y < hi[1] + 1 && z >= lo[2] && z < hi[2] + 1;
    }

    public static Trigger fromJson(JsonObject o) {
        Trigger t = new Trigger();
        t.id = J.str(o, "id", Ids.next("tr"));
        t.name = J.str(o, "name", "");
        t.enabled = J.b(o, "enabled", true);
        t.kind = J.en(o, "kind", TriggerKind.class, TriggerKind.EVENT);
        t.eventType = J.str(o, "eventType", "player.join");
        JsonObject c = J.obj(o, "condition");
        t.condition = c == null ? null : ConditionGraph.fromJson(c);
        t.edge = J.en(o, "edge", EdgeMode.class, EdgeMode.ENTERED);
        t.subject = J.en(o, "subject", TriggerSubject.class, TriggerSubject.EVENT_PLAYER);
        t.cooldownTicks = Math.max(0, J.i(o, "cooldownTicks", 0));
        t.intervalTicks = Math.max(1, J.i(o, "intervalTicks", 10));
        t.oncePerPlayer = J.b(o, "oncePerPlayer", false);
        t.oncePerServer = J.b(o, "oncePerServer", false);
        JsonObject a = J.obj(o, "area");
        if (a != null) {
            t.areaDim = J.str(a, "dim", "");
            t.areaFrom = J.intArray(a, "from", 3);
            t.areaTo = J.intArray(a, "to", 3);
        }
        t.extra = J.extra(o, KNOWN);
        return t;
    }
}
