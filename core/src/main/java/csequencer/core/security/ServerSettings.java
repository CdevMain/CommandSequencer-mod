package csequencer.core.security;

import com.google.gson.JsonObject;
import csequencer.core.json.J;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side configuration (separate from projects and runtime state). Persisted by the
 * platform as JSON. Unknown keys from older versions (the 1.0 CSQConfig) are migrated.
 */
public final class ServerSettings {
    public enum MonitorVisibility { ADMINS_ONLY, OWN_AND_PARTICIPATING, ALL }

    public Limits limits = new Limits();
    public final Map<Permission, Integer> permissionLevels = new EnumMap<>(Permission.class);
    /** Maximum operator level commands in sequences may run with. */
    public int commandPermissionLevel = 2;
    /** Root commands sequences may never run (also blocked after "execute ... run"). */
    public List<String> commandBlacklist = new ArrayList<>(List.of("op", "deop", "stop", "ban", "ban-ip", "pardon",
            "pardon-ip", "whitelist", "save-off", "save-all", "save-on", "kick", "reload", "debug", "jfr", "perf", "publish", "forge"));
    /** Allow command pieces in projects saved by players below commandPermissionLevel (not recommended). */
    public boolean allowUntrustedCommands = false;
    public MonitorVisibility monitorVisibility = MonitorVisibility.OWN_AND_PARTICIPATING;
    public boolean announcementsEnabled = true;
    public int historySize = 200;
    public int historyLogLines = 100;
    public int syncIntervalTicks = 5;
    public int overlayHeartbeatTicks = 20;
    /** Ticks a finished task stays visible (terminal state) before moving to history. */
    public int finishedLingerTicks = 60;
    public int audioBytesPerTickPerPlayer = 96 * 1024;
    public int uploadsPerMinute = 30;
    /** Install the built-in tutorials and examples into worlds that do not have them yet. */
    public boolean installExamples = true;
    public JsonObject extra = new JsonObject();

    public ServerSettings() {
        for (Permission p : Permission.values()) permissionLevels.put(p, p.defaultLevel);
    }

    public int level(Permission p) { return permissionLevels.getOrDefault(p, p.defaultLevel); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", 1);
        o.add("limits", limits.toJson());
        JsonObject perms = new JsonObject();
        for (var e : permissionLevels.entrySet()) perms.addProperty(e.getKey().id, e.getValue());
        o.add("permissionLevels", perms);
        o.addProperty("commandPermissionLevel", commandPermissionLevel);
        o.add("commandBlacklist", J.toArray(commandBlacklist));
        o.addProperty("allowUntrustedCommands", allowUntrustedCommands);
        o.addProperty("monitorVisibility", monitorVisibility.name());
        o.addProperty("announcementsEnabled", announcementsEnabled);
        o.addProperty("historySize", historySize);
        o.addProperty("historyLogLines", historyLogLines);
        o.addProperty("syncIntervalTicks", syncIntervalTicks);
        o.addProperty("overlayHeartbeatTicks", overlayHeartbeatTicks);
        o.addProperty("finishedLingerTicks", finishedLingerTicks);
        o.addProperty("audioBytesPerTickPerPlayer", audioBytesPerTickPerPlayer);
        o.addProperty("uploadsPerMinute", uploadsPerMinute);
        o.addProperty("installExamples", installExamples);
        J.mergeExtra(o, extra);
        return o;
    }

    public static ServerSettings fromJson(JsonObject o) {
        ServerSettings s = new ServerSettings();
        if (o == null) return s;
        s.limits = Limits.fromJson(J.obj(o, "limits"));
        JsonObject perms = J.obj(o, "permissionLevels");
        if (perms != null) for (var e : perms.entrySet()) {
            Permission p = Permission.byId(e.getKey());
            if (p != null) s.permissionLevels.put(p, Math.max(0, Math.min(4, J.i(perms, e.getKey(), p.defaultLevel))));
        }
        s.commandPermissionLevel = Math.max(0, Math.min(4, J.i(o, "commandPermissionLevel", s.commandPermissionLevel)));
        if (o.has("commandBlacklist")) s.commandBlacklist = new ArrayList<>(J.strings(o, "commandBlacklist"));
        s.allowUntrustedCommands = J.b(o, "allowUntrustedCommands", false);
        s.monitorVisibility = J.en(o, "monitorVisibility", MonitorVisibility.class, s.monitorVisibility);
        s.announcementsEnabled = J.b(o, "announcementsEnabled", true);
        s.historySize = Math.max(0, Math.min(10000, J.i(o, "historySize", s.historySize)));
        s.historyLogLines = Math.max(0, Math.min(2000, J.i(o, "historyLogLines", s.historyLogLines)));
        s.syncIntervalTicks = Math.max(1, Math.min(100, J.i(o, "syncIntervalTicks", s.syncIntervalTicks)));
        s.overlayHeartbeatTicks = Math.max(5, Math.min(200, J.i(o, "overlayHeartbeatTicks", s.overlayHeartbeatTicks)));
        s.finishedLingerTicks = Math.max(0, Math.min(1200, J.i(o, "finishedLingerTicks", s.finishedLingerTicks)));
        s.audioBytesPerTickPerPlayer = Math.max(1024, Math.min(1024 * 1024, J.i(o, "audioBytesPerTickPerPlayer", s.audioBytesPerTickPerPlayer)));
        s.uploadsPerMinute = Math.max(1, Math.min(1000, J.i(o, "uploadsPerMinute", s.uploadsPerMinute)));
        s.installExamples = J.b(o, "installExamples", true);

        // Migration of CommandSequencer 1.0 "commandsequencer-server.json" keys.
        if (o.has("requireOpLevelToRun")) s.permissionLevels.put(Permission.RUN, Math.max(0, Math.min(4, J.i(o, "requireOpLevelToRun", 2))));
        if (o.has("maxChainDepth")) s.limits.maxChainDepth = Math.max(0, J.i(o, "maxChainDepth", 8));
        if (o.has("maxDurationSeconds")) s.limits.maxTimelineTicks = Math.max(20, J.i(o, "maxDurationSeconds", 3600) * 20);
        if (o.has("maxEventsPerCSQ")) s.limits.maxPiecesPerProject = Math.max(1, J.i(o, "maxEventsPerCSQ", 256) * 4);
        if (o.has("allowCustomCSQUpload") && J.b(o, "allowCustomCSQUpload", false)) s.permissionLevels.put(Permission.UPLOAD, 0);

        s.extra = J.extra(o, java.util.Set.of("schemaVersion", "limits", "permissionLevels", "commandPermissionLevel", "commandBlacklist",
                "allowUntrustedCommands", "monitorVisibility", "announcementsEnabled", "historySize", "historyLogLines",
                "syncIntervalTicks", "overlayHeartbeatTicks", "finishedLingerTicks", "audioBytesPerTickPerPlayer", "uploadsPerMinute", "installExamples",
                "requireOpLevelToRun", "maxChainDepth", "maxDurationSeconds", "maxEventsPerCSQ", "allowCustomCSQUpload",
                "allowCommandBlockExecution"));
        return s;
    }
}
