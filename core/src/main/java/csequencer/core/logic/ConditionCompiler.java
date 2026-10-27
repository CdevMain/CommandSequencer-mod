package csequencer.core.logic;

import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.ConditionGraph;
import csequencer.core.pieces.CondNodeType;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.value.CompareOp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Compiles a {@link ConditionGraph} into a {@link CNode} tree. Detects cycles, wrong arity,
 * unknown node types, recursive reusable-condition references and excessive depth/size.
 * An empty graph compiles to TRUE (an unconfigured condition never blocks).
 */
public final class ConditionCompiler {
    public record Result(CNode root, List<String> errors, List<String> warnings) {
        public boolean ok() { return errors.isEmpty(); }
    }

    private final Function<String, ConditionGraph> refs;
    private final int maxDepth;
    private final int maxNodes;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Deque<String> refStack = new ArrayDeque<>();
    private int nodeCount;

    private ConditionCompiler(Function<String, ConditionGraph> refs, int maxDepth, int maxNodes) {
        this.refs = refs;
        this.maxDepth = maxDepth;
        this.maxNodes = maxNodes;
    }

    public static Result compile(ConditionGraph g, Function<String, ConditionGraph> refs, int maxDepth, int maxNodes) {
        ConditionCompiler c = new ConditionCompiler(refs == null ? s -> null : refs, maxDepth, maxNodes);
        CNode root = c.compileGraph(g, "");
        if (!c.errors.isEmpty()) root = new CNode.Const(null, false);
        return new Result(root, List.copyOf(c.errors), List.copyOf(c.warnings));
    }

    public static Result compile(JsonObject graphJson, Function<String, ConditionGraph> refs, int maxDepth, int maxNodes) {
        if (graphJson == null || graphJson.size() == 0) return new Result(new CNode.Const(null, true), List.of(), List.of());
        return compile(ConditionGraph.fromJson(graphJson), refs, maxDepth, maxNodes);
    }

    private CNode compileGraph(ConditionGraph g, String prefix) {
        if (g == null || g.isEmpty()) return new CNode.Const(prefix + "empty", true);
        ConditionGraph.Node root = g.node(g.root);
        if (root == null) {
            errors.add("Condition root node '" + g.root + "' does not exist");
            return new CNode.Const(null, false);
        }
        // Each node may feed exactly one parent.
        Set<String> seenFrom = new HashSet<>();
        for (ConditionGraph.Edge e : g.edges) {
            if (g.node(e.from) == null || g.node(e.to) == null) { warnings.add("Dangling connection ignored"); continue; }
            if (!seenFrom.add(e.from)) errors.add("Node '" + label(g.node(e.from)) + "' is connected to more than one parent");
        }
        Set<String> reachable = new HashSet<>();
        CNode out = compileNode(g, root, prefix, new HashSet<>(), reachable, 0);
        int unused = 0;
        for (ConditionGraph.Node n : g.nodes) if (!reachable.contains(n.id)) unused++;
        if (unused > 0) warnings.add(unused + " node(s) are not connected to the result and are ignored");
        return out;
    }

