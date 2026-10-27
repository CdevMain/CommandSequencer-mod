package csequencer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Right-click / dropdown menu drawn on top of a {@link CsqScreen}. */
public final class PopupMenu {
    public record Item(String label, String hint, boolean enabled, Runnable action, int color) {}

    private final List<Item> items = new ArrayList<>();
    private String title;
    int x, y, w, h;
    private static final int ROW = 13;

    public PopupMenu title(String t) { this.title = t; return this; }

    public PopupMenu add(String label, Runnable action) { return add(label, "", true, action); }

    public PopupMenu add(String label, String hint, boolean enabled, Runnable action) {
        items.add(new Item(label, hint == null ? "" : hint, enabled, action, Theme.TEXT));
        return this;
    }

    public PopupMenu danger(String label, String hint, Runnable action) {
        items.add(new Item(label, hint == null ? "" : hint, true, action, 0xFFFF8A8A));
        return this;
    }

    public PopupMenu separator() {
        items.add(null);
        return this;
    }

    public boolean isEmpty() { return items.isEmpty(); }

    void layout(Font f, int px, int py, int screenW, int screenH) {
        int maxW = title == null ? 60 : f.width(title) + 12;
        for (Item i : items) if (i != null) maxW = Math.max(maxW, f.width(i.label) + (i.hint.isEmpty() ? 0 : f.width(i.hint) + 16) + 18);
        w = maxW;
        h = 4 + (title == null ? 0 : 12);
        for (Item i : items) h += i == null ? 5 : ROW;
        x = Math.max(2, Math.min(px, screenW - w - 2));
        y = Math.max(2, Math.min(py, screenH - h - 2));
    }

    void render(GuiGraphics g, Font f, int mx, int my) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x66000000);
        Theme.card(g, x, y, w, h, Theme.SURFACE_2, Theme.BORDER_HI);
        int cy = y + 2;
        if (title != null) {
            g.drawString(f, title, x + 6, cy + 2, Theme.MUTED, false);
            cy += 12;
        }
        for (Item i : items) {
            if (i == null) {
                g.fill(x + 4, cy + 2, x + w - 4, cy + 3, Theme.BORDER);
                cy += 5;
                continue;
            }
            boolean hov = i.enabled && mx >= x && mx < x + w && my >= cy && my < cy + ROW;
            if (hov) g.fill(x + 1, cy, x + w - 1, cy + ROW, 0x334FC3F7);
            g.drawString(f, i.label, x + 8, cy + 3, i.enabled ? i.color : Theme.MUTED, false);
            if (!i.hint.isEmpty()) g.drawString(f, i.hint, x + w - f.width(i.hint) - 6, cy + 3, Theme.MUTED, false);
            cy += ROW;
        }
        g.pose().popPose();
    }

    /** Returns the clicked item action (or null) — the caller closes the menu either way. */
    Runnable click(double mx, double my) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return null;
        int cy = y + 2 + (title == null ? 0 : 12);
        for (Item i : items) {
            int rh = i == null ? 5 : ROW;
            if (i != null && i.enabled && my >= cy && my < cy + rh) return i.action;
            cy += rh;
        }
        return () -> {};
    }

    boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
}
