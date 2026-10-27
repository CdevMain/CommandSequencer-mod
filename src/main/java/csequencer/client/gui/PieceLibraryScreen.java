package csequencer.client.gui;

import csequencer.client.ClientState;
import csequencer.core.json.J;
import csequencer.core.pieces.ParamSpec;
import csequencer.core.pieces.PieceCategory;
import csequencer.core.pieces.PieceKind;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Piece Library organised by category: category sidebar, piece list and a detail panel.
 * When opened from the editor, "Insert" (or double-click / Enter) places the piece.
 */
public final class PieceLibraryScreen extends CsqScreen {
    private final Consumer<PieceType> onPick;
    private EditBox search;
    private PieceCategory category;
    private ListPane<PieceType> list;
    private static final int CAT_W = 118, CAT_ROW = 18;

    public PieceLibraryScreen(Screen parent, Consumer<PieceType> onPick) {
        super(onPick == null ? "Piece Library" : "Insert Piece", parent);
        this.onPick = onPick;
    }

    @Override protected Tab tab() { return onPick == null ? Tab.LIBRARY : Tab.EDITOR; }

    @Override
    protected void build() {
        search = box(CAT_W + 16, HEADER + 30, Math.max(120, listW() - 2), "Search all pieces… (teleport, wait, title)", search == null ? "" : search.getValue());
        setInitialFocus(search);
        list = new ListPane<>(24, this::rows, (g, t, x, y, w, h, sel, hov) -> {
            PieceCategory c = PieceRegistry.categoryOf(t);
            int col = c == null ? Theme.MUTED : c.color();
            g.fill(x + 3, y + 3, x + 19, y + h - 3, (col & 0x00FFFFFF) | 0x33000000);
            g.drawCenteredString(font, c == null ? "?" : c.icon(), x + 11, y + 8, col);
            g.drawString(font, Theme.clip(font, t.name(), w - 70), x + 24, y + 3, supported(t) ? Theme.TEXT : Theme.MUTED, false);
            g.drawString(font, Theme.clip(font, t.description(), w - 28), x + 24, y + 13, Theme.MUTED, false);
            String kind = kindLabel(t.kind());
            g.drawString(font, kind, x + w - font.width(kind) - 4, y + 3, Theme.dim(col, 0.8f), false);
        });
        list.empty = "No pieces match";
        list.bounds(CAT_W + 14, HEADER + 50, listW(), height - HEADER - 56 - (onPick != null ? 26 : 0));
        if (onPick != null) {
            btn("Insert", UiButton.Style.PRIMARY, "Place the selected piece (Enter / double-click)", CAT_W + 14, height - 26, 70, 20, this::pick);
            btn("Cancel", CAT_W + 88, height - 26, 60, 20, () -> minecraft.setScreen(parent));
        }
    }

    private int listW() { return Math.max(180, (width - CAT_W - 28) * 55 / 100); }

    static String kindLabel(PieceKind k) {
        return switch (k) {
            case ACTION -> "action";
            case LOGIC -> "condition";
            case EVENT -> "event";
            case FLOW -> "flow";
            case AUDIO -> "audio";
            case DATA -> "data";
            case DEBUG -> "debug";
        };
    }

    private boolean supported(PieceType t) {
        if (t.capability() == null) return true;
        var caps = J.strings(ClientState.hello, "capabilities");
        return caps.isEmpty() || caps.contains(t.capability().name());
    }

    private List<PieceType> rows() {
        String q = search == null ? "" : search.getValue().trim();
        if (!q.isEmpty()) return PieceRegistry.search(q);
        if (category == null) return PieceRegistry.pieces();
        return PieceRegistry.inCategory(category);
    }

