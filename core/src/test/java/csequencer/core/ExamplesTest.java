package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.engine.GameEvent;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.examples.Examples;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.Project;
import csequencer.core.platform.ClientChannel.Topic;
import csequencer.core.platform.PlatformInfo;
import csequencer.core.security.Limits;
import csequencer.core.validate.Validator;
import csequencer.core.value.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static csequencer.core.TestKit.meta;
import static org.junit.jupiter.api.Assertions.*;

/** The built-in tutorials and examples must be valid and must actually play through. */
class ExamplesTest {
    final TestKit kit = new TestKit();

    private Project example(String id) {
        for (Project p : Examples.all()) if (p.id.equals(id)) return ProjectCodec.read(ProjectCodec.write(p)).project();
        throw new AssertionError("missing example " + id);
    }

    private Task run(Project p, FakePlatform.Player who) {
        return kit.start(new StartRequest(p, meta(p, 4)).initiator(who.ref));
    }

    private boolean popupWithTitle(FakePlatform.Player pl, String title) {
        return kit.platform.sentTo(pl, Topic.HUD).stream().map(FakePlatform.Sent::payload)
                .anyMatch(o -> "popup".equals(o.get("kind").getAsString()) && o.has("title") && o.get("title").getAsString().equals(title));
    }

    @Test void allExamplesAreValid() {
        PlatformInfo all = new FakePlatform().info();
        for (Project p : Examples.all()) {
            Validator.Report r = Validator.validate(p, all, new Limits(), null, n -> true);
            assertFalse(r.hasErrors(), p.name + ": " + r.issues);
            assertFalse(p.description.isEmpty());
            assertEquals(p.id, ProjectCodec.read(ProjectCodec.write(p)).project().id, "stable id survives save/load");
        }
        assertEquals(7, Examples.all().size());
    }

    @Test void basicsTutorialRunsToTheEnd() {
        FakePlatform.Player a = kit.platform.join("Alice");
        Task t = run(example("example_tutorial_basics"), a);
        kit.ticks(300);
        assertEquals(TaskState.COMPLETED, t.state(), t.log().toString());
        assertEquals(0, t.actionsFailed(), t.log().toString());
        assertTrue(popupWithTitle(a, "This is a popup"));
    }

    @Test void logicTutorialReactsToSneakingAndClicks() {
        FakePlatform.Player a = kit.platform.join("Alice");
        a.sneaking = true;
        Task t = run(example("example_tutorial_logic"), a);
        kit.ticks(90);
        assertTrue(kit.platform.actions.stream().anyMatch(x -> x.contains("You were sneaking")));
        for (int i = 0; i < 5; i++) {
            kit.engine.post(GameEvent.of("player.interact_block", a.ref, "", null, Map.of()));
            kit.ticks(3);
        }
        kit.ticks(250);
        assertTrue(popupWithTitle(a, "5 clicks!"), "IF saw 5 clicks");
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void bossFightVictory() {
        FakePlatform.Player a = kit.platform.join("Alice");
        a.pos = new Vec3d(100, 64, 100);
        Task t = run(example("example_boss_fight"), a);
        kit.ticks(60);
        assertEquals(1, kit.platform.entities.size(), "boss spawned");
        double[] boss = kit.platform.entities.values().iterator().next();
        assertEquals(108, boss[4], 1e-9, "spawned 8 blocks south of the start");
        assertTrue(kit.platform.bossbar.stream().anyMatch(s -> s.startsWith("show:boss:100")));
        boss[0] = 8; // below 50%
        kit.ticks(20);
        assertTrue(popupWithTitle(a, "Enraged!"), "phase 2 started");
        assertEquals("Enraged", t.currentPhase());
        boss[0] = 0;
        kit.ticks(80);
        assertTrue(kit.platform.actions.stream().anyMatch(x -> x.contains("action.give_item") && x.endsWith("@Alice")), "reward given");
        assertEquals(TaskState.COMPLETED, t.state(), t.log().toString());
        assertEquals("Victory", t.resultMessage());
        assertTrue(kit.engine.hud.elementsOf(t.id()).isEmpty(), "boss bar, marker and timer cleaned up");
    }

    @Test void bossFightDefeatWhenTimeRunsOut() {
        FakePlatform.Player a = kit.platform.join("Alice");
        Task t = run(example("example_boss_fight"), a);
        kit.ticks(3800);
        assertEquals(TaskState.FAILED, t.state(), t.log().toString());
        assertEquals("Time ran out", t.resultMessage());
    }

    @Test void checkpointRaceFinishedInTime() {
        FakePlatform.Player a = kit.platform.join("Alice");
        a.pos = new Vec3d(0, 64, 0);
        Task t = run(example("example_checkpoint_race"), a);
        kit.ticks(50);
        JsonObject marker = kit.platform.sentTo(a, Topic.HUD).stream().map(FakePlatform.Sent::payload)
                .filter(o -> "marker".equals(o.get("kind").getAsString())).findFirst().orElseThrow();
        assertEquals(20, marker.getAsJsonObject("pos").get("x").getAsInt(), "first checkpoint is 20 blocks east");
        a.pos = new Vec3d(20, 64, 0);
        kit.ticks(10);
        a.pos = new Vec3d(20, 64, 20);
        kit.ticks(10);
        a.pos = new Vec3d(0, 64, 20);
        kit.ticks(40);
        assertTrue(kit.platform.actions.stream().anyMatch(x -> x.contains("action.give_item")), "rewards");
        assertEquals(TaskState.COMPLETED, t.state(), t.log().toString());
        assertEquals("Finished in time", t.resultMessage());
    }

    @Test void checkpointRaceOutOfTime() {
        FakePlatform.Player a = kit.platform.join("Alice");
        Task t = run(example("example_checkpoint_race"), a);
        kit.ticks(1300);
        assertEquals(TaskState.FAILED, t.state(), t.log().toString());
        assertEquals("Out of time", t.resultMessage());
        List<String> titles = kit.platform.actions.stream().filter(x -> x.contains("action.title")).toList();
        assertTrue(titles.stream().anyMatch(x -> x.contains("Out of time!")));
    }
}
