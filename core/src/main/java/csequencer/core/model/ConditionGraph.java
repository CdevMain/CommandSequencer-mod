package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Visual logic graph: nodes (operators, comparisons, groups, references, transition detectors)
 * connected child → parent. The engine compiles it into an evaluation tree with
 * {@link csequencer.core.logic.ConditionCompiler}, which also rejects cycles and arity errors.
 */
public final class ConditionGraph {
    public String id;
    public String name = "";
    public List<Node> nodes = new ArrayList<>();
    public List<Edge> edges = new ArrayList<>();
    /** Id of the node whose output is the graph's result. */
    public String root = "";
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("id", "name", "nodes", "edges", "root");

    public static final class Node {
        public String id;
        public String type;
        public JsonObject params = new JsonObject();
        public int x, y;
        public JsonObject extra = new JsonObject();
        private static final Set<String> KNOWN = Set.of("id", "type", "params", "x", "y");

        public Node() {}

        public Node(String type) {
            this.id = Ids.next("n");
            this.type = type;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", type);
            o.add("params", params == null ? new JsonObject() : params.deepCopy());
            o.addProperty("x", x);
            o.addProperty("y", y);
            J.mergeExtra(o, extra);
            return o;
        }

        public static Node fromJson(JsonObject o) {
            Node n = new Node();
            n.id = J.str(o, "id", Ids.next("n"));
            n.type = J.str(o, "type", "logic.true");
            JsonObject p = J.obj(o, "params");
            n.params = p == null ? new JsonObject() : p.deepCopy();
            n.x = J.i(o, "x", 0);
            n.y = J.i(o, "y", 0);
            n.extra = J.extra(o, KNOWN);
            return n;
        }
    }

    /** Connection: output of {@code from} feeds input slot {@code slot} of {@code to}. */
    public static final class Edge {
        public String from;
        public String to;
        public int slot;

        public Edge() {}

        public Edge(String from, String to, int slot) {
            this.from = from;
            this.to = to;
            this.slot = slot;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("from", from);
            o.addProperty("to", to);
            o.addProperty("slot", slot);
            return o;
        }

        public static Edge fromJson(JsonObject o) {
            return new Edge(J.str(o, "from", ""), J.str(o, "to", ""), J.i(o, "slot", 0));
        }
    }

    public Node node(String id) {
        for (Node n : nodes) if (n.id.equals(id)) return n;
        return null;
    }

    /** Children of a node, ordered by slot then edge order. */
    public List<Node> children(String nodeId) {
        List<Edge> es = new ArrayList<>();
        for (Edge e : edges) if (e.to.equals(nodeId)) es.add(e);
        es.sort(Comparator.comparingInt(e -> e.slot));
        List<Node> out = new ArrayList<>();
        for (Edge e : es) {
            Node n = node(e.from);
            if (n != null) out.add(n);
        }
        return out;
    }

    public boolean isEmpty() { return nodes.isEmpty() || root == null || root.isEmpty(); }

    public ConditionGraph copy() { return fromJson(toJson()); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.add("nodes", J.write(nodes, Node::toJson));
        o.add("edges", J.write(edges, Edge::toJson));
        o.addProperty("root", root);
        J.mergeExtra(o, extra);
        return o;
    }

    public static ConditionGraph fromJson(JsonObject o) {
        ConditionGraph g = new ConditionGraph();
        if (o == null) { g.id = Ids.next("c"); return g; }
        g.id = J.str(o, "id", Ids.next("c"));
        g.name = J.str(o, "name", "");
        g.nodes = J.list(o, "nodes", Node::fromJson);
        g.edges = J.list(o, "edges", Edge::fromJson);
        g.root = J.str(o, "root", "");
        g.extra = J.extra(o, KNOWN);
        return g;
    }

    // ── Builders (used by templates, tests and the editor) ─────────────────

    public static ConditionGraph single(Node n) {
        ConditionGraph g = new ConditionGraph();
        g.id = Ids.next("c");
        g.nodes.add(n);
        g.root = n.id;
        return g;
    }

    /** Adds {@code child} under {@code parent} in the next free slot. */
    public Node attach(Node parent, Node child) {
        if (node(child.id) == null) nodes.add(child);
        int slot = 0;
        for (Edge e : edges) if (e.to.equals(parent.id)) slot = Math.max(slot, e.slot + 1);
        edges.add(new Edge(child.id, parent.id, slot));
        return child;
    }
}
