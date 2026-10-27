package csequencer.core.value;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable typed runtime value: number, text, boolean, coordinate, entity, list or null.
 * Conversions are lenient (text "3" is the number 3) so user-built conditions behave intuitively.
 */
public final class Value {
    public static final Value NULL = new Value(ValueType.NULL, null);
    public static final Value TRUE = new Value(ValueType.BOOL, Boolean.TRUE);
    public static final Value FALSE = new Value(ValueType.BOOL, Boolean.FALSE);
    public static final Value ZERO = new Value(ValueType.NUMBER, 0.0);
    public static final Value EMPTY_LIST = new Value(ValueType.LIST, List.of());

    private final ValueType type;
    private final Object raw;

    private Value(ValueType type, Object raw) {
        this.type = type;
        this.raw = raw;
    }

    public static Value of(double d) { return new Value(ValueType.NUMBER, d); }
    public static Value of(boolean b) { return b ? TRUE : FALSE; }
    public static Value of(String s) { return s == null ? NULL : new Value(ValueType.TEXT, s); }
    public static Value of(Vec3d v) { return v == null ? NULL : new Value(ValueType.VEC3, v); }
    public static Value of(EntityRef e) { return e == null ? NULL : new Value(ValueType.ENTITY, e); }

    public static Value list(List<Value> values) {
        if (values == null || values.isEmpty()) return EMPTY_LIST;
        return new Value(ValueType.LIST, Collections.unmodifiableList(new ArrayList<>(values)));
    }

    public ValueType type() { return type; }
    public boolean isNull() { return type == ValueType.NULL; }

    // ── Conversions ─────────────────────────────────────────────────────────

    public boolean isNumeric() {
        if (type == ValueType.NUMBER || type == ValueType.BOOL) return true;
        if (type == ValueType.TEXT) return parseDouble((String) raw) != null;
        return false;
    }

    public double asNumber() {
        return switch (type) {
            case NUMBER -> (Double) raw;
            case BOOL -> ((Boolean) raw) ? 1 : 0;
            case TEXT -> { Double d = parseDouble((String) raw); yield d == null ? 0 : d; }
            case LIST -> ((List<?>) raw).size();
            default -> 0;
        };
    }

    public boolean asBool() {
        return switch (type) {
            case BOOL -> (Boolean) raw;
            case NUMBER -> (Double) raw != 0;
            case TEXT -> { String s = ((String) raw).trim(); yield s.equalsIgnoreCase("true") || (parseDouble(s) != null && parseDouble(s) != 0); }
            case NULL -> false;
            case LIST -> !((List<?>) raw).isEmpty();
            default -> true;
        };
    }

    public String asText() {
        return switch (type) {
            case NULL -> "";
            case NUMBER -> fmtNumber((Double) raw);
            case LIST -> {
                StringBuilder sb = new StringBuilder();
                for (Value v : asList()) { if (sb.length() > 0) sb.append(", "); sb.append(v.asText()); }
                yield sb.toString();
            }
            default -> raw.toString();
        };
    }

    public Vec3d asVec() {
        return switch (type) {
            case VEC3 -> (Vec3d) raw;
            case TEXT -> Vec3d.parse((String) raw);
            default -> null;
        };
    }

    public EntityRef asEntity() { return type == ValueType.ENTITY ? (EntityRef) raw : null; }

    @SuppressWarnings("unchecked")
    public List<Value> asList() {
        if (type == ValueType.LIST) return (List<Value>) raw;
        if (type == ValueType.NULL) return List.of();
        return List.of(this);
    }

    /** Value "exists" when it is non-null and not an empty text/list. */
    public boolean exists() {
        return switch (type) {
            case NULL -> false;
            case TEXT -> !((String) raw).isEmpty();
            case LIST -> !((List<?>) raw).isEmpty();
            default -> true;
        };
    }

    // ── Equality used by comparisons ────────────────────────────────────────

