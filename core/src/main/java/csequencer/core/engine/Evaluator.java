package csequencer.core.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.logic.CNode;
import csequencer.core.logic.ConditionCompiler;
import csequencer.core.logic.EvalContext;
import csequencer.core.logic.QueryExpr;
import csequencer.core.pieces.ParamSpec;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.QueryType;
import csequencer.core.platform.WorldQueryAdapter;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.ValueType;
import csequencer.core.value.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves values for the engine: core queries, variables, templates, and delegation of
 * platform queries to the adapter. Also compiles and evaluates conditions with budgets.
 */
final class Evaluator implements EvalContext.Resolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.:\\-]{1,64})}");
    private final Engine engine;

    Evaluator(Engine engine) { this.engine = engine; }

    // ── Conditions ─────────────────────────────────────────────────────────

    CNode compile(Task task, String cacheKey, JsonElement graph) {
        ConditionCompiler.Result r = task.compiled.get(cacheKey);
        if (r == null) {
            JsonObject g = graph != null && graph.isJsonObject() ? graph.getAsJsonObject() : null;
            r = ConditionCompiler.compile(g, task.project::condition, engine.settings.limits.maxConditionDepth, engine.settings.limits.maxNodesPerEvaluation);
            task.compiled.put(cacheKey, r);
            for (String e : r.errors()) task.log("error", "Condition " + cacheKey + ": " + e);
            for (String w : r.warnings()) task.logOnce("cw:" + cacheKey + w, "warn", "Condition " + cacheKey + ": " + w);
        }
        return r.root();
    }

    /** Evaluates a compiled condition. Budget overruns evaluate to false and are logged. */
    boolean test(CNode cond, Scope scope, String stateKey) {
        EvalContext ctx = new EvalContext(this, edgeStore(scope.task()), stateKey, scope.subject(), scope,
                engine.settings.limits.maxNodesPerEvaluation, engine.settings.limits.maxConditionDepth + 8, scope.task().random);
        try {
            return cond.eval(ctx);
        } catch (EvalContext.BudgetExceeded e) {
            scope.task().logOnce("budget:" + stateKey, "error", e.getMessage());
            scope.task().warn("condition budget exceeded");
            return false;
        } catch (RuntimeException e) {
            scope.task().log("error", "Condition evaluation failed: " + e);
            return false;
        } finally {
            int n = ctx.nodesEvaluated();
            scope.task().evalNodesTotal += n;
            scope.task().evalNodesThisTick += n;
            engine.evalNodesThisTick += n;
        }
    }

    private EvalContext.EdgeStore edgeStore(Task task) {
        return new EvalContext.EdgeStore() {
            @Override public Boolean get(String key) { return task.edgeStates.get(key); }
            @Override public void put(String key, boolean value) {
                if (task.edgeStates.size() < 100_000 || task.edgeStates.containsKey(key)) task.edgeStates.put(key, value);
            }
        };
    }

    // ── Values ─────────────────────────────────────────────────────────────

    Value value(JsonElement raw, Scope scope) { return resolve(QueryExpr.parse(raw), scope); }

    Value resolve(QueryExpr q, Scope scope) {
        EvalContext ctx = new EvalContext(this, edgeStore(scope.task()), "value", scope.subject(), scope,
                engine.settings.limits.maxNodesPerEvaluation, engine.settings.limits.maxConditionDepth + 8, scope.task().random);
        return resolve(q, ctx);
    }

    @Override
    public Value resolve(QueryExpr q, EvalContext ctx) {
        Scope base = (Scope) ctx.scope;
        Scope scope = ctx.subject == base.subject() ? base : base.withSubject(ctx.subject);
        Task task = scope.task();
        if (q.isLiteral()) return q.template ? Value.of(render(q.literal.asText(), scope)) : q.literal;
        JsonObject a = q.args;
        switch (q.id) {
            case "template": return Value.of(render(text(a, "text"), scope));
            case "var": return variable(text(a, "scope").isEmpty() ? "auto" : text(a, "scope"), render(text(a, "name"), scope), scope);
            case "math": return math(text(a, "op"), resolve(QueryExpr.parse(a.get("a")), ctx), resolve(QueryExpr.parse(a.get("b")), ctx));
            case "random.int": {
                int lo = (int) number(a, "min", 0), hi = (int) number(a, "max", 1);
                if (hi < lo) { int t = lo; lo = hi; hi = t; }
                return Value.of(lo + task.random.nextInt(Math.max(1, hi - lo + 1)));
            }
            case "random.chance": return Value.of(task.random.nextDouble() < number(a, "probability", 0.5));
            case "event.field": return scope.event() == null ? Value.NULL : scope.event().field(text(a, "field"));
            case "state.current": return task.vars.get("state:" + render(text(a, "machine"), scope));
            case "list.size": return Value.of(resolve(QueryExpr.parse(a.get("source")), ctx).asList().size());
            case "list.get": {
                List<Value> l = resolve(QueryExpr.parse(a.get("source")), ctx).asList();
                int i = (int) number(a, "index", 0);
                if (i < 0) i = l.size() + i;
                return i >= 0 && i < l.size() ? l.get(i) : Value.NULL;
            }
            case "list.filter": {
                List<Value> l = resolve(QueryExpr.parse(a.get("source")), ctx).asList();
                CNode f = compileIdentity(task, a);
                List<Value> out = new ArrayList<>();
                EntityRef savedS = ctx.subject;
                Value savedE = ctx.element;
                try {
                    for (Value v : l) {
                        ctx.element = v;
                        if (v.type() == ValueType.ENTITY) ctx.subject = v.asEntity();
                        if (f.eval(ctx)) out.add(v);
                    }
                } finally {
                    ctx.subject = savedS;
                    ctx.element = savedE;
                }
                return Value.list(out);
            }
            case "subject":
                if (!ctx.element.isNull() && ctx.element.type() != ValueType.ENTITY) return ctx.element;
                return ctx.subject == null ? Value.NULL : Value.of(ctx.subject);
            case "vec.offset": {
                Vec3d origin = resolve(QueryExpr.parse(a.get("base")), ctx).asVec();
                if (origin == null) return Value.NULL;
                return Value.of(new Vec3d(origin.x() + number(a, "dx", 0), origin.y() + number(a, "dy", 0), origin.z() + number(a, "dz", 0)));
            }
            case "seq.tick": return Value.of(task.tickCount);
            case "seq.branch_tick": return Value.of(scope.fiber() == null ? task.mainPosition() : scope.fiber().clock);
            case "seq.name": return Value.of(task.project.name);
            case "seq.initiator": return task.request.initiator == null ? Value.NULL : Value.of(task.request.initiator);
            case "seq.tracked": return task.tracked == null ? Value.NULL : Value.of(task.tracked);
            case "seq.phase": return Value.of(task.currentPhase());
            case "seq.participants": {
                List<Value> out = new ArrayList<>();
                for (UUID u : task.participants) {
                    EntityRef e = engine.platform.players().byUuid(u);
                    if (e != null) out.add(Value.of(e));
                }
                return Value.list(out);
            }
            default: return platformQuery(q, ctx, scope);
        }
    }

    private CNode compileIdentity(Task task, JsonObject args) {
        ConditionCompiler.Result r = task.compiledByIdentity.get(args);
        if (r == null) {
            JsonObject g = args.has("filter") && args.get("filter").isJsonObject() ? args.getAsJsonObject("filter") : null;
            r = ConditionCompiler.compile(g, task.project::condition, engine.settings.limits.maxConditionDepth, engine.settings.limits.maxNodesPerEvaluation);
            task.compiledByIdentity.put(args, r);
            for (String e : r.errors()) task.log("error", "List filter: " + e);
        }
        return r.root();
    }

    private Value platformQuery(QueryExpr q, EvalContext ctx, Scope scope) {
        QueryType type = PieceRegistry.query(q.id);
        Task task = scope.task();
        if (type == null) {
            task.logOnce("uq:" + q.id, "warn", "Unknown query '" + q.id + "'");
            return Value.NULL;
        }
        if (!engine.platform.info().supports(type.capability())) {
            task.logOnce("cap:" + q.id, "warn", "Query '" + q.id + "' is not supported on " + engine.platform.info().loader());
            return Value.NULL;
        }
        Map<String, Value> args = resolveArgs(type.params(), q.args, ctx, scope);
        EntityRef subject = ctx.subject;
        if (type.subjectBound()) {
            String who = args.getOrDefault("who", Value.NULL).asText();
            if (!who.isEmpty()) {
                EntityRef named = lookupEntity(who);
                subject = named;
                if (named == null) return defaultFor(type.result());
            }
            if (subject == null) return defaultFor(type.result());
        }
        String dim = scope.task().request.dimension;
        try {
            Value v = engine.platform.queries().query(q.id, args, new WorldQueryAdapter.QueryContext(subject, task.request.initiator, dim));
            return v == null ? Value.NULL : v;
        } catch (RuntimeException e) {
            task.logOnce("qe:" + q.id, "error", "Query '" + q.id + "' failed: " + e);
            return Value.NULL;
        }
    }

    private static Value defaultFor(ValueType t) {
        return switch (t) {
            case BOOL -> Value.FALSE;
            case LIST -> Value.EMPTY_LIST;
            default -> Value.NULL;
        };
    }

    EntityRef lookupEntity(String who) {
        EntityRef e = engine.platform.players().byName(who);
        if (e != null) return e;
        try {
            return engine.platform.players().byUuid(UUID.fromString(who));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Resolves raw parameter JSON against specs: nested queries evaluated, text rendered. */
    Map<String, Value> resolveArgs(List<ParamSpec> specs, JsonObject raw, EvalContext ctx, Scope scope) {
        Map<String, Value> out = new HashMap<>();
        for (ParamSpec spec : specs) {
            JsonElement e = raw != null && raw.has(spec.key) ? raw.get(spec.key) : spec.def;
            switch (spec.type) {
                case CONDITION, BRANCHES, CASES, OUTCOMES, TARGET, FUNCTIONS -> {}
                case QUERY -> out.put(spec.key, resolve(QueryExpr.parse(e), ctx));
                case JSON -> out.put(spec.key, e == null || e.isJsonNull() ? Value.NULL : Value.of(e.toString()));
                case BOOL -> out.put(spec.key, e != null && e.isJsonPrimitive() ? Value.of(e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : Value.of(render(e.getAsString(), scope)).asBool()) : Value.FALSE);
                case INT, NUMBER, DURATION -> out.put(spec.key, numberValue(e, scope, spec));
                case VEC3 -> {
                    Value v = Value.fromJson(e);
                    if (v.type() == ValueType.TEXT) {
                        String s = render(v.asText(), scope);
                        Vec3d vec = s.indexOf('~') >= 0 ? relative(s, ctx, scope) : Vec3d.parse(s);
                        v = vec == null ? (s.isBlank() ? Value.NULL : Value.of(s)) : Value.of(vec);
                    } else if (e != null && e.isJsonObject() && e.getAsJsonObject().has("q")) {
                        v = resolve(QueryExpr.parse(e), ctx);
                    }
                    out.put(spec.key, v);
                }
                default -> {
                    Value v = Value.fromJson(e);
                    if (v.type() == ValueType.TEXT) v = Value.of(render(v.asText(), scope));
                    else if (e != null && e.isJsonObject() && e.getAsJsonObject().has("q")) v = resolve(QueryExpr.parse(e), ctx);
                    out.put(spec.key, v);
                }
            }
        }
        return out;
    }

    /** Parses "x y z", allowing ~ relative coordinates (relative to the subject / tracked player). Null when invalid. */
    public Vec3d vecOf(String s, Scope scope) {
        if (s == null || s.isBlank()) return null;
        if (s.indexOf('~') < 0) return Vec3d.parse(s);
        EvalContext ctx = new EvalContext(this, edgeStore(scope.task()), "args", scope.subject(), scope,
                engine.settings.limits.maxNodesPerEvaluation, engine.settings.limits.maxConditionDepth + 8, scope.task().random);
        return relative(s, ctx, scope);
    }

    Map<String, Value> resolveArgs(List<ParamSpec> specs, JsonObject raw, Scope scope) {
        EvalContext ctx = new EvalContext(this, edgeStore(scope.task()), "args", scope.subject(), scope,
                engine.settings.limits.maxNodesPerEvaluation, engine.settings.limits.maxConditionDepth + 8, scope.task().random);
        return resolveArgs(specs, raw, ctx, scope);
    }

    private Value numberValue(JsonElement e, Scope scope, ParamSpec spec) {
        double d;
        if (e == null || e.isJsonNull()) d = 0;
        else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) d = e.getAsDouble();
        else if (e.isJsonObject() && e.getAsJsonObject().has("q")) d = resolve(QueryExpr.parse(e), scope).asNumber();
        else {
            String s = render(e.getAsString(), scope).trim();
            d = spec.type == csequencer.core.pieces.ParamType.DURATION ? Durations.parseTicks(s) : Value.of(s).asNumber();
        }
        if (Double.isNaN(d)) d = 0;
        d = Math.max(spec.min, Math.min(spec.max, d));
        return Value.of(d);
    }

    /**
     * Minecraft-style relative coordinates: "~5 ~ ~-3" is relative to the current subject (the tracked
     * player by default; an entity in loops/events). Plain numbers stay absolute.
     */
    private Vec3d relative(String s, EvalContext ctx, Scope scope) {
        String[] parts = s.trim().split("[,\\s]+");
        if (parts.length != 3) return null;
        EntityRef who = ctx.subject != null ? ctx.subject : scope.task().tracked;
        Vec3d anchor = null;
        if (who != null) {
            JsonObject q = new JsonObject();
            q.addProperty("q", who.player() ? "player.pos" : "entity.pos");
            JsonObject args = new JsonObject();
            if (!who.player()) args.add("entity", Value.of(who).toJson());
            q.add("args", args);
            EntityRef saved = ctx.subject;
            ctx.subject = who;
            try {
                anchor = resolve(QueryExpr.parse(q), ctx).asVec();
            } finally {
                ctx.subject = saved;
            }
        }
        double[] out = new double[3];
        double[] base = anchor == null ? new double[]{0, 0, 0} : new double[]{anchor.x(), anchor.y(), anchor.z()};
        for (int i = 0; i < 3; i++) {
            String p = parts[i];
            try {
                if (p.startsWith("~")) {
                    if (anchor == null) return null;
                    out[i] = base[i] + (p.length() > 1 ? Double.parseDouble(p.substring(1)) : 0);
                } else out[i] = Double.parseDouble(p);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Vec3d(out[0], out[1], out[2]);
    }

    // ── Variables ──────────────────────────────────────────────────────────

    Value variable(String scopeName, String name, Scope scope) {
        if (name == null || name.isEmpty()) return Value.NULL;
        Task task = scope.task();
        return switch (scopeName.toLowerCase(Locale.ROOT)) {
            case "local" -> scope.locals().get(name);
            case "sequence" -> task.vars.get(name);
            case "player" -> scope.subject() == null ? Value.NULL : engine.playerVars(scope.subject().uuid()).get(name);
            case "global" -> engine.globalVars.get(name);
            default -> {
                if (scope.locals().has(name)) yield scope.locals().get(name);
                if (task.vars.has(name)) yield task.vars.get(name);
                if (scope.subject() != null && engine.hasPlayerVars(scope.subject().uuid()) && engine.playerVars(scope.subject().uuid()).has(name))
                    yield engine.playerVars(scope.subject().uuid()).get(name);
                yield engine.globalVars.get(name);
            }
        };
    }

    Variables variableStore(String scopeName, Scope scope) {
        return switch (scopeName.toLowerCase(Locale.ROOT)) {
            case "local" -> scope.locals();
            case "player" -> scope.subject() == null ? null : engine.playerVars(scope.subject().uuid());
            case "global" -> engine.globalVars;
            default -> scope.task().vars;
        };
    }

    // ── Templates ──────────────────────────────────────────────────────────

    String render(String text, Scope scope) {
        if (text == null || text.indexOf('{') < 0) return text == null ? "" : text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String rep = placeholder(key, scope);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? m.group(0) : rep));
            if (sb.length() > 32000) break;
        }
        m.appendTail(sb);
        return sb.length() > 32000 ? sb.substring(0, 32000) : sb.toString();
    }

    private String placeholder(String key, Scope scope) {
        Task task = scope.task();
        EntityRef subj = scope.subject() != null ? scope.subject() : task.request.initiator;
        switch (key) {
            case "player": case "subject": return subj == null ? "" : subj.name();
            case "player.uuid": case "subject.uuid": return subj == null ? "" : subj.uuid().toString();
            case "initiator": return task.request.initiator == null ? "server" : task.request.initiator.name();
            case "tracked": return task.tracked == null ? "" : task.tracked.name();
            case "initiator.uuid": return task.request.initiator == null ? "" : task.request.initiator.uuid().toString();
            case "tick": return Integer.toString(task.tickCount);
            case "branch.tick": return Integer.toString(scope.fiber() == null ? task.mainPosition() : scope.fiber().clock);
            case "seq": case "sequence": return task.project.name;
            case "task": return task.id;
            case "phase": return task.currentPhase();
            case "participants": return Integer.toString(task.participants.size());
            default: break;
        }
        if (key.startsWith("event.")) return scope.event() == null ? "" : scope.event().field(key.substring(6)).asText();
        int colon = key.indexOf(':');
        if (colon > 0) {
            String sc = key.substring(0, colon), name = key.substring(colon + 1);
            String s = switch (sc) {
                case "var" -> "auto";
                case "local", "sequence", "player", "global" -> sc;
                case "seq" -> "sequence";
                default -> null;
            };
            if (s != null) return variable(s, name, scope).asText();
            return null;
        }
        Value v = variable("auto", key, scope);
        return v.isNull() && !scope.locals().has(key) && !task.vars.has(key) ? null : v.asText();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    static Value math(String op, Value a, Value b) {
        double x = a.asNumber(), y = b.asNumber();
        double r = switch (op == null ? "add" : op.toLowerCase(Locale.ROOT)) {
            case "sub" -> x - y;
            case "mul" -> x * y;
            case "div" -> y == 0 ? 0 : x / y;
            case "mod" -> y == 0 ? 0 : x % y;
            case "min" -> Math.min(x, y);
            case "max" -> Math.max(x, y);
            case "pow" -> Math.pow(x, Math.max(-64, Math.min(64, y)));
            case "abs" -> Math.abs(x);
            case "floor" -> Math.floor(x);
            case "ceil" -> Math.ceil(x);
            case "round" -> Math.round(x);
            case "neg" -> -x;
            default -> {
                if (!a.isNumeric() || !b.isNumeric()) yield Double.NaN;
                yield x + y;
            }
        };
        if (Double.isNaN(r)) return Value.of(a.asText() + b.asText());
        return Value.of(Double.isInfinite(r) ? 0 : r);
    }

    private static String text(JsonObject a, String key) {
        if (a == null || !a.has(key) || a.get(key).isJsonNull()) return "";
        JsonElement e = a.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static double number(JsonObject a, String key, double def) {
        try {
            return a != null && a.has(key) ? a.get(key).getAsDouble() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
