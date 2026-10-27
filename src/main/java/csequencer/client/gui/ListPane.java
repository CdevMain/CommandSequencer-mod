package csequencer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Supplier;

/** Scrollable, selectable list drawn inside a rectangle. Rows are rendered by a callback. */
public final class ListPane<T> {
    public interface RowRenderer<T> { void render(GuiGraphics g, T item, int x, int y, int w, int h, boolean selected, boolean hovered); }

    public int x, y, w, h;
    public final int rowH;
    private final Supplier<List<T>> items;
    private final RowRenderer<T> renderer;
    private int scroll;
    public int selected = -1;
    public String empty = "Nothing here yet.";

    public ListPane(int rowH, Supplier<List<T>> items, RowRenderer<T> renderer) {
        this.rowH = rowH;
        this.items = items;
        this.renderer = renderer;
    }

    public void bounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public T selectedItem() {
        List<T> l = items.get();
        return selected >= 0 && selected < l.size() ? l.get(selected) : null;
    }

    public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int mx, int my) {
        List<T> l = items.get();
        Theme.panel(g, x, y, w, h);
        if (l.isEmpty()) {
            Theme.empty(g, font, x + w / 2, y + h / 2 - 6, "○", empty, "");
            return;
        }
        int visible = Math.max(1, (h - 2) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, l.size() - visible)));
        if (selected >= l.size()) selected = l.size() - 1;
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (int i = scroll; i < Math.min(l.size(), scroll + visible + 1); i++) {
            int ry = y + 1 + (i - scroll) * rowH;
            boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + rowH && my < y + h;
            if (i == selected) {
                g.fill(x + 1, ry, x + w - 1, ry + rowH, Theme.SURFACE_3);
                g.fill(x + 1, ry, x + 3, ry + rowH, Theme.ACCENT);
            } else if (hov) g.fill(x + 1, ry, x + w - 1, ry + rowH, Theme.HOVER);
            if (i > scroll) g.fill(x + 6, ry, x + w - 6, ry + 1, 0x0FFFFFFF);
            renderer.render(g, l.get(i), x + 1, ry, w - 2, rowH, i == selected, hov);
        }
        g.disableScissor();
        if (l.size() > visible) {
            int th = Math.max(10, h * visible / l.size());
            int ty = y + (h - th) * scroll / Math.max(1, l.size() - visible);
            g.fill(x + w - 4, ty + 1, x + w - 2, ty + th - 1, Theme.BORDER_HI);
        }
    }

    /** Returns the clicked index or -1. Updates the selection. */
    public int click(double mx, double my) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return -1;
        int i = scroll + (int) ((my - y - 1) / rowH);
        if (i < 0 || i >= items.get().size()) return -1;
        selected = i;
        return i;
    }

    public boolean scroll(double mx, double my, double delta) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        scroll -= (int) Math.signum(delta) * 2;
        if (scroll < 0) scroll = 0;
        return true;
    }

    public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
}
