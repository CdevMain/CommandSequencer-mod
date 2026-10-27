package csequencer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import csequencer.core.engine.GameEvent;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {
    final TestKit kit = new TestKit();

    @Test void tickAccurateExecution() {
        Project p = project("timing");
        cmd(p.main, 0, 0, "a");
        cmd(p.main, 0, 10, "b");
        cmd(p.main, 0, 37, "c");
        Task t = kit.start(p);
        kit.ticks(50);
        assertEquals(List.of(1L), kit.ticksOf("a"));
        assertEquals(List.of(11L), kit.ticksOf("b"), "10 ticks after a");
        assertEquals(List.of(38L), kit.ticksOf("c"));
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void delayShiftsLaterPieces() {
        Project p = project("delay");
        cmd(p.main, 0, 0, "a");
        Piece d = piece(p.main, 0, 5, "flow.delay");
        d.params.addProperty("ticks", 20);
        cmd(p.main, 0, 5, "b");
        cmd(p.main, 0, 6, "c");
        kit.start(p);
        kit.ticks(40);
        assertEquals(List.of(26L), kit.ticksOf("b"), "same-tick piece after the delay runs 20 ticks later");
        assertEquals(List.of(27L), kit.ticksOf("c"), "following pieces shift by the delay");
    }

    @Test void durationsAcceptTimestamps() {
        Project p = project("ts");
        Piece d = piece(p.main, 0, 0, "flow.delay");
        d.params.addProperty("ticks", "1.5s");
        cmd(p.main, 0, 0, "after");
        kit.start(p);
        kit.ticks(40);
        assertEquals(List.of(31L), kit.ticksOf("after"));
    }

    @Test void sameTickOrderingIsDeterministic() {
        Project low = project("low");
        cmd(low.main, 0, 3, "low");
        Project high = project("high");
        high.settings.priority = 5;
        cmd(high.main, 0, 3, "high-t0");
        cmd(high.main, 1, 3, "high-t1");
        cmd(high.main, 0, 3, "high-t0-second");
        kit.start(low);
        kit.start(high);
        kit.ticks(10);
        assertEquals(List.of("high-t0", "high-t0-second", "high-t1", "low"), kit.commands(),
                "priority first, then track order, then placement order");
    }

    @Test void parallelJoinAllAndAny() {
        Project p = project("parallel");
        Timeline a = function(p, "A");
        cmd(a, 0, 0, "A0");
        cmd(a, 0, 10, "A10");
        Timeline b = function(p, "B");
        cmd(b, 0, 0, "B0");
        cmd(b, 0, 3, "B3");
        Piece par = piece(p.main, 0, 0, "flow.parallel");
        JsonArray fns = new JsonArray();
        fns.add(a.id);
        fns.add(b.id);
        par.params.add("functions", fns);
        par.params.addProperty("join", "all");
        cmd(p.main, 0, 0, "after");
        kit.start(p);
        kit.ticks(20);
        assertEquals(kit.ticksOf("A10"), kit.ticksOf("after"), "join all resumes when the slowest branch ends");

        TestKit k2 = new TestKit();
        par.params.addProperty("join", "any");
        k2.start(p);
        k2.ticks(20);
        assertEquals(k2.ticksOf("B3"), k2.ticksOf("after"), "join any resumes with the first finisher");
        assertTrue(k2.ticksOf("A10").isEmpty(), "the other branch is cancelled");
    }

    @Test void repeatLoopRunsExactlyNTimes() {
        Project p = project("repeat");
        Timeline f = function(p, "body");
        cmd(f, 0, 0, "it {i}");
        Piece r = piece(p.main, 0, 0, "flow.repeat");
        r.params.addProperty("function", f.id);
        r.params.addProperty("count", 3);
        cmd(p.main, 0, 0, "done");
        kit.start(p);
        kit.ticks(5);
        assertEquals(List.of("it 0", "it 1", "it 2", "done"), kit.commands());
    }

    @Test void repeatWithTimedBodyAndInterval() {
        Project p = project("repeat2");
        Timeline f = function(p, "body");
        cmd(f, 0, 0, "start {i}");
        cmd(f, 0, 4, "end {i}");
        Piece r = piece(p.main, 0, 0, "flow.repeat");
        r.params.addProperty("function", f.id);
        r.params.addProperty("count", 2);
        r.params.addProperty("interval", 10);
        kit.start(p);
        kit.ticks(40);
        assertEquals(List.of(1L), kit.ticksOf("start 0"));
        assertEquals(List.of(5L), kit.ticksOf("end 0"));
        assertEquals(List.of(15L), kit.ticksOf("start 1"), "10 tick pause between iterations");
    }

    @Test void whileLoopIsBounded() {
        Project p = project("while");
        Timeline f = function(p, "body");
        cmd(f, 0, 0, "loop");
        Piece w = piece(p.main, 0, 0, "flow.while");
        w.params.add("condition", ConditionGraph.single(node("logic.true")).toJson());
        w.params.addProperty("function", f.id);
        w.params.addProperty("maxIterations", 5);
        Task t = kit.start(p);
        kit.ticks(10);
        assertEquals(5, kit.commands().size());
        assertTrue(t.warnings().contains("loop iteration limit reached"));
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void conditionalLoopStopsWhenConditionTurnsFalse() {
        Project p = project("while2");
        Timeline f = function(p, "body");
        Piece inc = piece(f, 0, 0, "var.set");
        inc.params.addProperty("name", "n");
        inc.params.addProperty("op", "add");
        inc.params.addProperty("value", 1);
        Piece w = piece(p.main, 0, 0, "flow.while");
        w.params.add("condition", ConditionGraph.single(cmp(q("var", "name", "n"), "lt", 4)).toJson());
        w.params.addProperty("function", f.id);
        cmd(p.main, 0, 0, "n={n}");
        kit.start(p);
        kit.ticks(5);
        assertEquals(List.of("n=4"), kit.commands());
    }

    @Test void zeroTimeLoopsAreThrottledNotHung() {
        Project p = project("spin");
        cmd(p.main, 0, 0, "spin");
        Piece j = piece(p.main, 0, 0, "flow.jump");
        j.params.addProperty("tick", 0);
        j.params.addProperty("maxJumps", 2000);
        Task t = kit.start(p);
        kit.ticks(100);
        assertEquals(2001, kit.commands().size());
        assertTrue(t.deferredTicks() > 0, "work was spread over several ticks by the action budget");
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void timedWindowCancelsSlowFunction() {
        Project p = project("window");
        Timeline slow = function(p, "slow");
        cmd(slow, 0, 0, "L0");
        cmd(slow, 0, 100, "L100");
        Piece w = piece(p.main, 0, 0, "flow.timeout");
        w.params.addProperty("function", slow.id);
        w.params.addProperty("window", 20);
        cmd(p.main, 0, 0, "post");
        Task t = kit.start(p);
        kit.ticks(150);
        assertEquals(List.of("L0", "post"), kit.commands());
        assertEquals(List.of(21L), kit.ticksOf("post"));
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void waitUntilConditionAcrossParallelTracks() {
        Project p = project("wait");
        Piece w = piece(p.main, 0, 0, "flow.wait_until");
        w.params.add("condition", ConditionGraph.single(cmp(q("var", "name", "go"), "is_true", null)).toJson());
        cmd(p.main, 0, 0, "went");
        Piece set = piece(p.main, 1, 30, "var.set");
        set.params.addProperty("name", "go");
        set.params.addProperty("value", true);
        kit.start(p);
        kit.ticks(40);
        assertEquals(List.of(32L), kit.ticksOf("went"), "observed the tick after track 2 set the variable");
    }

    @Test void waitUntilTimeout() {
        Project p = project("timeout");
        Timeline onTimeout = function(p, "late");
        cmd(onTimeout, 0, 0, "timed out");
        Piece w = piece(p.main, 0, 0, "flow.wait_until");
        w.params.add("condition", ConditionGraph.single(node("logic.false")).toJson());
        w.params.addProperty("timeout", 10);
        w.params.addProperty("onTimeout", onTimeout.id);
        cmd(p.main, 0, 0, "continued");
        kit.start(p);
        kit.ticks(20);
        assertEquals(List.of("timed out", "continued"), kit.commands());
    }

    @Test void ifElseIfElseAndSwitch() {
        Project p = project("branching");
        Timeline one = function(p, "one");
        cmd(one, 0, 0, "one");
        Timeline two = function(p, "two");
        cmd(two, 0, 0, "two");
        Timeline other = function(p, "other");
        cmd(other, 0, 0, "other");
        Piece set = piece(p.main, 0, 0, "var.set");
        set.params.addProperty("name", "x");
        set.params.addProperty("value", 2);
        Piece iff = piece(p.main, 0, 1, "logic.if");
        iff.params.add("branches", branches(varEquals("x", 1), one.id, varEquals("x", 2), two.id));
        iff.params.addProperty("else", other.id);
        Piece sw = piece(p.main, 0, 2, "logic.switch");
        sw.params.add("value", q("var", "name", "x"));
        JsonArray cases = new JsonArray();
        JsonObject c1 = new JsonObject(); c1.addProperty("match", "2"); c1.addProperty("target", two.id); cases.add(c1);
        sw.params.add("cases", cases);
        sw.params.addProperty("default", other.id);
        Piece set3 = piece(p.main, 0, 3, "var.set");
        set3.params.addProperty("name", "x");
        set3.params.addProperty("value", 3);
        Piece iff2 = piece(p.main, 0, 4, "logic.if");
        iff2.params.add("branches", branches(varEquals("x", 1), one.id));
        iff2.params.addProperty("else", other.id);
        kit.start(p);
        kit.ticks(10);
        assertEquals(List.of("two", "two", "other"), kit.commands());
    }

    @Test void weightedRandomIsReproducibleWithSeed() {
        Project p = project("random");
        Timeline a = function(p, "a");
        cmd(a, 0, 0, "A");
        Timeline b = function(p, "b");
        cmd(b, 0, 0, "B");
        Piece r = piece(p.main, 0, 0, "flow.random");
        JsonArray outs = new JsonArray();
        JsonObject o1 = new JsonObject(); o1.addProperty("weight", 0); o1.addProperty("target", a.id); outs.add(o1);
        JsonObject o2 = new JsonObject(); o2.addProperty("weight", 5); o2.addProperty("target", b.id); outs.add(o2);
        r.params.add("outcomes", outs);
        r.params.addProperty("seed", "fixed");
        kit.start(p);
        kit.ticks(3);
        assertEquals(List.of("B"), kit.commands(), "zero-weight outcomes are never chosen");
    }

    @Test void eventListenerRunsFunctionPerEventOnceEvenIfDuplicated() {
        Project p = project("listen");
        Timeline onDeath = function(p, "onDeath");
        cmd(onDeath, 0, 0, "died {player}");
        Piece l = piece(p.main, 0, 0, "event.listen");
        l.params.addProperty("eventType", "player.death");
        l.params.addProperty("function", onDeath.id);
        l.params.addProperty("keepAlive", true);
        Task t = kit.start(p);
        FakePlatform.Player steve = kit.platform.join("Steve");
        kit.ticks(2);
        GameEvent e = GameEvent.of("player.death", steve.ref, "minecraft:overworld", null, Map.of());
        kit.engine.post(e);
        kit.engine.post(e); // same id → ignored
        kit.ticks(3);
        assertEquals(List.of("died Steve"), kit.commands());
        assertTrue(t.participants().contains(steve.ref.uuid()));
        assertEquals(TaskState.WAITING, t.state(), "kept alive by its listener");
    }

    @Test void conditionTriggerFiresOnEntryNotWhileInside() {
        Project p = project("arena");
        Timeline enter = function(p, "enter");
        cmd(enter, 0, 0, "entered {player}");
        Piece w = piece(p.main, 0, 0, "event.on_condition");
        ConditionGraph inside = ConditionGraph.single(cmp(q("player.in_region", "from", "0 0 0", "to", "10 100 10"), "is_true", null));
        w.params.add("condition", inside.toJson());
        w.params.addProperty("edge", "entered");
        w.params.addProperty("function", enter.id);
        w.params.addProperty("interval", 1);
        w.params.addProperty("keepAlive", true);
        FakePlatform.Player alex = kit.platform.join("Alex");
        alex.pos = new csequencer.core.value.Vec3d(50, 64, 50);
        kit.start(p);
        kit.ticks(3);
        alex.pos = new csequencer.core.value.Vec3d(5, 64, 5);
        kit.ticks(5);
        assertEquals(List.of("entered Alex"), kit.commands(), "fires once on entry");
        kit.ticks(5);
        assertEquals(1, kit.commands().size(), "staying inside does not fire again");
        alex.pos = new csequencer.core.value.Vec3d(50, 64, 50);
        kit.ticks(3);
        alex.pos = new csequencer.core.value.Vec3d(5, 64, 5);
        kit.ticks(3);
        assertEquals(2, kit.commands().size(), "re-entry fires again");
    }

    @Test void stateMachineRunsStateFunctions() {
        Project p = project("sm");
        Timeline idle = function(p, "idle");
        cmd(idle, 0, 0, "idle");
        Timeline fight = function(p, "fight");
        cmd(fight, 0, 0, "fight");
        Piece sm = piece(p.main, 0, 0, "state.machine");
        JsonArray states = new JsonArray();
        JsonObject s1 = new JsonObject(); s1.addProperty("match", "idle"); s1.addProperty("target", idle.id); states.add(s1);
        JsonObject s2 = new JsonObject(); s2.addProperty("match", "fight"); s2.addProperty("target", fight.id); states.add(s2);
        sm.params.add("states", states);
        sm.params.addProperty("initial", "idle");
        Piece set = piece(p.main, 0, 5, "state.set");
        set.params.addProperty("state", "fight");
        Piece set2 = piece(p.main, 0, 6, "state.set");
        set2.params.addProperty("state", "fight");
        kit.start(p);
        kit.ticks(10);
        assertEquals(List.of("idle", "fight"), kit.commands(), "re-entering the same state does not re-trigger");
    }

    @Test void counterRunsFunctionAtThreshold() {
        Project p = project("counter");
        Timeline hit = function(p, "hit");
        cmd(hit, 0, 0, "threshold");
        for (int i = 0; i < 7; i++) {
            Piece c = piece(p.main, 0, i, "counter.step");
            c.params.addProperty("threshold", 3);
            c.params.addProperty("function", hit.id);
        }
        kit.start(p);
        kit.ticks(10);
        assertEquals(2, kit.commands().size(), "fires at 3 and 6 with reset");
    }

    @Test void functionInputsAndOutputs() {
        Project p = project("fn");
        Timeline f = function(p, "double");
        f.inputs.add(new VariableDecl("x", csequencer.core.value.ValueType.NUMBER));
        f.outputs.add(new VariableDecl("y", csequencer.core.value.ValueType.NUMBER));
        Piece mul = piece(f, 0, 0, "var.set");
        mul.params.addProperty("scope", "local");
        mul.params.addProperty("name", "y");
        JsonObject math = q("math", "op", "mul", "a", q("var", "name", "x"), "b", 2);
        mul.params.add("value", math);
        Piece call = piece(p.main, 0, 0, "flow.call");
        call.params.addProperty("function", f.id);
        JsonObject args = new JsonObject();
        args.addProperty("x", 21);
        call.params.add("args", args);
        call.params.addProperty("resultVar", "r");
        cmd(p.main, 0, 0, "result {local:r.y}");
        kit.start(p);
        kit.ticks(3);
        assertEquals(List.of("result 42"), kit.commands());
    }

    @Test void callDepthIsLimited() {
        Project p = project("recursion");
        Timeline f = function(p, "rec");
        Piece self = piece(f, 0, 0, "flow.call");
        self.params.addProperty("function", f.id);
        Piece call = piece(p.main, 0, 0, "flow.call");
        call.params.addProperty("function", f.id);
        kit.settings.limits.maxCallDepth = 10;
        Task t = kit.start(p);
        kit.ticks(20);
        assertTrue(t.log().stream().anyMatch(l -> l.contains("Call depth limit")));
        assertTrue(t.state().terminal(), "recursion ends instead of running forever");
    }

    @Test void mutexSerializesBranches() {
        Project p = project("mutex");
        Timeline body = function(p, "body");
        cmd(body, 0, 0, "enter");
        cmd(body, 0, 5, "leave");
        for (int track = 0; track < 2; track++) {
            Piece m = piece(p.main, track, 0, "flow.mutex");
            m.params.addProperty("function", body.id);
            m.params.addProperty("lock", "arena");
        }
        kit.start(p);
        kit.ticks(20);
        assertEquals(List.of("enter", "leave", "enter", "leave"), kit.commands(), "no interleaving inside the exclusive section");
    }

    @Test void gateStopsBranch() {
        Project p = project("gate");
        Piece g = piece(p.main, 0, 0, "logic.gate");
        g.params.add("condition", ConditionGraph.single(node("logic.false")).toJson());
        cmd(p.main, 0, 1, "never");
        cmd(p.main, 1, 1, "other track");
        kit.start(p);
        kit.ticks(5);
        assertEquals(List.of("other track"), kit.commands());
    }

    @Test void realtimeModeCatchesUpAfterLag() {
        Project p = project("rt");
        p.settings.timing = Policies.TimingMode.REALTIME;
        cmd(p.main, 0, 0, "a");
        cmd(p.main, 0, 30, "b");
        Task t = kit.start(p);
        kit.ticksWithClock(5);
        kit.platform.now += 1000; // server stalls for one second
        kit.ticksWithClock(1);
        assertTrue(t.tickCount() >= 25, "caught up ~20 ticks in one server tick, was " + t.tickCount());
        kit.ticksWithClock(10);
        assertEquals(1, kit.ticksOf("b").size());
    }

    @Test void startFromTickSkipsEarlierPiecesButKeepsListeners() {
        Project p = project("seek");
        Timeline onDeath = function(p, "onDeath");
        cmd(onDeath, 0, 0, "listener alive");
        Piece l = piece(p.main, 0, 0, "event.listen");
        l.params.addProperty("eventType", "player.death");
        l.params.addProperty("function", onDeath.id);
        cmd(p.main, 0, 5, "early");
        cmd(p.main, 0, 50, "late");
        Task t = kit.start(new csequencer.core.engine.StartRequest(p, meta(p, 4)).fromTick(40));
        FakePlatform.Player s = kit.platform.join("S");
        kit.ticks(2);
        kit.engine.post(GameEvent.of("player.death", s.ref, "", null, Map.of()));
        kit.ticks(15);
        assertEquals(List.of("listener alive", "late"), kit.commands());
        assertEquals(TaskState.COMPLETED, t.state());
    }
}
