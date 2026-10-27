package csequencer.core.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Small, null-safe JSON helpers used by every codec in the core. */
public final class J {
    private J() {}

    public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    public static JsonObject parseObject(String s) {
        JsonElement e = JsonParser.parseString(s);
        if (!e.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object");
        return e.getAsJsonObject();
    }

    public static String str(JsonObject o, String k, String def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static int i(JsonObject o, String k, int def) {
        try {
            if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
            JsonElement e = o.get(k);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                double d = e.getAsDouble();
                if (Double.isNaN(d)) return def;
                return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(d)));
            }
            return (int) Math.round(Double.parseDouble(e.getAsString().trim()));
        } catch (Exception ex) {
            return def;
        }
    }

    public static long l(JsonObject o, String k, long def) {
        try {
            if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
            return o.get(k).getAsLong();
        } catch (Exception ex) {
            return def;
        }
    }

    public static double d(JsonObject o, String k, double def) {
        try {
            if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
            JsonElement e = o.get(k);
            double v = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.parseDouble(e.getAsString().trim());
            return Double.isFinite(v) ? v : def;
        } catch (Exception ex) {
            return def;
        }
    }

    public static boolean b(JsonObject o, String k, boolean def) {
        try {
            if (o == null || !o.has(k) || o.get(k).isJsonNull()) return def;
            JsonElement e = o.get(k);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            String s = e.getAsString().trim();
            return s.equalsIgnoreCase("true") || s.equals("1");
        } catch (Exception ex) {
            return def;
        }
    }

    public static JsonObject obj(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonObject()) return null;
        return o.getAsJsonObject(k);
    }

    public static JsonArray arr(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return new JsonArray();
        return o.getAsJsonArray(k);
    }

    public static <E extends Enum<E>> E en(JsonObject o, String k, Class<E> type, E def) {
        String s = str(o, k, null);
        if (s == null) return def;
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    public static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, k)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    public static JsonArray toArray(Collection<String> values) {
        JsonArray a = new JsonArray();
        for (String s : values) a.add(new JsonPrimitive(s));
        return a;
    }

    public static <T> List<T> list(JsonObject o, String k, Function<JsonObject, T> reader) {
        List<T> out = new ArrayList<>();
        for (JsonElement e : arr(o, k)) if (e.isJsonObject()) {
            T t = reader.apply(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    public static <T> JsonArray write(Collection<T> items, Function<T, JsonObject> writer) {
        JsonArray a = new JsonArray();
        for (T t : items) a.add(writer.apply(t));
        return a;
    }

    /** Collects keys of {@code o} not in {@code known}, so unknown data survives a load/save cycle. */
    public static JsonObject extra(JsonObject o, Set<String> known) {
        JsonObject ex = new JsonObject();
        if (o == null) return ex;
        for (var e : o.entrySet()) if (!known.contains(e.getKey())) ex.add(e.getKey(), e.getValue().deepCopy());
        return ex;
    }

    /** Writes preserved unknown fields back without overwriting known ones. */
    public static void mergeExtra(JsonObject target, JsonObject extra) {
        if (extra == null) return;
        for (var e : extra.entrySet()) if (!target.has(e.getKey())) target.add(e.getKey(), e.getValue().deepCopy());
    }

    public static JsonObject copy(JsonObject o) { return o == null ? new JsonObject() : o.deepCopy(); }

    public static com.google.gson.JsonArray toIntArray(int[] v) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        if (v != null) for (int x : v) a.add(x);
        return a;
    }

    /** Reads an int array of exactly {@code len} numbers, or null. */
    public static int[] intArray(JsonObject o, String key, int len) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        com.google.gson.JsonArray a = o.getAsJsonArray(key);
        if (a.size() != len) return null;
        int[] out = new int[len];
        try {
            for (int i = 0; i < len; i++) out[i] = (int) Math.max(-30_000_000, Math.min(30_000_000, a.get(i).getAsDouble()));
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }
}
