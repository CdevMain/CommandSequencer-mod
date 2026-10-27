package csequencer.core.platform;

import csequencer.core.security.Permission;

import java.util.List;
import java.util.UUID;

/** Permission lookups. Platforms with a permission API should consult it, falling back to op level. */
public interface PermissionAdapter {
    /** Operator level 0-4 of an online player (0 if unknown/offline). */
    int opLevel(UUID player);

    /** Whether the player has the permission; {@code defaultOpLevel} is the fallback requirement. */
    default boolean has(UUID player, Permission permission, int defaultOpLevel) {
        return opLevel(player) >= defaultOpLevel;
    }

    /** Raw permission node check (e.g. "csequencer.run"); false where unsupported. */
    default boolean hasNode(UUID player, String node) { return false; }

    List<String> teams(UUID player);
}
