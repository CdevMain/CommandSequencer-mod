package csequencer.client;

import com.google.gson.JsonObject;
import csequencer.client.gui.Theme;
import csequencer.core.engine.Durations;
import csequencer.core.json.J;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.*;

/**
 * Screen elements driven by sequence pieces: popups, custom overlays (banner, bar, timer, text),
 * waypoint markers projected from world positions, and cinematic effects (fade, flash, shake,
 * letterbox, vignette). Everything shown here comes from server messages; elements expire on
 * their own and are cleared on logout.
 */
public final class HudLayer implements IGuiOverlay {
    public static final HudLayer INSTANCE = new HudLayer();

    private record Popup(String title, String message, String icon, int color, String position, long durationMs, long at) {}

    private static final class Elem {
        String id, kind;
        JsonObject data;
        long receivedAt, expiresAt;
        float shownValue = -1;
    }

    private static final Deque<Popup> popups = new ArrayDeque<>();
    private static final Map<String, Elem> elems = new LinkedHashMap<>();

    // Cinematic state
    private static int fadeColor;
    private static float fadeFrom, fadeTo;
    private static long fadeStart, fadeMs;
    private static int flashColor;
    private static long flashStart, flashMs;
    private static float letterFrom, letterTo;
    private static long letterStart, letterMs;
    private static float vigFrom, vigTo;
    private static long vigStart, vigMs;
    private static float shakeIntensity;
    private static long shakeStart, shakeMs;
    private static final Random RNG = new Random();

    // Camera matrices captured each frame for marker projection
    private static final Matrix4f VIEW = new Matrix4f(), PROJ = new Matrix4f();
    private static Vec3 camPos = Vec3.ZERO;
    private static boolean haveCamera;

    private HudLayer() {}

    // ── Messages ───────────────────────────────────────────────────────────

