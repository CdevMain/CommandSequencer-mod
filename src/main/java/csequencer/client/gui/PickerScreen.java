package csequencer.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable modal list (optionally grouped) returning one value to its parent. */
public final class PickerScreen extends Screen {
    public record Option(String label, String sub, String value, int color, String group) {
        public Option(String label, String sub, String value) { this(label, sub, value, Theme.MUTED, ""); }
        public Option(String label, String sub, String value, int color) { this(label, sub, value, color, ""); }
        boolean header() { return value == null; }
    }

    private final Screen parent;
    private final List<Option> options;
    private final Consumer<String> onPick;
    private EditBox search;
    private ListPane<Option> list;

    public PickerScreen(Screen parent, String title, List<Option> options, Consumer<String> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.options = options;
        this.onPick = onPick;
    }

    private int panelW() { return Math.min(380, width - 20); }

    @Override
    protected void init() {
        int w = panelW(), x = (width - w) / 2;
        search = new UiTextField(font, x + 8, 34, w - 16, 17, "Search…");
        addRenderableWidget(search);
        setInitialFocus(search);
        list = new ListPane<>(22, this::rows, (g, o, rx, ry, rw, rh, sel, hov) -> {
            if (o.header()) {
                Theme.section(g, font, o.label(), rx + 4, ry + 10, rw - 8);
                return;
            }
            Theme.dot(g, rx + 5, ry + 8, o.color());
            g.drawString(font, Theme.clip(font, o.label(), rw - 18), rx + 15, ry + 3, Theme.TEXT, false);
            g.drawString(font, Theme.clip(font, o.sub(), rw - 18), rx + 15, ry + 12, Theme.MUTED, false);
        });
        list.bounds(x + 8, 56, w - 16, height - 66);
    }

    /** Filtered options with group headers inserted. */
    private List<Option> rows() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<Option> out = new ArrayList<>();
        String group = null;
        for (Option o : options) {
            if (!q.isEmpty() && !(o.label() + " " + o.sub() + " " + o.value() + " " + o.group()).toLowerCase(Locale.ROOT).contains(q)) continue;
            if (!o.group().isEmpty() && !o.group().equals(group)) {
                group = o.group();
                out.add(new Option(group, "", null, 0, group));
            }
            out.add(o);
        }
        return out;
    }

    private Option firstPickable() {
        for (Option o : rows()) if (!o.header()) return o;
        return null;
    }

    private void pick(Option o) {
        if (o == null || o.header()) return;
        minecraft.setScreen(parent);
        onPick.accept(o.value());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xD0060810);
        int w = panelW(), x = (width - w) / 2;
        Theme.card(g, x, 8, w, height - 12, Theme.SURFACE, Theme.BORDER_HI);
        g.drawString(font, title, x + 8, 17, Theme.TEXT, false);
        g.drawString(font, "Esc to cancel · Enter picks the first match", x + w - 8 - font.width("Esc to cancel · Enter picks the first match"), 17, Theme.MUTED, false);
        list.render(g, font, mx, my);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (super.mouseClicked(mx, my, b)) return true;
        int i = list.click(mx, my);
        if (i >= 0) { pick(rows().get(i)); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { return list.scroll(mx, my, d); }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { minecraft.setScreen(parent); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            Option sel = list.selectedItem();
            pick(sel != null && !sel.header() ? sel : firstPickable());
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
            List<Option> r = rows();
            int i = list.selected;
            do { i += key == GLFW.GLFW_KEY_DOWN ? 1 : -1; } while (i >= 0 && i < r.size() && r.get(i).header());
            if (i >= 0 && i < r.size()) list.selected = i;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
