package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.model.AudioRef;
import csequencer.core.model.Piece;
import csequencer.core.model.Project;
import csequencer.core.model.Timeline;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.platform.ClientChannel.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Popups, overlays, markers, screen effects, boss bars, cut audio and Wait For Death. */
class HudAndBossTest {
    final TestKit kit = new TestKit();

    private List<JsonObject> hud(FakePlatform.Player p) {
        return kit.platform.sentTo(p, Topic.HUD).stream().map(FakePlatform.Sent::payload).toList();
    }

    private JsonObject last(FakePlatform.Player p, String op) {
        List<JsonObject> l = hud(p).stream().filter(o -> o.get("op").getAsString().equals(op)).toList();
        assertFalse(l.isEmpty(), "no HUD '" + op + "' message");
        return l.get(l.size() - 1);
    }

    @Test void popupGoesToTargetsWithItsContent() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        FakePlatform.Player bob = kit.platform.join("Bob");
        Project p = project("popup");
        Piece pop = piece(p.main, 0, 0, "hud.popup");
        pop.params.addProperty("title", "Boss!");
        pop.params.addProperty("message", "Run, {player}");
        pop.params.addProperty("style", "danger");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(2);
        JsonObject m = last(alice, "show");
        assertEquals("popup", m.get("kind").getAsString());
        assertEquals("Run, Alice", m.get("message").getAsString());
        assertEquals(5000, m.get("durationMs").getAsLong());
        assertTrue(hud(bob).isEmpty(), "default target is the tracked player only");
    }

    @Test void overlayIsLiveForLateJoinersAndRemovedWhenTheSequenceEnds() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("overlay");
        Piece set = piece(p.main, 0, 0, "var.set");
        set.params.addProperty("name", "hp");
        set.params.addProperty("value", 80);
        Piece ov = piece(p.main, 0, 0, "hud.overlay");
        ov.params.addProperty("id", "boss");
        ov.params.addProperty("title", "Boss {hp}");
        ov.params.add("value", q("var", "name", "hp"));
        ov.params.add("targets", PieceRegistry.targetMode("all"));
        Piece set2 = piece(p.main, 0, 10, "var.set");
        set2.params.addProperty("name", "hp");
        set2.params.addProperty("value", 30);
        Piece end = piece(p.main, 0, 40, "flow.end");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(3);
        JsonObject shown = last(alice, "show");
        assertEquals("overlay", shown.get("kind").getAsString());
        assertEquals(80, shown.get("value").getAsInt());
        assertEquals("Boss 80", shown.get("title").getAsString());
        kit.ticks(15);
        JsonObject upd = last(alice, "update");
        assertEquals(30, upd.get("value").getAsInt(), "live refresh follows the variable");
        assertEquals("Boss 30", upd.get("title").getAsString());
        FakePlatform.Player late = kit.platform.join("Late");
        kit.engine.onPlayerJoin(late.ref);
        assertEquals(30, last(late, "show").get("value").getAsInt(), "late joiners get the current overlay");
        kit.ticks(30);
        assertEquals(TaskState.COMPLETED, t.state());
        assertEquals(shown.get("id").getAsString(), last(alice, "hide").get("id").getAsString(), "removed when the sequence ends");
        assertTrue(kit.engine.hud.elementsOf(t.id()).isEmpty());
    }

    @Test void markerAndHide() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("marker");
        Piece m = piece(p.main, 0, 0, "hud.marker");
        m.params.addProperty("pos", "10 70 -5");
        m.params.addProperty("label", "Arena");
        Piece hide = piece(p.main, 0, 5, "hud.hide");
        hide.params.addProperty("kind", "marker");
        piece(p.main, 0, 20, "flow.start");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(2);
        JsonObject s = last(alice, "show");
        assertEquals("marker", s.get("kind").getAsString());
        assertEquals(-5, s.getAsJsonObject("pos").get("z").getAsInt());
        assertEquals("Arena", s.get("label").getAsString());
        kit.ticks(6);
        assertEquals(s.get("id").getAsString(), last(alice, "hide").get("id").getAsString());
    }

    @Test void markerFollowsAnEntityAndDisappearsWithIt() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("follow");
        Piece spawn = piece(p.main, 0, 0, "action.spawn_entity");
        spawn.params.addProperty("storeAs", "boss");
        Piece m = piece(p.main, 0, 1, "hud.marker");
        m.params.add("follow", q("var", "name", "boss"));
        piece(p.main, 0, 100, "flow.start");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(3);
        JsonObject shown = last(alice, "show");
        assertEquals(64, shown.getAsJsonObject("pos").get("y").getAsInt());
        double[] boss = kit.platform.entities.values().iterator().next();
        boss[2] = 30;
        kit.ticks(6);
        assertEquals(30, last(alice, "update").getAsJsonObject("pos").get("x").getAsInt(), "position follows the entity");
        kit.platform.entities.clear();
        kit.ticks(6);
        assertEquals(shown.get("id").getAsString(), last(alice, "hide").get("id").getAsString(), "removed when the entity is gone");
        assertTrue(kit.engine.hud.elementsOf(t.id()).isEmpty());
    }

    @Test void heldScreenEffectsAreReleased() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("fade");
        Piece out = piece(p.main, 0, 0, "hud.screen");
        out.params.addProperty("effect", "fade_out");
        Piece in = piece(p.main, 0, 10, "hud.screen");
        in.params.addProperty("effect", "fade_in");
        in.params.addProperty("duration", 40);
        piece(p.main, 0, 20, "flow.start");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(3);
        assertEquals("fade_out", last(alice, "show").get("effect").getAsString());
        kit.ticks(10);
        JsonObject hide = last(alice, "hide");
        assertEquals(2000, hide.get("releaseMs").getAsLong(), "fade back in over the given time");
    }

    @Test void bossBarFollowsEntityHealthAndHidesOnDeath() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("bossbar");
        Piece spawn = piece(p.main, 0, 0, "action.spawn_entity");
        spawn.params.addProperty("storeAs", "boss");
        Piece bar = piece(p.main, 0, 1, "hud.bossbar");
        bar.params.add("entity", q("var", "name", "boss"));
        Piece wait = piece(p.main, 0, 2, "flow.wait_death");
        chatAfter(p.main, 2, "Boss defeated!");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(3);
        assertEquals("show:boss:100", kit.platform.bossbar.get(0));
        double[] boss = kit.platform.entities.values().iterator().next();
        boss[0] = 5;
        kit.ticks(6);
        assertTrue(kit.platform.bossbar.contains("show:boss:25"), kit.platform.bossbar.toString());
        assertEquals(TaskState.WAITING, t.state(), "waiting for the boss to die");
        boss[0] = 0;
        kit.ticks(12);
        assertTrue(kit.platform.bossbar.contains("hide:boss"), "bar hidden when the boss dies");
        assertTrue(kit.platform.actions.stream().anyMatch(a -> a.contains("Boss defeated!")));
        assertEquals(TaskState.COMPLETED, t.state());
    }

    private void chatAfter(Timeline tl, int tick, String msg) {
        Piece c = piece(tl, 0, tick, "action.chat");
        c.params.addProperty("message", msg);
    }

    @Test void waitForDeathCanRunAFunction() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("death");
        Timeline win = function(p, "win");
        Piece c = piece(win, 0, 0, "action.chat");
        c.params.addProperty("message", "victory");
        Piece spawn = piece(p.main, 0, 0, "action.spawn_entity");
        spawn.params.addProperty("storeAs", "boss");
        Piece w = piece(p.main, 0, 1, "flow.wait_death");
        w.params.addProperty("mode", "run_function");
        w.params.addProperty("function", win.id);
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(10);
        assertEquals(TaskState.WAITING, t.state(), "kept alive while watching the boss");
        kit.platform.entities.values().iterator().next()[0] = 0;
        kit.ticks(10);
        assertTrue(kit.platform.actions.stream().anyMatch(a -> a.contains("victory")));
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void cutAudioStopsAtThePieceEndWithFade() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("cut");
        AudioRef a = new AudioRef();
        a.id = "song";
        a.name = "Song";
        a.hash = "0123456789abcdef0123456789abcdef01234567";
        a.durationMs = 60_000;
        p.audio.add(a);
        Piece play = piece(p.main, 0, 0, "audio.play");
        play.params.addProperty("audio", "song");
        play.params.addProperty("offsetMs", 5000);
        play.params.addProperty("fadeOutMs", 500);
        play.duration = 40; // 2 seconds of the song: 5.0s → 7.0s
        piece(p.main, 1, 100, "flow.start");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticksWithClock(20);
        List<JsonObject> audio = kit.platform.sentTo(alice, Topic.AUDIO).stream().map(FakePlatform.Sent::payload).toList();
        assertEquals(7000, audio.get(0).get("endMs").getAsLong());
        assertTrue(audio.stream().noneMatch(o -> o.get("op").getAsString().equals("stop")), "still playing after 1s");
        kit.ticksWithClock(25);
        JsonObject stop = kit.platform.sentTo(alice, Topic.AUDIO).stream().map(FakePlatform.Sent::payload)
                .filter(o -> o.get("op").getAsString().equals("stop")).findFirst().orElseThrow();
        assertTrue(stop.get("fadeMs").getAsLong() <= 500, "fade ends exactly at the cut");
        assertTrue(kit.engine.audio.sessions().isEmpty());
    }

    @Test void changeVolumeBroadcastsARamp() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("vol");
        AudioRef a = new AudioRef();
        a.id = "song";
        a.name = "Song";
        a.hash = "0123456789abcdef0123456789abcdef01234567";
        a.durationMs = 60_000;
        p.audio.add(a);
        Piece play = piece(p.main, 0, 0, "audio.play");
        play.params.addProperty("audio", "song");
        Piece vol = piece(p.main, 0, 2, "audio.volume");
        vol.params.addProperty("volume", 0.2);
        vol.params.addProperty("fadeMs", 3000);
        piece(p.main, 0, 10, "flow.start");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(5);
        JsonObject v = kit.platform.sentTo(alice, Topic.AUDIO).stream().map(FakePlatform.Sent::payload)
                .filter(o -> o.get("op").getAsString().equals("volume")).findFirst().orElseThrow();
        assertEquals(0.2, v.get("volume").getAsDouble(), 1e-9);
        assertEquals(3000, v.get("fadeMs").getAsLong());
    }

    @Test void newPiecesAreInTheScreenCategory() {
        for (String id : List.of("hud.popup", "hud.overlay", "hud.marker", "hud.bossbar", "hud.screen", "hud.hide"))
            assertEquals("Screen", PieceRegistry.piece(id).category(), id);
    }

    @Test void arenaBarrierSendsPlayersBackUntilReleased() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("arena");
        Piece keep = piece(p.main, 0, 0, "zone.keep_inside");
        keep.params.addProperty("from", "0 60 0");
        keep.params.addProperty("to", "10 70 10");
        keep.params.addProperty("interval", 1);
        Piece release = piece(p.main, 0, 20, "zone.release");
        piece(p.main, 0, 40, "flow.start");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        alice.pos = new csequencer.core.value.Vec3d(5, 64, 5);
        kit.ticks(5);
        assertTrue(kit.platform.actions.stream().noneMatch(a -> a.contains("action.teleport")), "inside: nothing happens");
        alice.pos = new csequencer.core.value.Vec3d(50, 64, 50);
        kit.ticks(3);
        assertTrue(kit.platform.actions.stream().anyMatch(a -> a.contains("action.teleport") && a.endsWith("@Alice")), "outside: sent back");
        assertTrue(kit.platform.actions.stream().anyMatch(a -> a.contains("action.actionbar")), "with a warning");
        kit.ticks(15);
        int before = kit.platform.actions.size();
        kit.ticks(5);
        assertEquals(before, kit.platform.actions.size(), "released: no more teleports");
        kit.ticks(30);
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void barrierDoesNotKeepTheSequenceAlive() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("arena2");
        piece(p.main, 0, 0, "zone.keep_inside");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(5);
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void trackRandomParticipant() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        kit.platform.join("Bob");
        Project p = project("random");
        Piece tr = piece(p.main, 0, 0, "track.player");
        tr.params.addProperty("source", "random_online");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(2);
        assertNotNull(t.tracked());
        assertTrue(List.of("Alice", "Bob").contains(t.tracked().name()));
    }
}
