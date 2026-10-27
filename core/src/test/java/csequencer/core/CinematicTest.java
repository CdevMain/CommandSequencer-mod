package csequencer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.examples.Examples;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.Piece;
import csequencer.core.model.Project;
import csequencer.core.platform.ClientChannel.Topic;
import csequencer.core.value.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

/** Camera pieces, forced look, player controls and Screen Effect+ (server side). */
class CinematicTest {
    final TestKit kit = new TestKit();

    private List<JsonObject> hud(FakePlatform.Player p, String kind) {
        return kit.platform.sentTo(p, Topic.HUD).stream().map(FakePlatform.Sent::payload)
                .filter(o -> kind.equals(o.get("kind").getAsString())).toList();
    }

    private Task run(Project p, FakePlatform.Player who) { return kit.start(new StartRequest(p, meta(p, 4)).initiator(who.ref)); }

    @Test void pathKeyframesAreRelativeToTheTrackedPlayerAndUndoneAtTheEnd() {
        FakePlatform.Player a = kit.platform.join("A");
        a.pos = new Vec3d(100, 64, -50);
        Project p = project("cam");
        Piece path = piece(p.main, 0, 0, "camera.path");
        path.params.addProperty("points", "~2 ~1 ~ 90 10\n5 70 5\n~ ~3 ~-4 180 20");
        path.params.addProperty("duration", 40);
        path.params.addProperty("afterEnd", "hold");
        piece(p.main, 0, 30, "flow.end");
        Task t = run(p, a);
        kit.ticks(3);
        JsonObject show = hud(a, "camera").stream().filter(o -> o.get("op").getAsString().equals("show")).findFirst().orElseThrow();
        assertEquals("path", show.get("mode").getAsString());
        JsonArray pts = show.getAsJsonArray("points");
        assertEquals(3, pts.size());
        assertEquals(102, pts.get(0).getAsJsonArray().get(0).getAsDouble(), 1e-9);
        assertEquals(65, pts.get(0).getAsJsonArray().get(1).getAsDouble(), 1e-9);
        assertEquals(90, pts.get(0).getAsJsonArray().get(3).getAsDouble(), 1e-9);
        assertEquals(3, pts.get(1).getAsJsonArray().size(), "keyframe without rotation");
        assertEquals(-54, pts.get(2).getAsJsonArray().get(2).getAsDouble(), 1e-9);
        assertEquals(2000, show.get("durationMs").getAsLong());
        kit.ticks(40);
        assertTrue(t.state().terminal());
        assertTrue(hud(a, "camera").stream().anyMatch(o -> o.get("op").getAsString().equals("hide")),
                "the camera is given back when the sequence ends");
    }

    @Test void resetSendsTheReturnBlendAndInvalidPathsFail() {
        FakePlatform.Player a = kit.platform.join("A");
        Project p = project("cam2");
        Piece fixed = piece(p.main, 0, 0, "camera.fixed");
        fixed.params.addProperty("pos", "~3 ~2 ~3");
        Piece reset = piece(p.main, 0, 5, "camera.reset");
        reset.params.addProperty("blend", 30);
        Piece bad = piece(p.main, 1, 10, "camera.path");
        bad.params.addProperty("points", "1 2 3");
        piece(p.main, 0, 40, "flow.end");
        run(p, a);
        kit.ticks(20);
        List<JsonObject> cam = hud(a, "camera");
        JsonObject hide = cam.stream().filter(o -> o.get("op").getAsString().equals("hide")).findFirst().orElseThrow();
        assertEquals(1500, hide.get("releaseMs").getAsLong());
        assertEquals(1, cam.stream().filter(o -> o.get("op").getAsString().equals("show")).count(), "a path with one keyframe is not shown");
    }

    @Test void cameraExamplePlaysThroughWithForcedLookOnTheSpawnedEntity() {
        FakePlatform.Player a = kit.platform.join("A");
        Project p = ProjectCodec.read(ProjectCodec.write(Examples.camera())).project();
        Task t = run(p, a);
        kit.ticks(600);
        assertEquals(TaskState.COMPLETED, t.state(), String.join("\n", t.log()));
        Set<String> modes = new HashSet<>();
        for (JsonObject o : hud(a, "camera")) if (o.has("mode")) modes.add(o.get("mode").getAsString());
        assertEquals(Set.of("path", "fixed", "orbit"), modes);
        JsonObject look = hud(a, "look").stream().filter(o -> o.get("op").getAsString().equals("show")).findFirst().orElseThrow();
        assertEquals("entity", look.get("target").getAsString());
        assertFalse(look.get("entity").getAsString().isEmpty(), "entity uuid sent for client-side tracking");
    }

    @Test void madnessExampleAnimatesThePlayerForEveryoneAndShowsCustomWords() {
        FakePlatform.Player a = kit.platform.join("A");
        FakePlatform.Player other = kit.platform.join("Other");
        Project p = ProjectCodec.read(ProjectCodec.write(Examples.madness())).project();
        Task t = run(p, a);
        kit.ticks(520);
        assertEquals(TaskState.COMPLETED, t.state(), String.join("\n", t.log()));
        JsonObject anim = hud(other, "anim").stream().filter(o -> o.get("op").getAsString().equals("show")
                && o.get("anim").getAsString().equals("insanity")).findFirst().orElseThrow(() -> new AssertionError("others see the animation"));
        assertEquals(a.ref.uuid().toString(), anim.getAsJsonArray("players").get(0).getAsString());
        JsonObject ins = hud(a, "effect").stream().filter(o -> o.get("op").getAsString().equals("show")
                && o.get("effect").getAsString().equals("insanity")).findFirst().orElseThrow();
        assertTrue(ins.get("words").getAsString().contains("A,WAKE UP"), "placeholders in words are resolved: " + ins.get("words"));
        assertTrue(hud(other, "effect").isEmpty(), "screen effects only reach the targets");
    }

    @Test void effectsExampleShowsEveryEffectAndFreezesDuringSleep() {
        FakePlatform.Player a = kit.platform.join("A");
        Project p = ProjectCodec.read(ProjectCodec.write(Examples.screenEffects())).project();
        Task t = run(p, a);
        kit.ticks(1500);
        assertEquals(TaskState.COMPLETED, t.state(), String.join("\n", t.log()));
        Set<String> fx = new HashSet<>();
        for (JsonObject o : hud(a, "effect")) if (o.get("op").getAsString().equals("show")) fx.add(o.get("effect").getAsString());
        assertEquals(Set.of("dizzy", "sleep", "heat", "cold", "insanity", "poison", "invert", "melt", "grayscale", "blood"), fx);
        JsonObject ctl = hud(a, "controls").stream().filter(o -> o.get("op").getAsString().equals("show")).findFirst().orElseThrow();
        assertTrue(ctl.get("movement").getAsBoolean() && ctl.get("look").getAsBoolean());
        assertTrue(hud(a, "controls").stream().anyMatch(o -> o.get("op").getAsString().equals("hide")), "controls are released");
    }
}
