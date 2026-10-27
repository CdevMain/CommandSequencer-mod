package csequencer.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.json.J;

/**
 * Latest data received from the server. Everything here is server-confirmed; each value carries
 * the local receive time so screens can show when information is stale.
 */
public final class ClientState {
    private ClientState() {}

    public static JsonObject hello = new JsonObject();
    public static JsonObject overlay = new JsonObject();
    public static long overlayAt;
    public static JsonObject tasks = new JsonObject();
    public static long tasksAt;
    public static JsonObject monitor = new JsonObject();
    public static long monitorAt;
    public static JsonObject detail = new JsonObject();
    public static long detailAt;
    public static JsonArray history = new JsonArray();
    public static long historyAt;
    public static JsonObject projects = new JsonObject();
    public static long projectsAt;
    public static JsonObject serverSettings;
    public static boolean connected;

    public static boolean perm(String id) {
        JsonObject p = J.obj(hello, "perms");
        return p != null && J.b(p, id, false);
    }

    public static void reset() {
        hello = new JsonObject();
        overlay = new JsonObject();
        tasks = new JsonObject();
        monitor = new JsonObject();
        detail = new JsonObject();
        history = new JsonArray();
        projects = new JsonObject();
        serverSettings = null;
        overlayAt = tasksAt = monitorAt = detailAt = historyAt = projectsAt = 0;
        connected = false;
    }

    public static long age(long at) { return at == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - at; }
}