    private void pick() {
        PieceType t = list.selectedItem();
        if (t == null || onPick == null) return;
        minecraft.setScreen(parent);
        onPick.accept(t);
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        pageHeader(g, onPick == null ? "Piece Library" : "Insert a piece", PieceRegistry.pieces().size() + " pieces in " + PieceRegistry.pieceCategories().size() + " categories");
        // Categories sidebar
        int x = 6, y = HEADER + 30;
        Theme.panel(g, x, y, CAT_W, height - y - 6);
        List<Object> cats = new ArrayList<>();
        cats.add(null);
        cats.addAll(PieceRegistry.pieceCategories());
        int cy = y + 4;
        for (Object o : cats) {
            PieceCategory c = (PieceCategory) o;
            boolean selected = c == category && (search.getValue().isEmpty());
            boolean hov = in(mx, my, x + 2, cy, CAT_W - 4, CAT_ROW);
            if (selected) g.fill(x + 2, cy, x + CAT_W - 2, cy + CAT_ROW, Theme.SURFACE_3);
            else if (hov) g.fill(x + 2, cy, x + CAT_W - 2, cy + CAT_ROW, Theme.HOVER);
            if (selected) g.fill(x + 2, cy, x + 4, cy + CAT_ROW, c == null ? Theme.ACCENT : c.color());
            int col = c == null ? Theme.ACCENT : c.color();
            g.drawString(font, c == null ? "★" : c.icon(), x + 9, cy + 5, col, false);
            g.drawString(font, c == null ? "All pieces" : c.name(), x + 22, cy + 5, selected ? Theme.TEXT : Theme.TEXT_DIM, false);
            String n = String.valueOf(c == null ? PieceRegistry.pieces().size() : PieceRegistry.inCategory(c).size());
            g.drawString(font, n, x + CAT_W - font.width(n) - 7, cy + 5, Theme.MUTED, false);
            if (c != null && hov) tipArea(x + 2, cy, CAT_W - 4, CAT_ROW, c::description);
            cy += CAT_ROW;
            if (cy > height - 20) break;
        }
        list.render(g, font, mx, my);
        // Details
        int dx = list.x + list.w + 6, dw = width - dx - 6, top = HEADER + 30;
        if (dw < 120) return;
        Theme.panel(g, dx, top, dw, height - top - 6);
        PieceType t = list.selectedItem();
        if (t == null) {
            Theme.empty(g, font, dx + dw / 2, top + 70, "◆", "Select a piece", onPick == null ? "to see what it does" : "then press Insert");
            return;
        }
        PieceCategory c = PieceRegistry.categoryOf(t);
        int ix = dx + 10, iy = top + 10, cw = dw - 20;
        g.drawString(font, (c == null ? "" : c.icon() + "  ") + t.name(), ix, iy, Theme.TEXT, false);
        iy += 13;
        Theme.badge(g, font, c == null ? t.category() : c.name(), ix, iy, c == null ? Theme.MUTED : c.color());
        Theme.badge(g, font, kindLabel(t.kind()), ix + font.width(c == null ? t.category() : c.name()) + 14, iy, Theme.TEXT_DIM);
        iy += 16;
        for (var line : font.split(Component.literal(t.description()), cw)) { g.drawString(font, line, ix, iy, Theme.TEXT_DIM, false); iy += 10; }
        iy += 6;
        if (!supported(t)) { g.drawString(font, "✘ Not available on this server", ix, iy, Theme.ERR, false); iy += 12; }
        if (t.privileged()) { g.drawString(font, "⚠ Runs only in projects saved by trusted players", ix, iy, Theme.WARN, false); iy += 12; }
        Theme.section(g, font, "Parameters", ix, iy, cw);
        iy += 13;
        for (ParamSpec p : t.params()) {
            if (iy > height - 20) break;
            String def = p.def == null || p.def.isJsonNull() || !p.def.isJsonPrimitive() ? "" : p.def.getAsString();
            g.drawString(font, Theme.clip(font, p.label, cw / 2), ix, iy, Theme.TEXT, false);
            String right = p.type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + (def.isEmpty() ? "" : " = " + def);
            g.drawString(font, Theme.clip(font, right, cw / 2 - 4), ix + cw / 2 + 4, iy, Theme.MUTED, false);
            iy += 11;
        }
        if (t.params().isEmpty()) g.drawString(font, "No parameters", ix, iy, Theme.MUTED, false);
        g.drawString(font, "id: " + t.id(), ix, height - 18, Theme.MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (super.mouseClicked(mx, my, b)) return true;
        int y = HEADER + 34;
        if (in(mx, my, 8, y, CAT_W - 4, height - y)) {
            int idx = (int) ((my - y) / CAT_ROW);
            List<PieceCategory> cats = PieceRegistry.pieceCategories();
            if (idx == 0) category = null;
            else if (idx - 1 < cats.size()) category = cats.get(idx - 1);
            else return true;
            search.setValue("");
            list.selected = -1;
            return true;
        }
        int before = list.selected;
        int i = list.click(mx, my);
        if (i >= 0 && i == before && onPick != null) pick();
        return i >= 0;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && onPick != null) {
            if (list.selectedItem() == null && !rows().isEmpty()) list.selected = 0;
            pick();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) { list.selected = Math.min(rows().size() - 1, list.selected + 1); return true; }
        if (key == GLFW.GLFW_KEY_UP) { list.selected = Math.max(0, list.selected - 1); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { return list.scroll(mx, my, d) || super.mouseScrolled(mx, my, d); }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
