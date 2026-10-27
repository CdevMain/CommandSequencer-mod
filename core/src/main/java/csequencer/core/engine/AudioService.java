package csequencer.core.engine;

import com.google.gson.JsonObject;
import csequencer.core.model.AudioRef;
import csequencer.core.platform.ClientChannel;
import csequencer.core.value.EntityRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side audio sessions. The server owns the audio clock (wall-clock based); clients are
 * told what to play and at which position, receive periodic sync heartbeats to correct drift,
 * and late joiners are sent the current position. Sessions support fades, live volume changes
 * and an end position (trimmed clips). Sessions are released when their task ends.
 */
public final class AudioService {
    public static final int HEARTBEAT_TICKS = 100;

    public static final class Session {
        public final String id;
        public final String taskId;
        public final String channel;
        public final AudioRef audio;
        public final boolean allPlayers;
        public final Set<UUID> listeners = new LinkedHashSet<>();
        long startMs;
        boolean paused;
        long pausePosMs;
        double volume;
        public final boolean loop;
        long fadeInMs;
        /** Clip end (audio position, ms) after which the session stops; -1 = natural end. */
        long endMs = -1;
        long fadeOutMs;
        boolean stopping;

        Session(String id, String taskId, String channel, AudioRef audio, boolean allPlayers, double volume, boolean loop) {
            this.id = id;
            this.taskId = taskId;
            this.channel = channel;
            this.audio = audio;
            this.allPlayers = allPlayers;
            this.volume = volume;
            this.loop = loop;
        }

        public boolean paused() { return paused; }
        public double volume() { return volume; }
    }

    private final Engine engine;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private int nextId = 1;

    AudioService(Engine engine) { this.engine = engine; }

    public List<Session> sessions() { return List.copyOf(sessions.values()); }

    public List<Session> sessionsOf(String taskId) {
        List<Session> out = new ArrayList<>();
        for (Session s : sessions.values()) if (s.taskId.equals(taskId)) out.add(s);
        return out;
    }

    public long positionMs(Session s) {
        long pos = s.paused ? s.pausePosMs : engine.platform.millis() - s.startMs;
        if (s.loop && s.audio.durationMs > 0) pos = pos % s.audio.durationMs;
        return Math.max(0, pos);
    }

    Session find(String taskId, String channel) {
        for (Session s : sessions.values()) if (s.taskId.equals(taskId) && s.channel.equalsIgnoreCase(channel) && !s.stopping) return s;
        return null;
    }

    /**
     * Starts audio. {@code endMs} (audio position) cuts the clip there (-1 = play to the end);
     * {@code fadeOutMs} fades out before that point.
     */
    Session play(Task task, String channel, AudioRef audio, List<EntityRef> targets, boolean allPlayers, double volume, long offsetMs,
                 boolean loop, long fadeInMs, long endMs, long fadeOutMs) {
        Session old = find(task.id, channel);
        if (old != null) stop(old, 0);
        Session s = new Session(task.id + "#a" + (nextId++), task.id, channel, audio, allPlayers, volume, loop);
        s.startMs = engine.platform.millis() - Math.max(0, offsetMs);
        s.fadeInMs = Math.max(0, fadeInMs);
        s.endMs = endMs;
        s.fadeOutMs = Math.max(0, fadeOutMs);
        if (!allPlayers) for (EntityRef e : targets) s.listeners.add(e.uuid());
        sessions.put(s.id, s);
        broadcast(s, "play", null);
        return s;
    }

    /** Stops a session, optionally fading out on clients first. */
    void stop(Session s, long fadeMs) {
        if (sessions.remove(s.id) == null) return;
        s.stopping = true;
        JsonObject extra = new JsonObject();
        extra.addProperty("fadeMs", Math.max(0, fadeMs));
        broadcast(s, "stop", extra);
    }

    /** Changes the volume, ramping over {@code fadeMs} on clients. */
    void setVolume(Session s, double volume, long fadeMs) {
        s.volume = Math.max(0, Math.min(2, volume));
        JsonObject extra = new JsonObject();
        extra.addProperty("fadeMs", Math.max(0, fadeMs));
        broadcast(s, "volume", extra);
    }

    void pause(Session s) {
        if (s.paused) return;
        s.pausePosMs = positionMs(s);
        s.paused = true;
        broadcast(s, "pause", null);
    }

    void resume(Session s) {
        if (!s.paused) return;
        s.startMs = engine.platform.millis() - s.pausePosMs;
        s.paused = false;
        broadcast(s, "resume", null);
    }

    void seek(Session s, long posMs) {
        posMs = Math.max(0, posMs);
        if (s.paused) s.pausePosMs = posMs;
        else s.startMs = engine.platform.millis() - posMs;
        broadcast(s, "seek", null);
    }

    void pauseTask(Task t) { for (Session s : sessionsOf(t.id)) pause(s); }

    void resumeTask(Task t) { for (Session s : sessionsOf(t.id)) resume(s); }

    /** Releases every session of a task (cleanup on cancel / completion / failure). */
    void stopTask(Task t) { for (Session s : sessionsOf(t.id)) stop(s, 0); }

    void tick(long serverTick) {
        long now = engine.platform.millis();
        for (Session s : new ArrayList<>(sessions.values())) {
            if (s.paused) {
                if (serverTick % HEARTBEAT_TICKS == 0) broadcast(s, "sync", null);
                continue;
            }
            long pos = now - s.startMs;
            if (s.endMs >= 0 && !s.loop) {
                // Trimmed clip: fade out so the fade ends exactly at the cut.
                if (pos >= s.endMs - s.fadeOutMs) {
                    stop(s, Math.max(0, s.endMs - pos));
                    continue;
                }
            } else if (!s.loop && s.audio.durationMs > 0 && pos > s.audio.durationMs + 1000) {
                sessions.remove(s.id);
                continue;
            }
            if (serverTick % HEARTBEAT_TICKS == 0) broadcast(s, "sync", null);
        }
    }

    /** Sends the current state of every relevant session to a player who just joined. */
    void onPlayerJoin(UUID player) {
        for (Session s : sessions.values()) if (s.allPlayers || s.listeners.contains(player)) send(player, s, s.paused ? "pause" : "play", null);
    }

    private void broadcast(Session s, String op, JsonObject extra) {
        if (s.allPlayers) {
            for (EntityRef e : engine.platform.players().online()) send(e.uuid(), s, op, extra);
        } else {
            for (UUID u : s.listeners) if (engine.platform.players().isOnline(u)) send(u, s, op, extra);
        }
    }

    private void send(UUID player, Session s, String op, JsonObject extra) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.addProperty("session", s.id);
        o.addProperty("task", s.taskId);
        o.addProperty("channel", s.channel);
        o.addProperty("hash", s.audio.hash);
        o.addProperty("name", s.audio.name);
        o.addProperty("format", s.audio.format);
        o.addProperty("durationMs", s.audio.durationMs);
        long pos = positionMs(s);
        o.addProperty("positionMs", pos);
        o.addProperty("serverMs", engine.platform.millis());
        o.addProperty("volume", s.volume);
        o.addProperty("loop", s.loop);
        o.addProperty("paused", s.paused);
        // Only fade in when the client starts near the beginning (late joiners hear full volume).
        o.addProperty("fadeInMs", op.equals("play") && pos < s.fadeInMs ? s.fadeInMs - pos : 0);
        if (s.endMs >= 0) o.addProperty("endMs", s.endMs);
        if (extra != null) for (var e : extra.entrySet()) o.add(e.getKey(), e.getValue());
        engine.platform.clients().send(player, ClientChannel.Topic.AUDIO, o);
    }
}
