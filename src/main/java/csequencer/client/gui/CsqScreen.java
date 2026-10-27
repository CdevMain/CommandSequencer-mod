package csequencer.client.gui;

import csequencer.client.ClientState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base for all CommandSequencer screens: dark theme, a top bar with brand and tab navigation,
 * popup menus (right-click / dropdowns), tooltips and themed widget helpers.
 * Never pauses the game (multiplayer safe).
 */
public abstract class CsqScreen extends Screen {
    public static final int HEADER = 24;
    protected final Screen parent;
    private final List<Component> tooltip = new ArrayList<>();
    private record Tip(int x, int y, int w, int h, Supplier<String> text) {}
    private final List<Tip> frameTips = new ArrayList<>();
    private PopupMenu menu;

    protected CsqScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    public enum Tab {
        HUB("Home", "⌂"), PROJECTS("Projects", "▤"), EDITOR("Editor", "✎"), LIBRARY("Pieces", "◆"),
        TASKS("Tasks", "▶"), MONITOR("Monitor", "◉"), HISTORY("History", "↺"), SETTINGS("Settings", "⚙");

        final String label, icon;

        Tab(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    protected abstract Tab tab();

    /** Screens that draw their own full-screen layout (the Home screen) can hide the top bar. */
    protected boolean showTopBar() { return true; }

    protected abstract void build();

    /** Content between the background and the widgets. */
    protected abstract void drawContent(GuiGraphics g, int mx, int my, float pt);

    @Override
    protected final void init() {
        menu = null;
        if (!showTopBar()) {
            build();
            return;
        }
        String brand = "◆ COMMAND SEQUENCER";
        boolean showBrand = width >= 560;
        int x = showBrand ? font.width(brand) + 18 : 6;
        Tab[] tabs = Tab.values();
        int avail = width - x - 30;
        boolean iconsOnly = false;
        int total = 0;
        for (Tab t : tabs) total += font.width(t.icon + " " + t.label) + 12;
        if (total > avail) iconsOnly = true;
        for (Tab t : tabs) {
            String label = iconsOnly ? t.icon : t.icon + " " + t.label;
            int w = font.width(label) + (iconsOnly ? 14 : 12);
            UiButton b = new UiButton(x, 2, w, HEADER - 3, label, UiButton.Style.TAB, btn -> navigate(t));
            b.selected(t == tab());
            if (iconsOnly) b.tip(t.label);
            addRenderableWidget(b);
            x += w + 1;
        }
        build();
    }

    protected void navigate(Tab t) {
        if (t == tab()) return;
        Screen next = switch (t) {
            case HUB -> new HubScreen();
            case PROJECTS -> new ProjectBrowserScreen(this);
            case EDITOR -> TimelineEditorScreen.current() != null ? TimelineEditorScreen.current() : new ProjectBrowserScreen(this);
            case LIBRARY -> new PieceLibraryScreen(this, null);
            case TASKS -> new TaskManagerScreen(this);
            case MONITOR -> new MonitorScreen(this);
            case HISTORY -> new HistoryScreen(this);
            case SETTINGS -> new SettingsScreen(this);
        };
        onLeave();
        minecraft.setScreen(next);
    }

    /** Called when leaving via the tab bar (screens unsubscribe from server topics here). */
    protected void onLeave() {}

    // ── Rendering ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, Theme.BG);
        boolean on = ClientState.connected;
        if (showTopBar()) {
            g.fill(0, 0, width, HEADER, Theme.HEADER);
            g.fill(0, HEADER - 1, width, HEADER, Theme.BORDER);
            if (width >= 560) {
                g.drawString(font, "◆", 7, 8, Theme.ACCENT, false);
                g.drawString(font, "COMMAND SEQUENCER", 17, 8, Theme.TEXT, false);
            }
            Theme.dot(g, width - 12, 10, on ? Theme.OK : Theme.ERR);
        }
        frameTips.clear();
        int mxw = menu != null ? -1 : mx, myw = menu != null ? -1 : my;
        drawContent(g, mxw, myw, pt);
        super.render(g, mxw, myw, pt);
        if (menu != null) {
            menu.render(g, font, mx, my);
        } else {
            for (Tip t : frameTips) if (mx >= t.x && mx < t.x + t.w && my >= t.y && my < t.y + t.h) { tooltip(t.text.get()); break; }
            if (showTopBar() && mx >= width - 16 && my < HEADER) tooltip(on ? "Connected to the server" : "Not connected");
            if (!tooltip.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(0, 0, 450);
                g.renderComponentTooltip(font, tooltip, mx, my);
                g.pose().popPose();
            }
        }
        tooltip.clear();
    }

    /** Page title and subtitle drawn below the top bar. Returns the y where content can start. */
    protected int pageHeader(GuiGraphics g, String title, String subtitle) {
        g.drawString(font, title, 10, HEADER + 8, Theme.TEXT, false);
        if (subtitle != null && !subtitle.isEmpty()) g.drawString(font, Theme.clip(font, subtitle, width - 20), 10, HEADER + 19, Theme.MUTED, false);
        return HEADER + 32;
    }

    /** Shows a tooltip this frame. Lines are split on '\n'. */
    protected void tooltip(String text) {
        tooltip.clear();
        if (text == null || text.isEmpty()) return;
        for (String l : text.split("\n")) tooltip.add(Component.literal(l));
    }

    /** Tooltip region for this frame only (call from drawContent). */
    protected void tipArea(int x, int y, int w, int h, Supplier<String> text) { frameTips.add(new Tip(x, y, w, h, text)); }

    // ── Popup menus ────────────────────────────────────────────────────────

    public void openMenu(PopupMenu m, double x, double y) {
        if (m == null || m.isEmpty()) return;
        m.layout(font, (int) x, (int) y, width, height);
        menu = m;
    }

    public boolean menuOpen() { return menu != null; }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (menu != null) {
            Runnable r = menu.click(mx, my);
            menu = null;
            if (r != null) r.run();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (menu != null && key == GLFW.GLFW_KEY_ESCAPE) { menu = null; return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (menu != null) return true;
        return super.mouseScrolled(mx, my, d);
    }

    // ── Widget helpers ─────────────────────────────────────────────────────

    protected UiButton btn(String label, int x, int y, int w, int h, Runnable action) {
        return addRenderableWidget(UiButton.of(label, UiButton.Style.DEFAULT, x, y, w, h, action));
    }

    protected UiButton btn(String label, String tip, int x, int y, int w, int h, Runnable action) {
        return addRenderableWidget(UiButton.of(label, UiButton.Style.DEFAULT, x, y, w, h, action).tip(tip));
    }

    protected UiButton btn(String label, UiButton.Style style, String tip, int x, int y, int w, int h, Runnable action) {
        return addRenderableWidget(UiButton.of(label, style, x, y, w, h, action).tip(tip));
    }

    protected UiTextField box(int x, int y, int w, String hint, String value) {
        UiTextField b = new UiTextField(font, x, y, w, 16, hint);
        b.setValue(value == null ? "" : value);
        return addRenderableWidget(b);
    }

    /** Lets helper components (forms, inspectors) manage widgets of this screen. */
    public <T extends GuiEventListener & Renderable & NarratableEntry> T addWidget2(T w) { return addRenderableWidget(w); }

    public void removeWidget2(GuiEventListener w) { removeWidget(w); }

    public Font font() { return font; }

    @Override
    public void onClose() {
        onLeave();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    protected static boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
}
