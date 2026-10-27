package csequencer.core.value;

import java.util.UUID;

/**
 * Platform independent reference to a player or entity.
 * {@code player} is true when the reference denotes a player.
 */
public record EntityRef(UUID uuid, String name, String type, boolean player) {
    public static EntityRef player(UUID uuid, String name) { return new EntityRef(uuid, name, "minecraft:player", true); }

    public String key() { return uuid.toString(); }

    @Override public String toString() { return name != null && !name.isEmpty() ? name : uuid.toString(); }
}
