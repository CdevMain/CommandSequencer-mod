package csequencer.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import csequencer.core.json.J;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Area triggers on the client: the "area viewer" (wireframe boxes of the trigger areas the player
 * may edit, sent by the server) and the in-world selection tool used to draw a new area.
 *
 * <p>Selection: left-click sets corner 1, right-click sets corner 2 (the block you look at, or your
 * feet when looking at air), the CSQ key (K) confirms and returns to the trigger screen, the Task
 * Manager key (J) cancels.
 */
public final class AreaView {
    private AreaView() {}

    public interface Done { void accept(int[] from, int[] to, String dim); }

    private record Box(String name, String project, boolean armed, String dim, int[] from, int[] to) {}

    private static boolean viewer;
    private static final List<Box> boxes = new ArrayList<>();
    private static long lastRequest;

    // selection state
    private static boolean selecting;
    private static int[] cornerA, cornerB;
    private static String selDim = "";
    private static String selName = "";
    private static Screen returnTo;
    private static Done onDone;
    private static long armClickAt;

    public static boolean viewerOn() { return viewer || selecting; }
    public static boolean selecting() { return selecting; }

    public static void toggleViewer() {
        viewer = !viewer;
        Toasts.info(viewer ? "Area viewer ON — trigger areas are shown in the world" : "Area viewer OFF");
        if (viewer) request();
    }

    public static void setViewer(boolean on) { if (viewer != on) toggleViewer(); }

    private static void request() {
        lastRequest = System.currentTimeMillis();
        ClientNet.send("areas.get", new JsonObject());
    }

