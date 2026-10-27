package csequencer.core;

import csequencer.core.engine.GameEvent;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.model.Piece;
import csequencer.core.model.Project;
import csequencer.core.model.Timeline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tracked player (target mode "tracked", Track Player piece) and the join/leave pieces. */
class TrackingTest {
    final TestKit kit = new TestKit();

    private Piece chat(Timeline tl, int track, int tick, String msg) {
        Piece p = piece(tl, track, tick, "action.chat");
        p.params.addProperty("message", msg);
        return p;
    }

    /** Chat messages as "message@targets". */
    private List<String> chats() {
        List<String> out = new ArrayList<>();
        for (String a : kit.platform.actions) {
            String s = a.substring(a.indexOf(':') + 1);
            if (s.startsWith("action.chat:")) out.add(s.substring("action.chat:".length()));
        }
        return out;
    }

    @Test void newPiecesTargetTheTrackedPlayerWhichDefaultsToTheInitiator() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        kit.platform.join("Bob");
        Project p = project("t");
        chat(p.main, 0, 0, "hi {tracked}");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(2);
        assertEquals(List.of("hi Alice@Alice"), chats());
    }

    @Test void projectTrackedPlayerIsUsedWhenOnline() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        kit.platform.join("Bob");
        Project p = project("t");
        p.settings.trackedPlayer = "Bob";
        chat(p.main, 0, 0, "hello {player}");
        Piece health = piece(p.main, 0, 1, "logic.gate");
        health.params.add("condition", csequencer.core.model.ConditionGraph.single(cmp(q("player.name"), "eq", "Bob")).toJson());
        chat(p.main, 0, 2, "conditions see Bob too");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(4);
        assertEquals(List.of("hello Bob@Bob", "conditions see Bob too@Bob"), chats());
        assertEquals("Bob", t.tracked().name());
    }

    @Test void offlineTrackedPlayerFallsBackToInitiatorWithAWarning() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("t");
        p.settings.trackedPlayer = "Ghost";
        chat(p.main, 0, 0, "x");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(2);
        assertEquals(List.of("x@Alice"), chats());
        assertTrue(t.log().stream().anyMatch(l -> l.contains("Ghost") && l.contains("not online")));
    }

    @Test void trackPlayerPieceSwitchesTheTargetMidSequence() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        kit.platform.join("Bob");
        Project p = project("t");
        chat(p.main, 0, 0, "first");
        Piece tr = piece(p.main, 0, 5, "track.player");
        tr.params.addProperty("source", "named");
        tr.params.addProperty("player", "Bob");
        chat(p.main, 0, 6, "second");
        chat(p.main, 1, 6, "other track follows too");
        Piece explicit = chat(p.main, 1, 7, "explicit initiator");
        explicit.params.add("targets", csequencer.core.pieces.PieceRegistry.targetMode("initiator"));
        kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(10);
        assertEquals(List.of("first@Alice", "second@Bob", "other track follows too@Bob", "explicit initiator@Alice"), chats());
    }

    @Test void trackingAnOfflinePlayerFailsClearly() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        Project p = project("t");
        Piece tr = piece(p.main, 0, 0, "track.player");
        tr.params.addProperty("player", "Nobody");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(2);
        assertTrue(t.lastError().contains("Nobody") && t.lastError().contains("not online"), t.lastError());
        assertEquals("Alice", t.tracked().name(), "tracking unchanged");
    }

    @Test void onPlayerJoinFiltersByPlayerAndCanTrackThem() {
        Project p = project("join");
        Timeline welcome = function(p, "welcome");
        chat(welcome, 0, 0, "welcome {player}");
        Piece j = piece(p.main, 0, 0, "event.player_join");
        j.params.addProperty("who", "named");
        j.params.addProperty("player", "Steve");
        j.params.addProperty("function", welcome.id);
        j.params.addProperty("trackJoined", true);
        chat(p.main, 0, 40, "later message");
        Task t = kit.start(p);
        kit.ticks(2);
        FakePlatform.Player alex = kit.platform.join("Alex");
        kit.engine.post(GameEvent.of("player.join", alex.ref, "", null, Map.of()));
        FakePlatform.Player steve = kit.platform.join("Steve");
        kit.engine.post(GameEvent.of("player.join", steve.ref, "", null, Map.of()));
        kit.ticks(45);
        assertEquals(List.of("welcome Steve@Steve", "later message@Steve"), chats(), "only Steve matched; later pieces now target him");
        assertEquals("Steve", t.tracked().name());
    }

    @Test void onPlayerLeaveCanWaitForTheTrackedPlayer() {
        FakePlatform.Player alice = kit.platform.join("Alice");
        FakePlatform.Player bob = kit.platform.join("Bob");
        Project p = project("leave");
        Piece w = piece(p.main, 0, 0, "event.player_leave");
        w.params.addProperty("who", "tracked");
        w.params.addProperty("mode", "wait");
        Piece after = piece(p.main, 0, 0, "action.chat");
        after.params.addProperty("message", "{event.player} left");
        after.params.add("targets", csequencer.core.pieces.PieceRegistry.targetMode("all"));
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(alice.ref));
        kit.ticks(3);
        kit.engine.post(GameEvent.of("player.leave", bob.ref, "", null, Map.of()));
        kit.ticks(3);
        assertTrue(chats().isEmpty(), "Bob is not the tracked player");
        assertEquals(TaskState.WAITING, t.state());
        kit.engine.post(GameEvent.of("player.leave", alice.ref, "", null, Map.of()));
        kit.platform.leave(alice);
        kit.ticks(3);
        assertEquals(1, chats().size());
        assertTrue(chats().get(0).startsWith("Alice left"));
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void trackedSettingSurvivesSaveAndLoad() {
        Project p = project("persist");
        p.settings.trackedPlayer = "Bob";
        Project back = csequencer.core.json.ProjectCodec.read(csequencer.core.json.ProjectCodec.write(p)).project();
        assertEquals("Bob", back.settings.trackedPlayer);
    }
}
