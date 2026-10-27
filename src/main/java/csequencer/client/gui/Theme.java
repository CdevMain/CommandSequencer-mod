package csequencer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared colours and drawing helpers for every CommandSequencer screen. */
public final class Theme {
    // Surfaces (dark → light)
    public static final int BG = 0xF20B0E15;
    public static final int SURFACE = 0xFF121722;
    public static final int SURFACE_2 = 0xFF181E2C;
    public static final int SURFACE_3 = 0xFF202839;
    public static final int BORDER = 0xFF262F43;
    public static final int BORDER_HI = 0xFF3A4762;
    // Text
    public static final int TEXT = 0xFFEDEFF5;
    public static final int TEXT_DIM = 0xFFA6AEC0;
    public static final int MUTED = 0xFF687189;
    // Accents
    public static final int ACCENT = 0xFFE94560;
    public static final int ACCENT_HI = 0xFFFF6680;
    public static final int OK = 0xFF66BB6A;
    public static final int WARN = 0xFFFFC857;
    public static final int ERR = 0xFFEF5350;
    public static final int INFO = 0xFF4FC3F7;

    // Legacy names used across screens
    public static final int PANEL = SURFACE;
    public static final int PANEL_2 = SURFACE_2;
    public static final int LINE = BORDER;
    public static final int HEADER = 0xFF0F131C;
    public static final int SUB = TEXT_DIM;
    public static final int SELECT = 0x664FC3F7;
    public static final int HOVER = 0x14FFFFFF;

    private Theme() {}

    public static int stateColor(String s) {
        return switch (s == null ? "" : s) {
            case "RUNNING" -> 0xFFE94560;
            case "WAITING" -> 0xFF4FC3F7;
            case "PAUSED" -> 0xFFFFB74D;
            case "QUEUED" -> 0xFFB39DDB;
            case "COMPLETED" -> 0xFF66BB6A;
            case "FAILED" -> 0xFFEF5350;
            case "CANCELLED", "CANCELLING", "COMPLETING" -> 0xFF9E9E9E;
            case "AVAILABLE" -> 0xFF607D8B;
            default -> 0xFFB0BEC5;
        };
    }

    public static String stateLabel(String s) {
        return switch (s == null ? "" : s) {
            case "RUNNING" -> "Running";
            case "WAITING" -> "Waiting";
            case "PAUSED" -> "Paused";
            case "QUEUED" -> "Queued";
            case "COMPLETED" -> "Completed";
            case "FAILED" -> "Failed";
            case "CANCELLED" -> "Cancelled";
            case "CANCELLING", "COMPLETING" -> "Finishing";
            case "AVAILABLE" -> "Available";
            default -> s;
        };
    }

    /** Panel with a 1px border and slightly cut corners. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) { card(g, x, y, w, h, SURFACE, BORDER); }

    public static void card(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x + 1, y, x + w - 1, y + h, border);
        g.fill(x, y + 1, x + w, y + h - 1, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** Small uppercase section title with a divider line. */
    public static void section(GuiGraphics g, Font f, String title, int x, int y, int w) {
        String t = title.toUpperCase(java.util.Locale.ROOT);
        g.drawString(f, t, x, y, MUTED, false);
        int tw = f.width(t) + 4;
        g.fill(x + tw, y + 4, x + w, y + 5, BORDER);
    }

    public static int badge(GuiGraphics g, Font f, String text, int x, int y, int color) {
        int w = f.width(text) + 8;
        g.fill(x + 1, y, x + w - 1, y + 11, (color & 0x00FFFFFF) | 0x33000000);
        g.fill(x, y + 1, x + w, y + 10, (color & 0x00FFFFFF) | 0x33000000);
        g.drawString(f, text, x + 4, y + 2, color, false);
        return w;
    }

    public static void dot(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 5, color);
        g.fill(x, y + 1, x + 5, y + 4, color);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, double frac, int color) {
        g.fill(x, y, x + w, y + h, 0xFF262E40);
        if (frac >= 0) g.fill(x, y, x + (int) (w * Math.max(0, Math.min(1, frac))), y + h, color);
    }

    public static String clip(Font f, String s, int w) {
        if (s == null) return "";
        if (w <= 0) return "";
        if (f.width(s) <= w) return s;
        return f.plainSubstrByWidth(s, Math.max(0, w - f.width("…"))) + "…";
    }

    public static String ago(long ms) {
        if (ms < 0) return "—";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s / 60) % 60) + "m";
    }

    /** Empty-state message centred in an area: big icon, title and hint. */
    public static void empty(GuiGraphics g, Font f, int cx, int cy, String icon, String title, String hint) {
        g.pose().pushPose();
        g.pose().translate(cx, cy - 18, 0);
        g.pose().scale(2, 2, 1);
        g.drawCenteredString(f, icon, 0, 0, MUTED);
        g.pose().popPose();
        g.drawCenteredString(f, title, cx, cy + 4, TEXT_DIM);
        if (hint != null && !hint.isEmpty()) g.drawCenteredString(f, hint, cx, cy + 16, MUTED);
    }

    public static int dim(int color, float k) {
        int a = color & 0xFF000000;
        int r = (int) (((color >> 16) & 0xFF) * k), gg = (int) (((color >> 8) & 0xFF) * k), b = (int) ((color & 0xFF) * k);
        return a | (r << 16) | (gg << 8) | b;
    }
}
