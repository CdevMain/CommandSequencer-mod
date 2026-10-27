package csequencer.core.logic;

import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;

import java.util.Map;

/**
 * State of one condition evaluation. Not thread safe; created per evaluation.
 * {@code stateKey} namespaces transition state (e.g. one watcher, or one wait piece in one branch).
 */
public final class EvalContext {
    /** Supplies values for queries; implemented by the engine. */
    public interface Resolver {
        Value resolve(QueryExpr q, EvalContext ctx);
    }

    /** Persistent storage for transition detectors (edge nodes). */
    public interface EdgeStore {
        Boolean get(String key);
        void put(String key, boolean value);
    }

    public final Resolver resolver;
    public final EdgeStore edges;
    public final String stateKey;
    public final int maxNodes;
    public final int maxDepth;
    public final java.util.Random random;
    /** Engine-specific context object (the engine passes its scope here). */
    public final Object scope;
    public EntityRef subject;
    /** Current list element when evaluating inside a quantifier / list filter. */
    public Value element = Value.NULL;
    int nodes;
    int depth;
    /** Optional trace: node id → last result, for condition inspection in the debugger. */
    public Map<String, Boolean> trace;

    public EvalContext(Resolver resolver, EdgeStore edges, String stateKey, EntityRef subject, Object scope,
                       int maxNodes, int maxDepth, java.util.Random random) {
        this.resolver = resolver;
        this.edges = edges;
        this.stateKey = stateKey;
        this.subject = subject;
        this.scope = scope;
        this.maxNodes = maxNodes;
        this.maxDepth = maxDepth;
        this.random = random;
    }

    public int nodesEvaluated() { return nodes; }

    public String subjectKey() {
        if (subject != null) return subject.key();
        if (!element.isNull()) return "v:" + element.asText();
        return "global";
    }

    /** Thrown when an evaluation exceeds its node or depth budget. */
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String msg) { super(msg, null, false, false); }
    }
}
