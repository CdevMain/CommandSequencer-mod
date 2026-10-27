package csequencer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.engine.GameEvent;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.Task;
import csequencer.core.model.*;
import csequencer.core.platform.ClientChannel.Topic;
import csequencer.core.security.ServerSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Multiplayer synchronization: what each client is sent is decided on the server from real
 * engine state and the viewer's permissions.
 */
class SyncTest {
    final TestKit kit = new TestKit();

    private Project phased(String name) {
        Project p = project(name);
        p.main.markers.add(new Marker("Intro", 0, Policies.MarkerKind.PHASE));
        p.main.markers.add(new Marker("Awakening", 40, Policies.MarkerKind.PHASE));
        p.main.markers.add(new Marker("Lockdown", 80, Policies.MarkerKind.PHASE));
        cmd(p.main, 0, 100, "end");
        return p;
    }

    private JsonArray lastTasks(FakePlatform.Player p) {
        List<FakePlatform.Sent> s = kit.platform.sentTo(p, Topic.TASKS);
        assertFalse(s.isEmpty(), "no TASKS payload sent to " + p.ref.name());
        return s.get(s.size() - 1).payload().getAsJsonArray("tasks");
    }

    private JsonArray lastOverlay(FakePlatform.Player p) {
        List<FakePlatform.Sent> s = kit.platform.sentTo(p, Topic.OVERLAY);
        assertFalse(s.isEmpty(), "no OVERLAY payload sent to " + p.ref.name());
        return s.get(s.size() - 1).payload().getAsJsonArray("entries");
    }

    @Test void taskManagerViewsAreFilteredByPermission() {
        FakePlatform.Player admin = kit.platform.join("Admin");
        admin.op = 4;
        FakePlatform.Player alice = kit.platform.join("Alice");
        FakePlatform.Player bob = kit.platform.join("Bob");
        Project p = phased("Dragon Arena");
        p.settings.scope = Policies.ExecutionScope.PLAYER;
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        for (FakePlatform.Player pl : List.of(admin, alice, bob)) kit.engine.sync.subscribe(pl.ref.uuid(), Topic.TASKS, true);
        kit.ticks(6);
        assertEquals(1, lastTasks(admin).size(), "admins see every task");
        assertEquals(1, lastTasks(alice).size(), "owners see their own task");
        assertEquals(0, lastTasks(bob).size(), "others see nothing by default");
        assertTrue(lastTasks(admin).get(0).getAsJsonObject().get("canControl").getAsBoolean());
        assertFalse(lastTasks(alice).get(0).getAsJsonObject().get("canControl").getAsBoolean(), "op level 0 lacks CONTROL_OWN (level 2)");

        kit.settings.monitorVisibility = ServerSettings.MonitorVisibility.ALL;
        kit.ticks(5);
        assertEquals(1, lastTasks(bob).size());
    }

    @Test void taskSnapshotReflectsRealState() {
        FakePlatform.Player admin = kit.platform.join("Admin");
        admin.op = 4;
        kit.engine.sync.subscribe(admin.ref.uuid(), Topic.TASKS, true);
        Task t = kit.start(phased("Dragon Arena"));
        kit.ticks(50);
        JsonObject s = lastTasks(admin).get(0).getAsJsonObject();
        assertEquals(t.id(), s.get("id").getAsString());
        assertEquals("Awakening", s.get("phase").getAsString());
        assertEquals("Lockdown", s.get("nextPhase").getAsString());
        assertEquals(t.mainPosition(), s.get("position").getAsInt());
        assertEquals(100, s.get("length").getAsInt());
        assertEquals("RUNNING", s.get("state").getAsString());
        assertTrue(s.getAsJsonObject("caps").get("pause").getAsBoolean());
        kit.engine.pause(t, Task.PauseReason.USER);
        kit.ticks(1);
        s = lastTasks(admin).get(0).getAsJsonObject();
        assertEquals("PAUSED", s.get("state").getAsString(), "state change pushed immediately");
        assertTrue(s.getAsJsonObject("caps").get("resume").getAsBoolean());
    }

