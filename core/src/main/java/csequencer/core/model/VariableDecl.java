package csequencer.core.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.value.ValueType;

import java.util.Set;

/** Typed declaration of a variable, function input or function output. */
public final class VariableDecl {
    public String name;
    public ValueType type = ValueType.TEXT;
    public JsonElement defaultValue = JsonNull.INSTANCE;
    public String description = "";
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("name", "type", "default", "description");

    public VariableDecl() {}

    public VariableDecl(String name, ValueType type) {
        this.name = name;
        this.type = type;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type.id());
        o.add("default", defaultValue == null ? JsonNull.INSTANCE : defaultValue.deepCopy());
        o.addProperty("description", description);
        J.mergeExtra(o, extra);
        return o;
    }

    public static VariableDecl fromJson(JsonObject o) {
        VariableDecl v = new VariableDecl();
        v.name = J.str(o, "name", "var");
        v.type = ValueType.byId(J.str(o, "type", "text"));
        v.defaultValue = o.has("default") ? o.get("default").deepCopy() : JsonNull.INSTANCE;
        v.description = J.str(o, "description", "");
        v.extra = J.extra(o, KNOWN);
        return v;
    }
}