    public static void onMessage(JsonObject o) {
        String op = J.str(o, "op", ""), kind = J.str(o, "kind", ""), id = J.str(o, "id", "");
        long now = System.currentTimeMillis();
        switch (kind) {
            case "popup" -> {
                if (!op.equals("show")) return;
                if (popups.size() >= 6) popups.pollFirst();
                popups.addLast(new Popup(J.str(o, "title", ""), J.str(o, "message", ""), J.str(o, "icon", ""), J.i(o, "color", Theme.INFO),
                        J.str(o, "position", "right"), Math.max(500, J.l(o, "durationMs", 5000)), now));
                if (J.b(o, "sound", true)) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0f));
            }
            case "screen" -> screen(op, o, now);
            case "camera", "look", "controls", "effect", "anim" -> Cinematics.onMessage(kind, op, id, o);
            default -> {
                if (op.equals("hide")) { elems.remove(id); return; }
                Elem e = elems.computeIfAbsent(id, k -> new Elem());
                e.id = id;
                e.kind = kind;
                e.data = o;
                e.receivedAt = now;
                long rem = J.l(o, "remainingMs", -1);
                e.expiresAt = rem < 0 ? -1 : now + rem;
            }
        }
    }

    private static float level(float from, float to, long start, long ms, long now) {
        if (ms <= 0 || now >= start + ms) return to;
        float k = (now - start) / (float) ms;
        return from + (to - from) * Math.max(0, Math.min(1, k));
    }

    private static void screen(String op, JsonObject o, long now) {
        String effect = J.str(o, "effect", "");
        long t = Math.max(1, J.l(o, "transitionMs", 1000));
        float intensity = (float) J.d(o, "intensity", 1);
        int color = J.i(o, "color", 0xFF000000);
        if (op.equals("hide")) {
            long release = Math.max(1, J.l(o, "releaseMs", t));
            if (effect.startsWith("fade")) { fadeFrom = level(fadeFrom, fadeTo, fadeStart, fadeMs, now); fadeTo = 0; fadeStart = now; fadeMs = release; }
            else if (effect.startsWith("letterbox")) { letterFrom = level(letterFrom, letterTo, letterStart, letterMs, now); letterTo = 0; letterStart = now; letterMs = release; }
            else if (effect.startsWith("vignette")) { vigFrom = level(vigFrom, vigTo, vigStart, vigMs, now); vigTo = 0; vigStart = now; vigMs = release; }
            return;
        }
        switch (effect) {
            case "fade_out" -> { fadeColor = color; fadeFrom = level(fadeFrom, fadeTo, fadeStart, fadeMs, now); fadeTo = Math.min(1, intensity); fadeStart = now; fadeMs = t; }
            case "fade_in" -> { fadeColor = color; fadeFrom = Math.max(level(fadeFrom, fadeTo, fadeStart, fadeMs, now), 1); fadeTo = 0; fadeStart = now; fadeMs = t; }
            case "flash" -> { flashColor = color == 0xFF000000 ? 0xFFFFFFFF : color; flashStart = now; flashMs = t; }
            case "shake" -> { shakeIntensity = intensity; shakeStart = now; shakeMs = t; }
            case "letterbox_on" -> { letterFrom = level(letterFrom, letterTo, letterStart, letterMs, now); letterTo = intensity; letterStart = now; letterMs = t; }
            case "vignette_on" -> { vigFrom = level(vigFrom, vigTo, vigStart, vigMs, now); vigTo = intensity; vigStart = now; vigMs = t; }
            default -> {}
        }
    }

    public static void clear() {
        popups.clear();
        elems.clear();
        fadeFrom = fadeTo = letterFrom = letterTo = vigFrom = vigTo = 0;
        flashMs = shakeMs = 0;
    }

    // ── Events: camera capture and shake ───────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        VIEW.set(e.getPoseStack().last().pose());
        PROJ.set(e.getProjectionMatrix());
        camPos = e.getCamera().getPosition();
        haveCamera = true;
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        long now = System.currentTimeMillis();
        if (shakeMs <= 0 || now > shakeStart + shakeMs) return;
        float k = 1 - (now - shakeStart) / (float) shakeMs;
        float amp = shakeIntensity * 2.5f * k;
        e.setYaw(e.getYaw() + (RNG.nextFloat() - 0.5f) * amp);
        e.setPitch(e.getPitch() + (RNG.nextFloat() - 0.5f) * amp);
        e.setRoll(e.getRoll() + (RNG.nextFloat() - 0.5f) * amp * 0.6f);
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float pt, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        Font font = mc.font;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        drawVignette(g, w, h, level(vigFrom, vigTo, vigStart, vigMs, now));
        if (!mc.options.hideGui) {
            drawMarkers(g, font, w, h, now);
            AreaView.drawHud(g, font, w, h);
            drawOverlays(g, font, w, h, now);
            drawPopups(g, font, w, h, now);
        }
        drawLetterbox(g, w, h, level(letterFrom, letterTo, letterStart, letterMs, now));
        float fade = level(fadeFrom, fadeTo, fadeStart, fadeMs, now);
        if (fade > 0.001f) g.fill(0, 0, w, h, alpha(fadeColor, fade));
        if (flashMs > 0 && now < flashStart + flashMs) {
            float k = 1 - (now - flashStart) / (float) flashMs;
            g.fill(0, 0, w, h, alpha(flashColor, k * k));
        }
        g.pose().popPose();
    }

    private static int alpha(int rgb, float a) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF); }

    private void drawLetterbox(GuiGraphics g, int w, int h, float amount) {
        if (amount <= 0.001f) return;
        int bar = (int) (h * 0.11f * amount);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
    }

    private void drawVignette(GuiGraphics g, int w, int h, float amount) {
        if (amount <= 0.001f) return;
        int steps = 24, band = Math.max(4, Math.min(w, h) / 3);
        for (int i = 0; i < steps; i++) {
            float k = 1 - i / (float) steps;
            int a = (int) (Math.min(1, amount) * 150 * k * k / steps * 3);
            int c = Math.min(255, a) << 24;
            int d = band * i / steps;
            int d2 = band * (i + 1) / steps;
            g.fill(0, d, w, d2, c);
            g.fill(0, h - d2, w, h - d, c);
            g.fill(d, 0, d2, h, c);
            g.fill(w - d2, 0, w - d, h, c);
        }
    }

    // ── Popups ─────────────────────────────────────────────────────────────

    private void drawPopups(GuiGraphics g, Font font, int w, int h, long now) {
        popups.removeIf(p -> now - p.at > p.durationMs);
        Map<String, Integer> stackY = new HashMap<>();
        for (Popup p : popups) {
            boolean center = p.position.equals("center");
            int cw = center ? 220 : 170;
            List<FormattedCharSequence> lines = p.message.isEmpty() ? List.of() : font.split(Component.literal(p.message), cw - 34);
            if (lines.size() > 4) lines = lines.subList(0, 4);
            int ch = 22 + lines.size() * 10 + (p.title.isEmpty() ? -10 : 0);
            long age = now - p.at;
            float in = Math.min(1, age / 220f), out = Math.min(1, (p.durationMs - age) / 400f);
            float vis = Math.max(0, Math.min(in, out));
            int slide = (int) ((1 - easeOut(in)) * (cw + 12));
            int x, y;
            int baseY = switch (p.position) {
                case "top" -> Math.max(SequenceOverlay.lastBottom, SequenceOverlay.bossBottom) + 6;
                case "center" -> h / 2 - 40;
                default -> h / 4;
            };
            y = stackY.getOrDefault(p.position, baseY);
            switch (p.position) {
                case "left" -> x = 6 - slide;
                case "top", "center" -> { x = (w - cw) / 2; y -= (int) ((1 - easeOut(in)) * 10); }
                default -> x = w - cw - 6 + slide;
            }
            stackY.put(p.position, y + ch + 4);
            int a = (int) (vis * 235);
            if (a < 6) continue;
            int bg = (a << 24) | 0x0E121B;
            g.fill(x + 1, y, x + cw - 1, y + ch, bg);
            g.fill(x, y + 1, x + cw, y + ch - 1, bg);
            g.fill(x, y + 1, x + 3, y + ch - 1, (a << 24) | (p.color & 0xFFFFFF));
            g.fill(x + 7, y + 5, x + 25, y + 23, ((int) (a * 0.35f) << 24) | (p.color & 0xFFFFFF));
            g.pose().pushPose();
            g.pose().translate(x + 16, y + 9, 0);
            g.pose().scale(1.4f, 1.4f, 1);
            g.drawCenteredString(font, p.icon, 0, 0, (a << 24) | (p.color & 0xFFFFFF));
            g.pose().popPose();
            int tx = x + 30, ty = y + 5;
            if (!p.title.isEmpty()) {
                g.drawString(font, Theme.clip(font, p.title, cw - 36), tx, ty, (a << 24) | 0xFFFFFF, false);
                ty += 11;
            }
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, tx, ty, (a << 24) | 0xB8C0D0, false);
                ty += 10;
            }
            float left = 1 - age / (float) p.durationMs;
            g.fill(x + 3, y + ch - 2, x + 3 + (int) ((cw - 4) * Math.max(0, left)), y + ch - 1, ((int) (a * 0.8f) << 24) | (p.color & 0xFFFFFF));
        }
    }

    private static float easeOut(float t) { return 1 - (1 - t) * (1 - t); }

    // ── Custom overlays ────────────────────────────────────────────────────

    private void drawOverlays(GuiGraphics g, Font font, int w, int h, long now) {
        Map<String, Integer> stack = new HashMap<>();
        for (Iterator<Elem> it = elems.values().iterator(); it.hasNext(); ) {
            Elem e = it.next();
            if (e.expiresAt >= 0 && now > e.expiresAt) { it.remove(); continue; }
            if (!"overlay".equals(e.kind)) continue;
            JsonObject d = e.data;
            String style = J.str(d, "style", "banner"), pos = J.str(d, "position", "top");
            int color = J.i(d, "color", Theme.INFO);
            String title = J.str(d, "title", ""), sub = J.str(d, "subtitle", "");
            boolean hasBar = d.has("value");
            double value = J.d(d, "value", 0), max = J.d(d, "max", 100);
            long cd = d.has("countdownMs") ? Math.max(0, J.l(d, "countdownMs", 0) - (now - e.receivedAt)) : -1;
            float frac = hasBar ? (float) Math.max(0, Math.min(1, value / Math.max(1e-9, max))) : -1;
            if (hasBar) {
                e.shownValue = e.shownValue < 0 ? frac : e.shownValue + (frac - e.shownValue) * 0.2f;
            }
            int cw = switch (style) { case "timer" -> 110; case "text" -> Math.max(80, Math.min(260, Math.max(font.width(title), font.width(sub)) + 20)); default -> 200; };
            int ch = switch (style) {
                case "timer" -> 40;
                case "bar" -> 26;
                case "text" -> sub.isEmpty() ? 16 : 26;
                default -> 18 + (sub.isEmpty() ? 0 : 10) + (hasBar ? 9 : 0);
            };
            int x, y;
            int top = Math.max(SequenceOverlay.lastBottom, SequenceOverlay.bossBottom) + 4;
            switch (pos) {
                case "bottom" -> { x = (w - cw) / 2; y = h - 64 - ch - stack.getOrDefault(pos, 0); }
                case "left" -> { x = 6; y = 40 + stack.getOrDefault(pos, 0); }
                case "right" -> { x = w - cw - 6; y = 40 + stack.getOrDefault(pos, 0); }
                case "center" -> { x = (w - cw) / 2; y = h / 3 + stack.getOrDefault(pos, 0); }
                default -> { x = (w - cw) / 2; y = top + stack.getOrDefault(pos, 0); }
            }
            stack.merge(pos, ch + 4, Integer::sum);
            float fadeIn = Math.min(1, (now - e.receivedAt) / 250f);
            int a = (int) (Math.max(0.2f, fadeIn) * 220);
            int bg = (a << 24) | 0x0D1119;
            g.fill(x + 1, y, x + cw - 1, y + ch, bg);
            g.fill(x, y + 1, x + cw, y + ch - 1, bg);
            g.fill(x + 1, y, x + cw - 1, y + 1, (a << 24) | (color & 0xFFFFFF));
            switch (style) {
                case "timer" -> {
                    g.drawCenteredString(font, Theme.clip(font, title, cw - 8), x + cw / 2, y + 4, 0xFFC8CEDA);
                    String t = cd >= 0 ? Durations.clock((int) (cd / 50)) : hasBar ? Math.round(value) + "" : "--:--";
                    g.pose().pushPose();
                    g.pose().translate(x + cw / 2f, y + 16, 0);
                    g.pose().scale(2, 2, 1);
                    g.drawCenteredString(font, t, 0, 0, cd >= 0 && cd < 10000 && (now / 400) % 2 == 0 ? 0xFFFF5252 : 0xFFFFFFFF);
                    g.pose().popPose();
                }
                case "bar" -> {
                    g.drawString(font, Theme.clip(font, title, cw - 60), x + 6, y + 4, 0xFFFFFFFF, false);
                    String right = hasBar ? Math.round(value) + " / " + Math.round(max) : cd >= 0 ? Durations.clock((int) (cd / 50)) : "";
                    g.drawString(font, right, x + cw - 6 - font.width(right), y + 4, 0xFFC8CEDA, false);
                    if (hasBar) {
                        g.fill(x + 6, y + 15, x + cw - 6, y + 21, 0xFF262E40);
                        g.fill(x + 6, y + 15, x + 6 + (int) ((cw - 12) * e.shownValue), y + 21, 0xFF000000 | (color & 0xFFFFFF));
                        g.fill(x + 6, y + 15, x + 6 + (int) ((cw - 12) * e.shownValue), y + 16, 0x55FFFFFF);
                    }
                }
                case "text" -> {
                    g.drawCenteredString(font, title, x + cw / 2, y + 4, 0xFFFFFFFF);
                    if (!sub.isEmpty()) g.drawCenteredString(font, sub, x + cw / 2, y + 14, 0xFFB8C0D0);
                }
                default -> {
                    g.drawString(font, Theme.clip(font, title, cw - 60), x + 7, y + 5, 0xFFFFFFFF, false);
                    if (cd >= 0) {
                        String t = Durations.clock((int) (cd / 50));
                        g.drawString(font, t, x + cw - 7 - font.width(t), y + 5, cd < 10000 ? 0xFFFF8A80 : 0xFFFFD54F, false);
                    }
                    int yy = y + 15;
                    if (!sub.isEmpty()) { g.drawString(font, Theme.clip(font, sub, cw - 14), x + 7, yy, 0xFFB8C0D0, false); yy += 10; }
                    if (hasBar) {
                        g.fill(x + 7, yy + 1, x + cw - 7, yy + 5, 0xFF262E40);
                        g.fill(x + 7, yy + 1, x + 7 + (int) ((cw - 14) * e.shownValue), yy + 5, 0xFF000000 | (color & 0xFFFFFF));
                    }
                }
            }
        }
    }

    /** Projects a world position to GUI coordinates; null when behind the camera or unknown. */
    static float[] toScreen(double px, double py, double pz, int w, int h) {
        if (!haveCamera) return null;
        Vector4f v = new Vector4f((float) (px - camPos.x), (float) (py - camPos.y), (float) (pz - camPos.z), 1);
        VIEW.transform(v);
        PROJ.transform(v);
        if (v.w <= 0.05f) return null;
        float sx = (v.x / v.w + 1) / 2f * w, sy = (1 - v.y / v.w) / 2f * h;
        if (sx < -50 || sx > w + 50 || sy < -50 || sy > h + 50) return null;
        return new float[]{sx, sy};
    }

    // ── Waypoint markers ───────────────────────────────────────────────────

    private void drawMarkers(GuiGraphics g, Font font, int w, int h, long now) {
        Minecraft mc = Minecraft.getInstance();
        if (!haveCamera || mc.player == null) return;
        String dim = mc.player.level().dimension().location().toString();
        Vec3 eye = mc.player.getEyePosition();
        for (Elem e : elems.values()) {
            if (!"marker".equals(e.kind)) continue;
            JsonObject d = e.data;
            String mdim = J.str(d, "dimension", "");
            if (!mdim.isEmpty() && !mdim.equals(dim)) continue;
            JsonObject p = J.obj(d, "pos");
            if (p == null) continue;
            double px = J.d(p, "x", 0) + 0.5, py = J.d(p, "y", 0) + 0.5, pz = J.d(p, "z", 0) + 0.5;
            double dist = eye.distanceTo(new Vec3(px, py, pz));
            double hideWithin = J.d(d, "hideWithin", 0);
            if (hideWithin > 0 && dist < hideWithin) continue;
            Vector4f v = new Vector4f((float) (px - camPos.x), (float) (py - camPos.y), (float) (pz - camPos.z), 1);
            VIEW.transform(v);
            PROJ.transform(v);
            boolean behind = v.w <= 0.05f;
            float nx = v.x / Math.abs(v.w), ny = v.y / Math.abs(v.w);
            if (behind) { nx = -nx; ny = -ny; }
            float sx = (nx + 1) / 2f * w, sy = (1 - ny) / 2f * h;
            int margin = 18;
            boolean off = behind || sx < margin || sx > w - margin || sy < margin || sy > h - margin;
            if (off) {
                float cx = w / 2f, cy = h / 2f, dx = sx - cx, dy = sy - cy;
                if (behind && Math.abs(dx) < 1 && Math.abs(dy) < 1) dy = 1;
                float kx = dx == 0 ? Float.MAX_VALUE : (w / 2f - margin) / Math.abs(dx);
                float ky = dy == 0 ? Float.MAX_VALUE : (h / 2f - margin) / Math.abs(dy);
                float k = Math.min(kx, ky);
                sx = cx + dx * k;
                sy = cy + dy * k;
            }
            int color = J.i(d, "color", 0xFFFFD54F);
            int ix = (int) sx, iy = (int) sy;
            float pulse = 0.75f + 0.25f * (float) Math.sin(now / 250.0);
            g.fill(ix - 6, iy - 6, ix + 7, iy + 7, alpha(0x000000, 0.45f));
            g.fill(ix - 5, iy - 5, ix + 6, iy + 6, alpha(color, 0.35f * pulse));
            g.pose().pushPose();
            g.pose().translate(ix + 0.5f, iy - 3, 0);
            g.pose().scale(1.3f, 1.3f, 1);
            g.drawCenteredString(font, J.str(d, "icon", "◆"), 0, -1, 0xFF000000 | (color & 0xFFFFFF));
            g.pose().popPose();
            if (off) {
                String arrow = Math.abs(sx - w / 2f) > Math.abs(sy - h / 2f) ? (sx < w / 2f ? "◀" : "▶") : (sy < h / 2f ? "▲" : "▼");
                g.drawCenteredString(font, arrow, ix + (sx < w / 2f ? 12 : sx > w - margin - 1 ? -12 : 0), iy + (sy < h / 2f && Math.abs(sx - w / 2f) <= Math.abs(sy - h / 2f) ? 10 : sy > h / 2f && Math.abs(sx - w / 2f) <= Math.abs(sy - h / 2f) ? -16 : -4), 0xFFFFFFFF);
            }
            String label = J.str(d, "label", "");
            String text = label + (J.b(d, "showDistance", true) ? (label.isEmpty() ? "" : "  ") + Math.round(dist) + "m" : "");
            if (!text.isEmpty()) {
                int tw = font.width(text);
                int ty = iy + 9;
                if (ty > h - 12) ty = iy - 20;
                int tx = Math.max(2, Math.min(w - tw - 2, ix - tw / 2));
                g.fill(tx - 3, ty - 2, tx + tw + 3, ty + 9, alpha(0x000000, 0.55f));
                g.drawString(font, text, tx, ty, 0xFFFFFFFF, false);
            }
        }
    }
}
