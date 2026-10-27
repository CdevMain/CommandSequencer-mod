package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.logic.CNode;
import csequencer.core.logic.ConditionCompiler;
import csequencer.core.logic.EvalContext;
import csequencer.core.logic.QueryExpr;
import csequencer.core.model.ConditionGraph;
import csequencer.core.value.CompareOp;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.*;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class LogicTest {
    /** Resolver reading "var" queries from a map, so logic can be tested without an engine. */
    static final class MapResolver implements EvalContext.Resolver {
        final Map<String, Value> vars = new HashMap<>();
        int resolves;

        @Override public Value resolve(QueryExpr q, EvalContext ctx) {
            resolves++;
            if (q.isLiteral()) return q.literal;
            if (q.id.equals("var")) {
                String name = q.args.get("name").getAsString();
                if (ctx.subject != null && vars.containsKey(ctx.subject.name() + "." + name)) return vars.get(ctx.subject.name() + "." + name);
                return vars.getOrDefault(name, Value.NULL);
            }
            if (q.id.equals("list")) {
                List<Value> l = new ArrayList<>();
                for (String n : q.args.get("names").getAsString().split(",")) l.add(Value.of(EntityRef.player(UUID.nameUUIDFromBytes(n.getBytes()), n)));
                return Value.list(l);
            }
            return Value.NULL;
        }
    }

    final MapResolver resolver = new MapResolver();
    final Map<String, Boolean> edges = new HashMap<>();

    EvalContext ctx() {
        return new EvalContext(resolver, new EvalContext.EdgeStore() {
            @Override public Boolean get(String key) { return edges.get(key); }
            @Override public void put(String key, boolean value) { edges.put(key, value); }
        }, "test", null, null, 4096, 64, new Random(1));
    }

    CNode compile(ConditionGraph g) {
        ConditionCompiler.Result r = ConditionCompiler.compile(g, n -> null, 48, 2048);
        assertTrue(r.ok(), "compile errors: " + r.errors());
        return r.root();
    }

    ConditionGraph.Node bool(String var) { return cmp(q("var", "name", var), "is_true", null); }

    void set(String var, boolean v) { resolver.vars.put(var, Value.of(v)); }

    boolean op(String type, boolean... inputs) {
        ConditionGraph.Node root = node(type);
        ConditionGraph g = ConditionGraph.single(root);
        for (int i = 0; i < inputs.length; i++) {
            set("in" + i, inputs[i]);
            g.attach(root, bool("in" + i));
        }
        return compile(g).eval(ctx());
    }

    @Test void truthTables() {
        boolean[][] pairs = {{false, false}, {false, true}, {true, false}, {true, true}};
        for (boolean[] p : pairs) {
            boolean a = p[0], b = p[1];
            assertEquals(a && b, op("logic.and", a, b), "AND " + a + b);
            assertEquals(a || b, op("logic.or", a, b), "OR " + a + b);
            assertEquals(a ^ b, op("logic.xor", a, b), "XOR " + a + b);
            assertEquals(!(a && b), op("logic.nand", a, b), "NAND " + a + b);
            assertEquals(!(a || b), op("logic.nor", a, b), "NOR " + a + b);
            assertEquals(a == b, op("logic.xnor", a, b), "XNOR " + a + b);
        }
        assertTrue(op("logic.not", false));
        assertFalse(op("logic.not", true));
        // n-ary XOR = odd parity
        assertTrue(op("logic.xor", true, true, true));
        assertFalse(op("logic.xor", true, true, false, false));
    }

    @Test void nestedGroupsBehaveLikeParentheses() {
        // (a OR b) AND NOT (c AND d)
        ConditionGraph.Node and = node("logic.and");
        ConditionGraph.Node g1 = node("logic.group");
        g1.params.addProperty("op", "or");
        ConditionGraph.Node not = node("logic.not");
        ConditionGraph.Node g2 = node("logic.group");
        g2.params.addProperty("op", "and");
        ConditionGraph g = graph(and, and, g1, g1, bool("a"), g1, bool("b"), and, not, not, g2, g2, bool("c"), g2, bool("d"));
        CNode c = compile(g);
        for (int mask = 0; mask < 16; mask++) {
            boolean a = (mask & 1) != 0, b = (mask & 2) != 0, cc = (mask & 4) != 0, d = (mask & 8) != 0;
            set("a", a); set("b", b); set("c", cc); set("d", d);
            assertEquals((a || b) && !(cc && d), c.eval(ctx()), "mask " + mask);
        }
        assertTrue(c.describe().contains("AND"));
    }

    @Test void comparisonOperators() {
        Value five = Value.of(5);
        assertTrue(CompareOp.EQ.test(five, Value.of("5"), null));
        assertTrue(CompareOp.NEQ.test(five, Value.of(6), null));
        assertTrue(CompareOp.GT.test(five, Value.of(4), null));
        assertTrue(CompareOp.LTE.test(five, Value.of(5), null));
        assertTrue(CompareOp.IN_RANGE.test(five, Value.of(1), Value.of(10)));
        assertTrue(CompareOp.IN_RANGE.test(five, Value.of(10), Value.of(1)), "range bounds in either order");
        assertFalse(CompareOp.IN_RANGE.test(Value.of(11), Value.of(1), Value.of(10)));
        assertTrue(CompareOp.CONTAINS.test(Value.of("Dragon Arena"), Value.of("arena"), null));
        assertTrue(CompareOp.CONTAINS.test(Value.list(List.of(Value.of("a"), Value.of("b"))), Value.of("b"), null));
        assertTrue(CompareOp.EXISTS.test(Value.of("x"), null, null));
        assertFalse(CompareOp.EXISTS.test(Value.NULL, null, null));
        assertFalse(CompareOp.EXISTS.test(Value.of(""), null, null));
        assertTrue(CompareOp.STARTS_WITH.test(Value.of("minecraft:stone"), Value.of("minecraft:"), null));
        Vec3d v = new Vec3d(1, 2, 3);
        assertTrue(CompareOp.EQ.test(Value.of(v), Value.of("1 2 3"), null));
        EntityRef steve = EntityRef.player(UUID.randomUUID(), "Steve");
        assertTrue(CompareOp.EQ.test(Value.of(steve), Value.of("steve"), null), "entity compares to name");
        assertTrue(CompareOp.IS_TRUE.test(Value.of("true"), null, null));
        assertEquals(CompareOp.GTE, CompareOp.byId(">="));
    }

    @Test void stateCheckDiffersFromTransition() {
        // "inside" = state check; edge(entered) = transition.
        ConditionGraph inside = ConditionGraph.single(bool("inside"));
        ConditionGraph.Node edge = node("edge");
        edge.params.addProperty("mode", "entered");
        ConditionGraph entered = graph(edge, edge, bool("inside"));
        CNode state = compile(inside), trans = compile(entered);

        set("inside", true);
        assertTrue(state.eval(ctx()));
        assertFalse(trans.eval(ctx()), "first observation is a baseline, never a transition");
        assertTrue(state.eval(ctx()));
        assertFalse(trans.eval(ctx()), "still inside: no new entry");
        set("inside", false);
        assertFalse(trans.eval(ctx()));
        set("inside", true);
        assertTrue(trans.eval(ctx()), "entered");
        assertFalse(trans.eval(ctx()), "repeated true does not re-fire");
        assertTrue(state.eval(ctx()), "state check stays true");
    }

    @Test void exitedAndChangedEdges() {
        ConditionGraph.Node ex = node("edge");
        ex.params.addProperty("mode", "exited");
        CNode exited = compile(graph(ex, ex, bool("x")));
        ConditionGraph.Node ch = node("edge");
        ch.params.addProperty("mode", "changed");
        CNode changed = compile(graph(ch, ch, bool("x")));
        set("x", true);
        assertFalse(exited.eval(ctx()));
        assertFalse(changed.eval(ctx()));
        set("x", false);
        assertTrue(exited.eval(ctx()));
        assertTrue(changed.eval(ctx()));
        assertFalse(exited.eval(ctx()));
        assertFalse(changed.eval(ctx()));
        set("x", true);
        assertFalse(exited.eval(ctx()));
        assertTrue(changed.eval(ctx()));
    }

    @Test void edgesArePerSubject() {
        ConditionGraph.Node edge = node("edge");
        CNode trans = compile(graph(edge, edge, bool("inside")));
        EntityRef a = EntityRef.player(UUID.randomUUID(), "A"), b = EntityRef.player(UUID.randomUUID(), "B");
        resolver.vars.put("A.inside", Value.FALSE);
        resolver.vars.put("B.inside", Value.FALSE);
        EvalContext ca = ctx(); ca.subject = a;
        EvalContext cb = ctx(); cb.subject = b;
        trans.eval(ca); trans.eval(cb);
        resolver.vars.put("A.inside", Value.TRUE);
        EvalContext ca2 = ctx(); ca2.subject = a;
        EvalContext cb2 = ctx(); cb2.subject = b;
        assertTrue(trans.eval(ca2), "A entered");
        assertFalse(trans.eval(cb2), "B did not");
    }

    @Test void quantifiers() {
        resolver.vars.put("A.ready", Value.TRUE);
        resolver.vars.put("B.ready", Value.FALSE);
        resolver.vars.put("C.ready", Value.TRUE);
        for (String[] c : new String[][]{{"any", "true"}, {"all", "false"}, {"none", "false"}}) {
            ConditionGraph.Node qn = node("quant");
            qn.params.add("source", q("list", "names", "A,B,C"));
            qn.params.addProperty("mode", c[0]);
            assertEquals(Boolean.parseBoolean(c[1]), compile(graph(qn, qn, bool("ready"))).eval(ctx()), c[0]);
        }
        ConditionGraph.Node count = node("quant");
        count.params.add("source", q("list", "names", "A,B,C"));
        count.params.addProperty("mode", "count");
        count.params.addProperty("op", "gte");
        count.params.addProperty("n", 2);
        assertTrue(compile(graph(count, count, bool("ready"))).eval(ctx()));
    }

    @Test void reusableConditionsAndRecursionDetection() {
        ConditionGraph shared = ConditionGraph.single(bool("flag"));
        shared.name = "Flag";
        ConditionGraph.Node ref = node("logic.ref");
        ref.params.addProperty("condition", "Flag");
        ConditionCompiler.Result r = ConditionCompiler.compile(ConditionGraph.single(ref), n -> n.equalsIgnoreCase("Flag") ? shared : null, 48, 2048);
        assertTrue(r.ok());
        set("flag", true);
        assertTrue(r.root().eval(ctx()));

        // Self-reference must be rejected, not loop forever.
        ConditionGraph.Node selfRef = node("logic.ref");
        selfRef.params.addProperty("condition", "Loop");
        ConditionGraph loop = ConditionGraph.single(selfRef);
        loop.name = "Loop";
        ConditionCompiler.Result bad = ConditionCompiler.compile(loop, n -> loop, 48, 2048);
        assertFalse(bad.ok());
        assertTrue(bad.errors().get(0).contains("references itself"));
        assertFalse(bad.root().eval(ctx()), "invalid graphs evaluate to false");
    }

    @Test void structuralErrorsAreReported() {
        // cycle
        ConditionGraph.Node a = node("logic.and"), b = node("logic.or");
        ConditionGraph g = ConditionGraph.single(a);
        g.attach(a, b);
        g.attach(b, a);
        assertFalse(ConditionCompiler.compile(g, n -> null, 48, 2048).ok());
        // arity: NOT with two inputs
        ConditionGraph.Node not = node("logic.not");
        ConditionGraph g2 = graph(not, not, bool("x"), not, bool("y"));
        assertTrue(ConditionCompiler.compile(g2, n -> null, 48, 2048).errors().get(0).contains("needs 1"));
        // unknown type
        assertFalse(ConditionCompiler.compile(ConditionGraph.single(node("logic.maybe")), n -> null, 48, 2048).ok());
        // empty graph = true (never blocks)
        assertTrue(ConditionCompiler.compile((JsonObject) null, n -> null, 48, 2048).root().eval(ctx()));
    }

    @Test void deepNestingIsBounded() {
        ConditionGraph.Node root = node("logic.not");
        ConditionGraph g = ConditionGraph.single(root);
        ConditionGraph.Node cur = root;
        for (int i = 0; i < 100; i++) {
            ConditionGraph.Node n = node("logic.not");
            g.attach(cur, n);
            cur = n;
        }
        g.attach(cur, bool("x"));
        ConditionCompiler.Result r = ConditionCompiler.compile(g, n -> null, 48, 2048);
        assertFalse(r.ok());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("nesting")));
        // A shallower chain compiles and evaluates correctly
        ConditionCompiler.Result ok = ConditionCompiler.compile(g, n -> null, 200, 2048);
        assertTrue(ok.ok());
        set("x", true);
        assertEquals(false, ok.root().eval(new EvalContext(resolver, null, "t", null, null, 4096, 300, new Random())), "101 NOTs of true is false");
    }

    @Test void evaluationBudgetThrows() {
        ConditionGraph.Node or = node("logic.or");
        ConditionGraph g = ConditionGraph.single(or);
        for (int i = 0; i < 50; i++) g.attach(or, bool("never"));
        CNode c = compile(g);
        EvalContext tiny = new EvalContext(resolver, null, "t", null, null, 10, 64, new Random());
        assertThrows(EvalContext.BudgetExceeded.class, () -> c.eval(tiny));
    }

    @Test void shortCircuitSkipsWorkButNotTransitions() {
        ConditionGraph.Node and = node("logic.and");
        ConditionGraph g = ConditionGraph.single(and);
        set("f", false);
        g.attach(and, bool("f"));
        for (int i = 0; i < 10; i++) g.attach(and, bool("x"));
        CNode c = compile(g);
        resolver.resolves = 0;
        assertFalse(c.eval(ctx()));
        assertEquals(1, resolver.resolves, "AND short-circuits after the first false input");
    }
}
