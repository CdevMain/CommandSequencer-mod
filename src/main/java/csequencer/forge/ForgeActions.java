package csequencer.forge;

import com.mojang.brigadier.StringReader;
import csequencer.core.platform.ActionAdapter;
import csequencer.core.security.CommandFilter;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import csequencer.core.value.Vec3d;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Forge implementation of the dispatched action pieces. Never throws; returns failures instead. */
final class ForgeActions implements ActionAdapter {
    private record Spawned(ResourceKey<Level> level, UUID uuid) {}

    private final ForgePlatform pf;
    private final Map<String, List<Spawned>> spawned = new HashMap<>();
    /** Vanilla boss bars per task: taskId -> key -> bar. */
    private final Map<String, Map<String, net.minecraft.server.level.ServerBossEvent>> bossbars = new HashMap<>();

    ForgeActions(ForgePlatform pf) { this.pf = pf; }

    @Override
    public ActionResult execute(String type, Map<String, Value> a, ActionContext ctx) {
        try {
            return switch (type) {
                case "action.command" -> command(a, ctx);
                case "action.chat" -> chat(a, ctx);
                case "action.title" -> title(a, ctx);
                case "action.actionbar" -> eachPlayer(ctx, p -> p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(t(a, "message")))));
                case "action.sound" -> sound(a, ctx);
                case "action.teleport" -> teleport(a, ctx);
                case "action.spawn_entity" -> spawn(a, ctx);
                case "action.remove_entities" -> removeEntities(a, ctx);
                case "action.particles" -> particles(a, ctx);
                case "action.effect" -> effect(a, ctx);
                case "action.player_state" -> playerState(a, ctx);
                case "action.set_block" -> setBlock(a, ctx);
                case "action.fill" -> fill(a, ctx);
                case "action.clone" -> clone(a, ctx);
                case "action.structure" -> structure(a, ctx);
                case "action.scoreboard" -> scoreboard(a, ctx);
                case "action.give_item" -> give(a, ctx);
                case "action.clear_item" -> clear(a, ctx);
                case "hud.bossbar" -> bossbar(a, ctx);
                default -> ActionResult.fail("Unsupported action " + type);
            };
        } catch (Exception e) {
            return ActionResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void onTaskEnded(String taskId, boolean removeSpawned) {
        Map<String, net.minecraft.server.level.ServerBossEvent> bars = bossbars.remove(taskId);
        if (bars != null) for (var b : bars.values()) b.removeAllPlayers();
        List<Spawned> list = spawned.remove(taskId);
        if (!removeSpawned || list == null) return;
        for (Spawned s : list) {
            ServerLevel l = pf.server.getLevel(s.level);
            if (l == null) continue;
            Entity e = l.getEntity(s.uuid);
            if (e != null && !(e instanceof ServerPlayer)) e.discard();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private interface PlayerOp { void apply(ServerPlayer p); }

    private ActionResult eachPlayer(ActionContext ctx, PlayerOp op) {
        int n = 0;
        for (EntityRef r : ctx.targets()) {
            ServerPlayer p = pf.player(r);
            if (p == null) continue;
            op.apply(p);
            n++;
        }
        return n == 0 && !ctx.targets().isEmpty() ? ActionResult.fail("no online targets") : ActionResult.ok(n + " player(s)", n);
    }

    private static String t(Map<String, Value> a, String k) { return ForgeQueries.text(a, k); }

    private static double num(Map<String, Value> a, String k) { return a.getOrDefault(k, Value.ZERO).asNumber(); }

    private static boolean bool(Map<String, Value> a, String k) { return a.getOrDefault(k, Value.FALSE).asBool(); }

    private ServerLevel level(Map<String, Value> a, ActionContext ctx) {
        String d = t(a, "dimension");
        return pf.level(d.isEmpty() ? ctx.dimension() : d, pf.entity(ctx.subject()));
    }

    private static ChatFormatting color(String c) {
        ChatFormatting f = ChatFormatting.getByName(c);
        return f == null || !f.isColor() ? ChatFormatting.WHITE : f;
    }

    // ── Commands ───────────────────────────────────────────────────────────

    private ActionResult command(Map<String, Value> a, ActionContext ctx) {
        String cmd = t(a, "command");
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        String reason = CommandFilter.check(cmd, ctx.commandBlacklist());
        if (reason != null) return ActionResult.fail(reason);
        if (ctx.commandPermissionLevel() <= 0) return ActionResult.fail("commands not permitted");
        List<String> output = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component c) { if (output.size() < 8) output.add(c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        Entity subject = "subject".equals(t(a, "as")) ? pf.entity(ctx.subject()) : null;
        ServerLevel level = pf.level(ctx.dimension(), subject);
        Vec3 pos = subject != null ? subject.position() : Vec3.atLowerCornerOf(level.getSharedSpawnPos());
        Vec2 rot = subject != null ? subject.getRotationVector() : Vec2.ZERO;
        CommandSourceStack src = new CommandSourceStack(sink, pos, rot, level, ctx.commandPermissionLevel(),
                "CommandSequencer", Component.literal("CommandSequencer:" + ctx.projectName()), pf.server, subject);
        int result = pf.server.getCommands().performPrefixedCommand(src, cmd);
        String out = String.join(" | ", output);
        if (result <= 0) return ActionResult.fail(out.isEmpty() ? "command returned 0" : out);
        return ActionResult.ok(out.isEmpty() ? "result " + result : out, result);
    }

    // ── Messages ───────────────────────────────────────────────────────────

    private ActionResult chat(Map<String, Value> a, ActionContext ctx) {
        MutableComponent c = Component.literal(t(a, "message")).withStyle(color(t(a, "color")));
        if (bool(a, "bold")) c = c.withStyle(ChatFormatting.BOLD);
        MutableComponent msg = c;
        return eachPlayer(ctx, p -> p.sendSystemMessage(msg));
    }

    private ActionResult title(Map<String, Value> a, ActionContext ctx) {
        ChatFormatting col = color(t(a, "color"));
        Component title = Component.literal(t(a, "title")).withStyle(col);
        String sub = t(a, "subtitle");
        int fi = (int) num(a, "fadeIn"), st = (int) num(a, "stay"), fo = (int) num(a, "fadeOut");
        return eachPlayer(ctx, p -> {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(fi, st, fo));
            if (!sub.isEmpty()) p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sub)));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
        });
    }

    private ActionResult sound(Map<String, Value> a, ActionContext ctx) {
        ResourceLocation id = ResourceLocation.tryParse(t(a, "sound"));
        if (id == null) return ActionResult.fail("invalid sound id");
        SoundEvent ev = ForgeRegistries.SOUND_EVENTS.containsKey(id) ? ForgeRegistries.SOUND_EVENTS.getValue(id) : SoundEvent.createVariableRangeEvent(id);
        SoundSource src = switch (t(a, "source").toLowerCase(Locale.ROOT)) {
            case "music" -> SoundSource.MUSIC;
            case "record" -> SoundSource.RECORDS;
            case "weather" -> SoundSource.WEATHER;
            case "block" -> SoundSource.BLOCKS;
            case "hostile" -> SoundSource.HOSTILE;
            case "neutral" -> SoundSource.NEUTRAL;
            case "player" -> SoundSource.PLAYERS;
            case "ambient" -> SoundSource.AMBIENT;
            case "voice" -> SoundSource.VOICE;
            default -> SoundSource.MASTER;
        };
        Vec3d at = ForgeQueries.vec(a, "pos");
        float vol = (float) num(a, "volume"), pitch = (float) num(a, "pitch");
        Holder<SoundEvent> holder = Holder.direct(ev);
        return eachPlayer(ctx, p -> {
            double x = at == null ? p.getX() : at.x(), y = at == null ? p.getY() : at.y(), z = at == null ? p.getZ() : at.z();
            p.connection.send(new ClientboundSoundPacket(holder, src, x, y, z, vol, pitch, p.getRandom().nextLong()));
        });
    }

    // ── Movement & entities ────────────────────────────────────────────────

    private ActionResult teleport(Map<String, Value> a, ActionContext ctx) {
        Vec3d v = ForgeQueries.vec(a, "pos");
        if (v == null) return ActionResult.fail("invalid position");
        boolean relative = bool(a, "relative"), keep = bool(a, "keepRotation");
        String dim = t(a, "dimension");
        int n = 0;
        for (EntityRef r : ctx.targets()) {
            Entity e = pf.entity(r);
            if (e == null) continue;
            ServerLevel l = dim.isEmpty() ? (ServerLevel) e.level() : pf.level(dim, e);
            double x = v.x() + (relative ? e.getX() : 0), y = v.y() + (relative ? e.getY() : 0), z = v.z() + (relative ? e.getZ() : 0);
            float yaw = keep || a.get("yaw").isNull() ? e.getYRot() : (float) num(a, "yaw");
            float pitch = keep || a.get("pitch").isNull() ? e.getXRot() : (float) num(a, "pitch");
            if (e.teleportTo(l, x, y, z, EnumSet.noneOf(RelativeMovement.class), yaw, pitch)) n++;
        }
        return n == 0 ? ActionResult.fail("no target teleported") : ActionResult.ok(n + " teleported", n);
    }

    private ActionResult spawn(Map<String, Value> a, ActionContext ctx) throws Exception {
        String type = t(a, "entity");
        EntityType<?> et = EntityType.byString(type).orElse(null);
        if (et == null) return ActionResult.fail("unknown entity " + type);
        if (et == EntityType.PLAYER) return ActionResult.fail("cannot spawn players");
        Vec3d v = ForgeQueries.vec(a, "pos");
        if (v == null) return ActionResult.fail("invalid position");
        ServerLevel l = level(a, ctx);
        if (!l.isLoaded(BlockPos.containing(v.x(), v.y(), v.z()))) return ActionResult.fail("position not loaded");
        CompoundTag tag = new CompoundTag();
        String nbt = t(a, "nbt");
        if (!nbt.isEmpty()) tag = TagParser.parseTag(nbt);
        tag.putString("id", ForgeRegistries.ENTITY_TYPES.getKey(et).toString());
        int count = Math.max(1, Math.min(64, (int) num(a, "count")));
        String userTag = t(a, "tag");
        Value first = Value.NULL;
        int n = 0;
        for (int i = 0; i < count; i++) {
            Entity e = EntityType.loadEntityRecursive(tag.copy(), l, x -> {
                x.moveTo(v.x(), v.y(), v.z(), x.getYRot(), x.getXRot());
                return x;
            });
            if (e == null) return ActionResult.fail("could not create " + type);
            e.addTag("csq_task");
            if (!userTag.isEmpty()) e.addTag(userTag);
            if (!l.tryAddFreshEntityWithPassengers(e)) continue;
            spawned.computeIfAbsent(ctx.taskId(), k -> new ArrayList<>()).add(new Spawned(l.dimension(), e.getUUID()));
            if (first.isNull()) first = Value.of(ForgePlatform.ref(e));
            n++;
        }
        return new ActionResult(n > 0, n + " spawned", first, n);
    }

    private ActionResult removeEntities(Map<String, Value> a, ActionContext ctx) {
        Vec3d c = ForgeQueries.vec(a, "center");
        if (c == null) return ActionResult.fail("invalid center");
        double r = Math.min(256, Math.max(0, num(a, "radius")));
        ServerLevel l = level(a, ctx);
        String type = t(a, "entity"), tag = t(a, "tag");
        EntityType<?> et = type.isEmpty() ? null : EntityType.byString(type).orElse(null);
        if (!type.isEmpty() && et == null) return ActionResult.fail("unknown entity " + type);
        Set<UUID> mine = new HashSet<>();
        if (bool(a, "spawnedBySequence")) for (Spawned s : spawned.getOrDefault(ctx.taskId(), List.of())) mine.add(s.uuid);
        boolean onlyMine = bool(a, "spawnedBySequence");
        int n = 0;
        AABB box = new AABB(c.x() - r, c.y() - r, c.z() - r, c.x() + r, c.y() + r, c.z() + r);
        for (Entity e : l.getEntities((Entity) null, box, x -> !(x instanceof ServerPlayer))) {
            if (et != null && e.getType() != et) continue;
            if (!tag.isEmpty() && !e.getTags().contains(tag)) continue;
            if (onlyMine && !mine.contains(e.getUUID())) continue;
            if (e.position().distanceToSqr(c.x(), c.y(), c.z()) > r * r) continue;
            e.discard();
            if (++n >= 5000) break;
        }
        return ActionResult.ok(n + " removed", n);
    }

    private ActionResult particles(Map<String, Value> a, ActionContext ctx) throws Exception {
        ParticleOptions opt = ParticleArgument.readParticle(new StringReader(t(a, "particle")), BuiltInRegistries.PARTICLE_TYPE.asLookup());
        Vec3d at = ForgeQueries.vec(a, "pos");
        Vec3d d = ForgeQueries.vec(a, "delta");
        if (d == null) d = Vec3d.ZERO;
        int count = Math.max(0, Math.min(5000, (int) num(a, "count")));
        double speed = num(a, "speed");
        boolean force = bool(a, "force");
        ServerLevel l = level(a, ctx);
        List<ServerPlayer> viewers = new ArrayList<>();
        for (EntityRef r : ctx.targets()) { ServerPlayer p = pf.player(r); if (p != null) viewers.add(p); }
        if (viewers.isEmpty()) viewers.addAll(l.players());
        int sent = 0;
        for (ServerPlayer p : viewers) {
            if (p.level() != l && at != null) continue;
            double x = at == null ? p.getX() : at.x(), y = at == null ? p.getY() + 1 : at.y(), z = at == null ? p.getZ() : at.z();
            if (p.serverLevel().sendParticles(p, opt, force, x, y, z, count, d.x(), d.y(), d.z(), speed)) sent++;
        }
        return ActionResult.ok("shown to " + sent, sent);
    }

    private ActionResult effect(Map<String, Value> a, ActionContext ctx) {
        String mode = t(a, "mode");
        MobEffect eff = ForgeRegistries.MOB_EFFECTS.getValue(ResourceLocation.tryParse(t(a, "effect")));
        if (eff == null && !"clear_all".equals(mode)) return ActionResult.fail("unknown effect");
        int dur = Math.max(1, (int) num(a, "duration")), amp = Math.max(0, Math.min(255, (int) num(a, "amplifier")));
        boolean hide = bool(a, "hideParticles");
        int n = 0;
        for (EntityRef r : ctx.targets()) {
            if (!(pf.entity(r) instanceof LivingEntity le)) continue;
            switch (mode) {
                case "clear" -> le.removeEffect(eff);
                case "clear_all" -> le.removeAllEffects();
                default -> le.addEffect(new MobEffectInstance(eff, dur, amp, false, !hide));
            }
            n++;
        }
        return ActionResult.ok(n + " affected", n);
    }

    private ActionResult playerState(Map<String, Value> a, ActionContext ctx) {
        String prop = t(a, "property");
        Value v = a.getOrDefault("value", Value.NULL);
        double d = v.asNumber();
        return eachPlayer(ctx, p -> {
            switch (prop) {
                case "health" -> p.setHealth((float) Math.max(0, Math.min(p.getMaxHealth(), d)));
                case "heal_full" -> p.setHealth(p.getMaxHealth());
                case "food" -> p.getFoodData().setFoodLevel((int) Math.max(0, Math.min(20, d)));
                case "saturation" -> p.getFoodData().setSaturation((float) Math.max(0, Math.min(20, d)));
                case "xp_levels" -> p.setExperienceLevels((int) Math.max(0, d));
                case "fire_ticks" -> p.setRemainingFireTicks((int) Math.max(0, Math.min(20 * 60, d)));
                case "extinguish" -> p.clearFire();
                case "gamemode" -> p.setGameMode(GameType.byName(v.asText().toLowerCase(Locale.ROOT), p.gameMode.getGameModeForPlayer()));
                case "air" -> p.setAirSupply((int) Math.max(0, Math.min(p.getMaxAirSupply(), d)));
                default -> {}
            }
        });
    }

    // ── Blocks & structures ────────────────────────────────────────────────

    private static BlockState parseBlock(String s) throws Exception {
        return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), s, false).blockState();
    }

    private ActionResult setBlock(Map<String, Value> a, ActionContext ctx) throws Exception {
        BlockPos pos = ForgeQueries.pos(a, "pos");
        if (pos == null) return ActionResult.fail("invalid position");
        ServerLevel l = level(a, ctx);
        if (!l.isLoaded(pos)) return ActionResult.fail("position not loaded");
        BlockState st = parseBlock(t(a, "block"));
        switch (t(a, "mode")) {
            case "keep" -> { if (!l.getBlockState(pos).isAir()) return ActionResult.ok("kept", 0); }
            case "destroy" -> l.destroyBlock(pos, true);
            default -> {}
        }
        return l.setBlock(pos, st, 3) ? ActionResult.ok("placed") : ActionResult.ok("unchanged", 0);
    }

    private ActionResult fill(Map<String, Value> a, ActionContext ctx) throws Exception {
        BlockPos p1 = ForgeQueries.pos(a, "from"), p2 = ForgeQueries.pos(a, "to");
        if (p1 == null || p2 == null) return ActionResult.fail("invalid region");
        int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
        long vol = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (vol > ctx.fillBlockLimit()) return ActionResult.fail("region has " + vol + " blocks; server limit is " + ctx.fillBlockLimit());
        ServerLevel l = level(a, ctx);
        BlockState st = parseBlock(t(a, "block"));
        String filterId = t(a, "filter");
        BlockState filter = filterId.isEmpty() ? null : parseBlock(filterId);
        String mode = t(a, "mode");
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (!l.isLoaded(pos)) continue;
            boolean edge = pos.getX() == minX || pos.getX() == maxX || pos.getY() == minY || pos.getY() == maxY || pos.getZ() == minZ || pos.getZ() == maxZ;
            BlockState target = st;
            switch (mode) {
                case "hollow" -> { if (!edge) target = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(); }
                case "outline" -> { if (!edge) continue; }
                case "keep" -> { if (!l.getBlockState(pos).isAir()) continue; }
                case "destroy" -> l.destroyBlock(pos, true);
                default -> {}
            }
            if (filter != null && l.getBlockState(pos).getBlock() != filter.getBlock()) continue;
            if (l.setBlock(pos, target, 2)) n++;
        }
        return ActionResult.ok(n + " blocks", n);
    }

    private ActionResult clone(Map<String, Value> a, ActionContext ctx) {
        BlockPos f = ForgeQueries.pos(a, "from"), t2 = ForgeQueries.pos(a, "to"), d = ForgeQueries.pos(a, "dest");
        if (f == null || t2 == null || d == null) return ActionResult.fail("invalid region");
        long vol = (long) (Math.abs(f.getX() - t2.getX()) + 1) * (Math.abs(f.getY() - t2.getY()) + 1) * (Math.abs(f.getZ() - t2.getZ()) + 1);
        if (vol > ctx.fillBlockLimit()) return ActionResult.fail("region exceeds the server block limit");
        String mode = switch (t(a, "mode")) {
            case "masked" -> "masked";
            case "move" -> "replace move";
            default -> "replace";
        };
        ServerLevel l = level(a, ctx);
        String cmd = String.format(Locale.ROOT, "execute in %s run clone %d %d %d %d %d %d %d %d %d %s", ForgePlatform.dim(l),
                f.getX(), f.getY(), f.getZ(), t2.getX(), t2.getY(), t2.getZ(), d.getX(), d.getY(), d.getZ(), mode);
        int r = pf.server.getCommands().performPrefixedCommand(pf.source(2), cmd);
        return r > 0 ? ActionResult.ok("cloned " + r, r) : ActionResult.fail("clone failed (region unloaded or overlapping)");
    }

    private ActionResult structure(Map<String, Value> a, ActionContext ctx) {
        ResourceLocation id = ResourceLocation.tryParse(t(a, "structure"));
        BlockPos pos = ForgeQueries.pos(a, "pos");
        if (id == null || pos == null) return ActionResult.fail("invalid structure or position");
        ServerLevel l = level(a, ctx);
        Optional<StructureTemplate> tpl = l.getStructureManager().get(id);
        if (tpl.isEmpty()) return ActionResult.fail("structure " + id + " not found");
        Rotation rot = switch (t(a, "rotation")) {
            case "clockwise_90" -> Rotation.CLOCKWISE_90;
            case "180" -> Rotation.CLOCKWISE_180;
            case "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        Mirror mir = switch (t(a, "mirror")) {
            case "left_right" -> Mirror.LEFT_RIGHT;
            case "front_back" -> Mirror.FRONT_BACK;
            default -> Mirror.NONE;
        };
        var size = tpl.get().getSize();
        if ((long) size.getX() * size.getY() * size.getZ() > ctx.fillBlockLimit() * 4L) return ActionResult.fail("structure exceeds the server block limit");
        StructurePlaceSettings s = new StructurePlaceSettings().setRotation(rot).setMirror(mir);
        boolean ok = tpl.get().placeInWorld(l, pos, pos, s, RandomSource.create(), 2);
        return ok ? ActionResult.ok("placed " + id) : ActionResult.fail("placement failed");
    }

    // ── Boss bars ──────────────────────────────────────────────────────────

    private ActionResult bossbar(Map<String, Value> a, ActionContext ctx) {
        String key = t(a, "key");
        Map<String, net.minecraft.server.level.ServerBossEvent> bars = bossbars.computeIfAbsent(ctx.taskId(), k -> new HashMap<>());
        if ("hide".equals(t(a, "op"))) {
            var b = bars.remove(key);
            if (b != null) b.removeAllPlayers();
            return ActionResult.ok("hidden");
        }
        net.minecraft.world.BossEvent.BossBarColor color;
        try {
            color = net.minecraft.world.BossEvent.BossBarColor.valueOf(t(a, "color").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            color = net.minecraft.world.BossEvent.BossBarColor.RED;
        }
        net.minecraft.world.BossEvent.BossBarOverlay overlay;
        try {
            overlay = net.minecraft.world.BossEvent.BossBarOverlay.valueOf(t(a, "style").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            overlay = net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS;
        }
        var bar = bars.get(key);
        Component name = Component.literal(t(a, "name"));
        if (bar == null) {
            bar = new net.minecraft.server.level.ServerBossEvent(name, color, overlay);
            bars.put(key, bar);
        }
        bar.setName(name);
        bar.setColor(color);
        bar.setOverlay(overlay);
        bar.setProgress((float) Math.max(0, Math.min(1, num(a, "progress"))));
        bar.setDarkenScreen(bool(a, "darkenSky"));
        java.util.Set<UUID> wanted = new HashSet<>();
        for (EntityRef r : ctx.targets()) {
            ServerPlayer p = pf.player(r);
            if (p == null) continue;
            wanted.add(p.getUUID());
            if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        }
        for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) if (!wanted.contains(p.getUUID())) bar.removePlayer(p);
        return ActionResult.ok("boss bar " + key, wanted.size());
    }

    // ── Scoreboard & items ─────────────────────────────────────────────────

    private ActionResult scoreboard(Map<String, Value> a, ActionContext ctx) {
        String objName = t(a, "objective");
        if (objName.isEmpty() || objName.length() > 16 || !objName.matches("[A-Za-z0-9_.+\\-]+")) return ActionResult.fail("invalid objective name");
        Scoreboard sb = pf.server.getScoreboard();
        Objective o = sb.getObjective(objName);
        if (o == null) {
            if (!bool(a, "create")) return ActionResult.fail("objective " + objName + " does not exist");
            o = sb.addObjective(objName, ObjectiveCriteria.DUMMY, Component.literal(objName), ObjectiveCriteria.RenderType.INTEGER);
        }
        List<String> holders = new ArrayList<>();
        String holder = t(a, "holder");
        if (!holder.isEmpty()) holders.add(holder);
        else for (EntityRef r : ctx.targets()) holders.add(r.player() ? r.name() : r.uuid().toString());
        int value = (int) num(a, "value");
        for (String h : holders) {
            switch (t(a, "op")) {
                case "add" -> sb.getOrCreatePlayerScore(h, o).add(value);
                case "remove" -> sb.getOrCreatePlayerScore(h, o).add(-value);
                case "reset" -> sb.resetPlayerScore(h, o);
                default -> sb.getOrCreatePlayerScore(h, o).setScore(value);
            }
        }
        return ActionResult.ok(holders.size() + " score(s)", holders.size());
    }

    private ActionResult give(Map<String, Value> a, ActionContext ctx) throws Exception {
        Item item = ForgeQueries.item(t(a, "item"));
        if (item == null) return ActionResult.fail("unknown item");
        int count = Math.max(1, Math.min(6400, (int) num(a, "count")));
        String nbt = t(a, "nbt");
        CompoundTag tag = nbt.isEmpty() ? null : TagParser.parseTag(nbt);
        return eachPlayer(ctx, p -> {
            int left = count;
            while (left > 0) {
                ItemStack s = new ItemStack(item, Math.min(left, item.getMaxStackSize()));
                if (tag != null) s.setTag(tag.copy());
                left -= s.getCount();
                if (!p.getInventory().add(s)) p.drop(s, false);
            }
        });
    }

    private ActionResult clear(Map<String, Value> a, ActionContext ctx) {
        String id = t(a, "item");
        Item item = id.isEmpty() ? null : ForgeQueries.item(id);
        if (!id.isEmpty() && item == null) return ActionResult.fail("unknown item");
        int max = (int) num(a, "count");
        return eachPlayer(ctx, p -> {
            int left = max < 0 ? Integer.MAX_VALUE : max;
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s.isEmpty() || (item != null && s.getItem() != item)) continue;
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
            inv.setChanged();
        });
    }
}
