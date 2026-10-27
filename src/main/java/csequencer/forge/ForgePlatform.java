package csequencer.forge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import csequencer.CommandSequencerMod;
import csequencer.core.platform.*;
import csequencer.core.security.Permission;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Vec3d;
import csequencer.net.CSQNetwork;
import csequencer.net.Payloads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Forge 1.20.1 implementation of the core {@link PlatformAdapter}. Server thread only. */
public final class ForgePlatform implements PlatformAdapter {
    public static final EnumSet<Capability> CAPABILITIES = EnumSet.allOf(Capability.class);

    final MinecraftServer server;
    private final PlatformInfo info;
    private final ForgeQueries queries;
    private final ForgeActions actions;
    private final Players players = new Players();
    private final Perms perms = new Perms();
    private final CoreLogger logger = new CoreLogger() {
        @Override public void info(String msg) { CommandSequencerMod.LOGGER.info("[CSQ] {}", msg); }
        @Override public void warn(String msg) { CommandSequencerMod.LOGGER.warn("[CSQ] {}", msg); }
        @Override public void error(String msg, Throwable t) { CommandSequencerMod.LOGGER.error("[CSQ] " + msg, t); }
    };

    public ForgePlatform(MinecraftServer server) {
        this.server = server;
        String forge = ModList.get().getModContainerById("forge").map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        this.info = new PlatformInfo("forge", forge, server.getServerVersion(), CAPABILITIES);
        this.queries = new ForgeQueries(this);
        this.actions = new ForgeActions(this);
    }

    @Override public PlatformInfo info() { return info; }
    @Override public PlayerDirectory players() { return players; }
    @Override public WorldQueryAdapter queries() { return queries; }
    @Override public ActionAdapter actions() { return actions; }
    @Override public PermissionAdapter permissions() { return perms; }
    @Override public CoreLogger logger() { return logger; }

    @Override public ClientChannel clients() {
        return (uuid, topic, payload) -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) send(p, topic.name().toLowerCase(Locale.ROOT), payload);
        };
    }

    public static void send(ServerPlayer p, String topic, JsonObject payload) {
        byte[] data = Payloads.json(payload);
        if (data.length > CSQNetwork.MAX_S2C) {
            ServerNet.sendChunked(p, "json:" + topic, "{}", data);
            return;
        }
        CSQNetwork.toPlayer(p, new CSQNetwork.S2CMessage(topic, data));
    }

    // ── Helpers shared by queries/actions ──────────────────────────────────

    public static EntityRef ref(Entity e) {
        if (e instanceof ServerPlayer p) return EntityRef.player(p.getUUID(), p.getGameProfile().getName());
        ResourceLocation type = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return new EntityRef(e.getUUID(), e.getName().getString(), type == null ? "" : type.toString(), false);
    }

    public ServerPlayer player(EntityRef r) { return r == null ? null : server.getPlayerList().getPlayer(r.uuid()); }

    /** Finds a loaded entity (player or not) by reference. */
    public Entity entity(EntityRef r) {
        if (r == null) return null;
        ServerPlayer p = server.getPlayerList().getPlayer(r.uuid());
        if (p != null) return p;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(r.uuid());
            if (e != null) return e;
        }
        return null;
    }

    /** Resolves a dimension id; falls back to the subject's level, then the overworld. */
    public ServerLevel level(String dim, Entity fallback) {
        if (dim != null && !dim.isBlank()) {
            ResourceLocation rl = ResourceLocation.tryParse(dim.trim());
            if (rl != null) {
                ServerLevel l = server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
                if (l != null) return l;
            }
        }
        if (fallback != null && fallback.level() instanceof ServerLevel sl) return sl;
        return server.overworld();
    }

    public static Vec3d vec(Vec3 v) { return new Vec3d(v.x, v.y, v.z); }

    public static String dim(Level l) { return l.dimension().location().toString(); }

    public CommandSourceStack source(int level) { return server.createCommandSourceStack().withPermission(level).withSuppressedOutput(); }

    // ── Players ────────────────────────────────────────────────────────────

    private final class Players implements PlayerDirectory {
        @Override public List<EntityRef> online() {
            List<EntityRef> out = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) out.add(ref(p));
            return out;
        }

        @Override public EntityRef byName(String name) {
            if (name == null || name.isEmpty()) return null;
            ServerPlayer p = server.getPlayerList().getPlayerByName(name);
            return p == null ? null : ref(p);
        }

        @Override public EntityRef byUuid(UUID uuid) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p == null ? null : ref(p);
        }

        @Override public String dimensionOf(UUID uuid) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p == null ? null : dim(p.level());
        }

        @Override public Vec3d positionOf(UUID uuid) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p == null || p.isSpectator() || !p.isAlive() ? null : vec(p.position());
        }

        @Override public List<EntityRef> inTeam(String team) {
            List<EntityRef> out = new ArrayList<>();
            PlayerTeam t = server.getScoreboard().getPlayerTeam(team);
            if (t == null) return out;
            for (String n : t.getPlayers()) {
                ServerPlayer p = server.getPlayerList().getPlayerByName(n);
                if (p != null) out.add(ref(p));
            }
            return out;
        }

        @Override public List<EntityRef> select(String selector, EntityRef origin, boolean playersOnly) {
            List<EntityRef> out = new ArrayList<>();
            try {
                CommandSourceStack src = source(2);
                Entity o = entity(origin);
                if (o != null) src = src.withEntity(o).withPosition(o.position()).withLevel(level(null, o));
                var sel = EntityArgument.entities().parse(new StringReader(selector));
                for (Entity e : sel.findEntities(src)) {
                    if (playersOnly && !(e instanceof ServerPlayer)) continue;
                    out.add(ref(e));
                    if (out.size() >= 1000) break;
                }
            } catch (Exception e) {
                logger.warn("Invalid selector '" + selector + "': " + e.getMessage());
            }
            return out;
        }
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private final class Perms implements PermissionAdapter {
        @Override public int opLevel(UUID uuid) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p == null ? 0 : server.getProfilePermissions(p.getGameProfile());
        }

        @Override public boolean has(UUID uuid, Permission permission, int defaultOpLevel) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) return false;
            return ForgePermissions.check(p, permission);
        }

        @Override public boolean hasNode(UUID uuid, String node) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p != null && ForgePermissions.checkDynamic(p, node);
        }

        @Override public List<String> teams(UUID uuid) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null || p.getTeam() == null) return List.of();
            return List.of(p.getTeam().getName());
        }
    }
}
