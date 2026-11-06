package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.examples.Examples;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.AudioRef;
import csequencer.core.model.Piece;
import csequencer.core.model.Project;
import csequencer.core.platform.ClientChannel.Topic;
import csequencer.core.security.Limits;
import csequencer.core.validate.Validator;
import csequencer.core.value.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The importable area templates: run a command on entry, music only while inside. */
class AreaTemplatesTest {
    final TestKit kit = new TestKit();

    private static Project roundTrip(Project p) { return ProjectCodec.read(ProjectCodec.write(p)).project(); }

    private List<JsonObject> audio(FakePlatform.Player p) {
        return kit.platform.sentTo(p, Topic.AUDIO).stream().map(FakePlatform.Sent::payload).toList();
    }

    @Test void commandRunsOnceWhenAPlayerWalksIn() {
        Project p = roundTrip(Examples.areaCommand());
        assertFalse(Validator.validate(p, null, new Limits(), null, n -> true).hasErrors());
        kit.register(p);
        FakePlatform.Player a = kit.platform.join("A");
        a.pos = new Vec3d(50, 64, 50);
        kit.ticks(20);
        a.pos = new Vec3d(5, 64, 5);
        kit.ticks(40);
        assertEquals(List.of("say A entered the area!"), kit.commands());
    }

    @Test void musicPlaysOnlyForThePlayerInsideAndFadesOutWhenTheyLeave() {
        Project p = roundTrip(Examples.areaMusic());
        AudioRef song = new AudioRef();
        song.id = "song";
        song.name = "Song";
        song.hash = "0123456789abcdef0123456789abcdef01234567";
        song.durationMs = 120_000;
        p.audio.add(song);
        for (Piece x : p.main.pieces) if (x.type.equals("audio.play")) x.params.addProperty("audio", "song");
        assertFalse(Validator.validate(p, null, new Limits(), h -> true, n -> true).hasErrors());
        kit.register(p);
        FakePlatform.Player a = kit.platform.join("A");
        FakePlatform.Player b = kit.platform.join("B");
        a.pos = new Vec3d(50, 64, 50);
        b.pos = new Vec3d(80, 64, 80);
        kit.ticksWithClock(20);
        a.pos = new Vec3d(5, 64, 5);
        kit.ticksWithClock(40);
        JsonObject play = audio(a).stream().filter(o -> o.get("op").getAsString().equals("play")).findFirst().orElseThrow();
        assertTrue(play.get("loop").getAsBoolean());
        assertTrue(audio(b).isEmpty(), "only the player inside hears it");
        assertTrue(audio(a).stream().noneMatch(o -> o.get("op").getAsString().equals("stop")), "keeps playing while inside");
        a.pos = new Vec3d(30, 64, 30);
        kit.ticksWithClock(80);
        JsonObject stop = audio(a).stream().filter(o -> o.get("op").getAsString().equals("stop")).findFirst().orElseThrow();
        assertEquals(1500, stop.get("fadeMs").getAsLong());
        assertTrue(kit.engine.tasks().stream().allMatch(t -> t.state().terminal()), "the sequence ends after leaving");
    }
}
