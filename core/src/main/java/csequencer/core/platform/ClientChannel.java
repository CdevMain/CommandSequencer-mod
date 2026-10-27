package csequencer.core.platform;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Server → client delivery of display data. The platform maps each {@link Topic} to a packet.
 * Payloads are small JSON objects; implementations may compress and must respect packet limits.
 */
public interface ClientChannel {
    enum Topic { OVERLAY, TASKS, MONITOR, AUDIO, NOTIFY, HISTORY, TASK_DETAIL, HUD }

    void send(UUID player, Topic topic, JsonObject payload);
}
