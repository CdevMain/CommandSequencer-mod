package csequencer.forge;

import csequencer.CommandSequencerMod;
import csequencer.core.security.Permission;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers one Forge permission node per {@link Permission} ("csequencer.run", ...). Permission
 * mods (e.g. LuckPerms) can override them; the default resolver uses the operator levels from the
 * server settings, so vanilla servers work without any permission mod.
 */
public final class ForgePermissions {
    private static final Map<Permission, PermissionNode<Boolean>> NODES = new EnumMap<>(Permission.class);
    private static volatile boolean registered;

    private ForgePermissions() {}

    static {
        for (Permission p : Permission.values()) {
            NODES.put(p, new PermissionNode<>(CommandSequencerMod.MODID, p.id, PermissionTypes.BOOLEAN,
                    (player, uuid, ctx) -> player != null && player.server.getProfilePermissions(player.getGameProfile()) >= requiredLevel(p)));
        }
    }

    static int requiredLevel(Permission p) {
        CSQServer s = CSQServer.get();
        return s == null ? p.defaultLevel : s.settings().level(p);
    }

    @SubscribeEvent
    public static void onGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES.values().toArray(new PermissionNode<?>[0]));
        registered = true;
    }

    public static boolean check(ServerPlayer player, Permission p) {
        if (!registered) return player.server.getProfilePermissions(player.getGameProfile()) >= requiredLevel(p);
        try {
            return PermissionAPI.getPermission(player, NODES.get(p));
        } catch (RuntimeException e) {
            return player.server.getProfilePermissions(player.getGameProfile()) >= requiredLevel(p);
        }
    }

    /**
     * Dynamic per-sequence nodes ("csequencer.announcements.&lt;project&gt;") are not registrable in
     * advance; they are approximated by the static ANNOUNCEMENTS node plus op level.
     */
    public static boolean checkDynamic(ServerPlayer player, String node) {
        return check(player, Permission.MONITOR_ALL);
    }
}
