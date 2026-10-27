package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.engine.*;
import csequencer.core.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleTest {
    final TestKit kit = new TestKit();

    private Project busyProject(String name) {
        Project p = project(name);
        AudioRef a = new AudioRef();
        a.id = "a1";
        a.name = "Theme";
        a.hash = "0123456789abcdef0123456789abcdef01234567";
        a.durationMs = 60_000;
        p.audio.add(a);
        Timeline body = function(p, "body");
        cmd(body, 0, 0, "in");
        cmd(body, 0, 200, "never");
        Piece play = piece(p.main, 0, 0, "audio.play");
        play.params.addProperty("audio", "a1");
        Piece listen = piece(p.main, 1, 0, "event.listen");
        listen.params.addProperty("eventType", "player.death");
        listen.params.addProperty("function", body.id);
        listen.params.addProperty("keepAlive", true);
        Piece mutex = piece(p.main, 2, 0, "flow.mutex");
        mutex.params.addProperty("function", body.id);
        mutex.params.addProperty("lock", "stage");
        Piece debounce = piece(p.main, 3, 0, "flow.debounce");
        debounce.params.addProperty("function", body.id);
        debounce.params.addProperty("ticks", 500);
        Piece spawn = piece(p.main, 3, 0, "action.spawn_entity");
        spawn.params.addProperty("removeOnEnd", true);
        cmd(p.main, 0, 1000, "end");
        return p;
    }

    @Test void cancellationReleasesEverything() {
        kit.platform.join("Steve");
        Task t = kit.start(busyProject("busy"));
        kit.ticks(5);
        assertFalse(kit.engine.audio.sessionsOf(t.id()).isEmpty());
        assertTrue(kit.engine.isLocked("stage"));
        assertTrue(t.listenerCount() > 0);
        assertTrue(t.liveFiberCount() > 0);

        assertTrue(kit.engine.cancel(t, "test"));
        assertEquals(TaskState.CANCELLED, t.state());
        assertEquals(0, t.liveFiberCount(), "no orphaned branches");
        assertEquals(0, t.listenerCount(), "listeners removed");
        assertTrue(kit.engine.audio.sessionsOf(t.id()).isEmpty(), "audio released");
        assertFalse(kit.engine.isLocked("stage"), "locks released");
        assertTrue(kit.platform.ended.contains(t.id() + ":remove"), "adapter told to remove spawned entities");
        int before = kit.platform.actions.size();
        kit.ticks(600);
        assertEquals(before, kit.platform.actions.size(), "no timer or listener fires after cancellation");
        assertTrue(kit.platform.sent.stream().anyMatch(s -> s.topic() == csequencer.core.platform.ClientChannel.Topic.AUDIO
                && "stop".equals(s.payload().get("op").getAsString())), "clients told to stop audio");
        assertNotNull(kit.engine.history.find(t.id()), "history retained");
    }

    @Test void concurrentSequencesAreIsolated() {
        Project a = project("A");
        a.settings.errorPolicy = Policies.ErrorPolicy.FAIL_TASK;
        cmd(a.main, 0, 2, "THROW");
        cmd(a.main, 0, 3, "a-after");
        Project b = project("B");
        for (int i = 0; i < 6; i++) cmd(b.main, 0, i, "b" + i);
        Piece set = piece(b.main, 0, 0, "var.set");
        set.params.addProperty("name", "shared");
        set.params.addProperty("value", "B");
        Task ta = kit.start(a), tb = kit.start(b);
        kit.ticks(10);
        assertEquals(TaskState.FAILED, ta.state());
        assertTrue(ta.lastError().contains("adapter exploded"));
        assertEquals(TaskState.COMPLETED, tb.state());
        assertEquals(6, kit.commands().stream().filter(c -> c.startsWith("b")).count());
        assertFalse(kit.commands().contains("a-after"));
        assertTrue(ta.vars().get("shared").isNull(), "sequence variables are per task");
    }

    @Test void stopBranchPolicyOnlyStopsThatBranch() {
        Project p = project("branchfail");
        p.settings.errorPolicy = Policies.ErrorPolicy.STOP_BRANCH;
        cmd(p.main, 0, 0, "FAIL");
        cmd(p.main, 0, 1, "track0-after");
        cmd(p.main, 1, 1, "track1-after");
        Task t = kit.start(p);
        kit.ticks(5);
        assertEquals(List.of("track1-after"), kit.commands().stream().filter(c -> c.endsWith("after")).toList());
        assertEquals(TaskState.COMPLETED, t.state());
        assertEquals(1, t.actionsFailed());
    }

    @Test void concurrencyPolicies() {
        Project p = project("solo");
        cmd(p.main, 0, 20, "x");
        p.settings.concurrency = Policies.ConcurrencyPolicy.IGNORE_NEW;
        kit.start(p);
        assertFalse(kit.engine.start(new StartRequest(p, meta(p, 4))).ok());

        TestKit k2 = new TestKit();
        p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        Task first = k2.start(p);
        k2.ticks(5);
        Task second = k2.start(p);
        assertEquals(TaskState.CANCELLED, first.state());
        assertEquals(TaskState.RUNNING, second.state());

        TestKit k3 = new TestKit();
        p.settings.concurrency = Policies.ConcurrencyPolicy.QUEUE;
        Task q1 = k3.start(p);
        Task q2 = k3.start(p);
        assertEquals(TaskState.QUEUED, q2.state());
        k3.ticks(25);
        assertEquals(TaskState.COMPLETED, q1.state());
        assertNotEquals(TaskState.QUEUED, q2.state(), "promoted after the first finished");
        k3.ticks(25);
        assertEquals(2, k3.commands().size());
    }

    @Test void playerScopeAllowsOneInstancePerPlayer() {
        Project p = project("personal");
        p.settings.scope = Policies.ExecutionScope.PLAYER;
        cmd(p.main, 0, 50, "x");
        FakePlatform.Player a = kit.platform.join("A"), b = kit.platform.join("B");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(a.ref));
        kit.start(new StartRequest(p, meta(p, 4)).initiator(b.ref));
        assertFalse(kit.engine.start(new StartRequest(p, meta(p, 4)).initiator(a.ref)).ok());
        assertEquals(2, kit.engine.activeCount());
    }

    @Test void limitsAreEnforced() {
        kit.settings.limits.maxActiveTasks = 2;
        kit.settings.limits.maxTasksPerPlayer = 1;
        FakePlatform.Player a = kit.platform.join("A");
        Project p = project("many");
        p.settings.concurrency = Policies.ConcurrencyPolicy.ALLOW_PARALLEL;
        cmd(p.main, 0, 100, "x");
        kit.start(new StartRequest(p, meta(p, 4)).initiator(a.ref));
        assertFalse(kit.engine.start(new StartRequest(p, meta(p, 4)).initiator(a.ref)).ok(), "per-player limit");
        kit.start(p);
        StartResult r = kit.engine.start(new StartRequest(p, meta(p, 4)));
        assertFalse(r.ok());
        assertTrue(r.message().contains("limit"));
    }

    @Test void duplicateStartRequestsAreDropped() {
        Project p = project("dedupe");
        p.settings.concurrency = Policies.ConcurrencyPolicy.ALLOW_PARALLEL;
        cmd(p.main, 0, 5, "x");
        assertTrue(kit.engine.start(new StartRequest(p, meta(p, 4)).dedupe("click-1")).ok());
        assertFalse(kit.engine.start(new StartRequest(p, meta(p, 4)).dedupe("click-1")).ok());
        assertTrue(kit.engine.start(new StartRequest(p, meta(p, 4)).dedupe("click-2")).ok());
    }

    @Test void disconnectPolicies() {
        FakePlatform.Player a = kit.platform.join("A");
        Project pause = project("pause");
        pause.settings.scope = Policies.ExecutionScope.SEQUENCE;
        pause.settings.disconnect = Policies.DisconnectPolicy.PAUSE_UNTIL_RETURN;
        pause.settings.disconnectGraceTicks = 100;
        cmd(pause.main, 0, 30, "resumed-work");
        Project cancel = project("cancel");
        cancel.settings.scope = Policies.ExecutionScope.SEQUENCE;
        cancel.settings.disconnect = Policies.DisconnectPolicy.CANCEL;
        cmd(cancel.main, 0, 30, "x");
        Project server = project("server");
        server.settings.scope = Policies.ExecutionScope.SERVER;
        server.settings.disconnect = Policies.DisconnectPolicy.CANCEL;
        cmd(server.main, 0, 30, "server-work");
        Task tp = kit.start(new StartRequest(pause, meta(pause, 4)).initiator(a.ref));
        Task tc = kit.start(new StartRequest(cancel, meta(cancel, 4)).initiator(a.ref));
        Task ts = kit.start(new StartRequest(server, meta(server, 4)).initiator(a.ref));
        kit.ticks(5);
        kit.platform.leave(a);
        kit.engine.onPlayerLeave(a.ref);
        assertEquals(TaskState.PAUSED, tp.state());
        assertEquals(TaskState.CANCELLED, tc.state());
        assertEquals(TaskState.RUNNING, ts.state(), "server-scoped sequences ignore disconnects");
        kit.ticks(50);
        assertTrue(kit.commands().contains("server-work"));
        assertFalse(kit.commands().contains("resumed-work"));
        kit.platform.players.put(a.ref.uuid(), a);
        kit.engine.onPlayerJoin(a.ref);
        assertEquals(TaskState.RUNNING, tp.state(), "resumes when the initiator reconnects");
        kit.ticks(40);
        assertTrue(kit.commands().contains("resumed-work"));
    }

    @Test void disconnectGraceExpiryCancels() {
        FakePlatform.Player a = kit.platform.join("A");
        Project p = project("grace");
        p.settings.disconnect = Policies.DisconnectPolicy.PAUSE_UNTIL_RETURN;
        p.settings.disconnectGraceTicks = 20;
        p.settings.scope = Policies.ExecutionScope.SEQUENCE;
        cmd(p.main, 0, 100, "x");
        Task t = kit.start(new StartRequest(p, meta(p, 4)).initiator(a.ref));
        kit.ticks(2);
        kit.platform.leave(a);
        kit.engine.onPlayerLeave(a.ref);
        kit.ticks(25);
        assertEquals(TaskState.CANCELLED, t.state());
    }

    @Test void chainingAndChainDepthLimit() {
        Project next = project("next");
        cmd(next.main, 0, 0, "next ran");
        kit.register(next);
        Project first = project("first");
        Piece c = piece(first.main, 0, 0, "seq.control");
        c.params.addProperty("action", "start");
        c.params.addProperty("project", "next");
        kit.start(first);
        kit.ticks(5);
        assertTrue(kit.commands().contains("next ran"));

        // A sequence that starts itself is stopped by the chain depth limit.
        TestKit k2 = new TestKit();
        k2.settings.limits.maxChainDepth = 3;
        Project loop = project("loop");
        loop.settings.concurrency = Policies.ConcurrencyPolicy.ALLOW_PARALLEL;
        cmd(loop.main, 0, 0, "loop ran");
        Piece self = piece(loop.main, 0, 0, "seq.control");
        self.params.addProperty("action", "start");
        k2.register(loop);
        k2.start(loop);
        k2.ticks(20);
        assertEquals(4, k2.commands().size(), "original + 3 chained runs");
    }

    @Test void pauseResumeAndStepDebugging() {
        Project p = project("debug");
        for (int i = 0; i < 10; i++) cmd(p.main, 0, i, "s" + i);
        p.main.pieces.get(3).breakpoint = true;
        Task t = kit.start(new StartRequest(p, meta(p, 4)).debug(true));
        kit.ticks(10);
        assertEquals(TaskState.PAUSED, t.state(), "stopped at breakpoint");
        assertEquals(List.of("s0", "s1", "s2"), kit.commands());
        kit.engine.step(t);
        kit.ticks(1);
        assertEquals(List.of("s0", "s1", "s2", "s3"), kit.commands(), "one step executes the breakpoint piece");
        assertEquals(TaskState.PAUSED, t.state());
        kit.engine.step(t);
        kit.ticks(3);
        assertEquals(5, kit.commands().size(), "one tick per step");
        kit.engine.resume(t);
        kit.ticks(10);
        assertEquals(10, kit.commands().size());
        assertEquals(TaskState.COMPLETED, t.state());
    }

    @Test void restartKeepsStableTaskId() {
        Project p = project("restart");
        cmd(p.main, 0, 0, "begin");
        cmd(p.main, 0, 50, "x");
        Task t = kit.start(p);
        kit.ticks(3);
        StartResult r = kit.engine.restart(t);
        assertTrue(r.ok());
        assertEquals(t.id(), r.task().id());
        assertEquals(2, r.task().runNumber());
        kit.ticks(3);
        assertEquals(List.of("begin", "begin"), kit.commands());
    }

    @Test void untrustedProjectsCannotRunCommands() {
        Project p = project("untrusted");
        cmd(p.main, 0, 0, "say hi");
        Piece chat = piece(p.main, 0, 0, "action.chat");
        chat.params.addProperty("message", "chat still works");
        Task t = kit.start(new StartRequest(p, meta(p, 0)));
        kit.ticks(3);
        assertTrue(kit.commands().isEmpty());
        assertTrue(t.lastError().contains("disabled"));
        assertTrue(kit.platform.actions.stream().anyMatch(a -> a.contains("chat still works")));
    }

    @Test void blacklistedCommandsAreBlocked() {
        Project p = project("evil");
        cmd(p.main, 0, 0, "op Steve");
        cmd(p.main, 0, 0, "execute as @a run deop Steve");
        cmd(p.main, 0, 0, "minecraft:stop");
        cmd(p.main, 0, 0, "say fine");
        Task t = kit.start(p);
        kit.ticks(2);
        assertEquals(List.of("say fine"), kit.commands());
        assertEquals(3, t.actionsFailed());
    }

    @Test void recoveryResumesFromSavedPosition() {
        Project p = project("persistent");
        p.settings.recovery = Policies.RecoveryPolicy.RESUME;
        Piece set = piece(p.main, 0, 0, "var.set");
        set.params.addProperty("name", "score");
        set.params.addProperty("value", 7);
        cmd(p.main, 0, 10, "early");
        cmd(p.main, 0, 60, "late {score}");
        kit.register(p);
        Task t = kit.start(kit.catalog.get(0).project());
        kit.ticks(30);
        JsonObject runtime = kit.engine.shutdown();
        assertEquals(TaskState.CANCELLED, t.state());

        TestKit fresh = new TestKit();
        fresh.catalog.add(kit.catalog.get(0));
        List<String> report = fresh.engine.importRuntime(runtime);
        assertTrue(report.get(0).startsWith("Resumed"), report.toString());
        Task resumed = fresh.engine.tasks().get(0);
        assertEquals(t.id(), resumed.id(), "stable id across restarts");
        fresh.ticks(40);
        assertEquals(List.of("late 7"), fresh.commands(), "skipped completed pieces, restored variables");
    }

    @Test void discardPolicyIsNotRecovered() {
        Project p = project("ephemeral");
        cmd(p.main, 0, 100, "x");
        kit.register(p);
        kit.start(p);
        kit.ticks(2);
        JsonObject runtime = kit.engine.shutdown();
        assertEquals(0, runtime.getAsJsonArray("tasks").size());
    }

    @Test void globalAndPlayerVariablesPersist() {
        FakePlatform.Player a = kit.platform.join("A");
        Project p = project("vars");
        Piece g = piece(p.main, 0, 0, "var.set");
        g.params.addProperty("scope", "global");
        g.params.addProperty("name", "visits");
        g.params.addProperty("op", "add");
        g.params.addProperty("value", 1);
        Piece pv = piece(p.main, 0, 0, "var.set");
        pv.params.addProperty("scope", "player");
        pv.params.addProperty("name", "seen");
        pv.params.addProperty("value", true);
        kit.start(new StartRequest(p, meta(p, 4)).initiator(a.ref));
        kit.ticks(2);
        JsonObject runtime = kit.engine.exportRuntime();
        TestKit fresh = new TestKit();
        fresh.engine.importRuntime(runtime);
        assertEquals(1, fresh.engine.globalVars().get("visits").asNumber());
        assertTrue(fresh.engine.playerVars(a.ref.uuid()).get("seen").asBool());
    }

    @Test void finishedTasksLingerThenMoveToHistory() {
        kit.settings.finishedLingerTicks = 10;
        Project p = project("linger");
        cmd(p.main, 0, 0, "x");
        Task t = kit.start(p);
        kit.ticks(2);
        assertEquals(TaskState.COMPLETED, t.state());
        assertNotNull(kit.engine.task(t.id()), "still visible right after finishing");
        kit.ticks(12);
        assertNull(kit.engine.task(t.id()));
        assertEquals("COMPLETED", kit.engine.history.find(t.id()).get("result").getAsString());
    }

    @Test void eventsForPausedTasksAreDeliveredOnResume() {
        Project p = project("pausedEvents");
        Timeline f = function(p, "f");
        cmd(f, 0, 0, "got it");
        Piece l = piece(p.main, 0, 0, "event.listen");
        l.params.addProperty("eventType", "player.death");
        l.params.addProperty("function", f.id);
        l.params.addProperty("keepAlive", true);
        FakePlatform.Player s = kit.platform.join("S");
        Task t = kit.start(p);
        kit.ticks(2);
        kit.engine.pause(t, Task.PauseReason.USER);
        kit.engine.post(GameEvent.of("player.death", s.ref, "", null, Map.of()));
        kit.ticks(5);
        assertTrue(kit.commands().isEmpty());
        kit.engine.resume(t);
        kit.ticks(2);
        assertEquals(List.of("got it"), kit.commands());
    }
}
