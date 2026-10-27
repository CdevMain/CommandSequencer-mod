package csequencer.core.model;

import com.google.gson.JsonObject;
import csequencer.core.json.J;
import csequencer.core.model.Policies.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Per-project execution and presentation settings. */
public final class ProjectSettings {
    public ExecutionScope scope = ExecutionScope.SERVER;
    public ConcurrencyPolicy concurrency = ConcurrencyPolicy.IGNORE_NEW;
    public DisconnectPolicy disconnect = DisconnectPolicy.CONTINUE;
    /** Ticks a PAUSE_UNTIL_RETURN task waits before it is cancelled. */
    public int disconnectGraceTicks = 20 * 60 * 5;
    public RecoveryPolicy recovery = RecoveryPolicy.DISCARD;
    public TimingMode timing = TimingMode.TICK;
    public int maxCatchUpTicks = 40;
    public ErrorPolicy errorPolicy = ErrorPolicy.CONTINUE;
    /** Higher priority tasks are processed first within a tick and win overlay space. */
    public int priority = 0;
    public AnnounceMode announce = AnnounceMode.ALL;
    public List<String> announceTeams = new ArrayList<>();
    /** Announce the next phase this many ticks before it begins (0 = disabled). */
    public int preAnnounceTicks = 60;
    /** Keep the overlay visible for the whole run instead of only around phase changes. */
    public boolean overlayKeepVisible = true;
    /** Dimension id for DIMENSION scope / dimension specific sequences (empty = any). */
    public String dimension = "";
    /** Default interval (ticks) for polled condition checks. */
    public int evalIntervalTicks = 1;
    /** Optional explicit duration of the main timeline, used for progress (0 = auto). */
    public int lengthTicks = 0;
    /** Player (name) the sequence tracks by default; empty = the player who started it. */
    public String trackedPlayer = "";
    public JsonObject extra = new JsonObject();

    private static final Set<String> KNOWN = Set.of("scope", "concurrency", "disconnect", "disconnectGraceTicks", "recovery",
            "timing", "maxCatchUpTicks", "errorPolicy", "priority", "announce", "announceTeams", "preAnnounceTicks",
            "overlayKeepVisible", "dimension", "evalIntervalTicks", "lengthTicks", "trackedPlayer");

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("scope", scope.name());
        o.addProperty("concurrency", concurrency.name());
        o.addProperty("disconnect", disconnect.name());
        o.addProperty("disconnectGraceTicks", disconnectGraceTicks);
        o.addProperty("recovery", recovery.name());
        o.addProperty("timing", timing.name());
        o.addProperty("maxCatchUpTicks", maxCatchUpTicks);
        o.addProperty("errorPolicy", errorPolicy.name());
        o.addProperty("priority", priority);
        o.addProperty("announce", announce.name());
        o.add("announceTeams", J.toArray(announceTeams));
        o.addProperty("preAnnounceTicks", preAnnounceTicks);
        o.addProperty("overlayKeepVisible", overlayKeepVisible);
        o.addProperty("dimension", dimension);
        o.addProperty("evalIntervalTicks", evalIntervalTicks);
        o.addProperty("lengthTicks", lengthTicks);
        o.addProperty("trackedPlayer", trackedPlayer);
        J.mergeExtra(o, extra);
        return o;
    }

    public static ProjectSettings fromJson(JsonObject o) {
        ProjectSettings s = new ProjectSettings();
        if (o == null) return s;
        s.scope = J.en(o, "scope", ExecutionScope.class, s.scope);
        s.concurrency = J.en(o, "concurrency", ConcurrencyPolicy.class, s.concurrency);
        s.disconnect = J.en(o, "disconnect", DisconnectPolicy.class, s.disconnect);
        s.disconnectGraceTicks = Math.max(0, J.i(o, "disconnectGraceTicks", s.disconnectGraceTicks));
        s.recovery = J.en(o, "recovery", RecoveryPolicy.class, s.recovery);
        s.timing = J.en(o, "timing", TimingMode.class, s.timing);
        s.maxCatchUpTicks = Math.max(0, Math.min(200, J.i(o, "maxCatchUpTicks", s.maxCatchUpTicks)));
        s.errorPolicy = J.en(o, "errorPolicy", ErrorPolicy.class, s.errorPolicy);
        s.priority = J.i(o, "priority", 0);
        s.announce = J.en(o, "announce", AnnounceMode.class, s.announce);
        s.announceTeams = J.strings(o, "announceTeams");
        s.preAnnounceTicks = Math.max(0, J.i(o, "preAnnounceTicks", s.preAnnounceTicks));
        s.overlayKeepVisible = J.b(o, "overlayKeepVisible", s.overlayKeepVisible);
        s.dimension = J.str(o, "dimension", "");
        s.evalIntervalTicks = Math.max(1, J.i(o, "evalIntervalTicks", 1));
        s.lengthTicks = Math.max(0, J.i(o, "lengthTicks", 0));
        s.trackedPlayer = J.str(o, "trackedPlayer", "").trim();
        s.extra = J.extra(o, KNOWN);
        return s;
    }
}
