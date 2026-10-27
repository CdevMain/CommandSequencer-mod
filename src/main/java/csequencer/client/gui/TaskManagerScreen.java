package csequencer.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import csequencer.client.ClientNet;
import csequencer.client.ClientState;
import csequencer.client.Toasts;
import csequencer.core.engine.Durations;
import csequencer.core.json.J;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Live Task Manager. Rows are exactly what the server sent (subscribed while open); controls are
 * enabled only when the server says this viewer may use them and the task supports them.
 */
public final class TaskManagerScreen extends CsqScreen {
    private static final String[] STATES = {"ALL", "ACTIVE", "RUNNING", "WAITING", "PAUSED", "QUEUED", "COMPLETED", "FAILED", "CANCELLED"};
    private static final String[] SORTS = {"start", "name", "state", "owner", "scope", "progress"};
    private ListPane<JsonObject> list;
    private EditBox filter;
    private int stateIdx = 1, sortIdx;
    private String selectedId;
    private long lastDetailReq;
    private Button bPause, bResume, bStep, bRestart, bCancel, bOpen;
    private int logScroll;

    public TaskManagerScreen(Screen parent) { super("Task Manager", parent); }

    @Override protected Tab tab() { return Tab.TASKS; }

    @Override
    protected void build() {
        ClientNet.send("subscribe", ClientNet.obj("topic", "tasks", "on", true));
        filter = box(6, HEADER + 6, 150, "Filter: name, player, id…", filter == null ? "" : filter.getValue());
        btn("State: " + STATES[stateIdx], "Filter by state", 160, HEADER + 5, 100, 18, () -> { stateIdx = (stateIdx + 1) % STATES.length; rebuildWidgets(); });
        btn("Sort: " + SORTS[sortIdx], "Sort order", 264, HEADER + 5, 90, 18, () -> { sortIdx = (sortIdx + 1) % SORTS.length; rebuildWidgets(); });
        if (ClientState.perm("control.all") || ClientState.perm("admin"))
            btn("Stop all", UiButton.Style.DANGER, "Cancel every active task on the server", width - 70, HEADER + 5, 64, 18, () -> confirm("Cancel every active task?", () -> ClientNet.send("tasks.stopall", null)));
        int listW = Math.max(260, width * 55 / 100);
        list = new ListPane<>(22, this::rows, this::row);
        list.empty = "No tasks visible. Running sequences appear here live.";
        list.bounds(6, HEADER + 28, listW, height - HEADER - 34);
        int dx = listW + 12, by = height - 26;
        bPause = btn("Pause", dx, by, 50, 20, () -> control("pause"));
        bResume = btn("Resume", dx + 52, by, 54, 20, () -> control("resume"));
        bStep = btn("Step", "Run exactly one tick (paused tasks)", dx + 108, by, 40, 20, () -> control("step"));
        bRestart = btn("Restart", dx + 150, by, 54, 20, () -> confirm("Restart this task from the beginning?", () -> control("restart")));
        bCancel = btn("Cancel", UiButton.Style.DANGER, "Cancel this task", dx + 206, by, 54, 20, () -> confirm("Cancel this task? Its listeners, audio and spawned resources are released.", () -> control("cancel")));
        bOpen = btn("Open", UiButton.Style.PRIMARY, "Open the sequence in the editor with live execution", dx + 262, by, 46, 20, this::openLive);
    }

    private void confirm(String msg, Runnable yes) {
        minecraft.setScreen(new ConfirmScreen(ok -> { minecraft.setScreen(this); if (ok) yes.run(); }, Component.literal("Confirm"), Component.literal(msg)));
    }

    private void control(String action) {
        if (selectedId == null) return;
        ClientNet.request("task.control", ClientNet.obj("task", selectedId, "action", action), r -> {
            if (J.b(r, "ok", false)) Toasts.success(J.str(r, "msg", action));
            else Toasts.error(J.str(r, "msg", "failed"));
        });
    }