    @Test void overlayAnnouncementModes() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        FakePlatform.Player bob = kit.platform.join("Bob");
        bob.teams.add("red");
        Project all = phased("Public Show");
        Project parts = phased("Private Quest");
        parts.settings.announce = Policies.AnnounceMode.PARTICIPANTS;
        Project teams = phased("Red Team Event");
        teams.settings.announce = Policies.AnnounceMode.TEAMS;
        teams.settings.announceTeams.add("red");
        Project none = phased("Silent");
        none.settings.announce = Policies.AnnounceMode.NONE;
        kit.start(all);
        kit.start(new StartRequest(parts, meta(parts, 4)).initiator(alice.ref));
        kit.start(teams);
        kit.start(none);
        kit.ticks(5);
        List<String> aliceSees = names(lastOverlay(alice));
        List<String> bobSees = names(lastOverlay(bob));
        assertEquals(List.of("Private Quest", "Public Show"), aliceSees, "personal sequences first");
        assertTrue(bobSees.containsAll(List.of("Public Show", "Red Team Event")));
        assertFalse(bobSees.contains("Private Quest"));
        assertFalse(bobSees.contains("Silent"));
        JsonObject personal = lastOverlay(alice).get(0).getAsJsonObject();
        assertTrue(personal.get("personal").getAsBoolean());
        assertFalse(lastOverlay(bob).get(0).getAsJsonObject().get("personal").getAsBoolean());

