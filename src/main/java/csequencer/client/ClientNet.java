package csequencer.client;

import com.google.gson.JsonObject;
import csequencer.CommandSequencerMod;
import csequencer.client.audio.ClientAudio;
import csequencer.core.json.J;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.Project;
import csequencer.net.CSQNetwork;
import csequencer.net.ChunkAssembler;
import csequencer.net.Payloads;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Client side of the protocol: requests with callbacks, chunked uploads, downloads, topics. */
public final class ClientNet {
    private static final int MAX_JSON = 8 * 1024 * 1024;
    private static final Map<String, Consumer<JsonObject>> callbacks = new HashMap<>();
    private static final Map<String, Consumer<Project>> projectCallbacks = new HashMap<>();
    private static final ChunkAssembler downloads = new ChunkAssembler(8, 120_000);
    private static int nextReq = 1;
    private static int nextTransfer = 1;

    private ClientNet() {}

    public static String request(String op, JsonObject payload, Consumer<JsonObject> onResult) {
        String id = "r" + (nextReq++);
        JsonObject o = payload == null ? new JsonObject() : payload.deepCopy();
        o.addProperty("req", id);
        if (onResult != null) callbacks.put(id, onResult);
        CSQNetwork.toServer(new CSQNetwork.C2SAction(op, Payloads.json(o)));
        return id;
    }

    public static void send(String op, JsonObject payload) { request(op, payload, null); }

    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else if (v instanceof Boolean b) o.addProperty((String) kv[i], b);
            else if (v instanceof com.google.gson.JsonElement e) o.add((String) kv[i], e);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        return o;
    }

    public static void downloadProject(String id, Consumer<Project> cb) {
        String req = request("project.get", obj("id", id), r -> {
            if (!J.b(r, "ok", true)) Toasts.error(J.str(r, "msg", "Download failed"));
        });
        projectCallbacks.put(req, cb);
    }

    /** Uploads a project (gzip JSON, chunked to respect the 32 KB client→server packet limit). */
    public static void uploadProject(Project p, long baseRevision, boolean force, Consumer<JsonObject> onResult) {
        byte[] data = Payloads.gzip(ProjectCodec.writeCompact(p).getBytes(StandardCharsets.UTF_8));
        String req = "r" + (nextReq++);
        if (onResult != null) callbacks.put(req, onResult);
        JsonObject meta = obj("req", req, "baseRevision", baseRevision, "force", force);
        sendChunks("project", J.COMPACT.toJson(meta), data);
    }

    public static void uploadAudio(byte[] data, String hash, String format, Consumer<JsonObject> onResult) {
        String req = "r" + (nextReq++);
        if (onResult != null) callbacks.put(req, onResult);
        sendChunks("audio", J.COMPACT.toJson(obj("req", req, "hash", hash, "format", format)), data);
    }

    private static void sendChunks(String kind, String meta, byte[] data) {
        int id = nextTransfer++;
        int total = Math.max(1, (data.length + CSQNetwork.CHUNK_C2S - 1) / CSQNetwork.CHUNK_C2S);
        for (int i = 0; i < total; i++) {
            int from = i * CSQNetwork.CHUNK_C2S, to = Math.min(data.length, from + CSQNetwork.CHUNK_C2S);
            CSQNetwork.toServer(new CSQNetwork.C2SChunk(id, kind, i, total, i == 0 ? meta : meta, Arrays.copyOfRange(data, from, to)));
        }
    }

    // ── Incoming ───────────────────────────────────────────────────────────

    public static void onMessage(String topic, byte[] data) {
        JsonObject o;
        try {
            o = Payloads.json(data, MAX_JSON);
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Bad message on {}: {}", topic, e.getMessage());
            return;
        }
        dispatch(topic, o);
    }

    private static void dispatch(String topic, JsonObject o) {
        long now = System.currentTimeMillis();
        ClientState.connected = true;
        switch (topic) {
            case "hello" -> ClientState.hello = o;
            case "overlay" -> { ClientState.overlay = o; ClientState.overlayAt = now; SequenceOverlay.onUpdate(o); }
            case "tasks" -> { ClientState.tasks = o; ClientState.tasksAt = now; }
            case "monitor" -> { ClientState.monitor = o; ClientState.monitorAt = now; }
            case "task_detail" -> { ClientState.detail = o; ClientState.detailAt = now; }
            case "history" -> { ClientState.history = J.arr(o, "records"); ClientState.historyAt = now; }
            case "projects" -> { ClientState.projects = o; ClientState.projectsAt = now; }
            case "settings" -> ClientState.serverSettings = o;
            case "audio" -> ClientAudio.onServerCommand(o);
            case "hud" -> HudLayer.onMessage(o);
            case "areas" -> AreaView.onMessage(o);
            case "notify" -> Toasts.push(J.str(o, "msg", ""), J.str(o, "level", "info"));
            case "result" -> {
                Consumer<JsonObject> cb = callbacks.remove(J.str(o, "req", ""));
                if (cb != null) cb.accept(o);
                else if (!J.b(o, "ok", true)) Toasts.error(J.str(o, "msg", "Request failed"));
                else if (!J.str(o, "msg", "").isEmpty()) Toasts.success(J.str(o, "msg", ""));
            }
            default -> {}
        }
    }

    public static void onChunk(CSQNetwork.S2CChunk c) {
        int max = c.kind().equals("audio") ? 256 * 1024 * 1024 : 64 * 1024 * 1024;
        ChunkAssembler.Done d;
        try {
            d = downloads.accept(c.transfer(), c.kind(), c.index(), c.total(), c.meta(), c.data(), max);
        } catch (IllegalArgumentException e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Download error: {}", e.getMessage());
            return;
        }
        if (d == null) return;
        try {
            JsonObject meta = J.parseObject(d.meta());
            if (d.kind().startsWith("json:")) {
                dispatch(d.kind().substring(5), Payloads.json(d.data(), MAX_JSON));
            } else if (d.kind().equals("project")) {
                String req = J.str(meta, "req", "");
                Consumer<Project> cb = projectCallbacks.remove(req);
                var rr = ProjectCodec.read(new String(Payloads.gunzip(d.data(), MAX_JSON), StandardCharsets.UTF_8));
                ProjectSession.lastMeta = J.obj(meta, "meta");
                ProjectSession.lastCanEdit = J.b(meta, "canEdit", false);
                if (cb != null) cb.accept(rr.project());
            } else if (d.kind().equals("audio")) {
                ClientAudio.onDownloaded(J.str(meta, "hash", ""), J.str(meta, "format", "wav"), d.data());
            }
        } catch (Exception e) {
            CommandSequencerMod.LOGGER.warn("[CSQ] Failed to process download {}: {}", d.kind(), e.getMessage());
        }
    }

    public static void onLogout() {
        callbacks.clear();
        projectCallbacks.clear();
        downloads.clear();
    }
}