    private void openLive() {
        JsonObject t = find(selectedId);
        if (t == null) return;
        ClientNet.downloadProject(J.str(t, "projectId", ""), p -> minecraft.setScreen(TimelineEditorScreen.open(p, J.str(t, "id", ""))));
    }

    @Override
    protected void onLeave() { ClientNet.send("subscribe", ClientNet.obj("topic", "tasks", "on", false)); }

    private List<JsonObject> rows() {
        List<JsonObject> out = new ArrayList<>();
        String q = filter == null ? "" : filter.getValue().toLowerCase(Locale.ROOT).trim();
        String st = STATES[stateIdx];
        for (JsonElement e : J.arr(ClientState.tasks, "tasks")) {
            JsonObject t = e.getAsJsonObject();
            String state = J.str(t, "state", "");
            if (st.equals("ACTIVE") && !(state.equals("RUNNING") || state.equals("WAITING") || state.equals("PAUSED") || state.equals("QUEUED"))) continue;
            if (!st.equals("ALL") && !st.equals("ACTIVE") && !st.equals(state)) continue;
            if (!q.isEmpty()) {
                String hay = (J.str(t, "project", "") + " " + J.str(t, "owner", "") + " " + J.str(t, "id", "") + " " + J.str(t, "scope", "")
                        + " " + String.join(" ", J.strings(t, "participants"))).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            out.add(t);
        }
        Comparator<JsonObject> c = switch (SORTS[sortIdx]) {
            case "name" -> Comparator.comparing(t -> J.str(t, "project", "").toLowerCase(Locale.ROOT));
            case "state" -> Comparator.comparing(t -> J.str(t, "state", ""));
            case "owner" -> Comparator.comparing(t -> J.str(t, "owner", "").toLowerCase(Locale.ROOT));
            case "scope" -> Comparator.comparing(t -> J.str(t, "scope", ""));
            case "progress" -> Comparator.comparingDouble(t -> -J.d(t, "progress", -1));
            default -> Comparator.comparingLong(t -> -J.l(t, "startedAt", 0));
        };
        out.sort(c);
        return out;
    }

    private JsonObject find(String id) {
        if (id == null) return null;
        for (JsonElement e : J.arr(ClientState.tasks, "tasks")) if (id.equals(J.str(e.getAsJsonObject(), "id", ""))) return e.getAsJsonObject();
        return null;
    }

    private void row(GuiGraphics g, JsonObject t, int x, int y, int w, int h, boolean sel, boolean hov) {
        String state = J.str(t, "state", "");
        int col = Theme.stateColor(state);
        g.fill(x, y + 2, x + 2, y + h - 2, col);
        g.drawString(font, Theme.clip(font, J.str(t, "project", ""), w - 120), x + 6, y + 2, Theme.TEXT, false);
        Theme.badge(g, font, state, x + w - 76, y + 2, col);
        String sub = J.str(t, "id", "") + " · " + J.str(t, "owner", "") + " · " + J.str(t, "scope", "").toLowerCase(Locale.ROOT);
        g.drawString(font, Theme.clip(font, sub, w - 70), x + 6, y + 12, Theme.SUB, false);
        double p = J.d(t, "progress", -1);
        Theme.bar(g, x + w - 60, y + 15, 54, 3, p, col);
    }

    @Override
    public void tick() {
        if (list != null && list.selectedItem() == null && selectedId != null) {
            List<JsonObject> r = rows();
            for (int i = 0; i < r.size(); i++) if (selectedId.equals(J.str(r.get(i), "id", ""))) list.selected = i;
        }
        JsonObject sel = list == null ? null : list.selectedItem();
        if (sel == null && selectedId != null && find(selectedId) != null) return;
        selectedId = sel == null ? null : J.str(sel, "id", null);
        String selState = sel == null ? "" : J.str(sel, "state", "");
        boolean finished = selState.equals("COMPLETED") || selState.equals("FAILED") || selState.equals("CANCELLED");
        boolean haveFinal = finished && selectedId != null && selectedId.equals(J.str(ClientState.detail, "id", ""))
                && J.str(ClientState.detail, "state", "").equals(selState);
        if (selectedId != null && !haveFinal && System.currentTimeMillis() - lastDetailReq > 500) {
            lastDetailReq = System.currentTimeMillis();
            ClientNet.send("task.detail", ClientNet.obj("task", selectedId));
        }
        JsonObject caps = sel == null ? null : J.obj(sel, "caps");
        boolean can = sel != null && J.b(sel, "canControl", false);
        bPause.active = can && caps != null && J.b(caps, "pause", false);
        bResume.active = can && caps != null && J.b(caps, "resume", false);
        bStep.active = can && caps != null && J.b(caps, "step", false);
        bRestart.active = can && caps != null && J.b(caps, "restart", false);
        bCancel.active = can && caps != null && J.b(caps, "cancel", false);
        bOpen.active = sel != null;
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        list.render(g, font, mx, my);
        int dx = list.x + list.w + 6, dw = width - dx - 6, top = HEADER + 28;
        Theme.panel(g, dx, top, dw, height - top - 30);
        long age = ClientState.age(ClientState.tasksAt);
        String meta = J.i(ClientState.tasks, "active", 0) + "/" + J.i(ClientState.tasks, "limit", 0) + " active · updated " + (age == Long.MAX_VALUE ? "never" : Theme.ago(age) + " ago");
        g.drawString(font, meta, 360, HEADER + 10, age > 3000 ? Theme.WARN : Theme.SUB, false);
        JsonObject t = find(selectedId);
        if (t == null) {
            g.drawCenteredString(font, "Select a task to inspect it", dx + dw / 2, top + 30, Theme.SUB);
            return;
        }
        JsonObject d = selectedId.equals(J.str(ClientState.detail, "id", "")) ? ClientState.detail : t;
        int x = dx + 6, y = top + 6, cw = dw - 12;
        g.drawString(font, Theme.clip(font, J.str(d, "project", ""), cw - 80), x, y, Theme.TEXT, false);
        Theme.badge(g, font, J.str(d, "state", ""), x + cw - 70, y - 1, Theme.stateColor(J.str(d, "state", "")));
        y += 12;
        line(g, x, y, "Task", J.str(d, "id", "") + " (run " + J.i(d, "run", 1) + ")" + (J.b(d, "debug", false) ? " · debug" : "")); y += 10;
        line(g, x, y, "State", J.str(d, "reason", "")); y += 10;
        line(g, x, y, "Owner", J.str(d, "owner", "") + " · started by " + J.str(d, "trigger", "")); y += 10;
        line(g, x, y, "Tracking", J.str(d, "tracked", "").isEmpty() ? "nobody" : J.str(d, "tracked", "")); y += 10;
        line(g, x, y, "Scope", J.str(d, "scope", "") + " · " + J.str(d, "scopeKey", "") + (J.str(d, "dimension", "").isEmpty() ? "" : " · " + J.str(d, "dimension", ""))); y += 10;
        line(g, x, y, "Targets", J.i(d, "participantCount", 0) + ": " + String.join(", ", J.strings(d, "participants"))); y += 10;
        int pos = J.i(d, "position", 0), len = J.i(d, "length", 0);
        line(g, x, y, "Position", Durations.format(pos) + " / " + Durations.format(len) + "  (tick " + pos + "/" + len + ")"); y += 10;
        Theme.bar(g, x, y + 1, cw, 4, J.d(d, "progress", -1), Theme.stateColor(J.str(d, "state", ""))); y += 8;
        line(g, x, y, "Phase", J.str(d, "phase", "—") + (J.str(d, "nextPhase", "").isEmpty() ? "" : " → " + J.str(d, "nextPhase", "")
                + (J.i(d, "ticksToNextPhase", -1) >= 0 ? " in " + Durations.clock(J.i(d, "ticksToNextPhase", 0)) : ""))); y += 10;
        line(g, x, y, "Branch", J.str(d, "branch", "") + " · " + J.i(d, "branches", 0) + " live"); y += 10;
        JsonObject na = J.obj(d, "nextAction");
        String next = na == null || na.size() == 0 ? "—" : J.str(na, "label", "") + " (" + J.str(na, "type", "") + ")"
                + (J.i(na, "ticks", -1) >= 0 ? " in " + J.i(na, "ticks", 0) + "t" : "") + (J.str(na, "waiting", "").isEmpty() ? "" : " · waiting: " + J.str(na, "waiting", ""));
        line(g, x, y, "Next", next); y += 10;
        line(g, x, y, "Elapsed", Theme.ago(J.l(d, "elapsedMs", 0)) + " · " + J.i(d, "tick", 0) + " ticks"); y += 10;
        line(g, x, y, "Actions", J.i(d, "ok", 0) + " done · " + J.i(d, "pending", 0) + " pending · " + J.i(d, "failed", 0) + " failed"); y += 10;
        JsonObject cost = J.obj(d, "cost");
        if (cost != null) { line(g, x, y, "Cost", J.l(cost, "avgUs", 0) + "µs/tick avg · max " + J.l(cost, "maxUs", 0) + "µs · " + J.l(cost, "evals", 0) + " evals · " + J.i(cost, "listeners", 0) + " listeners"); y += 10; }
        if (!J.str(d, "lastResult", "").isEmpty()) { line(g, x, y, "Last", J.str(d, "lastResult", "")); y += 10; }
        if (!J.str(d, "lastError", "").isEmpty()) {
            g.drawString(font, Theme.clip(font, "Error: " + J.str(d, "lastError", ""), cw), x, y, Theme.ERR, false);
            tipArea(x, y, cw, 10, () -> J.str(d, "lastError", ""));
            y += 10;
        }
        for (String w : J.strings(d, "warnings")) { g.drawString(font, Theme.clip(font, "⚠ " + w, cw), x, y, Theme.WARN, false); y += 10; }
        // Branches and log (detail only)
        var branches = J.arr(d, "branchList");
        if (branches.size() > 0) {
            y += 3;
            g.drawString(font, "Branches", x, y, Theme.SUB, false);
            y += 10;
            for (int i = 0; i < Math.min(6, branches.size()); i++) {
                JsonObject b = branches.get(i).getAsJsonObject();
                String s = J.str(b, "state", "").charAt(0) + " " + J.str(b, "name", "") + " @" + J.i(b, "clock", 0)
                        + (J.str(b, "wait", "").isEmpty() ? (J.str(b, "next", "").isEmpty() ? "" : " → " + J.str(b, "next", "")) : " ⏸ " + J.str(b, "wait", ""));
                g.drawString(font, Theme.clip(font, s, cw), x + 4, y, J.str(b, "state", "").equals("FAILED") ? Theme.ERR : 0xFFB8C0D0, false);
                y += 10;
            }
        }
        var log = J.arr(d, "log");
        if (log.size() > 0) {
            y += 3;
            g.drawString(font, "Log (scroll)", x, y, Theme.SUB, false);
            y += 10;
            int bottom = height - 32;
            int lines = Math.max(1, (bottom - y) / 9);
            logScroll = Math.max(0, Math.min(logScroll, Math.max(0, log.size() - lines)));
            int start = Math.max(0, log.size() - lines - logScroll);
            for (int i = start; i < Math.min(log.size(), start + lines); i++) {
                String l = log.get(i).getAsString();
                int c = l.contains("[ERROR]") ? Theme.ERR : l.contains("[WARN]") ? Theme.WARN : 0xFF9AA3B5;
                g.drawString(font, Theme.clip(font, l, cw), x, y, c, false);
                y += 9;
            }
        }
    }

    private void line(GuiGraphics g, int x, int y, String label, String value) {
        g.drawString(font, label, x, y, Theme.SUB, false);
        g.drawString(font, Theme.clip(font, value, width - x - 70), x + 52, y, Theme.TEXT, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (super.mouseClicked(mx, my, b)) return true;
        if (list.click(mx, my) >= 0) { logScroll = 0; return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (list.scroll(mx, my, delta)) return true;
        logScroll += delta > 0 ? 3 : -3;
        if (logScroll < 0) logScroll = 0;
        return true;
    }
}
