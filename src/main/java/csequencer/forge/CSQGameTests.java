package csequencer.forge;

import csequencer.CommandSequencerMod;
import csequencer.core.editor.TimelineOps;
import csequencer.core.engine.StartRequest;
import csequencer.core.engine.StartResult;
import csequencer.core.engine.Task;
import csequencer.core.engine.TaskState;
import csequencer.core.model.ConditionGraph;
import csequencer.core.model.Piece;
import csequencer.core.model.Project;
import csequencer.core.model.Timeline;
import csequencer.core.project.ProjectMeta;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * In-world integration tests of the Forge adapter, run by {@code gradlew runGameTestServer}
 * (a dedicated-server environment). Only registered when the game test namespace is enabled.
 */
@GameTestHolder(CommandSequencerMod.MODID)
@PrefixGameTestTemplate(false)
public final class CSQGameTests {
    private CSQGameTests() {}

    private static String pos(GameTestHelper h, int x, int y, int z) {
        BlockPos p = h.absolutePos(new BlockPos(x, y, z));
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static Task start(GameTestHelper h, Project p) {
        CSQServer s = CSQServer.get();
        if (s == null) throw new IllegalStateException("CommandSequencer server not started");
        ProjectMeta m = new ProjectMeta();
        m.id = p.id;
        m.trustLevel = 4;
        StartResult r = s.engine().start(new StartRequest(p, m).dimension(ForgePlatform.dim(h.getLevel())).reason("gametest"));
        if (!r.ok()) throw new IllegalStateException(r.message());
        return r.task();
    }

    private static Piece piece(Timeline tl, String type, int tick) { return TimelineOps.create(tl, type, tl.tracks.get(0).id, tick); }

    /** A timed set-block action is executed by the Forge adapter at the right moment. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void timedSetBlock(GameTestHelper h) {
        Project p = Project.create("gt_setblock");
        Piece set = piece(p.main, "action.set_block", 10);
        set.params.addProperty("pos", pos(h, 1, 1, 1));
        set.params.addProperty("block", "minecraft:gold_block");
        start(h, p);
        h.runAfterDelay(5, () -> h.assertBlockNotPresent(Blocks.GOLD_BLOCK, new BlockPos(1, 1, 1)));
        h.succeedWhen(() -> h.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(1, 1, 1)));
    }

    /** Command pieces run through the vanilla dispatcher with the trusted permission level. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void commandPiece(GameTestHelper h) {
        Project p = Project.create("gt_command");
        Piece c = piece(p.main, "action.command", 0);
        c.params.addProperty("command", "setblock " + pos(h, 2, 1, 2) + " minecraft:diamond_block");
        start(h, p);
        h.succeedWhen(() -> h.assertBlockPresent(Blocks.DIAMOND_BLOCK, new BlockPos(2, 1, 2)));
    }

    /** IF/ELSE evaluates a world query (block type) through the adapter. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void conditionOnBlockQuery(GameTestHelper h) {
        h.setBlock(new BlockPos(0, 1, 0), Blocks.STONE);
        Project p = Project.create("gt_logic");
        Timeline yes = Timeline.create("yes", "fn");
        Piece y = TimelineOps.create(yes, "action.set_block", yes.tracks.get(0).id, 0);
        y.params.addProperty("pos", pos(h, 3, 1, 3));
        y.params.addProperty("block", "minecraft:emerald_block");
        p.functions.add(yes);
        Piece iff = piece(p.main, "logic.if", 2);
        ConditionGraph.Node cmp = new ConditionGraph.Node("cmp");
        com.google.gson.JsonObject left = new com.google.gson.JsonObject();
        left.addProperty("q", "block.type");
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("pos", pos(h, 0, 1, 0));
        left.add("args", args);
        cmp.params.add("left", left);
        cmp.params.addProperty("op", "eq");
        cmp.params.addProperty("right", "minecraft:stone");
        com.google.gson.JsonArray branches = new com.google.gson.JsonArray();
        com.google.gson.JsonObject b = new com.google.gson.JsonObject();
        b.add("condition", ConditionGraph.single(cmp).toJson());
        b.addProperty("target", yes.id);
        branches.add(b);
        iff.params.add("branches", branches);
        start(h, p);
        h.succeedWhen(() -> h.assertBlockPresent(Blocks.EMERALD_BLOCK, new BlockPos(3, 1, 3)));
    }

    /** Cancelling a sequence removes entities it spawned with "remove when sequence ends". */
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void cancelCleansUpSpawnedEntities(GameTestHelper h) {
        Project p = Project.create("gt_cleanup");
        Piece sp = piece(p.main, "action.spawn_entity", 0);
        sp.params.addProperty("entity", "minecraft:pig");
        sp.params.addProperty("pos", pos(h, 2, 1, 2));
        sp.params.addProperty("count", 2);
        sp.params.addProperty("removeOnEnd", true);
        Piece wait = piece(p.main, "flow.delay", 1);
        wait.params.addProperty("ticks", 1000);
        Task t = start(h, p);
        AABB box = new AABB(h.absolutePos(BlockPos.ZERO)).inflate(8);
        h.runAfterDelay(10, () -> {
            int pigs = h.getLevel().getEntities(EntityType.PIG, box, e -> true).size();
            if (pigs != 2) h.fail("expected 2 pigs, found " + pigs);
            CSQServer.get().engine().cancel(t, "gametest");
            if (t.state() != TaskState.CANCELLED) h.fail("task not cancelled: " + t.state());
        });
        h.runAfterDelay(15, () -> {
            int pigs = h.getLevel().getEntities(EntityType.PIG, box, e -> e.isAlive()).size();
            if (pigs != 0) h.fail("spawned entities not cleaned up: " + pigs);
            if (CSQServer.get().engine().audio.sessionsOf(t.id()).size() != 0) h.fail("audio sessions leaked");
            h.succeed();
        });
    }

    /** A fill larger than the server block limit is refused instead of freezing the server. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fillRespectsBlockLimit(GameTestHelper h) {
        Project p = Project.create("gt_fill");
        Piece f = piece(p.main, "action.fill", 0);
        f.params.addProperty("from", pos(h, 0, 1, 0));
        f.params.addProperty("to", pos(h, 100, 40, 100));
        f.params.addProperty("block", "minecraft:stone");
        Piece small = piece(p.main, "action.fill", 1);
        small.params.addProperty("from", pos(h, 0, 2, 0));
        small.params.addProperty("to", pos(h, 1, 2, 1));
        small.params.addProperty("block", "minecraft:glass");
        Task t = start(h, p);
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.GLASS, new BlockPos(1, 2, 1));
            if (t.actionsFailed() != 1) h.fail("expected the huge fill to be refused, failures=" + t.actionsFailed());
        });
    }

    /** Boss fight flow: spawn a mob, show a boss bar following its health, wait for its death, then act. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void bossFightFlow(GameTestHelper h) {
        Project p = Project.create("gt_boss");
        Piece spawn = piece(p.main, "action.spawn_entity", 0);
        spawn.params.addProperty("entity", "minecraft:pig");
        spawn.params.addProperty("pos", pos(h, 2, 1, 2));
        spawn.params.addProperty("storeAs", "boss");
        spawn.params.addProperty("nbt", "{NoAI:1b}");
        Piece bar = piece(p.main, "hud.bossbar", 1);
        com.google.gson.JsonObject var = new com.google.gson.JsonObject();
        var.addProperty("q", "var");
        com.google.gson.JsonObject va = new com.google.gson.JsonObject();
        va.addProperty("name", "boss");
        var.add("args", va);
        bar.params.add("entity", var);
        Piece ov = piece(p.main, "hud.overlay", 1);
        ov.params.addProperty("style", "timer");
        ov.params.addProperty("countdown", 200);
        Piece mk = piece(p.main, "hud.marker", 1);
        mk.params.add("follow", var.deepCopy());
        Piece zone = piece(p.main, "zone.keep_inside", 1);
        zone.params.addProperty("from", pos(h, 0, 0, 0));
        zone.params.addProperty("to", pos(h, 4, 4, 4));
        Piece wait = piece(p.main, "flow.wait_death", 2);
        wait.params.add("entity", var.deepCopy());
        Piece win = piece(p.main, "action.set_block", 3);
        win.params.addProperty("pos", pos(h, 0, 1, 0));
        win.params.addProperty("block", "minecraft:gold_block");
        Task t = start(h, p);
        h.runAfterDelay(20, () -> {
            if (t.state() != TaskState.WAITING) h.fail("expected the sequence to wait for the boss, was " + t.state());
            if (CSQServer.get().engine().hud.elementsOf(t.id()).size() < 3) h.fail("boss bar / overlay / marker not registered");
            for (var pig : h.getLevel().getEntities(EntityType.PIG, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(8), e -> true)) pig.kill();
        });
        h.runAfterDelay(45, () -> {
            h.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(0, 1, 0));
            if (!CSQServer.get().engine().hud.elementsOf(t.id()).stream().noneMatch(e -> e.kind.equals("bossbar"))) h.fail("boss bar not removed after death");
            h.succeed();
        });
    }
}