    /** Server reply: the area triggers this player may edit. */
    public static void onMessage(JsonObject o) {
        boxes.clear();
        JsonArray a = J.arr(o, "areas");
        if (a == null) return;
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            JsonObject b = e.getAsJsonObject();
            int[] f = J.intArray(b, "from", 3), t = J.intArray(b, "to", 3);
            if (f == null || t == null) continue;
            boxes.add(new Box(J.str(b, "name", ""), J.str(b, "project", ""), J.b(b, "armed", true), J.str(b, "dim", ""), f, t));
        }
    }

    public static void clear() {
        boxes.clear();
        viewer = false;
        selecting = false;
        returnTo = null;
        onDone = null;
    }

    // ── Selection tool ─────────────────────────────────────────────────────

    /** Closes the current screen and lets the player draw a box in the world. */
    public static void beginSelection(Screen back, String name, int[] from, int[] to, Done done) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        selecting = true;
        returnTo = back;
        onDone = done;
        selName = name == null ? "" : name;
        cornerA = from == null ? null : from.clone();
        cornerB = to == null ? null : to.clone();
        selDim = mc.player.level().dimension().location().toString();
        armClickAt = System.currentTimeMillis() + 250; // ignore the click that closed the screen
        mc.setScreen(null);
        request();
    }

    /** K pressed while selecting. */
    public static void confirm() {
        Minecraft mc = Minecraft.getInstance();
        if (cornerA == null || cornerB == null) {
            Toasts.warn("Set both corners first (left-click and right-click a block)");
            return;
        }
        selecting = false;
        Done d = onDone;
        Screen back = returnTo;
        onDone = null;
        returnTo = null;
        mc.setScreen(back);
        if (d != null) d.accept(cornerA.clone(), cornerB.clone(), selDim);
    }

    /** J pressed while selecting. */
    public static void cancel() {
        Minecraft mc = Minecraft.getInstance();
        selecting = false;
        Screen back = returnTo;
        onDone = null;
        returnTo = null;
        mc.setScreen(back);
        Toasts.info("Area selection cancelled");
    }

    private static int[] aimed() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hr = mc.hitResult;
        BlockPos p = hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : mc.player.blockPosition();
        return new int[]{p.getX(), p.getY(), p.getZ()};
    }

    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered e) {
        if (!selecting || Minecraft.getInstance().player == null) return;
        if (!e.isAttack() && !e.isUseItem()) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (System.currentTimeMillis() < armClickAt) return;
        int[] p = aimed();
        if (e.isAttack()) cornerA = p;
        else cornerB = p;
        Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§b[CSQ]§r Corner " + (e.isAttack() ? "1" : "2") + " = " + p[0] + " " + p[1] + " " + p[2] + sizeText()), true);
    }

    private static String sizeText() {
        if (cornerA == null || cornerB == null) return "";
        return "  (" + (Math.abs(cornerA[0] - cornerB[0]) + 1) + "×" + (Math.abs(cornerA[1] - cornerB[1]) + 1) + "×" + (Math.abs(cornerA[2] - cornerB[2]) + 1) + ")";
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (viewerOn() && System.currentTimeMillis() - lastRequest > 3000) request();
    }

    // ── World rendering ────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !viewerOn()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String dim = mc.player.level().dimension().location().toString();
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Box b : boxes) {
            if (!b.dim.isEmpty() && !b.dim.equals(dim)) continue;
            AABB box = aabb(b.from, b.to);
            if (box.getCenter().distanceTo(cam) > 512) continue;
            boolean inside = box.contains(mc.player.position());
            float r = b.armed ? 0.25f : 0.6f, g = b.armed ? 0.85f : 0.6f, bl = b.armed ? 1f : 0.6f;
            if (inside) { r = 0.35f; g = 1f; bl = 0.45f; }
            LevelRenderer.renderLineBox(ps, lines, box, r, g, bl, 0.9f);
        }
        if (selecting) {
            int[] a = cornerA, b = cornerB;
            if (a != null || b != null) {
                int[] live = aimed();
                AABB box = aabb(a != null ? a : live, b != null ? b : live);
                LevelRenderer.renderLineBox(ps, lines, box.inflate(0.002), 1f, 0.8f, 0.2f, 1f);
            }
            int[] live = aimed();
            LevelRenderer.renderLineBox(ps, lines, aabb(live, live).inflate(0.004), 1f, 1f, 1f, 0.6f);
        }
        ps.popPose();
        buf.endBatch(RenderType.lines());
    }

    private static AABB aabb(int[] a, int[] b) {
        return new AABB(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]) + 1, Math.max(a[1], b[1]) + 1, Math.max(a[2], b[2]) + 1);
    }

    // ── HUD ────────────────────────────────────────────────────────────────

    /** Labels above each area and the selection help bar (called from the HUD layer). */
    static void drawHud(GuiGraphics g, Font font, int w, int h) {
        if (!viewerOn()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String dim = mc.player.level().dimension().location().toString();
        for (Box b : boxes) {
            if (!b.dim.isEmpty() && !b.dim.equals(dim)) continue;
            AABB box = aabb(b.from, b.to);
            Vec3 c = box.getCenter();
            if (c.distanceTo(mc.player.position()) > 96) continue;
            float[] s = HudLayer.toScreen(c.x, box.maxY + 0.4, c.z, w, h);
            if (s == null) continue;
            String label = "▣ " + (b.name.isEmpty() ? "Area" : b.name) + " → " + b.project + (b.armed ? "" : " (off)");
            int lw = font.width(label);
            g.fill((int) s[0] - lw / 2 - 3, (int) s[1] - 2, (int) s[0] + lw / 2 + 3, (int) s[1] + 10, 0x99000000);
            g.drawString(font, label, (int) s[0] - lw / 2, (int) s[1], b.armed ? 0xFF7FDBFF : 0xFFAAAAAA, false);
        }
        String top;
        if (selecting) {
            top = "Drawing area" + (selName.isEmpty() ? "" : " for '" + selName + "'") + ":  Left-click = corner 1  ·  Right-click = corner 2  ·  "
                    + key(ClientSetup.OPEN_HUB) + " = done  ·  " + key(ClientSetup.OPEN_TASKS) + " = cancel";
            String c = "1: " + fmt(cornerA) + "    2: " + fmt(cornerB) + sizeText();
            int tw = Math.max(font.width(top), font.width(c)) + 12;
            g.fill(w / 2 - tw / 2, h - 78, w / 2 + tw / 2, h - 52, 0xCC101418);
            g.fill(w / 2 - tw / 2, h - 78, w / 2 + tw / 2, h - 77, 0xFFFFC94D);
            g.drawCenteredString(font, top, w / 2, h - 74, 0xFFFFE08A);
            g.drawCenteredString(font, c, w / 2, h - 63, 0xFFDDDDDD);
        } else {
            top = "▣ Area viewer · " + boxes.size() + " area(s) · " + key(ClientSetup.TOGGLE_AREAS) + " to hide";
            g.drawString(font, top, 4, h - 12, 0xAA7FDBFF, true);
        }
    }

    private static String fmt(int[] p) { return p == null ? "—" : p[0] + " " + p[1] + " " + p[2]; }

    private static String key(net.minecraft.client.KeyMapping k) { return k.getTranslatedKeyMessage().getString(); }
}
