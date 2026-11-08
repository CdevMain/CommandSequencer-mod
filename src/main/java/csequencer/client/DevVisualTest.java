package csequencer.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.CommandSequencerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Development-only visual check, enabled with the system property {@code csq.devtest} (see build.gradle):
 * once in a world it shows each screen effect and camera mode, saves a screenshot of each
 * ({@code run/screenshots/csq_*.png}) and closes the game. Never active in a normal game.
 */
public final class DevVisualTest {
    private DevVisualTest() {}

    public static boolean enabled() { return System.getProperty("csq.devtest") != null; }

    private record Step(String name, Runnable setup, int waitTicks) {}

    private static final List<Step> steps = new ArrayList<>();
    private static int ticksInWorld, stepIndex = -1, stepTicks;

    private static JsonObject effect(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("effect", name);
        o.addProperty("intensity", 1.0);
        o.addProperty("fadeInMs", 0);
        o.addProperty("releaseMs", 0);
        return o;
    }

    private static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(v.x);
        a.add(v.y);
        a.add(v.z);
        return a;
    }

    private static void build() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        java.util.UUID me = mc.player.getUUID();
        if (server != null) server.execute(() -> {
            server.overworld().setDayTime(6000);
            server.overworld().setWeatherParameters(6000, 0, false, false);
            var sp = server.getPlayerList().getPlayer(me);
            if (sp != null) {
                sp.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), 45f, 10f);
            }
        });
        steps.add(new Step("setup", () -> {}, 40));
        String mode = String.valueOf(System.getProperty("csq.devtest"));
        if (mode.contains("ui")) {
            uiSteps(mc);
            if (!mode.contains("fx")) return;
        }
        steps.add(new Step("baseline", () -> {}, 20));
        for (String fx : new String[]{"dizzy", "sleep", "heat", "cold", "poison", "insanity", "invert", "melt", "grayscale", "blood"}) {
            steps.add(new Step("fx_" + fx, () -> Cinematics.onMessage("effect", "show", "dev:" + fx, effect(fx)), fx.equals("blood") ? 100 : 20));
            steps.add(new Step("clear_" + fx, () -> Cinematics.onMessage("effect", "hide", "dev:" + fx, effect(fx)), 3));
        }
        steps.add(new Step("cam_fixed", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("mode", "fixed");
            o.add("pos", vec(mc.player.position().add(4, 3, 4)));
            o.addProperty("look", "at_player");
            Cinematics.onMessage("camera", "show", "dev:cam", o);
        }, 20));
        steps.add(new Step("cam_orbit", () -> {
            JsonObject o = new JsonObject();
            o.addProperty("mode", "orbit");
            o.addProperty("look", "at_player");
            o.addProperty("radius", 5);
            o.addProperty("height", 2);
            o.addProperty("speed", 90);
            Cinematics.onMessage("camera", "show", "dev:cam", o);
        }, 30));
        steps.add(new Step("anim_insanity", () -> {
            JsonObject an = new JsonObject();
            an.addProperty("anim", "insanity");
            an.addProperty("intensity", 1.0);
            JsonArray ids = new JsonArray();
            ids.add(mc.player.getUUID().toString());
            an.add("players", ids);
            Cinematics.onMessage("anim", "show", "dev:anim", an);
            JsonObject o = new JsonObject();
            o.addProperty("mode", "fixed");
            o.add("pos", vec(mc.player.position().add(2.5, 1.6, 2.5)));
            o.addProperty("look", "at_player");
            Cinematics.onMessage("camera", "show", "dev:cam", o);
        }, 25));
        steps.add(new Step("cam_reset", () -> Cinematics.onMessage("camera", "hide", "dev:cam", new JsonObject()), 10));
    }

    /** Opens every screen once (with an example project in the editor) so each can be screenshotted. */
    private static void uiSteps(Minecraft mc) {
        var boss = csequencer.core.json.ProjectCodec.read(csequencer.core.json.ProjectCodec.write(csequencer.core.examples.Examples.bossFight())).project();
        steps.add(new Step("ui_hub", () -> mc.setScreen(new csequencer.client.gui.HubScreen()), 25));
        steps.add(new Step("ui_projects", () -> mc.setScreen(new csequencer.client.gui.ProjectBrowserScreen(null)), 40));
        steps.add(new Step("ui_editor", () -> mc.setScreen(csequencer.client.gui.TimelineEditorScreen.open(boss, null)), 30));
        steps.add(new Step("ui_project_settings", () -> mc.setScreen(new csequencer.client.gui.ProjectSettingsScreen(
                csequencer.client.gui.TimelineEditorScreen.current(), csequencer.client.gui.TimelineEditorScreen.current())), 25));
        steps.add(new Step("ui_library", () -> mc.setScreen(new csequencer.client.gui.PieceLibraryScreen(null, t -> {})), 25));
        steps.add(new Step("ui_library_selected", () -> {
            try {
                var f = csequencer.client.gui.PieceLibraryScreen.class.getDeclaredField("list");
                f.setAccessible(true);
                Object list = f.get(mc.screen);
                var sel = list.getClass().getDeclaredField("selected");
                sel.setAccessible(true);
                sel.setInt(list, 8);
            } catch (ReflectiveOperationException ex) {
                CommandSequencerMod.LOGGER.warn("[CSQ devtest] could not select a piece: {}", ex.toString());
            }
        }, 10));
        steps.add(new Step("ui_logic", () -> {
            JsonObject g = new JsonObject();
            for (var pc : boss.main.pieces) if (pc.params.has("condition") && pc.params.get("condition").isJsonObject()) { g = pc.params.getAsJsonObject("condition"); break; }
            mc.setScreen(new csequencer.client.gui.LogicGraphScreen(null, boss, "Example condition", g, x -> {}));
        }, 25));
        steps.add(new Step("ui_tasks", () -> mc.setScreen(new csequencer.client.gui.TaskManagerScreen(null)), 30));
        steps.add(new Step("ui_monitor", () -> mc.setScreen(new csequencer.client.gui.MonitorScreen(null)), 30));
        steps.add(new Step("ui_history", () -> mc.setScreen(new csequencer.client.gui.HistoryScreen(null)), 25));
        steps.add(new Step("ui_settings", () -> mc.setScreen(new csequencer.client.gui.SettingsScreen(null)), 25));
        steps.add(new Step("ui_close", () -> mc.setScreen(null), 5));
    }

    private static String pendingShot;
    private static boolean shotTaken;

    /** Screenshots must be taken right after a frame is rendered (the buffer is cleared before the next tick). */
    @SubscribeEvent
    public static void onRenderEnd(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || pendingShot == null) return;
        String name = pendingShot;
        pendingShot = null;
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "csq_" + name + ".png", mc.getMainRenderTarget(),
                msg -> CommandSequencerMod.LOGGER.info("[CSQ devtest] {}: {}", name, msg.getString()));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (steps.isEmpty() && mc.screen != null) return;
        if (++ticksInWorld < 160) return;
        if (steps.isEmpty()) build();
        if (pendingShot != null) return; // wait for the screenshot of the current step
        if (stepIndex < 0 || ++stepTicks >= steps.get(stepIndex).waitTicks) {
            if (stepIndex >= 0 && !shotTaken) {
                String name = steps.get(stepIndex).name;
                if (!name.startsWith("clear_") && !name.equals("setup") && !name.equals("ui_close")) { pendingShot = name; shotTaken = true; return; }
            }
            shotTaken = false;
            stepIndex++;
            stepTicks = 0;
            if (stepIndex >= steps.size()) {
                CommandSequencerMod.LOGGER.info("[CSQ devtest] done, active effects: {}", Cinematics.activeEffects());
                mc.tell(() -> mc.tell(mc::stop));
                return;
            }
            CommandSequencerMod.LOGGER.info("[CSQ devtest] step {}", steps.get(stepIndex).name);
            steps.get(stepIndex).setup.run();
        }
    }
}
