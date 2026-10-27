package csequencer.client;

import com.mojang.blaze3d.platform.InputConstants;
import csequencer.client.audio.ClientAudio;
import csequencer.client.gui.HubScreen;
import csequencer.client.gui.TaskManagerScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/** Client-only wiring. Loaded exclusively through DistExecutor on the physical client. */
public final class ClientSetup {
    public static final String CATEGORY = "key.categories.csequencer";
    public static final KeyMapping OPEN_HUB = new KeyMapping("key.csequencer.hub", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping OPEN_TASKS = new KeyMapping("key.csequencer.tasks", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping("key.csequencer.overlay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    public static final KeyMapping TOGGLE_AREAS = new KeyMapping("key.csequencer.areas", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY);

    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        ClientConfig.load();
        modBus.addListener(ClientSetup::onKeys);
        modBus.addListener(ClientSetup::onOverlays);
        modBus.addListener(Cinematics::onRegisterShaders);
        MinecraftForge.EVENT_BUS.register(ClientSetup.class);
        MinecraftForge.EVENT_BUS.register(HudLayer.class);
        MinecraftForge.EVENT_BUS.register(AreaView.class);
        MinecraftForge.EVENT_BUS.register(Cinematics.class);
        if (DevVisualTest.enabled()) MinecraftForge.EVENT_BUS.register(DevVisualTest.class);
    }

    private static void onKeys(RegisterKeyMappingsEvent e) {
        e.register(OPEN_HUB);
        e.register(OPEN_TASKS);
        e.register(TOGGLE_OVERLAY);
        e.register(TOGGLE_AREAS);
    }

    private static void onOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAbove(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id(), "sequence_overlay", SequenceOverlay.INSTANCE);
        e.registerAboveAll("hud_layer", HudLayer.INSTANCE);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (OPEN_HUB.consumeClick()) {
            if (mc.screen != null) continue;
            if (AreaView.selecting()) AreaView.confirm();
            else mc.setScreen(new HubScreen());
        }
        while (OPEN_TASKS.consumeClick()) {
            if (mc.screen != null) continue;
            if (AreaView.selecting()) AreaView.cancel();
            else mc.setScreen(new TaskManagerScreen(null));
        }
        while (TOGGLE_AREAS.consumeClick()) if (mc.screen == null && !AreaView.selecting()) AreaView.toggleViewer();
        while (TOGGLE_OVERLAY.consumeClick()) {
            ClientConfig.collapsed = !ClientConfig.collapsed;
            ClientConfig.save();
        }
    }

    /** Tracks the bottom of vanilla boss bars so the overlay never covers them. */
    @SubscribeEvent
    public static void onBossBar(CustomizeGuiOverlayEvent.BossEventProgress e) {
        SequenceOverlay.bossBottom = Math.max(SequenceOverlay.bossBottom, e.getY() + e.getIncrement());
    }

    @SubscribeEvent
    public static void onRenderPre(RenderGuiEvent.Pre e) { SequenceOverlay.bossBottom = 0; }

    @SubscribeEvent
    public static void onRenderPost(RenderGuiEvent.Post e) { Toasts.render(e.getGuiGraphics(), e.getWindow().getGuiScaledWidth()); }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post e) { Toasts.render(e.getGuiGraphics(), e.getScreen().width); }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) { ClientState.connected = true; }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        ClientAudio.stopAll();
        ClientNet.onLogout();
        ClientState.reset();
        SequenceOverlay.clear();
        HudLayer.clear();
        AreaView.clear();
        Cinematics.clear();
    }
}