    private CNode compileNode(ConditionGraph g, ConditionGraph.Node n, String prefix, Set<String> path, Set<String> reachable, int depth) {
        if (++nodeCount > maxNodes) {
            if (nodeCount == maxNodes + 1) errors.add("Condition has more than " + maxNodes + " nodes");
            return new CNode.Const(null, false);
        }
        if (depth > maxDepth) {
            errors.add("Condition nesting exceeds " + maxDepth + " levels");
            return new CNode.Const(null, false);
        }
        if (!path.add(n.id)) {
            errors.add("Cycle detected at node '" + label(n) + "'");
            return new CNode.Const(null, false);
        }
        reachable.add(n.id);
        try {
            CondNodeType type = PieceRegistry.node(n.type);
            if (type == null) {
                errors.add("Unknown logic node type '" + n.type + "'");
                return new CNode.Const(null, false);
            }
            List<ConditionGraph.Node> kids = g.children(n.id);
            if (kids.size() < type.minChildren() || kids.size() > type.maxChildren()) {
                String expect = type.minChildren() == type.maxChildren() ? String.valueOf(type.minChildren())
                        : type.minChildren() + (type.maxChildren() == CondNodeType.UNBOUNDED ? "+" : "-" + type.maxChildren());
                errors.add(type.name() + " node '" + label(n) + "' needs " + expect + " input(s), has " + kids.size());
                return new CNode.Const(null, false);
            }
            List<CNode> children = new ArrayList<>();
            for (ConditionGraph.Node k : kids) children.add(compileNode(g, k, prefix, path, reachable, depth + 1));
            String id = prefix + n.id;
            JsonObject p = n.params;
            return switch (n.type) {
                case "logic.and" -> new CNode.Op(id, CNode.OpKind.AND, children);
                case "logic.or" -> new CNode.Op(id, CNode.OpKind.OR, children);
                case "logic.xor" -> new CNode.Op(id, CNode.OpKind.XOR, children);
                case "logic.nand" -> new CNode.Op(id, CNode.OpKind.NAND, children);
                case "logic.nor" -> new CNode.Op(id, CNode.OpKind.NOR, children);
                case "logic.xnor" -> new CNode.Op(id, CNode.OpKind.XNOR, children);
                case "logic.not" -> new CNode.Not(id, children.get(0));
                case "logic.true" -> new CNode.Const(id, true);
                case "logic.false" -> new CNode.Const(id, false);
                case "logic.group" -> new CNode.Op(id, opKind(J.str(p, "op", "and")), children);
                case "chance" -> new CNode.Chance(id, Math.max(0, Math.min(1, J.d(p, "probability", 0.5))));
                case "edge" -> new CNode.Edge(id, children.get(0), switch (J.str(p, "mode", "entered").toLowerCase(Locale.ROOT)) {
                    case "exited" -> CNode.EdgeKind.EXITED;
                    case "changed" -> CNode.EdgeKind.CHANGED;
                    default -> CNode.EdgeKind.ENTERED;
                });
                case "cmp" -> compileCmp(id, n);
                case "quant" -> {
                    CNode.QuantMode mode = switch (J.str(p, "mode", "any").toLowerCase(Locale.ROOT)) {
                        case "all" -> CNode.QuantMode.ALL;
                        case "none" -> CNode.QuantMode.NONE;
                        case "count" -> CNode.QuantMode.COUNT;
                        default -> CNode.QuantMode.ANY;
                    };
                    CompareOp op = CompareOp.byId(J.str(p, "op", "gte"));
                    yield new CNode.Quant(id, QueryExpr.parse(p.get("source")), mode, op == null ? CompareOp.GTE : op, J.d(p, "n", 1), children.get(0));
                }
                case "logic.ref" -> compileRef(id, J.str(p, "condition", ""), depth);
                default -> {
                    errors.add("Unsupported logic node '" + n.type + "'");
                    yield new CNode.Const(id, false);
                }
            };
        } finally {
            path.remove(n.id);
        }
    }

    private CNode compileCmp(String id, ConditionGraph.Node n) {
        CompareOp op = CompareOp.byId(J.str(n.params, "op", "eq"));
        if (op == null) {
            errors.add("Unknown comparison operator '" + J.str(n.params, "op", "") + "'");
            return new CNode.Const(id, false);
        }
        if (!n.params.has("left")) warnings.add("Comparison '" + label(n) + "' has no left value");
        return new CNode.Cmp(id, QueryExpr.parse(n.params.get("left")), op, QueryExpr.parse(n.params.get("right")), QueryExpr.parse(n.params.get("right2")));
    }

    private CNode compileRef(String id, String name, int depth) {
        if (name.isEmpty()) {
            errors.add("Reusable condition reference has no condition selected");
            return new CNode.Const(id, false);
        }
        if (refStack.contains(name.toLowerCase(Locale.ROOT))) {
            errors.add("Reusable condition '" + name + "' references itself (recursion)");
            return new CNode.Const(id, false);
        }
        if (refStack.size() >= 16) {
            errors.add("Reusable condition references nested deeper than 16");
            return new CNode.Const(id, false);
        }
        ConditionGraph target = refs.apply(name);
        if (target == null) {
            errors.add("Reusable condition '" + name + "' not found");
            return new CNode.Const(id, false);
        }
        refStack.push(name.toLowerCase(Locale.ROOT));
        try {
            // Prefix keeps transition state of two references to the same condition separate.
            return new CNode.Ref(id, name, compileGraph(target, id + "/"));
        } finally {
            refStack.pop();
        }
    }

    static CNode.OpKind opKind(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "or" -> CNode.OpKind.OR;
            case "xor" -> CNode.OpKind.XOR;
            case "nand" -> CNode.OpKind.NAND;
            case "nor" -> CNode.OpKind.NOR;
            case "xnor" -> CNode.OpKind.XNOR;
            default -> CNode.OpKind.AND;
        };
    }

    private static String label(ConditionGraph.Node n) {
        if (n == null) return "?";
        String name = J.str(n.params, "name", "");
        return name.isEmpty() ? n.id : name;
    }
}
