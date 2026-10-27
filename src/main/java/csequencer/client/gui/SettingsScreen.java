package csequencer.client.gui;

import com.google.gson.JsonObject;
import csequencer.client.ClientConfig;
import csequencer.client.ClientNet;
import csequencer.client.ClientState;
import csequencer.client.Toasts;
import csequencer.core.json.J;
import csequencer.core.security.Permission;
import csequencer.core.security.ServerSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Settings: personal overlay/audio preferences (stored locally) and, for admins, server
 * permissions, limits, visibility and execution behaviour (stored on the server).
 */
public final class SettingsScreen extends CsqScreen {
    private boolean serverTab;
    private ServerSettings server;
    private final Map<String, EditBox> fields = new LinkedHashMap<>();

    public SettingsScreen(Screen parent) { super("Settings", parent); }

    @Override protected Tab tab() { return Tab.SETTINGS; }

    @Override
    protected void build() {
        fields.clear();
        btn("Personal", 6, HEADER + 6, 70, 18, () -> { serverTab = false; rebuildWidgets(); }).active = serverTab;
        boolean admin = ClientState.perm("admin");
        var sb = btn("Server (admin)", admin ? "Edit server permissions and limits" : "Requires the admin permission", 80, HEADER + 6, 100, 18, () -> {
            serverTab = true;
            ClientNet.send("settings.get", null);
            rebuildWidgets();
        });
        sb.active = admin && !serverTab;
        if (!serverTab) buildPersonal();
        else buildServer();
    }

    private void buildPersonal() {
        int x = 20, y = HEADER + 36, w = 200, dy = 22;
        btn("Overlay: " + (ClientConfig.overlayEnabled ? "shown" : "hidden"), x, y, w, 20, () -> { ClientConfig.overlayEnabled = !ClientConfig.overlayEnabled; save(); }); y += dy;
        btn("Style: " + (ClientConfig.collapsed ? "compact" : "full"), "Compact shows one line per sequence", x, y, w, 20, () -> { ClientConfig.collapsed = !ClientConfig.collapsed; save(); }); y += dy;
        btn("Position: " + ClientConfig.position.name().toLowerCase(Locale.ROOT).replace('_', ' '), x, y, w, 20, () -> {
            ClientConfig.position = ClientConfig.Position.values()[(ClientConfig.position.ordinal() + 1) % ClientConfig.Position.values().length]; save(); }); y += dy;
        btn("Scale: " + String.format(Locale.ROOT, "%.2f", ClientConfig.scale), x, y, w, 20, () -> { ClientConfig.scale = ClientConfig.scale >= 1.75 ? 0.5 : ClientConfig.scale + 0.25; save(); }); y += dy;
        btn("Opacity: " + Math.round(ClientConfig.opacity * 100) + "%", x, y, w, 20, () -> { ClientConfig.opacity = ClientConfig.opacity >= 0.99 ? 0.3 : Math.min(1, ClientConfig.opacity + 0.1); save(); }); y += dy;
        btn("Visible: " + (ClientConfig.alwaysVisible ? "whole sequence" : "per sequence setting"), "Force the overlay to stay visible for the entire sequence", x, y, w, 20, () -> { ClientConfig.alwaysVisible = !ClientConfig.alwaysVisible; save(); }); y += dy;
        int x2 = x + w + 20; y = HEADER + 36;
        btn("Show for: " + ClientConfig.displaySeconds + "s after a change", x2, y, w, 20, () -> { ClientConfig.displaySeconds = ClientConfig.displaySeconds >= 30 ? 3 : ClientConfig.displaySeconds + 3; save(); }); y += dy;
        btn("Animations: " + (ClientConfig.animations ? "on" : "off"), x2, y, w, 20, () -> { ClientConfig.animations = !ClientConfig.animations; save(); }); y += dy;
        btn("Max sequences shown: " + ClientConfig.maxEntries, x2, y, w, 20, () -> { ClientConfig.maxEntries = ClientConfig.maxEntries % 6 + 1; save(); }); y += dy;
        btn("Offset from top: " + ClientConfig.offsetY + "px", x2, y, w, 20, () -> { ClientConfig.offsetY = ClientConfig.offsetY >= 60 ? 0 : ClientConfig.offsetY + 10; save(); }); y += dy;
        btn("Sequence audio volume: " + Math.round(ClientConfig.audioVolume * 100) + "%", x2, y, w, 20, () -> { ClientConfig.audioVolume = ClientConfig.audioVolume >= 0.99 ? 0 : Math.min(1, ClientConfig.audioVolume + 0.1); save(); }); y += dy;
        btn("Download sequence audio: " + (ClientConfig.downloadAudio ? "yes" : "no"), x2, y, w, 20, () -> { ClientConfig.downloadAudio = !ClientConfig.downloadAudio; save(); }); y += dy;
        btn("Editor snap: " + (ClientConfig.editorSnap == 0 ? "off" : ClientConfig.editorSnap + " ticks"), x2, y, w, 20, () -> {
            int[] steps = {0, 1, 5, 10, 20, 40};
            int i = 0;
            while (i < steps.length && steps[i] != ClientConfig.editorSnap) i++;
            ClientConfig.editorSnap = steps[(i + 1) % steps.length];
            save();
        });
    }

    private void save() {
        ClientConfig.save();
        rebuildWidgets();
    }

