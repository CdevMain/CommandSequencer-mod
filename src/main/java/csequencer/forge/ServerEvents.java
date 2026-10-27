package csequencer.forge;

import csequencer.core.engine.GameEvent;
import csequencer.core.value.EntityRef;
import csequencer.core.value.Value;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/** Bridges Forge server events into the engine. Only server-side instances are forwarded. */
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) { CSQServer.start(e.getServer()); }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) { CSQServer.stop(); }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        CSQServer s = CSQServer.get();
        if (s != null) s.tick();
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) { CSQCommands.register(e.getDispatcher()); }

    private static void post(String type, ServerPlayer p, Entity other, Map<String, Value> data) {
        CSQServer s = CSQServer.get();
        if (s == null) return;
        Entity at = p != null ? p : other;
        String dim = at == null ? "" : ForgePlatform.dim(at.level());
        EntityRef pr = p == null ? null : ForgePlatform.ref(p);
        EntityRef er = other == null ? null : ForgePlatform.ref(other);
        s.engine().post(GameEvent.ofEntity(type, pr, er, dim, at == null ? null : ForgePlatform.vec(at.position()), data));
    }

    private static Map<String, Value> data(Object... kv) {
        Map<String, Value> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1] instanceof Value v ? v : Value.of(String.valueOf(kv[i + 1])));
        return m;
    }

    private static String id(ResourceLocation rl) { return rl == null ? "" : rl.toString(); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        CSQServer s = CSQServer.get();
        if (s == null) return;
        s.engine().onPlayerJoin(ForgePlatform.ref(p));
        post("player.join", p, null, Map.of());
        ServerNet.sendHello(s, p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        CSQServer s = CSQServer.get();
        if (s == null) return;
        post("player.leave", p, null, Map.of());
        s.engine().onPlayerLeave(ForgePlatform.ref(p));
        ServerNet.onLogout(p.getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) post("player.respawn", p, null, Map.of());
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p)
            post("player.change_dimension", p, null, data("from", e.getFrom().location().toString(), "to", e.getTo().location().toString()));
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity().level().isClientSide()) return;
        Entity killer = e.getSource().getEntity();
        String cause = e.getSource().getMsgId();
        Value killerV = killer == null ? Value.NULL : Value.of(ForgePlatform.ref(killer));
        if (e.getEntity() instanceof ServerPlayer p) {
            post("player.death", p, killer, data("killer", killerV, "cause", cause));
        } else {
            ServerPlayer kp = killer instanceof ServerPlayer x ? x : null;
            CSQServer s = CSQServer.get();
            if (s == null) return;
            Entity dead = e.getEntity();
            s.engine().post(GameEvent.ofEntity("entity.death", kp == null ? null : ForgePlatform.ref(kp), ForgePlatform.ref(dead),
                    ForgePlatform.dim(dead.level()), ForgePlatform.vec(dead.position()),
                    data("killer", killerV, "entityType", id(ForgeRegistries.ENTITY_TYPES.getKey(dead.getType())))));
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        var pos = e.getPos();
        post("player.interact_block", p, null, data("block", id(ForgeRegistries.BLOCKS.getKey(e.getLevel().getBlockState(pos).getBlock())),
                "blockPos", pos.getX() + " " + pos.getY() + " " + pos.getZ(), "hand", "main"));
    }

    @SubscribeEvent
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        post("player.interact_entity", p, e.getTarget(), data("entityType", id(ForgeRegistries.ENTITY_TYPES.getKey(e.getTarget().getType()))));
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        post("player.use_item", p, null, data("item", id(ForgeRegistries.ITEMS.getKey(e.getItemStack().getItem()))));
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        post("player.attack_entity", p, e.getTarget(), data("entityType", id(ForgeRegistries.ENTITY_TYPES.getKey(e.getTarget().getType()))));
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel)) return;
        var pos = e.getPos();
        post("player.break_block", p, null, data("block", id(ForgeRegistries.BLOCKS.getKey(e.getState().getBlock())), "blockPos", pos.getX() + " " + pos.getY() + " " + pos.getZ()));
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel)) return;
        var pos = e.getPos();
        post("player.place_block", p, null, data("block", id(ForgeRegistries.BLOCKS.getKey(e.getPlacedBlock().getBlock())), "blockPos", pos.getX() + " " + pos.getY() + " " + pos.getZ()));
    }
}
