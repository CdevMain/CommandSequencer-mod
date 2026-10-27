package csequencer.core.engine;

import com.google.gson.JsonObject;
import csequencer.core.value.Value;

import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded variable store (one per scope instance). */
public final class Variables {
    public static final int MAX_VARS = 4096;
    public static final int MAX_NAME = 64;

    private final Map<String, Value> values = new LinkedHashMap<>();

    public Value get(String name) { return values.getOrDefault(name, Value.NULL); }

    public boolean has(String name) { return values.containsKey(name); }

    /** Returns false if the store is full or the name invalid. */
    public boolean set(String name, Value v) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) return false;
        if (v == null || v.isNull()) {
            values.remove(name);
            return true;
        }
        if (!values.containsKey(name) && values.size() >= MAX_VARS) return false;
        values.put(name, v);
        return true;
    }

    public void remove(String name) { values.remove(name); }

    public void clear() { values.clear(); }

    public Map<String, Value> asMap() { return java.util.Collections.unmodifiableMap(values); }

    public int size() { return values.size(); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        for (var e : values.entrySet()) o.add(e.getKey(), e.getValue().toJson());
        return o;
    }

    public static Variables fromJson(JsonObject o) {
        Variables v = new Variables();
        if (o != null) for (var e : o.entrySet()) v.set(e.getKey(), Value.fromJson(e.getValue()));
        return v;
    }
}
