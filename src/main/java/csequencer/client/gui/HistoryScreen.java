package csequencer.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.client.ClientNet;
import csequencer.client.ClientState;
import csequencer.core.json.J;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Execution History: previous runs with result, duration, errors and log. */
public final class HistoryScreen extends CsqScreen {
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd/MM HH:mm:ss");
    private ListPane<JsonObject> list;
    private EditBox filter;
    private boolean failuresOnly;
    private int logScroll;

    public HistoryScreen(Screen parent) { super("Execution History", parent); }

    @Override protected Tab tab() { return Tab.HISTORY; }

    @Override
    protected void build() {
        ClientNet.send("history.get", null);
        filter = box(6, HEADER + 6, 160, "Filter by name or player…", filter == null ? "" : filter.getValue());
        btn(failuresOnly ? "Failures only" : "All results", 170, HEADER + 5, 90, 18, () -> { failuresOnly = !failuresOnly; rebuildWidgets(); });
        btn("Refresh", 264, HEADER + 5, 60, 18, () -> ClientNet.send("history.get", null));
        list = new ListPane<>(22, this::rows, this::row);
        list.empty = "No runs recorded yet.";
        list.bounds(6, HEADER + 28, Math.max(240, width * 45 / 100), height - HEADER - 34);
    }

    private List<JsonObject> rows() {
        List<JsonObject> out = new ArrayList<>();
        String q = filter == null ? "" : filter.getValue().toLowerCase(Locale.ROOT);
        for (JsonElement e : ClientState.history) {
            JsonObject h = e.getAsJsonObject();
            if (failuresOnly && !J.str(h, "result", "").equals("FAILED")) continue;
            if (!q.isEmpty() && !(J.str(h, "project", "") + " " + J.str(h, "owner", "")).toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(h);
        }
        return out;
    }

    private void row(GuiGraphics g, JsonObject h, int x, int y, int w, int rh, boolean sel, boolean hov) {
        String r = J.str(h, "result", "");
        int col = Theme.stateColor(r);
        g.fill(x, y + 2, x + 2, y + rh - 2, col);
        g.drawString(font, Theme.clip(font, J.str(h, "project", ""), w - 90), x + 6, y + 2, Theme.TEXT, false);
        Theme.badge(g, font, r, x + w - 76, y + 2, col);
        String sub;
        synchronized (FMT) { sub = FMT.format(new Date(J.l(h, "startedAt", 0))); }
        sub += " · " + J.str(h, "owner", "") + " · " + Theme.ago(J.l(h, "endedAt", 0) - J.l(h, "startedAt", 0));
        g.drawString(font, Theme.clip(font, sub, w - 10), x + 6, y + 12, Theme.SUB, false);
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        list.render(g, font, mx, my);
        int dx = list.x + list.w + 6, dw = width - dx - 6, top = HEADER + 28;
        Theme.panel(g, dx, top, dw, height - top - 6);
        JsonObject h = list.selectedItem();
        if (h == null) { g.drawCenteredString(font, "Select a run", dx + dw / 2, top + 30, Theme.SUB); return; }
        int x = dx + 6, y = top + 6, cw = dw - 12;
        g.drawString(font, J.str(h, "project", "") + "  (" + J.str(h, "taskId", "") + ", run " + J.i(h, "run", 1) + ")", x, y, Theme.TEXT, false); y += 12;
        String[][] rows = {
                {"Result", J.str(h, "result", "") + (J.str(h, "message", "").isEmpty() ? "" : " — " + J.str(h, "message", ""))},
                {"Started by", J.str(h, "owner", "") + " (" + J.str(h, "trigger", "") + ")"},
                {"Scope", J.str(h, "scope", "")},
                {"Duration", Theme.ago(J.l(h, "endedAt", 0) - J.l(h, "startedAt", 0)) + " · " + J.i(h, "ticks", 0) + " ticks · reached " + J.i(h, "position", 0) + "/" + J.i(h, "length", 0)},
                {"Actions", J.i(h, "ok", 0) + " ok · " + J.i(h, "failed", 0) + " failed · " + J.l(h, "avgUs", 0) + "µs/tick"},
                {"Participants", String.join(", ", J.strings(h, "participants"))},
        };
        for (String[] r : rows) {
            g.drawString(font, r[0], x, y, Theme.SUB, false);
            g.drawString(font, Theme.clip(font, r[1], cw - 70), x + 66, y, Theme.TEXT, false);
            y += 10;
        }
        if (!J.str(h, "error", "").isEmpty()) { g.drawString(font, Theme.clip(font, "Error: " + J.str(h, "error", ""), cw), x, y, Theme.ERR, false); y += 10; }
        for (String w : J.strings(h, "warnings")) { g.drawString(font, Theme.clip(font, "⚠ " + w, cw), x, y, Theme.WARN, false); y += 10; }
        y += 4;
        var log = J.arr(h, "log");
        int lines = Math.max(1, (height - 10 - y) / 9);
        logScroll = Math.max(0, Math.min(logScroll, Math.max(0, log.size() - lines)));
        int start = Math.max(0, log.size() - lines - logScroll);
        for (int i = start; i < Math.min(log.size(), start + lines); i++) {
            String l = log.get(i).getAsString();
            g.drawString(font, Theme.clip(font, l, cw), x, y, l.contains("[ERROR]") ? Theme.ERR : l.contains("[WARN]") ? Theme.WARN : 0xFF9AA3B5, false);
            y += 9;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (super.mouseClicked(mx, my, b)) return true;
        if (list.click(mx, my) >= 0) { logScroll = 0; return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (list.scroll(mx, my, d)) return true;
        logScroll = Math.max(0, logScroll + (d > 0 ? 3 : -3));
        return true;
    }
}
