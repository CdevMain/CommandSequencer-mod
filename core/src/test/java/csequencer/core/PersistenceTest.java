package csequencer.core;

import com.google.gson.JsonObject;
import csequencer.core.editor.EditorHistory;
import csequencer.core.editor.TimelineOps;
import csequencer.core.engine.Durations;
import csequencer.core.json.J;
import csequencer.core.json.ProjectCodec;
import csequencer.core.model.*;
import csequencer.core.pieces.PieceRegistry;
import csequencer.core.pieces.PieceType;
import csequencer.core.platform.Capability;
import csequencer.core.platform.PlatformInfo;
import csequencer.core.project.FileProjectStore;
import csequencer.core.project.ProjectMeta;
import csequencer.core.security.CommandFilter;
import csequencer.core.security.Limits;
import csequencer.core.security.ServerSettings;
import csequencer.core.validate.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static csequencer.core.TestKit.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    private Project sample() {
        Project p = project("Sample");
        p.description = "desc";
        p.tags.add("boss");
        p.settings.timing = Policies.TimingMode.REALTIME;
        p.main.markers.add(new Marker("Intro", 0, Policies.MarkerKind.PHASE));
        Timeline f = function(p, "helper");
        f.inputs.add(new VariableDecl("x", csequencer.core.value.ValueType.NUMBER));
        cmd(f, 0, 3, "say {x}");
        cmd(p.main, 0, 0, "say hi");
        Piece iff = piece(p.main, 1, 20, "logic.if");
        iff.params.add("branches", branches(varEquals("a", 1), f.id));
        PieceGroup g = TimelineOps.group(p.main, List.of(iff), "Logic");
        g.collapsed = true;
        ConditionGraph shared = varEquals("hp", 0);
        shared.name = "Dead";
        p.conditions.add(shared);
        Trigger t = new Trigger();
        t.id = "tr1";
        t.eventType = "player.death";
        p.triggers.add(t);
        AudioRef a = new AudioRef();
        a.id = "a1";
        a.name = "Song";
        a.hash = "0123456789abcdef0123456789abcdef01234567";
        a.format = "mp3";
        a.durationMs = 1234;
        p.audio.add(a);
        return p;
    }

    @Test void roundTripIsLossless() {
        Project p = sample();
        String once = ProjectCodec.write(p);
        ProjectCodec.ReadResult rr = ProjectCodec.read(once);
        assertFalse(rr.migrated());
        assertTrue(rr.warnings().isEmpty(), rr.warnings().toString());
        String twice = ProjectCodec.write(rr.project());
        assertEquals(once, twice);
        Project back = rr.project();
        assertEquals("Sample", back.name);
        assertEquals(Policies.TimingMode.REALTIME, back.settings.timing);
        assertEquals(1, back.functions.size());
        assertEquals("Dead", back.conditions.get(0).name);
        assertTrue(back.main.groups.get(0).collapsed);
        assertEquals("mp3", back.audio.get(0).format);
        assertEquals(Project.SCHEMA_VERSION, J.parseObject(once).get("schemaVersion").getAsInt());
    }

    @Test void unknownDataIsPreserved() {
        JsonObject o = ProjectCodec.toJson(sample());
        o.addProperty("futureTopLevel", "keep me");
        JsonObject firstPiece = o.getAsJsonObject("main").getAsJsonArray("pieces").get(0).getAsJsonObject();
        firstPiece.addProperty("futurePieceField", 42);
        firstPiece.getAsJsonObject("params").addProperty("futureParam", true);
        JsonObject s = o.getAsJsonObject("settings");
        s.addProperty("futureSetting", "x");
        String out = ProjectCodec.write(ProjectCodec.read(o).project());
        assertTrue(out.contains("\"futureTopLevel\": \"keep me\""));
        assertTrue(out.contains("\"futurePieceField\": 42"));
        assertTrue(out.contains("\"futureParam\": true"));
        assertTrue(out.contains("\"futureSetting\": \"x\""));
    }

    @Test void unknownPieceTypesSurviveAndAreReported() {
        Project p = sample();
        Piece weird = new Piece("thirdparty.laser_show", p.main.tracks.get(0).id, 5);
        weird.params.addProperty("intensity", 9);
        p.main.pieces.add(weird);
        Project back = ProjectCodec.read(ProjectCodec.write(p)).project();
        Piece kept = back.main.piece(weird.id);
        assertNotNull(kept);
        assertEquals(9, kept.params.get("intensity").getAsInt());
        Validator.Report r = Validator.validate(back, null, new Limits(), null, null);
        assertTrue(r.issues.stream().anyMatch(i -> i.code().equals("piece.unknown") && weird.id.equals(i.pieceId())));
    }

    @Test void newerSchemaLoadsBestEffort() {
        JsonObject o = ProjectCodec.toJson(sample());
        o.addProperty("schemaVersion", Project.SCHEMA_VERSION + 3);
        ProjectCodec.ReadResult rr = ProjectCodec.read(o);
        assertTrue(rr.newerSchema());
        assertFalse(rr.warnings().isEmpty());
        assertEquals(Project.SCHEMA_VERSION + 3, J.parseObject(ProjectCodec.write(rr.project())).get("schemaVersion").getAsInt(),
                "never silently downgrades the schema number");
    }

    @Test void legacyFilesMigrate() {
        String legacy = """
                {"name":"dungeon_intro","savedAt":1700000000000,"chain":"dungeon_part2",
                 "events":[{"id":"6f0f2c3e-6f3a-4d55-9d3c-5f8c2d1e9a00","tick":40,"command":"/say Oi","label":"intro"},
                           {"id":"7a1b2c3d-0000-4d55-9d3c-5f8c2d1e9a01","tick":0,"command":"title @a title \\"Hi\\"","label":""}],
                 "triggers":[{"name":"entrada","csqName":"dungeon_intro","x1":100,"y1":60,"z1":100,"x2":120,"y2":80,"z2":120,"cooldownTicks":200,"enabled":true}]}
                """;
        ProjectCodec.ReadResult rr = ProjectCodec.read(legacy);
        assertTrue(rr.migrated());
        assertEquals(0, rr.sourceVersion());
        Project p = rr.project();
        assertEquals("dungeon_intro", p.name);
        long commands = p.main.pieces.stream().filter(x -> x.type.equals("action.command")).count();
        assertEquals(2, commands);
        Piece intro = p.main.pieces.stream().filter(x -> "intro".equals(x.label)).findFirst().orElseThrow();
        assertEquals(40, intro.tick);
        assertEquals("say Oi", intro.params.get("command").getAsString(), "leading slash removed");
        assertTrue(p.main.pieces.stream().anyMatch(x -> x.type.equals("seq.control")), "legacy chain preserved as Sequence Control");
        assertEquals(1, p.triggers.size());
        assertEquals(Policies.TriggerKind.CONDITION, p.triggers.get(0).kind);
        Validator.Report r = Validator.validate(p, null, new Limits(), null, null);
        assertFalse(r.hasErrors(), r.issues.toString());
    }

    @Test void legacyServerConfigMigrates() {
        JsonObject legacy = J.parseObject("{\"allowCustomCSQUpload\":false,\"maxEventsPerCSQ\":256,\"maxDurationSeconds\":3600,\"maxChainDepth\":5,\"requireOpLevelToRun\":3}");
        ServerSettings s = ServerSettings.fromJson(legacy);
        assertEquals(5, s.limits.maxChainDepth);
        assertEquals(3600 * 20, s.limits.maxTimelineTicks);
        assertEquals(3, s.level(csequencer.core.security.Permission.RUN));
        ServerSettings again = ServerSettings.fromJson(s.toJson());
        assertEquals(s.toJson().toString(), again.toJson().toString());
    }

    @Test void validationFindsMissingResourcesAndIncompatibilities() {
        Project p = sample();
        Piece call = piece(p.main, 0, 5, "flow.call");
        call.params.addProperty("function", "does-not-exist");
        Piece play = piece(p.main, 0, 6, "audio.play");
        play.params.addProperty("audio", "ghost");
        Piece bad = piece(p.main, 0, 7, "flow.wait_until");
        ConditionGraph.Node not = node("logic.not");
        bad.params.add("condition", ConditionGraph.single(not).toJson());
        Validator.Report r = Validator.validate(p, PlatformInfo.headless(Capability.COMMANDS), new Limits(), h -> false, n -> false);
        List<String> codes = r.issues.stream().map(Validator.Issue::code).toList();
        assertTrue(codes.contains("fn.missing"));
        assertTrue(codes.contains("audio.missing"), "both the unknown reference and the missing file");
        assertTrue(codes.contains("condition.invalid"));
        assertTrue(codes.contains("piece.unsupported"), "audio unsupported on a commands-only platform");
        assertTrue(r.hasErrors());
        assertNotNull(r.toJson());
    }

    @Test void everyBuiltInPieceProducesAValidDefault() {
        for (PieceType t : PieceRegistry.pieces()) {
            Project p = project("defaults");
            Piece x = TimelineOps.create(p.main, t.id(), p.main.tracks.get(0).id, 0);
            assertNotNull(x.params);
            assertEquals(ProjectCodec.write(p), ProjectCodec.write(ProjectCodec.read(ProjectCodec.write(p)).project()), t.id());
        }
        assertTrue(PieceRegistry.pieces().size() >= 50, "library size " + PieceRegistry.pieces().size());
        assertFalse(PieceRegistry.search("teleport").isEmpty());
        assertFalse(PieceRegistry.search("wait condition").isEmpty());
    }

    @Test void fileStoreSavesAtomicallyAndReloads(@TempDir Path dir) throws Exception {
        FileProjectStore store = new FileProjectStore(dir, Runnable::run);
        store.loadAll();
        Project p = sample();
        ProjectMeta m = meta(p, 2);
        m.ownerUuid = "owner";
        store.save(p, m);
        p.name = "Mutated after save";
        assertEquals("Sample", store.find(p.id).project().name, "store keeps its own copy");
        assertEquals(1, store.find("sample").project().revision);
        store.save(store.find(p.id).project(), m);
        assertEquals(2, store.find(p.id).project().revision);
        Files.writeString(dir.resolve("projects").resolve("broken.csq.json"), "{ not json");
        FileProjectStore reloaded = new FileProjectStore(dir, Runnable::run);
        reloaded.loadAll();
        assertEquals(1, reloaded.all().size());
        assertEquals("owner", reloaded.find(p.id).meta().ownerUuid);
        assertTrue(reloaded.loadReport().stream().anyMatch(s -> s.contains("broken")), "corrupt files are reported, not deleted");
        assertTrue(Files.exists(dir.resolve("projects").resolve("broken.csq.json")));
        assertTrue(reloaded.delete(p.id));
        assertTrue(Files.list(dir.resolve("trash")).findAny().isPresent(), "deletes are soft (moved to trash)");
    }

    @Test void editorOperations() {
        Project p = project("edit");
        Timeline tl = p.main;
        Piece a = cmd(tl, 0, 10, "a");
        Piece b = cmd(tl, 1, 15, "b");
        EditorHistory h = new EditorHistory(50);
        String before = ProjectCodec.write(p);
        h.push(before);
        TimelineOps.move(tl, List.of(a, b), -20, 0);
        assertEquals(0, a.tick, "clamped so the earliest piece stays at 0");
        assertEquals(5, b.tick, "relative spacing preserved");
        String undone = h.undo(ProjectCodec.write(p));
        assertEquals(before, undone);
        assertNotNull(h.redo(undone));
        String clip = TimelineOps.copy(tl, List.of(a, b));
        List<Piece> pasted = TimelineOps.paste(tl, clip, 100, 0);
        assertEquals(2, pasted.size());
        assertEquals(100, pasted.get(0).tick);
        assertEquals(105, pasted.get(1).tick);
        assertNotEquals(a.id, pasted.get(0).id, "pasted pieces get new ids");
        assertEquals(tl.tracks.get(1).id, pasted.get(1).trackId, "track offsets preserved");
        assertEquals(20, TimelineOps.snap(23, 10, null, 0));
        assertEquals(37, TimelineOps.snap(36, 10, List.of(new Marker("m", 37, Policies.MarkerKind.CUE)), 3), "snaps to nearby markers");
        assertTrue(TimelineOps.removeTrack(tl, tl.tracks.get(1).id));
        assertTrue(tl.pieces.stream().allMatch(x -> x.trackId.equals(tl.tracks.get(0).id)), "pieces moved, not deleted");
        assertFalse(TimelineOps.removeTrack(tl, tl.tracks.get(0).id), "last track cannot be removed");
    }

    @Test void everyPieceHasACategory() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var c : PieceRegistry.pieceCategories()) names.add(c.name());
        for (PieceType t : PieceRegistry.pieces()) {
            assertTrue(names.contains(t.category()), t.id() + " is in unknown category " + t.category());
            assertEquals(PieceRegistry.categoryOf(t).color(), t.color(), "piece colour follows its category");
        }
        int total = 0;
        for (var c : PieceRegistry.pieceCategories()) {
            assertFalse(PieceRegistry.inCategory(c).isEmpty(), c.name() + " is empty");
            total += PieceRegistry.inCategory(c).size();
        }
        assertEquals(PieceRegistry.pieces().size(), total);
    }

    @Test void durationsParse() {
        assertEquals(40, Durations.parseTicks("2s"));
        assertEquals(30, Durations.parseTicks("1.5s"));
        assertEquals(80, Durations.parseTicks("00:04"));
        assertEquals(1245, Durations.parseTicks("1:02.250"));
        assertEquals(7, Durations.parseTicks("7"));
        assertEquals(1, Durations.parseTicks("50ms"));
        assertEquals("00:04", Durations.clock(80));
    }

    @Test void commandFilter() {
        List<String> bl = new ServerSettings().commandBlacklist;
        assertNull(CommandFilter.check("say hello", bl));
        assertNotNull(CommandFilter.check("op Steve", bl));
        assertNotNull(CommandFilter.check("/minecraft:op Steve", bl));
        assertNotNull(CommandFilter.check("execute as @a at @s run op @s", bl));
        assertNotNull(CommandFilter.check("say a\nop b", bl));
        assertNull(CommandFilter.check("execute as @a run say opportunity", bl));
    }
}
