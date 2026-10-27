package csequencer.core.platform;

import csequencer.core.value.EntityRef;

import java.util.List;
import java.util.UUID;

/** Online player lookup. Implementations must return stable {@link EntityRef}s (uuid based). */
public interface PlayerDirectory {
    List<EntityRef> online();

    EntityRef byName(String name);

    EntityRef byUuid(UUID uuid);

    default boolean isOnline(UUID uuid) { return byUuid(uuid) != null; }

    /** Dimension id of an online player, or null. */
    String dimensionOf(UUID uuid);

    /** Feet position of an online player, or null. */
    default csequencer.core.value.Vec3d positionOf(UUID uuid) { return null; }

    /** Online players in a scoreboard team. */
    List<EntityRef> inTeam(String team);

    /**
     * Resolves a vanilla entity selector (e.g. "@a[distance=..10]") relative to {@code origin}
     * (may be null). Returns only players when {@code playersOnly}.
     */
    List<EntityRef> select(String selector, EntityRef origin, boolean playersOnly);
}
