package csequencer.core.pieces;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;

/** Typed parameter declaration for pieces, queries and condition nodes. */
public final class ParamSpec {
    public final String key;
    public final String label;
    public final ParamType type;
    public final JsonElement def;
    public final double min;
    public final double max;
    public final List<String> options;
    public final boolean required;
    public final String help;

    private ParamSpec(String key, String label, ParamType type, JsonElement def, double min, double max,
                      List<String> options, boolean required, String help) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.def = def;
        this.min = min;
        this.max = max;
        this.options = options;
        this.required = required;
        this.help = help;
    }

    public static Builder of(String key, String label, ParamType type) { return new Builder(key, label, type); }

    public static final class Builder {
        private final String key, label;
        private final ParamType type;
        private JsonElement def = JsonNull.INSTANCE;
        private double min = -Double.MAX_VALUE, max = Double.MAX_VALUE;
        private List<String> options = List.of();
        private boolean required;
        private String help = "";

        Builder(String key, String label, ParamType type) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.def = switch (type) {
                case INT, NUMBER, DURATION -> new JsonPrimitive(0);
                case BOOL -> new JsonPrimitive(false);
                case BRANCHES, CASES, OUTCOMES, FUNCTIONS -> new JsonArray();
                case JSON -> new JsonObject();
                case TARGET -> { JsonObject o = new JsonObject(); o.addProperty("mode", "initiator"); yield o; }
                default -> new JsonPrimitive("");
            };
        }

        public Builder def(String v) { def = new JsonPrimitive(v); return this; }
        public Builder def(double v) { def = new JsonPrimitive(v); return this; }
        public Builder def(boolean v) { def = new JsonPrimitive(v); return this; }
        public Builder def(JsonElement v) { def = v; return this; }
        public Builder range(double lo, double hi) { min = lo; max = hi; return this; }
        public Builder options(String... o) { options = List.of(o); if (type == ParamType.ENUM && def.isJsonPrimitive() && def.getAsString().isEmpty() && o.length > 0) def = new JsonPrimitive(o[0]); return this; }
        public Builder required() { required = true; return this; }
        public Builder help(String h) { help = h; return this; }
        public ParamSpec build() { return new ParamSpec(key, label, type, def, min, max, options, required, help); }
    }
}
