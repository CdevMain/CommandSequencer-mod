package csequencer.core.engine;

import com.google.gson.JsonObject;
import csequencer.core.platform.ClientChannel;
import csequencer.core.value.EntityRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Screen elements shown on clients by sequences: popups, overlays (banners, bars, timers),
 * waypoint markers and screen effects. Persistent elements (overlays, markers, letterbox/fade)
 * are remembered so late joiners receive them, can be refreshed live (re-evaluating text and
 * values every few ticks), and are removed automatically when their task ends.
 *
 * <p>Message format on {@link ClientChannel.Topic#HUD}: {@code {op: show|update|hide|clear, id, kind, ...}}.
 * Boss bars are server-side (vanilla) and only use the refresh mechanism here.
 */
public final class HudService {
    public static final int REFRESH_TICKS = 5;

    public static final class Element {
        public final String id;
        public final String taskId;
        public final String kind;
        public final boolean allPlayers;
        public final Set<UUID> targets = new LinkedHashSet<>();
        JsonObject payload;
        long expiresAtMs;
        /** Re-builds the payload (live elements); null = static. Returning null removes the element. */
        Supplier<JsonObject> refresh;
        /** Called when the element is removed (e.g. to hide a boss bar on the server). */
        Runnable onRemove;
        /** Client-side elements are sent to players; server-side ones (boss bars) are not. */
        final boolean clientSide;

        Element(String id, String taskId, String kind, boolean allPlayers, boolean clientSide) {
            this.id = id;
            this.taskId = taskId;
            this.kind = kind;
            this.allPlayers = allPlayers;
            this.clientSide = clientSide;
        }

        public JsonObject payload() { return payload; }
    }

    private final Engine engine;
    private final Map<String, Element> elements = new LinkedHashMap<>();

    HudService(Engine engine) { this.engine = engine; }

    public List<Element> elements() { return List.copyOf(elements.values()); }

    public List<Element> elementsOf(String taskId) {
        List<Element> out = new ArrayList<>();
        for (Element e : elements.values()) if (e.taskId.equals(taskId)) out.add(e);
        return out;
    }

    static String elementId(Task t, String kind, String key) { return t.id + ":" + kind + ":" + (key == null || key.isEmpty() ? "default" : key); }

    /** Sends a one-off element (popup, flash, shake) that is not remembered. */
    void flash(String kind, JsonObject payload, List<EntityRef> targets, boolean all) {
        JsonObject o = payload.deepCopy();
        o.addProperty("op", "show");
        o.addProperty("kind", kind);
        o.addProperty("id", kind + ":" + engine.serverTick + ":" + System.identityHashCode(payload));
        if (all) for (EntityRef e : engine.platform.players().online()) send(e.uuid(), o);
        else for (EntityRef e : targets) send(e.uuid(), o);
    }

    /**
     * Shows (or replaces) a persistent element. {@code durationTicks <= 0} keeps it until hidden or
     * until the task ends.
     */
    Element show(Task t, String kind, String key, JsonObject payload, List<EntityRef> targets, boolean all, int durationTicks,
                 Supplier<JsonObject> refresh, boolean clientSide) {
        String id = elementId(t, kind, key);
        Element old = elements.remove(id);
        if (old != null && old.onRemove != null && !clientSide) old.onRemove = null; // replaced in place
        Element e = new Element(id, t.id, kind, all, clientSide);
        if (!all) for (EntityRef r : targets) e.targets.add(r.uuid());
        e.payload = payload;
        e.expiresAtMs = durationTicks > 0 ? engine.platform.millis() + durationTicks * 50L : -1;
        e.refresh = refresh;
        elements.put(id, e);
        if (clientSide) broadcast(e, "show");
        return e;
    }

    /** Hides elements of a task. {@code key} empty = all keys; {@code kind} "any" = all kinds. */
    int hide(Task t, String kind, String key) {
        int n = 0;
        for (Element e : new ArrayList<>(elements.values())) {
            if (!e.taskId.equals(t.id)) continue;
            if (!"any".equals(kind) && !e.kind.equals(kind)) continue;
            if (key != null && !key.isEmpty() && !e.id.equals(t.id + ":" + e.kind + ":" + key)) continue;
            remove(e);
            n++;
        }
        return n;
    }

    private void remove(Element e) {
        if (elements.remove(e.id) == null) return;
        if (e.clientSide) broadcast(e, "hide");
        if (e.onRemove != null) {
            try {
                e.onRemove.run();
            } catch (RuntimeException ignored) {
                // cleanup must never throw
            }
        }
    }

    /** Removes everything a task put on screen (cleanup on cancel / completion / failure). */
    void clearTask(Task t) { for (Element e : elementsOf(t.id)) remove(e); }

    void tick(long serverTick) {
        long now = engine.platform.millis();
        for (Element e : new ArrayList<>(elements.values())) {
            if (e.expiresAtMs >= 0 && now >= e.expiresAtMs) {
                remove(e);
                continue;
            }
            if (e.refresh != null && serverTick % REFRESH_TICKS == 0) {
                JsonObject next;
                try {
                    next = e.refresh.get();
                } catch (RuntimeException ex) {
                    next = e.payload;
                }
                if (next == null) {
                    remove(e);
                    continue;
                }
                if (!next.equals(e.payload)) {
                    e.payload = next;
                    if (e.clientSide) broadcast(e, "update");
                }
            }
        }
    }

    /** Re-sends persistent elements to a player who just joined. */
    void onPlayerJoin(UUID player) {
        for (Element e : elements.values()) if (e.clientSide && (e.allPlayers || e.targets.contains(player))) send(player, message(e, "show"));
    }

    private JsonObject message(Element e, String op) {
        JsonObject o = e.payload == null ? new JsonObject() : e.payload.deepCopy();
        o.addProperty("op", op);
        o.addProperty("id", e.id);
        o.addProperty("kind", e.kind);
        o.addProperty("task", e.taskId);
        o.addProperty("remainingMs", e.expiresAtMs < 0 ? -1 : Math.max(0, e.expiresAtMs - engine.platform.millis()));
        return o;
    }

    private void broadcast(Element e, String op) {
        JsonObject m = message(e, op);
        if (e.allPlayers) for (EntityRef p : engine.platform.players().online()) send(p.uuid(), m);
        else for (UUID u : e.targets) if (engine.platform.players().isOnline(u)) send(u, m);
    }

    private void send(UUID player, JsonObject o) { engine.platform.clients().send(player, ClientChannel.Topic.HUD, o); }
}
