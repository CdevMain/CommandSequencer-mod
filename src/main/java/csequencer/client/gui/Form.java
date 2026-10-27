package csequencer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Vertical, scrollable form inside a rectangle of a {@link CsqScreen}. Rows hold section titles,
 * labels, wrapped hints and themed widgets; widgets outside the visible area are hidden.
 */
public final class Form {
    private enum Kind { LABEL, SECTION, HINT, WIDGETS, GAP }

    private record Row(Kind kind, String text, int color, List<AbstractWidget> widgets, List<Integer> xs, int h) {}

    private final CsqScreen screen;
    public int x, y, w, h;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private int contentH;
    public String title;

    public Form(CsqScreen screen) { this.screen = screen; }

    public void bounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public void clear() {
        for (Row r : rows) for (AbstractWidget wd : r.widgets) screen.removeWidget2(wd);
        rows.clear();
        contentH = 0;
    }

    private int top() { return y + (title == null ? 0 : 16); }

    private int innerH() { return h - (title == null ? 0 : 16); }

    public int width() { return w - 14; }

    public Font font() { return screen.font(); }

    public void label(String text, int color) { add(new Row(Kind.LABEL, text, color, List.of(), List.of(), 11)); }

    public void label(String text) { add(new Row(Kind.LABEL, text, Theme.TEXT_DIM, List.of(), List.of(), 11)); }

    /** Small uppercase section heading with a divider. */
    public void section(String text) {
        if (!rows.isEmpty()) gap();
        add(new Row(Kind.SECTION, text, Theme.MUTED, List.of(), List.of(), 13));
    }

    /** Wrapped, dim help text. */
    public void hint(String text) {
        for (FormattedCharSequence line : font().split(Component.literal(text), width())) {
            StringBuilder sb = new StringBuilder();
            line.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
            add(new Row(Kind.HINT, sb.toString(), Theme.MUTED, List.of(), List.of(), 10));
        }
    }

    public void gap() { add(new Row(Kind.GAP, "", 0, List.of(), List.of(), 5)); }

    /** One row of widgets placed left to right. */
    public void row(AbstractWidget... ws) {
        List<Integer> xs = new ArrayList<>();
        int cx = 0;
        for (AbstractWidget wd : ws) {
            xs.add(cx);
            cx += wd.getWidth() + 3;
            screen.addWidget2(wd);
        }
        add(new Row(Kind.WIDGETS, null, 0, List.of(ws), xs, 20));
    }

    private void add(Row r) {
        rows.add(r);
        contentH += r.h;
        layout();
    }

    public EditBox text(String hint, String value, int width, Consumer<String> onChange) {
        UiTextField b = new UiTextField(screen.font(), 0, 0, width, 17, hint);
        b.setValue(value == null ? "" : value);
        b.setResponder(onChange);
        return b;
    }

    public Button button(String label, int width, Runnable action) {
        return UiButton.of(label, UiButton.Style.DEFAULT, 0, 0, width, 17, action);
    }

    public Button primary(String label, int width, Runnable action) {
        return UiButton.of(label, UiButton.Style.PRIMARY, 0, 0, width, 17, action);
    }

    public Button danger(String label, int width, Runnable action) {
        return UiButton.of(label, UiButton.Style.DANGER, 0, 0, width, 17, action);
    }

    public Button button(Supplier<String> label, int width, Runnable action) {
        UiButton[] holder = new UiButton[1];
        holder[0] = UiButton.of(label.get(), UiButton.Style.DEFAULT, 0, 0, width, 17, () -> {
            action.run();
            holder[0].setLabel(label.get());
        });
        return holder[0];
    }

