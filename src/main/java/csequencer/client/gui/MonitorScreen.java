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
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/** Sequence Monitor: every sequence on the server (available or not) with its live state. */
public final class MonitorScreen extends CsqScreen {
    private ListPane<JsonObject> list;
    private Button bRun, bOpen, bTriggers;

    public MonitorScreen(Screen parent) { super("Sequence Monitor", parent); }

    @Override protected Tab tab() { return Tab.MONITOR; }

    @Override
    protected void build() {
        ClientNet.send("subscribe", ClientNet.obj("topic", "monitor", "on", true));
        ClientNet.send("projects.list", null);
        list = new ListPane<>(34, this::rows, this::row);
        list.empty = "No sequences on this server.";
        list.bounds(6, HEADER + 8, width - 12, height - HEADER - 40);
        int y = height - 26;
        bRun = btn("Run", UiButton.Style.PRIMARY, "Start this sequence (you become the initiator)", 6, y, 50, 20, this::run);
        bOpen = btn("Open in editor", 60, y, 90, 20, this::open);
        bTriggers = btn("Toggle triggers", "Arm or disarm automatic start triggers", 154, y, 100, 20, this::toggleTriggers);
        btn("Task Manager", 258, y, 90, 20, () -> navigate(Tab.TASKS));
    }

    @Override
    protected void onLeave() { ClientNet.send("subscribe", ClientNet.obj("topic", "monitor", "on", false)); }

    private List<JsonObject> rows() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : J.arr(ClientState.monitor, "projects")) out.add(e.getAsJsonObject());
        return out;
    }

    private void row(GuiGraphics g, JsonObject p, int x, int y, int w, int h, boolean sel, boolean hov) {
        String status = J.str(p, "status", "AVAILABLE");
        int col = Theme.stateColor(status);
        g.fill(x, y + 2, x + 3, y + h - 2, col);
        g.drawString(font, J.str(p, "name", ""), x + 8, y + 3, Theme.TEXT, false);
        int bx = x + 8 + font.width(J.str(p, "name", "")) + 6;
        bx += Theme.badge(g, font, label(status), bx, y + 2, col) + 4;
        if (J.i(p, "triggers", 0) > 0) Theme.badge(g, font, J.b(p, "triggersEnabled", true) ? "triggers armed" : "triggers off", bx, y + 2, J.b(p, "triggersEnabled", true) ? Theme.INFO : Theme.SUB);
        var inst = J.arr(p, "instances");
        String info;
        if (inst.size() == 0) {
            info = "Owner " + (J.str(p, "owner", "").isEmpty() ? "—" : J.str(p, "owner", ""))
                    + (J.str(p, "lastResult", "").isEmpty() ? "" : " · last run " + J.str(p, "lastResult", "").toLowerCase());
            if (J.i(p, "hiddenInstances", 0) > 0) info += " · " + J.i(p, "hiddenInstances", 0) + " instance(s) you cannot see";
        } else {
            JsonObject t = inst.get(0).getAsJsonObject();
            String next = J.str(t, "nextPhase", "");
            int ttn = J.i(t, "ticksToNextPhase", -1);
            info = inst.size() + " instance(s) · " + J.str(t, "owner", "") + " · stage: " + (J.str(t, "phase", "").isEmpty() ? "—" : J.str(t, "phase", ""))
                    + (next.isEmpty() ? "" : " · next: " + next + (ttn >= 0 ? " in " + Durations.clock(ttn) : ""))
                    + " · " + J.i(t, "participantCount", 0) + " participant(s)";
            Theme.bar(g, x + w - 110, y + 14, 100, 4, J.d(t, "progress", -1), col);
        }
        g.drawString(font, Theme.clip(font, info, w - 130), x + 8, y + 14, Theme.SUB, false);
        if (inst.size() > 0) {
            JsonObject t = inst.get(0).getAsJsonObject();
            JsonObject na = J.obj(t, "nextAction");
            String s = na == null || na.size() == 0 ? "" : "next event: " + J.str(na, "label", "") + (J.i(na, "ticks", -1) >= 0 ? " (" + J.i(na, "ticks", 0) + "t)" : "");
            g.drawString(font, Theme.clip(font, s, w - 130), x + 8, y + 24, 0xFF6F7890, false);
        }
    }

    private static String label(String s) {
        return switch (s) {
            case "AVAILABLE" -> "available";
            case "QUEUED" -> "queued";
            case "RUNNING" -> "running";
            case "PAUSED" -> "paused";
            case "WAITING" -> "waiting";
            case "COMPLETING", "CANCELLING" -> "finishing";
            case "FAILED" -> "failed";
            case "COMPLETED" -> "completed";
            case "CANCELLED" -> "cancelled";
            default -> s.toLowerCase();
        };
    }

    private void run() {
        JsonObject p = list.selectedItem();
        if (p == null) return;
        ClientNet.request("project.run", ClientNet.obj("id", J.str(p, "id", "")), r -> {
            if (J.b(r, "ok", false)) Toasts.success(J.str(r, "msg", "Started"));
            else Toasts.error(J.str(r, "msg", "Could not start"));
        });
    }

    private void open() {
        JsonObject p = list.selectedItem();
        if (p == null) return;
        var inst = J.arr(p, "instances");
        String live = inst.size() > 0 ? J.str(inst.get(0).getAsJsonObject(), "id", null) : null;
        ClientNet.downloadProject(J.str(p, "id", ""), proj -> minecraft.setScreen(TimelineEditorScreen.open(proj, live)));
    }

    private void toggleTriggers() {
        JsonObject p = list.selectedItem();
        if (p == null) return;
        ClientNet.request("project.triggers", ClientNet.obj("id", J.str(p, "id", ""), "enabled", !J.b(p, "triggersEnabled", true)), r -> {
            if (J.b(r, "ok", false)) Toasts.success(J.str(r, "msg", ""));
            else Toasts.error(J.str(r, "msg", ""));
        });
    }

    @Override
    public void tick() {
        JsonObject p = list.selectedItem();
        bRun.active = p != null && ClientState.perm("run");
        bOpen.active = p != null;
        bTriggers.active = p != null && J.i(p, "triggers", 0) > 0 && ClientState.perm("edit");
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        list.render(g, font, mx, my);
        long age = ClientState.age(ClientState.monitorAt);
        String s = "Server state · " + J.i(ClientState.monitor, "active", 0) + " active · " + (age == Long.MAX_VALUE ? "waiting for server…" : "updated " + Theme.ago(age) + " ago");
        g.drawString(font, s, width - font.width(s) - 8, height - 20, age > 3000 ? Theme.WARN : Theme.SUB, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) { return super.mouseClicked(mx, my, b) || list.click(mx, my) >= 0; }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { return list.scroll(mx, my, d); }
}