    /** Loose equality: numbers compare numerically, entities by UUID, text case-insensitively. */
    public boolean looseEquals(Value o) {
        if (o == null) return isNull();
        if (type == ValueType.NULL || o.type == ValueType.NULL) return type == o.type;
        if (type == ValueType.ENTITY || o.type == ValueType.ENTITY) {
            EntityRef a = asEntity(), b = o.asEntity();
            if (a != null && b != null) return a.uuid().equals(b.uuid());
            EntityRef e = a != null ? a : b;
            Value other = a != null ? o : this;
            String t = other.asText();
            return t.equalsIgnoreCase(e.name()) || t.equalsIgnoreCase(e.uuid().toString());
        }
        if (type == ValueType.VEC3 || o.type == ValueType.VEC3) {
            Vec3d a = asVec(), b = o.asVec();
            return a != null && a.equals(b);
        }
        if (type == ValueType.BOOL || o.type == ValueType.BOOL) return asBool() == o.asBool();
        if (isNumeric() && o.isNumeric() && (type == ValueType.NUMBER || o.type == ValueType.NUMBER || bothNumericText(o))) {
            return Math.abs(asNumber() - o.asNumber()) < 1e-9;
        }
        if (type == ValueType.LIST && o.type == ValueType.LIST) {
            List<Value> a = asList(), b = o.asList();
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!a.get(i).looseEquals(b.get(i))) return false;
            return true;
        }
        return asText().equalsIgnoreCase(o.asText());
    }

    private boolean bothNumericText(Value o) { return type == ValueType.TEXT && o.type == ValueType.TEXT; }

    /** Contains: list membership or case-insensitive substring. */
    public boolean contains(Value needle) {
        if (type == ValueType.LIST) {
            for (Value v : asList()) if (v.looseEquals(needle)) return true;
            return false;
        }
        return asText().toLowerCase(java.util.Locale.ROOT).contains(needle.asText().toLowerCase(java.util.Locale.ROOT));
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    public JsonElement toJson() {
        return switch (type) {
            case NULL -> JsonNull.INSTANCE;
            case NUMBER -> new JsonPrimitive((Double) raw);
            case BOOL -> new JsonPrimitive((Boolean) raw);
            case TEXT -> new JsonPrimitive((String) raw);
            case VEC3 -> {
                Vec3d v = (Vec3d) raw;
                JsonObject o = new JsonObject();
                o.addProperty("x", v.x()); o.addProperty("y", v.y()); o.addProperty("z", v.z());
                yield o;
            }
            case ENTITY -> {
                EntityRef e = (EntityRef) raw;
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.uuid().toString());
                o.addProperty("name", e.name());
                o.addProperty("entityType", e.type());
                o.addProperty("player", e.player());
                yield o;
            }
            case LIST -> {
                JsonArray a = new JsonArray();
                for (Value v : asList()) a.add(v.toJson());
                yield a;
            }
        };
    }

    public static Value fromJson(JsonElement e) {
        if (e == null || e.isJsonNull()) return NULL;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return of(p.getAsBoolean());
            if (p.isNumber()) return of(p.getAsDouble());
            return of(p.getAsString());
        }
        if (e.isJsonArray()) {
            List<Value> l = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) l.add(fromJson(x));
            return list(l);
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("uuid")) {
            try {
                return of(new EntityRef(UUID.fromString(o.get("uuid").getAsString()),
                        o.has("name") ? o.get("name").getAsString() : "",
                        o.has("entityType") ? o.get("entityType").getAsString() : "",
                        o.has("player") && o.get("player").getAsBoolean()));
            } catch (Exception ignored) {
                return NULL;
            }
        }
        if (o.has("x") && o.has("y") && o.has("z")) {
            return of(new Vec3d(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()));
        }
        return of(o.toString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static Double parseDouble(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 40) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String fmtNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return Double.toString(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        String s = String.format(java.util.Locale.ROOT, "%.4f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Value v)) return false;
        return type == v.type && Objects.equals(raw, v.raw);
    }

    @Override public int hashCode() { return Objects.hash(type, raw); }

    @Override public String toString() { return type.id() + ":" + asText(); }
}
