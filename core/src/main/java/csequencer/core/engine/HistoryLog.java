package csequencer.core.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Bounded execution history (most recent first). Persistence is delegated via {@link #onChange}. */
public final class HistoryLog {
    private final Deque<JsonObject> records = new ArrayDeque<>();
    private int max;
    public Consumer<HistoryLog> onChange = h -> {};

    public HistoryLog(int max) { this.max = Math.max(0, max); }

    public void setMax(int max) {
        this.max = Math.max(0, max);
        trim();
    }

    public synchronized void add(JsonObject record) {
        if (max == 0) return;
        records.addFirst(record);
        trim();
        onChange.accept(this);
    }

    private void trim() { while (records.size() > max) records.removeLast(); }

    public synchronized List<JsonObject> list() { return new ArrayList<>(records); }

    public synchronized JsonObject find(String taskId) {
        for (JsonObject o : records) if (o.has("taskId") && o.get("taskId").getAsString().equals(taskId)) return o;
        return null;
    }

    public synchronized void clear() {
        records.clear();
        onChange.accept(this);
    }

    public synchronized JsonArray toJson() {
        JsonArray a = new JsonArray();
        for (JsonObject o : records) a.add(o);
        return a;
    }

    public synchronized void load(JsonArray a) {
        records.clear();
        if (a != null) for (JsonElement e : a) if (e.isJsonObject() && records.size() < max) records.addLast(e.getAsJsonObject());
    }
}
