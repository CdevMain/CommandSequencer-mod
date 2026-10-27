package csequencer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.editor.TimelineOps;
import csequencer.core.engine.Engine;
import csequencer.core.engine.ProjectCatalog;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.StartResult;
import csequencer.core.engine.Task;
import csequencer.core.model.*;
import csequencer.core.project.ProjectMeta;
import csequencer.core.security.ServerSettings;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builders and helpers shared by the tests. */
public final class TestKit {
    public final FakePlatform platform = new FakePlatform();
    public final ServerSettings settings = new ServerSettings();
    public final List<ProjectCatalog.Entry> catalog = new ArrayList<>();
    public final Engine engine;

    public TestKit() {
        settings.finishedLingerTicks = 0;
        engine = new Engine(platform, settings, new ProjectCatalog() {
            @Override public Entry find(String idOrName) {
                for (Entry e : catalog) if (e.project().id.equals(idOrName) || e.project().name.equalsIgnoreCase(idOrName)) return e;
                return null;
            }
            @Override public List<Entry> all() { return catalog; }
        });
        platform.engineRef = engine::serverTick;
    }

    public static Project project(String name) {
        Project p = Project.create(name);
        return p;
    }

    public static Track track(Timeline tl, int index) {
        while (tl.tracks.size() <= index) tl.tracks.add(new Track("Track " + (tl.tracks.size() + 1)));
        return tl.tracks.get(index);
    }

    public static Piece cmd(Timeline tl, int track, int tick, String command) {
        Piece p = TimelineOps.create(tl, "action.command", track(tl, track).id, tick);
        p.params.addProperty("command", command);
        return p;
    }

    public static Piece piece(Timeline tl, int track, int tick, String type) {
        return TimelineOps.create(tl, type, track(tl, track).id, tick);
    }

    public static Timeline function(Project p, String name) {
        Timeline f = Timeline.create(name, "fn");
        p.functions.add(f);
        return f;
    }

    public static ProjectMeta meta(Project p, int trust) {
        ProjectMeta m = new ProjectMeta();
        m.id = p.id;
        m.name = p.name;
        m.trustLevel = trust;
        return m;
    }

    public ProjectCatalog.Entry register(Project p) {
        ProjectCatalog.Entry e = new ProjectCatalog.Entry(p, meta(p, 4));
        catalog.add(e);
        return e;
    }

    public Task start(Project p) { return start(new StartRequest(p, meta(p, 4))); }

    public Task start(StartRequest r) {
        StartResult res = engine.start(r);
        assertTrue(res.ok(), "start failed: " + res.message());
        return res.task();
    }

    public void ticks(int n) { for (int i = 0; i < n; i++) engine.tick(); }

    public void ticksWithClock(int n) {
        for (int i = 0; i < n; i++) {
            platform.now += 50;
            engine.tick();
        }
    }

    /** Commands executed (detail strings only, without the tick prefix). */
    public List<String> commands() {
        List<String> out = new ArrayList<>();
        for (String a : platform.actions) {
            String s = a.substring(a.indexOf(':') + 1);
            if (s.startsWith("action.command:")) out.add(s.substring("action.command:".length()));
        }
        return out;
    }

    /** Engine ticks at which the command with this text ran. */
    public List<Long> ticksOf(String command) {
        List<Long> out = new ArrayList<>();
        for (String a : platform.actions) {
            int c = a.indexOf(':');
            if (a.substring(c + 1).equals("action.command:" + command)) out.add(Long.parseLong(a.substring(0, c)));
        }
        return out;
    }

    // ── Condition builders ────────────────────────────────────────────────

    public static JsonObject q(String id, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("q", id);
        JsonObject args = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) args.addProperty((String) kv[i], n);
            else if (v instanceof Boolean b) args.addProperty((String) kv[i], b);
            else if (v instanceof JsonObject j) args.add((String) kv[i], j);
            else args.addProperty((String) kv[i], String.valueOf(v));
        }
        o.add("args", args);
        return o;
    }

    public static ConditionGraph.Node cmp(JsonObject left, String op, Object right) {
        ConditionGraph.Node n = new ConditionGraph.Node("cmp");
        n.params.add("left", left);
        n.params.addProperty("op", op);
        if (right instanceof Number num) n.params.addProperty("right", num);
        else if (right instanceof JsonObject j) n.params.add("right", j);
        else if (right != null) n.params.addProperty("right", String.valueOf(right));
        return n;
    }

    public static ConditionGraph.Node node(String type) { return new ConditionGraph.Node(type); }

    /** Graph whose root is {@code root}, children attached via the varargs pairs (parent, child). */
    public static ConditionGraph graph(ConditionGraph.Node root, ConditionGraph.Node... parentChildPairs) {
        ConditionGraph g = ConditionGraph.single(root);
        for (int i = 0; i + 1 < parentChildPairs.length; i += 2) g.attach(parentChildPairs[i], parentChildPairs[i + 1]);
        return g;
    }

    public static ConditionGraph varEquals(String name, Object value) {
        return ConditionGraph.single(cmp(q("var", "name", name), "eq", value));
    }

    public static JsonArray branches(Object... condTargetPairs) {
        JsonArray a = new JsonArray();
        for (int i = 0; i + 1 < condTargetPairs.length; i += 2) {
            JsonObject b = new JsonObject();
            b.add("condition", ((ConditionGraph) condTargetPairs[i]).toJson());
            b.addProperty("target", (String) condTargetPairs[i + 1]);
            a.add(b);
        }
        return a;
    }
}