    private void buildServer() {
        if (ClientState.serverSettings == null) return;
        server = ServerSettings.fromJson(ClientState.serverSettings);
        int x = 20, y = HEADER + 36;
        for (Permission p : Permission.values()) {
            field("perm." + p.id, "Level for " + p.id, String.valueOf(server.level(p)), x, y);
            y += 18;
        }
        int x2 = x + 220;
        y = HEADER + 36;
        Object[][] limits = {
                {"maxActiveTasks", server.limits.maxActiveTasks}, {"maxTasksPerPlayer", server.limits.maxTasksPerPlayer},
                {"maxActionsPerTick", server.limits.maxActionsPerTick}, {"maxActionsPerTaskPerTick", server.limits.maxActionsPerTaskPerTick},
                {"maxEvalNodesPerTick", server.limits.maxEvalNodesPerTick}, {"maxLoopIterations", server.limits.maxLoopIterations},
                {"maxCallDepth", server.limits.maxCallDepth}, {"maxFibersPerTask", server.limits.maxFibersPerTask},
                {"maxPiecesPerProject", server.limits.maxPiecesPerProject}, {"fillBlockLimit", server.limits.fillBlockLimit},
                {"maxChainDepth", server.limits.maxChainDepth}, {"maxAudioBytes", server.limits.maxAudioBytes},
        };
        for (Object[] l : limits) { field("limit." + l[0], (String) l[0], String.valueOf(l[1]), x2, y); y += 18; }
        int x3 = x2 + 220;
        y = HEADER + 36;
        field("commandPermissionLevel", "Command level (0-4)", String.valueOf(server.commandPermissionLevel), x3, y); y += 18;
        field("historySize", "History size", String.valueOf(server.historySize), x3, y); y += 18;
        field("syncIntervalTicks", "Sync interval (ticks)", String.valueOf(server.syncIntervalTicks), x3, y); y += 22;
        btn("Announcements: " + (server.announcementsEnabled ? "on" : "off"), x3, y, 150, 18, () -> { server.announcementsEnabled = !server.announcementsEnabled; push(); }); y += 20;
        btn("Monitor: " + server.monitorVisibility.name().toLowerCase(Locale.ROOT).replace('_', ' '), "Who sees which tasks in the monitor", x3, y, 150, 18, () -> {
            server.monitorVisibility = ServerSettings.MonitorVisibility.values()[(server.monitorVisibility.ordinal() + 1) % ServerSettings.MonitorVisibility.values().length]; push(); }); y += 20;
        btn("Untrusted commands: " + (server.allowUntrustedCommands ? "allowed" : "blocked"), "Allow command pieces saved by players below the command level", x3, y, 150, 18, () -> {
            server.allowUntrustedCommands = !server.allowUntrustedCommands; push(); }); y += 26;
        btn("Save server settings", x3, y, 150, 20, this::push);
    }

    private void field(String key, String label, String value, int x, int y) {
        EditBox b = box(x + 120, y, 80, label, value);
        fields.put(key, b);
        tipArea(x, y, 120, 16, () -> label);
    }

    private void push() {
        if (server == null) return;
        try {
            for (var e : fields.entrySet()) {
                String k = e.getKey(), v = e.getValue().getValue().trim();
                if (k.startsWith("perm.")) server.permissionLevels.put(Permission.byId(k.substring(5)), Integer.parseInt(v));
            }
            JsonObject o = server.toJson();
            JsonObject lim = o.getAsJsonObject("limits");
            for (var e : fields.entrySet()) {
                String k = e.getKey(), v = e.getValue().getValue().trim();
                if (k.startsWith("limit.")) lim.addProperty(k.substring(6), Long.parseLong(v));
                else if (!k.startsWith("perm.")) o.addProperty(k, Long.parseLong(v));
            }
            ClientNet.request("settings.set", ClientNet.obj("settings", o), r -> {
                if (J.b(r, "ok", false)) { Toasts.success(J.str(r, "msg", "Saved")); ClientNet.send("settings.get", null); }
                else Toasts.error(J.str(r, "msg", "Failed"));
            });
        } catch (NumberFormatException ex) {
            Toasts.error("All fields must be whole numbers");
        }
    }

    private long seenSettings;

    @Override
    public void tick() {
        if (serverTab && ClientState.serverSettings != null && System.identityHashCode(ClientState.serverSettings) != seenSettings) {
            seenSettings = System.identityHashCode(ClientState.serverSettings);
            rebuildWidgets();
        }
    }

    @Override
    protected void drawContent(GuiGraphics g, int mx, int my, float pt) {
        if (!serverTab) {
            g.drawString(font, "Personal preferences are stored on this computer (config/csequencer-client.json).", 20, height - 20, Theme.SUB, false);
            return;
        }
        if (ClientState.serverSettings == null) { g.drawCenteredString(font, "Loading server settings…", width / 2, HEADER + 60, Theme.SUB); return; }
        int y = HEADER + 40;
        for (Permission p : Permission.values()) { g.drawString(font, p.id, 20, y, Theme.TEXT, false); y += 18; }
        y = HEADER + 40;
        for (String k : fields.keySet()) if (k.startsWith("limit.")) { g.drawString(font, Theme.clip(font, k.substring(6), 116), 240, y, Theme.TEXT, false); y += 18; }
        y = HEADER + 40;
        for (String k : new String[]{"Command level", "History size", "Sync interval"}) { g.drawString(font, k, 460, y, Theme.TEXT, false); y += 18; }
        g.drawString(font, "Permission levels are operator levels (0-4). Permission mods can override the csequencer.* nodes.", 20, height - 20, Theme.SUB, false);
    }
}
