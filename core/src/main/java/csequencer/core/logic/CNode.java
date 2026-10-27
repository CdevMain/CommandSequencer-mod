package csequencer.core.logic;

import csequencer.core.value.CompareOp;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.ValueType;

import java.util.List;

/** Compiled, immutable condition tree node. */
public abstract class CNode {
    public final String id;

    protected CNode(String id) { this.id = id; }

    public final boolean eval(EvalContext ctx) {
        if (++ctx.nodes > ctx.maxNodes) throw new EvalContext.BudgetExceeded("Condition exceeded " + ctx.maxNodes + " node evaluations");
        if (++ctx.depth > ctx.maxDepth) {
            ctx.depth--;
            throw new EvalContext.BudgetExceeded("Condition nesting deeper than " + ctx.maxDepth);
        }
        try {
            boolean r = doEval(ctx);
            if (ctx.trace != null && id != null) ctx.trace.put(id + "@" + ctx.subjectKey(), r);
            return r;
        } finally {
            ctx.depth--;
        }
    }

    protected abstract boolean doEval(EvalContext ctx);

    public abstract String describe();

    // ── Node implementations ─────────────────────────────────────────────

    public enum OpKind { AND, OR, XOR, NAND, NOR, XNOR }

    public static final class Op extends CNode {
        final OpKind kind;
        final List<CNode> children;

        public Op(String id, OpKind kind, List<CNode> children) {
            super(id);
            this.kind = kind;
            this.children = List.copyOf(children);
        }

        @Override protected boolean doEval(EvalContext ctx) {
            switch (kind) {
                case AND, NAND -> {
                    boolean all = true;
                    // Short-circuit; edge nodes inside should be placed where they are always evaluated
                    // (documented). We still evaluate every child for stateful correctness when it contains edges.
                    for (CNode c : children) if (!c.eval(ctx)) { all = false; if (!containsStateful(children)) break; }
                    return kind == OpKind.AND ? all : !all;
                }
                case OR, NOR -> {
                    boolean any = false;
                    for (CNode c : children) if (c.eval(ctx)) { any = true; if (!containsStateful(children)) break; }
                    return kind == OpKind.OR ? any : !any;
                }
                default -> {
                    int trues = 0;
                    for (CNode c : children) if (c.eval(ctx)) trues++;
                    boolean odd = (trues & 1) == 1;
                    return kind == OpKind.XOR ? odd : !odd;
                }
            }
        }

        private Boolean stateful;

        private boolean containsStateful(List<CNode> cs) {
            if (stateful == null) {
                boolean s = false;
                for (CNode c : cs) if (c.isStateful()) { s = true; break; }
                stateful = s;
            }
            return stateful;
        }

        @Override boolean isStateful() {
            for (CNode c : children) if (c.isStateful()) return true;
            return false;
        }

