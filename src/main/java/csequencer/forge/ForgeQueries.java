package csequencer.forge;

import csequencer.core.platform.WorldQueryAdapter;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Forge implementation of the platform queries declared in the core PieceRegistry. */
final class ForgeQueries implements WorldQueryAdapter {
    private static final int MAX_BLOCK_COUNT_VOLUME = 32768;
    private final ForgePlatform pf;

    ForgeQueries(ForgePlatform pf) { this.pf = pf; }

    @Override
    public Value query(String id, Map<String, Value> a, QueryContext ctx) {
        if (id.startsWith("player.")) return player(id, a, pf.player(ctx.subject()));
        return switch (id) {
            case "players.online" -> list(pf.server.getPlayerList().getPlayers());
            case "players.count" -> Value.of(pf.server.getPlayerList().getPlayerCount());
            case "players.in_radius" -> {
                Vec3d c = vec(a, "center");
                double r = a.get("radius").asNumber();
                ServerLevel l = pf.level(text(a, "dimension"), null);
                List<ServerPlayer> ps = new ArrayList<>();
                if (c != null) for (ServerPlayer p : l.players()) if (p.position().distanceToSqr(c.x(), c.y(), c.z()) <= r * r) ps.add(p);
                yield list(ps);
            }
            case "players.in_region" -> {
                AABB box = box(a);
                ServerLevel l = pf.level(text(a, "dimension"), null);
                List<ServerPlayer> ps = new ArrayList<>();
                if (box != null) for (ServerPlayer p : l.players()) if (box.contains(p.position())) ps.add(p);
                yield list(ps);
            }
            case "players.near_subject" -> {
                ServerPlayer s = pf.player(ctx.subject());
                if (s == null) yield Value.EMPTY_LIST;
                double r = a.get("radius").asNumber();
                List<ServerPlayer> ps = new ArrayList<>();
                for (ServerPlayer p : s.serverLevel().players()) if (p != s && p.distanceToSqr(s) <= r * r) ps.add(p);
                yield list(ps);
            }
            case "players.nearest" -> {
                Vec3d c = vec(a, "center");
                ServerLevel l = pf.level(text(a, "dimension"), null);
                if (c == null) yield Value.NULL;
                Player p = l.getNearestPlayer(c.x(), c.y(), c.z(), -1, false);
                yield p == null ? Value.NULL : Value.of(ForgePlatform.ref(p));
            }
            case "players.in_team" -> {
                List<Value> out = new ArrayList<>();
                for (EntityRef r : pf.players().inTeam(text(a, "team"))) out.add(Value.of(r));
                yield Value.list(out);
            }
            case "block.type" -> {
                BlockPos pos = pos(a, "pos");
                if (pos == null) yield Value.NULL;
                ServerLevel l = pf.level(text(a, "dimension"), pf.entity(ctx.subject()));
                if (!l.isLoaded(pos)) yield Value.NULL;
                ResourceLocation key = ForgeRegistries.BLOCKS.getKey(l.getBlockState(pos).getBlock());
                yield Value.of(key == null ? "" : key.toString());
            }
            case "block.state" -> {
                BlockPos pos = pos(a, "pos");
                if (pos == null) yield Value.NULL;
                ServerLevel l = pf.level(text(a, "dimension"), pf.entity(ctx.subject()));
                if (!l.isLoaded(pos)) yield Value.NULL;
                BlockState st = l.getBlockState(pos);
                String prop = text(a, "property");
                for (Property<?> p : st.getProperties()) if (p.getName().equalsIgnoreCase(prop)) yield Value.of(propValue(st, p));
                yield Value.NULL;
            }
            case "block.count" -> {
                BlockPos p1 = pos(a, "from"), p2 = pos(a, "to");
                if (p1 == null || p2 == null) yield Value.NULL;
                long vol = (long) (Math.abs(p1.getX() - p2.getX()) + 1) * (Math.abs(p1.getY() - p2.getY()) + 1) * (Math.abs(p1.getZ() - p2.getZ()) + 1);
                if (vol > MAX_BLOCK_COUNT_VOLUME) yield Value.NULL;
                Block b = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(text(a, "block")));
                ServerLevel l = pf.level(text(a, "dimension"), pf.entity(ctx.subject()));
                int n = 0;
                for (BlockPos p : BlockPos.betweenClosed(p1, p2)) if (l.isLoaded(p) && l.getBlockState(p).is(b)) n++;
                yield Value.of(n);
            }
            case "world.time" -> Value.of(pf.level(text(a, "dimension"), null).getDayTime() % 24000);
            case "world.game_time" -> Value.of(pf.server.overworld().getGameTime());
            case "world.weather" -> {
                ServerLevel l = pf.level(text(a, "dimension"), null);
                yield Value.of(l.isThundering() ? "thunder" : l.isRaining() ? "rain" : "clear");
            }
            case "score.get" -> score(text(a, "objective"), text(a, "holder"));
            case "entities.list", "entities.count" -> {
                List<Entity> es = entities(a, ctx);
                if (id.equals("entities.count")) yield Value.of(es.size());
                List<Value> out = new ArrayList<>();
                for (Entity e : es) out.add(Value.of(ForgePlatform.ref(e)));
                yield Value.list(out);
            }
            case "entity.health", "entity.max_health", "entity.health_pct", "entity.alive", "entity.type", "entity.pos", "entity.distance", "entity.has_tag" -> entity(id, a, ctx);
            default -> Value.NULL;
        };
    }

    private Value player(String id, Map<String, Value> a, ServerPlayer p) {
        if (p == null) return id.equals("player.online") ? Value.FALSE : Value.NULL;
        return switch (id) {
            case "player.name" -> Value.of(p.getGameProfile().getName());
            case "player.uuid" -> Value.of(p.getUUID().toString());
            case "player.online" -> Value.TRUE;
            case "player.health" -> Value.of(p.getHealth());
            case "player.max_health" -> Value.of(p.getMaxHealth());
            case "player.health_pct" -> Value.of(p.getMaxHealth() <= 0 ? 0 : p.getHealth() / p.getMaxHealth() * 100.0);
            case "player.pos" -> Value.of(ForgePlatform.vec(p.position()));
            case "player.x" -> Value.of(p.getX());
            case "player.y" -> Value.of(p.getY());
            case "player.z" -> Value.of(p.getZ());
            case "player.dimension" -> Value.of(ForgePlatform.dim(p.level()));
            case "player.distance_to" -> {
                Vec3d v = vec(a, "pos");
                String other = text(a, "other");
                if (!other.isEmpty()) {
                    Entity o = otherEntity(other);
                    yield o == null || o.level() != p.level() ? Value.NULL : Value.of(p.distanceTo(o));
                }
                yield v == null ? Value.NULL : Value.of(Math.sqrt(p.position().distanceToSqr(v.x(), v.y(), v.z())));
            }
            case "player.in_region" -> {
                AABB box = box(a);
                String dim = text(a, "dimension");
                yield Value.of(box != null && (dim.isEmpty() || dim.equals(ForgePlatform.dim(p.level()))) && box.contains(p.position()));
            }
            case "player.in_radius" -> {
                Vec3d c = vec(a, "center");
                String dim = text(a, "dimension");
                double r = a.get("radius").asNumber();
                yield Value.of(c != null && (dim.isEmpty() || dim.equals(ForgePlatform.dim(p.level()))) && p.position().distanceToSqr(c.x(), c.y(), c.z()) <= r * r);
            }
            case "player.has_item" -> {
                Item item = item(text(a, "item"));
                yield Value.of(item != null && p.getInventory().countItem(item) >= Math.max(1, a.get("count").asNumber()));
            }
            case "player.item_count" -> {
                Item item = item(text(a, "item"));
                yield Value.of(item == null ? 0 : p.getInventory().countItem(item));
            }
            case "player.equipment" -> {
                ItemStack s = p.getItemBySlot(slot(text(a, "slot")));
                ResourceLocation k = ForgeRegistries.ITEMS.getKey(s.getItem());
                yield Value.of(s.isEmpty() || k == null ? "minecraft:air" : k.toString());
            }
            case "player.durability", "player.durability_pct" -> {
                ItemStack s = p.getItemBySlot(slot(text(a, "slot")));
                if (s.isEmpty() || !s.isDamageableItem()) yield Value.NULL;
                int left = s.getMaxDamage() - s.getDamageValue();
                yield Value.of(id.endsWith("pct") ? left * 100.0 / s.getMaxDamage() : left);
            }
            case "player.state" -> Value.of(switch (text(a, "state").toLowerCase(Locale.ROOT)) {
                case "sneaking" -> p.isShiftKeyDown();
                case "sprinting" -> p.isSprinting();
                case "swimming" -> p.isSwimming();
                case "burning" -> p.isOnFire();
                case "on_ground" -> p.onGround();
                case "dead" -> p.isDeadOrDying();
                case "flying" -> p.getAbilities().flying;
                case "sleeping" -> p.isSleeping();
                case "in_water" -> p.isInWater();
                case "gliding" -> p.isFallFlying();
                case "creative" -> p.isCreative();
                case "spectator" -> p.isSpectator();
                case "invisible" -> p.isInvisible();
                default -> false;
            });
            case "player.gamemode" -> Value.of(p.gameMode.getGameModeForPlayer().getName());
            case "player.team" -> Value.of(p.getTeam() == null ? "" : p.getTeam().getName());
            case "player.score" -> score(text(a, "objective"), p.getScoreboardName());
            case "player.xp_level" -> Value.of(p.experienceLevel);
            case "player.food" -> Value.of(p.getFoodData().getFoodLevel());
            case "player.permission_level" -> Value.of(pf.server.getProfilePermissions(p.getGameProfile()));
            case "player.has_permission" -> {
                csequencer.core.security.Permission perm = csequencer.core.security.Permission.byId(text(a, "node").replaceFirst("^csequencer\\.", ""));
                yield Value.of(perm != null && ForgePermissions.check(p, perm));
            }
            default -> Value.NULL;
        };
    }

    private Value entity(String id, Map<String, Value> a, QueryContext ctx) {
        Value ev = a.getOrDefault("entity", Value.NULL);
        Entity e = null;
        if (ev.asEntity() != null) e = pf.entity(ev.asEntity());
        else if (!ev.asText().isEmpty()) e = otherEntity(ev.asText());
        if (id.equals("entity.alive")) return Value.of(e != null && e.isAlive());
        if (e == null) return Value.NULL;
        return switch (id) {
            case "entity.health" -> e instanceof LivingEntity le ? Value.of(le.getHealth()) : Value.NULL;
            case "entity.max_health" -> e instanceof LivingEntity le ? Value.of(le.getMaxHealth()) : Value.NULL;
            case "entity.health_pct" -> e instanceof LivingEntity le && le.getMaxHealth() > 0 ? Value.of(le.getHealth() / le.getMaxHealth() * 100.0) : Value.NULL;
            case "entity.type" -> {
                ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                yield Value.of(k == null ? "" : k.toString());
            }
            case "entity.pos" -> Value.of(ForgePlatform.vec(e.position()));
            case "entity.distance" -> {
                Vec3d v = vec(a, "pos");
                if (v != null) yield Value.of(Math.sqrt(e.position().distanceToSqr(v.x(), v.y(), v.z())));
                Entity s = pf.entity(ctx.subject());
                yield s == null || s.level() != e.level() ? Value.NULL : Value.of(s.distanceTo(e));
            }
            case "entity.has_tag" -> Value.of(e.getTags().contains(text(a, "tag")));
            default -> Value.NULL;
        };
    }

    private List<Entity> entities(Map<String, Value> a, QueryContext ctx) {
        Vec3d c = vec(a, "center");
        double r = Math.min(256, Math.max(0, a.get("radius").asNumber()));
        List<Entity> out = new ArrayList<>();
        if (c == null) return out;
        ServerLevel l = pf.level(text(a, "dimension"), pf.entity(ctx.subject()));
        String type = text(a, "entity");
        EntityType<?> et = type.isEmpty() ? null : EntityType.byString(type).orElse(null);
        if (!type.isEmpty() && et == null) return out;
        String tag = text(a, "tag");
        AABB box = new AABB(c.x() - r, c.y() - r, c.z() - r, c.x() + r, c.y() + r, c.z() + r);
        for (Entity e : l.getEntities((Entity) null, box, x -> (et == null || x.getType() == et) && (tag.isEmpty() || x.getTags().contains(tag))
                && x.position().distanceToSqr(c.x(), c.y(), c.z()) <= r * r)) {
            out.add(e);
            if (out.size() >= 2000) break;
        }
        return out;
    }

    private Entity otherEntity(String s) {
        ServerPlayer p = pf.server.getPlayerList().getPlayerByName(s);
        if (p != null) return p;
        try {
            return pf.entity(new EntityRef(UUID.fromString(s), "", "", false));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Value score(String objective, String holder) {
        Scoreboard sb = pf.server.getScoreboard();
        Objective o = sb.getObjective(objective);
        if (o == null || holder == null || holder.isEmpty() || !sb.hasPlayerScore(holder, o)) return Value.NULL;
        return Value.of(sb.getOrCreatePlayerScore(holder, o).getScore());
    }

    private static Value list(List<? extends Entity> es) {
        List<Value> out = new ArrayList<>();
        for (Entity e : es) out.add(Value.of(ForgePlatform.ref(e)));
        return Value.list(out);
    }

    private static <T extends Comparable<T>> String propValue(BlockState s, Property<T> p) { return p.getName(s.getValue(p)); }

    static String text(Map<String, Value> a, String k) { return a.getOrDefault(k, Value.NULL).asText().trim(); }

    static Vec3d vec(Map<String, Value> a, String k) { return a.getOrDefault(k, Value.NULL).asVec(); }

    static BlockPos pos(Map<String, Value> a, String k) {
        Vec3d v = vec(a, k);
        return v == null ? null : BlockPos.containing(v.x(), v.y(), v.z());
    }

    static AABB box(Map<String, Value> a) {
        Vec3d f = vec(a, "from"), t = vec(a, "to");
        if (f == null || t == null) return null;
        return new AABB(Math.min(f.x(), t.x()), Math.min(f.y(), t.y()), Math.min(f.z(), t.z()),
                Math.max(f.x(), t.x()) + 1, Math.max(f.y(), t.y()) + 1, Math.max(f.z(), t.z()) + 1);
    }

    static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) return null;
        return ForgeRegistries.ITEMS.getValue(rl);
    }

    static EquipmentSlot slot(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> EquipmentSlot.MAINHAND;
        };
    }
}