    /** Toggle switch styled as a chip. */
    public Button toggle(String label, boolean initial, int width, Consumer<Boolean> onChange) {
        boolean[] v = {initial};
        UiButton[] holder = new UiButton[1];
        holder[0] = UiButton.of((v[0] ? "● " : "○ ") + label, UiButton.Style.CHIP, 0, 0, width, 17, () -> {
            v[0] = !v[0];
            holder[0].selected(v[0]).setLabel((v[0] ? "● " : "○ ") + label);
            onChange.accept(v[0]);
        });
        holder[0].selected(v[0]).accent(Theme.OK);
        return holder[0];
    }

    /** Dropdown: shows "prefix value ▾"; clicking opens a menu listing every option. */
    public Button cycle(String prefix, List<String> options, String current, int width, Consumer<String> onChange) {
        String[] cur = {current == null || current.isEmpty() ? (options.isEmpty() ? "" : options.get(0)) : current};
        for (String o : options) if (o.equalsIgnoreCase(cur[0])) cur[0] = o;
        UiButton[] holder = new UiButton[1];
        holder[0] = UiButton.of(prefix + pretty(cur[0]) + " ▾", UiButton.Style.DEFAULT, 0, 0, width, 17, () -> {
            PopupMenu m = new PopupMenu();
            for (String o : options) m.add((o.equalsIgnoreCase(cur[0]) ? "✔ " : "   ") + pretty(o), () -> {
                cur[0] = o;
                holder[0].setLabel(prefix + pretty(o) + " ▾");
                onChange.accept(o);
            });
            screen.openMenu(m, holder[0].getX(), holder[0].getY() + 18);
        });
        return holder[0];
    }

    /** "STOP_BRANCH" → "Stop branch", "each_player" → "Each player". */
    public static String pretty(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.contains(":") || s.contains(".") || s.contains(" ")) return s;
        String t = s.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    public void layout() {
        int max = Math.max(0, contentH - innerH() + 6);
        scroll = Math.max(0, Math.min(scroll, max));
        int cy = top() + 4 - scroll;
        for (Row r : rows) {
            for (int i = 0; i < r.widgets.size(); i++) {
                AbstractWidget wd = r.widgets.get(i);
                wd.setX(x + 6 + r.xs.get(i));
                wd.setY(cy + 1);
                wd.visible = cy >= top() && cy + r.h <= y + h;
            }
            cy += r.h;
        }
    }

    public void render(GuiGraphics g) {
        Theme.panel(g, x, y, w, h);
        if (title != null) {
            g.fill(x + 1, y + 1, x + w - 1, y + 15, Theme.SURFACE_2);
            g.drawString(screen.font(), Theme.clip(screen.font(), title, w - 12), x + 6, y + 4, Theme.TEXT, false);
            g.fill(x + 1, y + 15, x + w - 1, y + 16, Theme.BORDER);
        }
        int cy = top() + 4 - scroll;
        g.enableScissor(x + 1, top() + 1, x + w - 1, y + h - 1);
        Font f = screen.font();
        for (Row r : rows) {
            boolean vis = cy + r.h > top() && cy < y + h;
            if (vis) switch (r.kind) {
                case LABEL -> g.drawString(f, Theme.clip(f, r.text, width()), x + 6, cy + 2, r.color, false);
                case HINT -> g.drawString(f, r.text, x + 6, cy + 1, r.color, false);
                case SECTION -> Theme.section(g, f, r.text, x + 6, cy + 3, x + w - 8 - (x + 6));
                default -> {}
            }
            cy += r.h;
        }
        g.disableScissor();
        if (contentH > innerH()) {
            int ih = innerH();
            int th = Math.max(12, ih * ih / contentH);
            int ty = top() + (ih - th) * scroll / Math.max(1, contentH - ih + 6);
            g.fill(x + w - 4, ty + 1, x + w - 2, ty + th - 1, Theme.BORDER_HI);
        }
    }

    public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    public boolean scroll(double mx, double my, double d) {
        if (!contains(mx, my)) return false;
        scroll -= (int) (Math.signum(d) * 20);
        layout();
        return true;
    }
}