        kit.settings.announcementsEnabled = false;
        kit.ticks(25);
        assertEquals(0, lastOverlay(alice).size(), "server admins can disable announcements");
    }

    private static List<String> names(JsonArray entries) {
        List<String> out = new java.util.ArrayList<>();
        for (var e : entries) out.add(e.getAsJsonObject().get("name").getAsString());
        return out;
    }

    @Test void overlayCarriesPhasesProgressAndPreAnnouncement() {
        FakePlatform.Player p = kit.platform.join("P");
        Project proj = phased("Dragon Arena");
        proj.settings.preAnnounceTicks = 20;
        kit.start(proj);
        kit.ticks(15);
        JsonObject e = lastOverlay(p).get(0).getAsJsonObject();
        assertEquals("Intro", e.get("phase").getAsString());
        assertEquals("Awakening", e.get("next").getAsString());
        assertFalse(e.get("preAnnounce").getAsBoolean(), "next phase is still more than 20 ticks away");
        kit.ticks(10);
        e = lastOverlay(p).get(0).getAsJsonObject();
        assertTrue(e.get("preAnnounce").getAsBoolean(), "next phase announced before it begins");
        assertTrue(e.get("ticksToNext").getAsInt() <= 20);
        double progress = e.get("progress").getAsDouble();
        assertTrue(progress > 0.18 && progress < 0.3, "progress " + progress);
    }

    @Test void overlayHeartbeatLetsClientsDetectStaleness() {
        FakePlatform.Player p = kit.platform.join("P");
        Project proj = phased("Show");
        Task t = kit.start(proj);
        kit.ticks(2);
        kit.engine.pause(t, Task.PauseReason.USER);
        kit.ticks(3);
        int before = kit.platform.sentTo(p, Topic.OVERLAY).size();
        kit.ticks(kit.settings.overlayHeartbeatTicks + 1);
        assertTrue(kit.platform.sentTo(p, Topic.OVERLAY).size() > before, "unchanged state is still re-sent as a heartbeat");
        JsonObject last = kit.platform.sentTo(p, Topic.OVERLAY).get(kit.platform.sentTo(p, Topic.OVERLAY).size() - 1).payload();
        assertTrue(last.has("serverTick") && last.has("serverMs"), "payload is timestamped");
    }

    @Test void lateJoinerReceivesCurrentStateAndAudioPosition() {
        Project proj = phased("Concert");
        AudioRef a = new AudioRef();
        a.id = "song";
        a.name = "Song";
        a.hash = "0123456789abcdef0123456789abcdef01234567";
        a.durationMs = 120_000;
        proj.audio.add(a);
        Piece play = piece(proj.main, 1, 0, "audio.play");
        play.params.addProperty("audio", "song");
        kit.start(proj);
        kit.ticksWithClock(40); // 2 seconds in
        FakePlatform.Player late = kit.platform.join("Late");
        kit.engine.onPlayerJoin(late.ref);
        List<FakePlatform.Sent> audio = kit.platform.sentTo(late, Topic.AUDIO);
        assertEquals(1, audio.size());
        JsonObject msg = audio.get(0).payload();
        assertEquals("play", msg.get("op").getAsString());
        assertTrue(msg.get("positionMs").getAsLong() >= 1900, "joins at the current position, not from the start");
        kit.ticks(1);
        assertEquals(1, lastOverlay(late).size(), "overlay state sent to the late joiner");
    }

    @Test void reconnectDoesNotRepeatOncePerPlayerTriggers() {
        Project welcome = project("Welcome");
        cmd(welcome.main, 0, 0, "welcome {player}");
        Trigger t = new Trigger();
        t.id = "join";
        t.eventType = "player.join";
        t.oncePerPlayer = true;
        welcome.triggers.add(t);
        welcome.settings.concurrency = Policies.ConcurrencyPolicy.ALLOW_PARALLEL;
        kit.register(welcome);
        FakePlatform.Player s = kit.platform.join("Steve");
        GameEvent join = GameEvent.of("player.join", s.ref, "", null, Map.of());
        kit.engine.post(join);
        kit.ticks(3);
        kit.engine.post(join); // duplicated delivery
        kit.engine.post(GameEvent.of("player.join", s.ref, "", null, Map.of())); // real reconnect
        kit.ticks(3);
        assertEquals(List.of("welcome Steve"), kit.commands());
        JsonObject runtime = kit.engine.exportRuntime();
        TestKit fresh = new TestKit();
        fresh.catalog.add(kit.catalog.get(0));
        fresh.engine.importRuntime(runtime);
        fresh.platform.players.put(s.ref.uuid(), s);
        fresh.engine.post(GameEvent.of("player.join", s.ref, "", null, Map.of()));
        fresh.ticks(3);
        assertTrue(fresh.commands().isEmpty(), "once-per-player state survives a server restart");
    }

    @Test void conditionTriggerStartsSequenceOnRegionEntry() {
        Project arena = project("Arena");
        arena.settings.scope = Policies.ExecutionScope.PLAYER;
        cmd(arena.main, 0, 0, "arena {player}");
        Trigger t = new Trigger();
        t.id = "enter";
        t.kind = Policies.TriggerKind.CONDITION;
        t.subject = Policies.TriggerSubject.EACH_PLAYER;
        t.intervalTicks = 1;
        t.condition = ConditionGraph.single(cmp(q("player.in_region", "from", "0 0 0", "to", "10 100 10"), "is_true", null));
        arena.triggers.add(t);
        kit.register(arena);
        FakePlatform.Player a = kit.platform.join("A");
        a.pos = new csequencer.core.value.Vec3d(100, 64, 100);
        kit.ticks(3);
        a.pos = new csequencer.core.value.Vec3d(5, 64, 5);
        kit.ticks(10);
        assertEquals(List.of("arena A"), kit.commands(), "fires once on entry, not every tick while inside");
    }

    @Test void areaTriggerFiresOnEntryOnlyWhenConditionHolds() {
        Project arena = project("Area");
        arena.settings.scope = Policies.ExecutionScope.PLAYER;
        cmd(arena.main, 0, 0, "area {player}");
        Trigger t = new Trigger();
        t.id = "box";
        t.kind = Policies.TriggerKind.AREA;
        t.subject = Policies.TriggerSubject.EACH_PLAYER;
        t.intervalTicks = 1;
        t.areaFrom = new int[]{10, 60, 10};
        t.areaTo = new int[]{0, 70, 0};
        t.condition = ConditionGraph.single(cmp(q("player.state", "state", "sneaking"), "is_true", null));
        arena.triggers.add(t);
        assertFalse(csequencer.core.validate.Validator.validate(arena, null, new csequencer.core.security.Limits(), null, n -> true).hasErrors());
        kit.register(arena);
        FakePlatform.Player a = kit.platform.join("A");
        FakePlatform.Player b = kit.platform.join("B");
        a.pos = new csequencer.core.value.Vec3d(100, 64, 100);
        b.pos = new csequencer.core.value.Vec3d(100, 64, 100);
        b.sneaking = true;
        kit.ticks(3);
        a.pos = new csequencer.core.value.Vec3d(5.5, 64, 5.5);   // inside, but not sneaking → blocked by the condition
        b.pos = new csequencer.core.value.Vec3d(10.9, 70.5, 0.2); // inside (corners are inclusive) and sneaking
        kit.ticks(10);
        assertEquals(List.of("area B"), kit.commands());
        a.sneaking = true;
        kit.ticks(3);
        assertEquals(List.of("area B", "area A"), kit.commands(), "condition becoming true inside the area fires");
        b.pos = new csequencer.core.value.Vec3d(11.0, 64, 5);    // just outside
        kit.ticks(3);
        assertEquals(3 - 1, kit.commands().size(), "leaving does not fire an ENTERED trigger");
    }

    @Test void monitorListsAvailableAndRunningSequences() {
        FakePlatform.Player admin = kit.platform.join("Admin");
        admin.op = 4;
        Project idle = project("Idle One");
        kit.register(idle);
        Project running = phased("Busy One");
        kit.register(running);
        kit.start(running);
        kit.engine.sync.subscribe(admin.ref.uuid(), Topic.MONITOR, true);
        kit.ticks(6);
        List<FakePlatform.Sent> m = kit.platform.sentTo(admin, Topic.MONITOR);
        JsonArray projects = m.get(m.size() - 1).payload().getAsJsonArray("projects");
        assertEquals(2, projects.size());
        for (var e : projects) {
            JsonObject o = e.getAsJsonObject();
            String expected = o.get("name").getAsString().equals("Busy One") ? "RUNNING" : "AVAILABLE";
            assertEquals(expected, o.get("status").getAsString());
        }
    }

    @Test void historyIsFilteredForNonAdmins() {
        FakePlatform.Player admin = kit.platform.join("Admin");
        admin.op = 4;
        FakePlatform.Player alice = kit.platform.join("Alice");
        FakePlatform.Player bob = kit.platform.join("Bob");
        Project p = project("Hist");
        p.settings.concurrency = Policies.ConcurrencyPolicy.ALLOW_PARALLEL;
        cmd(p.main, 0, 0, "x");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.start(new StartRequest(p, meta(p, 4)).initiator(bob.ref));
        kit.ticks(3);
        kit.engine.sync.sendHistory(admin.ref.uuid());
        kit.engine.sync.sendHistory(alice.ref.uuid());
        assertEquals(2, kit.platform.sentTo(admin, Topic.HISTORY).get(0).payload().getAsJsonArray("records").size());
        assertEquals(1, kit.platform.sentTo(alice, Topic.HISTORY).get(0).payload().getAsJsonArray("records").size());
    }

    @Test void taskDetailRequiresVisibility() {
        FakePlatform.Player bob = kit.platform.join("Bob");
        Task t = kit.start(phased("Secret"));
        kit.ticks(2);
        assertFalse(kit.engine.sync.sendDetail(bob.ref.uuid(), t.id()));
        bob.op = 4;
        assertTrue(kit.engine.sync.sendDetail(bob.ref.uuid(), t.id()));
        JsonObject d = kit.platform.sentTo(bob, Topic.TASK_DETAIL).get(0).payload();
        assertTrue(d.getAsJsonArray("branchList").size() > 0);
        assertTrue(d.getAsJsonArray("log").size() > 0);
    }
}
