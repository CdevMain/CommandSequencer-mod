package csequencer.core.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.value.Value;

/**
 * Parsed value expression. JSON forms:
 * <ul>
 *   <li>primitive → literal (text containing "{" is rendered as a template)</li>
 *   <li>{"q": "player.health", "args": {...}} → query</li>
 *   <li>{"value": ...} → literal</li>
 * </ul>
 */
public final class QueryExpr {
    public static final QueryExpr NULL = new QueryExpr("literal", Value.NULL, false, new JsonObject());

    public final String id;
    public final Value literal;
    public final boolean template;
    public final JsonObject args;

    private QueryExpr(String id, Value literal, boolean template, JsonObject args) {
        this.id = id;
        this.literal = literal;
        this.template = template;
        this.args = args;
    }

    public boolean isLiteral() { return "literal".equals(id); }

    public static QueryExpr literal(Value v) {
        boolean tpl = v.type() == csequencer.core.value.ValueType.TEXT && v.asText().indexOf('{') >= 0;
        return new QueryExpr("literal", v, tpl, new JsonObject());
    }

    public static QueryExpr parse(JsonElement e) {
        if (e == null || e.isJsonNull()) return NULL;
        if (e.isJsonPrimitive() || e.isJsonArray()) return literal(Value.fromJson(e));
        JsonObject o = e.getAsJsonObject();
        if (o.has("q")) {
            String id = J.str(o, "q", "literal");
            JsonObject args = J.obj(o, "args");
            if (args == null) args = new JsonObject();
            if ("literal".equals(id)) {
                return literal(Value.fromJson(args.has("value") ? args.get("value") : o.get("value")));
            }
            return new QueryExpr(id, Value.NULL, false, args);
        }
        if (o.has("value")) return literal(Value.fromJson(o.get("value")));
        return literal(Value.fromJson(o));
    }

    /** Human readable form for the editor and condition inspection. */
    public String describe() {
        if (isLiteral()) return literal.isNull() ? "∅" : '"' + literal.asText() + '"';
        StringBuilder sb = new StringBuilder(id);
        if (args.size() > 0) {
            sb.append('(');
            boolean first = true;
            for (var en : args.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                JsonElement v = en.getValue();
                sb.append(en.getKey()).append('=').append(v.isJsonPrimitive() ? v.getAsString() : (v.isJsonObject() && v.getAsJsonObject().has("q") ? parse(v).describe() : "…"));
            }
            sb.append(')');
        }
        return sb.toString();
    }
}
