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
import java.util.ArrayList;
import java.util.List;
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

    private final List<int[]> sectionPos = new ArrayList<>();
    private final List<String> sectionNames = new ArrayList<>();

    private void section(String name, int x, int y) {
        sectionNames.add(name);
        sectionPos.add(new int[]{x, y});
    }

    private void buildPersonal() {
        sectionNames.clear();
        sectionPos.clear();
        boolean two = width >= 430;
        int w = two ? Math.min(210, (width - 50) / 2) : Math.min(260, width - 40), dy = 21;
        int x = two ? Math.max(14, (width - 2 * w - 20) / 2) : (width - w) / 2, y = HEADER + 40;
        section("Top-of-screen overlay", x, y - 11);
        btn("Overlay: " + (ClientConfig.overlayEnabled ? "shown" : "hidden"), "The bar at the top that shows running sequences and their phases", x, y, w, 19, () -> { ClientConfig.overlayEnabled = !ClientConfig.overlayEnabled; save(); }); y += dy;
        btn("Style: " + (ClientConfig.collapsed ? "compact" : "full"), "Compact shows one line per sequence", x, y, w, 19, () -> { ClientConfig.collapsed = !ClientConfig.collapsed; save(); }); y += dy;
        btn("Position: " + ClientConfig.position.name().toLowerCase(Locale.ROOT).replace('_', ' '), "Where the overlay sits on the screen", x, y, w, 19, () -> {
            ClientConfig.position = ClientConfig.Position.values()[(ClientConfig.position.ordinal() + 1) % ClientConfig.Position.values().length]; save(); }); y += dy;
        btn("Size: " + String.format(Locale.ROOT, "%.2f", ClientConfig.scale), "Overlay size", x, y, w, 19, () -> { ClientConfig.scale = ClientConfig.scale >= 1.75 ? 0.5 : ClientConfig.scale + 0.25; save(); }); y += dy;
        btn("Opacity: " + Math.round(ClientConfig.opacity * 100) + "%", "Overlay background opacity", x, y, w, 19, () -> { ClientConfig.opacity = ClientConfig.opacity >= 0.99 ? 0.3 : Math.min(1, ClientConfig.opacity + 0.1); save(); }); y += dy;
        btn("Visible: " + (ClientConfig.alwaysVisible ? "whole sequence" : "per sequence setting"), "Force the overlay to stay visible for the entire sequence", x, y, w, 19, () -> { ClientConfig.alwaysVisible = !ClientConfig.alwaysVisible; save(); }); y += dy;
        btn("Show for: " + ClientConfig.displaySeconds + "s after a change", "How long the overlay stays after a phase change", x, y, w, 19, () -> { ClientConfig.displaySeconds = ClientConfig.displaySeconds >= 30 ? 3 : ClientConfig.displaySeconds + 3; save(); }); y += dy;
        btn("Max sequences shown: " + ClientConfig.maxEntries, "How many running sequences the overlay lists", x, y, w, 19, () -> { ClientConfig.maxEntries = ClientConfig.maxEntries % 6 + 1; save(); }); y += dy;
        btn("Offset from top: " + ClientConfig.offsetY + "px", "Move the overlay down (e.g. under other mods' bars)", x, y, w, 19, () -> { ClientConfig.offsetY = ClientConfig.offsetY >= 60 ? 0 : ClientConfig.offsetY + 10; save(); }); y += dy;
        btn("Animations: " + (ClientConfig.animations ? "on" : "off"), "Slide and fade animations of the overlay", x, y, w, 19, () -> { ClientConfig.animations = !ClientConfig.animations; save(); }); y += dy;
        if (two) { x = x + w + 20; y = HEADER + 40; } else y += 14;
        section("Audio", x, y - 11);
        btn("Sequence audio volume: " + Math.round(ClientConfig.audioVolume * 100) + "%", "Volume of music played by sequences (not the normal game sounds)", x, y, w, 19, () -> { ClientConfig.audioVolume = ClientConfig.audioVolume >= 0.99 ? 0 : Math.min(1, ClientConfig.audioVolume + 0.1); save(); }); y += dy;
        btn("Download sequence audio: " + (ClientConfig.downloadAudio ? "yes" : "no"), "Turn off to never download music from servers", x, y, w, 19, () -> { ClientConfig.downloadAudio = !ClientConfig.downloadAudio; save(); }); y += dy + 14;
        section("Editor & screens", x, y - 11);
        btn("Interface size: " + csequencer.client.UiScale.MODES[ClientConfig.uiSize], "Auto gives these screens enough room even with a large GUI scale.\nCompact fits even more. The game HUD and your Minecraft GUI scale do not change.", x, y, w, 19, () -> {
            ClientConfig.uiSize = (ClientConfig.uiSize + 1) % csequencer.client.UiScale.MODES.length;
            ClientConfig.save();
            minecraft.setScreen(new SettingsScreen(parent));
        }); y += dy;
        btn("Editor snap: " + (ClientConfig.editorSnap == 0 ? "off" : ClientConfig.editorSnap + " ticks"), "Pieces snap to this grid when dragged (hold Shift to ignore)", x, y, w, 19, () -> {
            int[] steps = {0, 1, 5, 10, 20, 40};
            int i = 0;
            while (i < steps.length && steps[i] != ClientConfig.editorSnap) i++;
            ClientConfig.editorSnap = steps[(i + 1) % steps.length];
            save();
        }); y += dy;
        btn("Pieces panel: " + new String[]{"full list", "icon rail", "hidden"}[ClientConfig.paletteMode], "The list of pieces on the left of the editor (P in the editor)", x, y, w, 19, () -> {
            ClientConfig.paletteMode = (ClientConfig.paletteMode + 1) % 3;
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
            for (int i = 0; i < sectionNames.size(); i++) g.drawString(font, sectionNames.get(i).toUpperCase(Locale.ROOT), sectionPos.get(i)[0], sectionPos.get(i)[1], Theme.SUB, false);
            g.drawString(font, Theme.clip(font, "Saved on this computer: config/csequencer-client.json", width - 20), 10, height - 12, Theme.MUTED, false);
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