        @Override public String describe() {
            StringBuilder sb = new StringBuilder();
            boolean neg = kind == OpKind.NAND || kind == OpKind.NOR || kind == OpKind.XNOR;
            String sep = switch (kind) { case AND, NAND -> " AND "; case OR, NOR -> " OR "; default -> " XOR "; };
            if (neg) sb.append("NOT ");
            sb.append('(');
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(sep);
                sb.append(children.get(i).describe());
            }
            return sb.append(')').toString();
        }
    }

    public static final class Not extends CNode {
        final CNode child;

        public Not(String id, CNode child) { super(id); this.child = child; }

        @Override protected boolean doEval(EvalContext ctx) { return !child.eval(ctx); }

        @Override boolean isStateful() { return child.isStateful(); }

        @Override public String describe() { return "NOT " + child.describe(); }
    }

    public static final class Const extends CNode {
        final boolean value;

        public Const(String id, boolean value) { super(id); this.value = value; }

        @Override protected boolean doEval(EvalContext ctx) { return value; }

        @Override public String describe() { return value ? "TRUE" : "FALSE"; }
    }

    public static final class Cmp extends CNode {
        final QueryExpr left, right, right2;
        final CompareOp op;

        public Cmp(String id, QueryExpr left, CompareOp op, QueryExpr right, QueryExpr right2) {
            super(id);
            this.left = left;
            this.op = op;
            this.right = right;
            this.right2 = right2;
        }

        @Override protected boolean doEval(EvalContext ctx) {
            Value a = ctx.resolver.resolve(left, ctx);
            Value b = op.operands >= 1 ? ctx.resolver.resolve(right, ctx) : Value.NULL;
            Value c = op.operands >= 2 ? ctx.resolver.resolve(right2, ctx) : Value.NULL;
            return op.test(a, b, c);
        }

        @Override public String describe() {
            return switch (op.operands) {
                case 0 -> left.describe() + " " + op.symbol;
                case 2 -> left.describe() + " " + op.symbol + " [" + right.describe() + ", " + right2.describe() + "]";
                default -> left.describe() + " " + op.symbol + " " + right.describe();
            };
        }
    }

    public enum EdgeKind { ENTERED, EXITED, CHANGED }

    /**
     * Transition detector. Stores the previous value of its child per (state key, subject).
     * The first observation of a subject only records a baseline and never fires.
     */
    public static final class Edge extends CNode {
        final CNode child;
        final EdgeKind kind;

        public Edge(String id, CNode child, EdgeKind kind) {
            super(id);
            this.child = child;
            this.kind = kind;
        }

        @Override protected boolean doEval(EvalContext ctx) {
            boolean now = child.eval(ctx);
            String key = ctx.stateKey + "|" + id + "|" + ctx.subjectKey();
            Boolean prev = ctx.edges.get(key);
            ctx.edges.put(key, now);
            if (prev == null) return false;
            return switch (kind) {
                case ENTERED -> now && !prev;
                case EXITED -> !now && prev;
                case CHANGED -> now != prev;
            };
        }

        @Override boolean isStateful() { return true; }

        @Override public String describe() { return kind.name().toLowerCase() + "(" + child.describe() + ")"; }
    }

    public enum QuantMode { ANY, ALL, NONE, COUNT }

    public static final class Quant extends CNode {
        final QueryExpr source;
        final QuantMode mode;
        final CompareOp countOp;
        final double n;
        final CNode child;

        public Quant(String id, QueryExpr source, QuantMode mode, CompareOp countOp, double n, CNode child) {
            super(id);
            this.source = source;
            this.mode = mode;
            this.countOp = countOp;
            this.n = n;
            this.child = child;
        }

        @Override protected boolean doEval(EvalContext ctx) {
            List<Value> items = ctx.resolver.resolve(source, ctx).asList();
            EntityRef savedSubject = ctx.subject;
            Value savedElement = ctx.element;
            int matches = 0;
            boolean stateful = child.isStateful();
            try {
                for (Value item : items) {
                    ctx.element = item;
                    if (item.type() == ValueType.ENTITY) ctx.subject = item.asEntity();
                    boolean r = child.eval(ctx);
                    if (r) matches++;
                    if (!stateful) {
                        if (mode == QuantMode.ANY && r) return true;
                        if (mode == QuantMode.ALL && !r) return false;
                        if (mode == QuantMode.NONE && r) return false;
                    }
                }
            } finally {
                ctx.subject = savedSubject;
                ctx.element = savedElement;
            }
            return switch (mode) {
                case ANY -> matches > 0;
                case ALL -> matches == items.size();
                case NONE -> matches == 0;
                case COUNT -> countOp.test(Value.of(matches), Value.of(n), Value.NULL);
            };
        }

        @Override boolean isStateful() { return child.isStateful(); }

        @Override public String describe() {
            String m = mode == QuantMode.COUNT ? "count " + countOp.symbol + " " + Value.fmtNumber(n) : mode.name().toLowerCase();
            return m + " of " + source.describe() + ": " + child.describe();
        }
    }

    public static final class Chance extends CNode {
        final double p;

        public Chance(String id, double p) { super(id); this.p = p; }

        @Override protected boolean doEval(EvalContext ctx) { return ctx.random.nextDouble() < p; }

        @Override public String describe() { return "chance(" + Value.fmtNumber(p) + ")"; }
    }

    /** Wraps the compiled tree of a reusable named condition. */
    public static final class Ref extends CNode {
        final String name;
        final CNode target;

        public Ref(String id, String name, CNode target) {
            super(id);
            this.name = name;
            this.target = target;
        }

        @Override protected boolean doEval(EvalContext ctx) { return target.eval(ctx); }

        @Override boolean isStateful() { return target.isStateful(); }

        @Override public String describe() { return "[" + name + "]"; }
    }

    boolean isStateful() { return false; }
}
