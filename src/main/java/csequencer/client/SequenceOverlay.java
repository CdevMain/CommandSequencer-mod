package csequencer.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.core.engine.Durations;
import csequencer.core.json.J;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-of-screen sequence overlay. Shows only server-confirmed data: between updates it only
 * advances the progress/countdown of RUNNING sequences, bounded to a few ticks past the last
 * confirmed value, and marks the display as stale when updates stop arriving.
 */
public final class SequenceOverlay implements IGuiOverlay {
    public static final SequenceOverlay INSTANCE = new SequenceOverlay();
    private static final long STALE_MS = 3000;
    /** Max ticks the display may run ahead of the last confirmed position. */
    private static final int MAX_EXTRAPOLATION = 10;

    private static final class Anim {
        String phase = "", state = "";
        long changedAt, firstSeen;
        double shownProgress;
    }

    private static final Map<String, Anim> anims = new HashMap<>();
    /** Bottom edge of vanilla boss bars this frame (updated by ClientEvents). */
    public static int bossBottom;
    /** Bottom edge (screen pixels) of what this overlay drew this frame; custom HUD overlays stack below it. */
    public static int lastBottom;

    private SequenceOverlay() {}

    static void onUpdate(JsonObject o) {
        long now = System.currentTimeMillis();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonElement el : J.arr(o, "entries")) {
            JsonObject e = el.getAsJsonObject();
            String id = J.str(e, "task", "");
            seen.add(id);
            Anim a = anims.computeIfAbsent(id, k -> { Anim x = new Anim(); x.firstSeen = now; x.changedAt = now; return x; });
            String phase = J.str(e, "phase", ""), state = J.str(e, "state", "");
            if (!phase.equals(a.phase) || !state.equals(a.state)) {
                a.changedAt = now;
                a.phase = phase;
                a.state = state;
            }
        }
        anims.keySet().retainAll(seen);
    }

    public static void clear() { anims.clear(); }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        lastBottom = 0;
        if (!ClientConfig.overlayEnabled || mc.options.hideGui || mc.player == null) return;
        List<JsonObject> entries = new ArrayList<>();
        for (JsonElement el : J.arr(ClientState.overlay, "entries")) entries.add(el.getAsJsonObject());
        if (entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean stale = ClientState.age(ClientState.overlayAt) > STALE_MS;
        List<JsonObject> visible = new ArrayList<>();
        for (JsonObject e : entries) if (shouldShow(e, now)) visible.add(e);
        if (visible.isEmpty()) return;
        if (visible.size() > ClientConfig.maxEntries) visible = visible.subList(0, ClientConfig.maxEntries);

        float scale = (float) ClientConfig.scale;
        int boxW = 230;
        int y0 = Math.max(ClientConfig.offsetY, bossBottom + 2);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        int sw = (int) (screenW / scale);
        int x = switch (ClientConfig.position) {
            case TOP_LEFT -> 4;
            case TOP_RIGHT -> sw - boxW - 4;
            default -> (sw - boxW) / 2;
        };
        int y = (int) (y0 / scale);
        Font font = mc.font;
        for (int i = 0; i < visible.size(); i++) {
            JsonObject e = visible.get(i);
            if (ClientConfig.collapsed || i > 0) y += compact(g, font, e, x, y, boxW, now, stale) + 2;
            else y += full(g, font, e, x, y, boxW, now, stale) + 3;
        }
        lastBottom = (int) (y * scale);
        int hidden = entries.size() - visible.size();
        if (hidden > 0 && !ClientConfig.collapsed) {
            String more = "+" + hidden + " more sequence" + (hidden > 1 ? "s" : "");
            g.drawString(font, more, x + boxW - font.width(more) - 2, y, alpha(0xAAAAAA, 0.8f), true);
        }
        g.pose().popPose();
    }

    private boolean shouldShow(JsonObject e, long now) {
        Anim a = anims.get(J.str(e, "task", ""));
        if (a == null) return true;
        String state = J.str(e, "state", "");
        boolean terminal = state.equals("COMPLETED") || state.equals("FAILED") || state.equals("CANCELLED");
        if (terminal) return true;
        if (ClientConfig.alwaysVisible || J.b(e, "keepVisible", true)) return true;
        if (J.b(e, "preAnnounce", false)) return true;
        return now - a.changedAt < ClientConfig.displaySeconds * 1000L;
    }

    /** Position, extrapolated only for RUNNING sequences and only a few ticks past confirmation. */
    private static int position(JsonObject e, long now, boolean stale) {
        int pos = J.i(e, "position", 0);
        if (stale || !"RUNNING".equals(J.str(e, "state", ""))) return pos;
        int ahead = (int) Math.min(MAX_EXTRAPOLATION, ClientState.age(ClientState.overlayAt) / 50);
        int len = J.i(e, "length", 0);
        return len > 0 ? Math.min(len, pos + ahead) : pos + ahead;
    }

    private int full(GuiGraphics g, Font font, JsonObject e, int x, int y, int w, long now, boolean stale) {
        String id = J.str(e, "task", "");
        Anim a = anims.get(id);
        float fade = 1f;
        if (ClientConfig.animations && a != null) fade = Math.min(1f, (now - a.firstSeen) / 300f);
        float op = (float) ClientConfig.opacity * fade;
        String state = J.str(e, "state", "");
        boolean personal = J.b(e, "personal", false);
        int accent = stateColor(state);
        int h = 54;
        boolean hasAudio = !J.str(e, "audio", "").isEmpty();
        boolean hasNext = !J.str(e, "next", "").isEmpty();
        if (hasAudio) h += 10;
        if (!hasNext) h -= 10;
        g.fill(x, y, x + w, y + h, alpha(0x0C1018, op * 0.92f));
        g.fill(x, y, x + 2, y + h, alpha(personal ? accent : 0x5A6378, op));
        g.fill(x, y, x + w, y + 1, alpha(accent, op * 0.7f));

        int tx = x + 7, ty = y + 4;
        g.drawString(font, "COMMAND SEQUENCER", tx, ty, alpha(0x8892A6, op), false);
        String badge = "[" + badge(state, e) + "]";
        String scope = personal ? "YOU" : "SERVER";
        g.drawString(font, scope, x + w - font.width(scope) - 6, ty, alpha(personal ? 0xFFD54F : 0x6B7385, op), false);
        ty += 11;

        float phaseFade = 1f;
        if (ClientConfig.animations && a != null) phaseFade = Math.min(1f, (now - a.changedAt) / 400f);
        String phase = J.str(e, "phase", "");
        String title = J.str(e, "name", "?") + (phase.isEmpty() ? "" : " — " + phase);
        g.drawString(font, badge, tx, ty, alpha(accent, op), false);
        int bx = tx + font.width(badge) + 4;
        int slide = ClientConfig.animations ? (int) ((1 - phaseFade) * 6) : 0;
        g.drawString(font, font.plainSubstrByWidth(title, w - (bx - x) - 6), bx, ty - slide, alpha(0xFFFFFF, op * Math.max(0.25f, phaseFade)), false);
        ty += 11;

        String detail = statusLine(e, stale);
        g.drawString(font, font.plainSubstrByWidth(detail, w - 14), tx, ty, alpha(0xC8CEDA, op), false);
        ty += 10;
        if (hasNext) {
            int ttn = J.i(e, "ticksToNext", -1);
            if (ttn >= 0 && !stale && "RUNNING".equals(state)) ttn = Math.max(0, ttn - (int) Math.min(MAX_EXTRAPOLATION, ClientState.age(ClientState.overlayAt) / 50));
            String next = "Next: " + J.str(e, "next", "") + (ttn >= 0 ? " · " + Durations.clock(ttn) : "");
            boolean pre = J.b(e, "preAnnounce", false);
            g.drawString(font, font.plainSubstrByWidth(next, w - 14), tx, ty, alpha(pre ? 0xFFD54F : 0x9EA7B8, op), false);
            ty += 10;
        }
        if (hasAudio) {
            g.drawString(font, font.plainSubstrByWidth("♪ " + J.str(e, "audio", ""), w - 14), tx, ty, alpha(0xCE93D8, op), false);
            ty += 10;
        }
        // Progress bar
        double prog = J.d(e, "progress", -1);
        int len = J.i(e, "length", 0);
        if (len > 0 && prog >= 0) prog = Math.min(1, position(e, now, stale) / (double) len);
        int barX = tx, barW = w - 50, barY = ty + 2;
        g.fill(barX, barY, barX + barW, barY + 4, alpha(0x2A3140, op));
        if (prog >= 0) {
            if (a != null) {
                a.shownProgress = ClientConfig.animations ? a.shownProgress + (prog - a.shownProgress) * 0.25 : prog;
                if (Math.abs(a.shownProgress - prog) < 0.001) a.shownProgress = prog;
            }
            double shown = a == null ? prog : a.shownProgress;
            g.fill(barX, barY, barX + (int) (barW * shown), barY + 4, alpha(accent, op));
            String pct = Math.round(prog * 100) + "%";
            g.drawString(font, pct, barX + barW + 6, barY - 2, alpha(0xFFFFFF, op), false);
        } else {
            g.drawString(font, "—", barX + barW + 6, barY - 2, alpha(0x9EA7B8, op), false);
        }
        return h;
    }

    private int compact(GuiGraphics g, Font font, JsonObject e, int x, int y, int w, long now, boolean stale) {
        float op = (float) ClientConfig.opacity;
        String state = J.str(e, "state", "");
        int accent = stateColor(state);
        int h = 13;
        g.fill(x, y, x + w, y + h, alpha(0x0C1018, op * 0.85f));
        g.fill(x, y, x + 2, y + h, alpha(J.b(e, "personal", false) ? accent : 0x5A6378, op));
        String phase = J.str(e, "phase", "");
        String txt = "[" + badge(state, e) + "] " + J.str(e, "name", "") + (phase.isEmpty() ? "" : " · " + phase);
        g.drawString(font, font.plainSubstrByWidth(txt, w - 50), x + 6, y + 3, alpha(0xE0E4EC, op), false);
        double prog = J.d(e, "progress", -1);
        int len = J.i(e, "length", 0);
        if (len > 0 && prog >= 0) prog = Math.min(1, position(e, now, stale) / (double) len);
        if (prog >= 0) {
            int bx = x + w - 40;
            g.fill(bx, y + 5, bx + 34, y + 8, alpha(0x2A3140, op));
            g.fill(bx, y + 5, bx + (int) (34 * prog), y + 8, alpha(accent, op));
        }
        return h;
    }

    private static String statusLine(JsonObject e, boolean stale) {
        if (stale) return "⚠ No update from server — status may be outdated";
        String state = J.str(e, "state", "");
        return switch (state) {
            case "PAUSED" -> "Paused" + (J.str(e, "paused", "").isEmpty() ? "" : " (" + J.str(e, "paused", "").toLowerCase() + ")");
            case "WAITING" -> "Waiting" + (J.str(e, "waiting", "").isEmpty() ? "" : ": " + J.str(e, "waiting", ""));
            case "COMPLETED" -> "Finished" + (J.str(e, "result", "").isEmpty() ? "" : " — " + J.str(e, "result", ""));
            case "FAILED" -> "Failed: " + J.str(e, "result", "");
            case "CANCELLED" -> "Cancelled" + (J.str(e, "result", "").isEmpty() ? "" : " — " + J.str(e, "result", ""));
            case "COMPLETING", "CANCELLING" -> "Finishing…";
            default -> "Current: " + (J.str(e, "phase", "").isEmpty() ? "running" : J.str(e, "phase", ""));
        };
    }

    private static String badge(String state, JsonObject e) {
        return switch (state) {
            case "RUNNING" -> "LIVE";
            case "PAUSED" -> "PAUSED";
            case "WAITING" -> "WAIT";
            case "COMPLETED" -> "DONE";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "STOPPED";
            default -> state;
        };
    }

    static int stateColor(String state) {
        return switch (state) {
            case "RUNNING" -> 0xE94560;
            case "PAUSED" -> 0xFFB74D;
            case "WAITING" -> 0x4FC3F7;
            case "COMPLETED" -> 0x81C784;
            case "FAILED" -> 0xE53935;
            case "CANCELLED" -> 0x9E9E9E;
            default -> 0xB0BEC5;
        };
    }

    private static int alpha(int rgb, float a) {
        int al = Math.max(4, Math.min(255, (int) (a * 255)));
        return (al << 24) | (rgb & 0xFFFFFF);
    }
}
