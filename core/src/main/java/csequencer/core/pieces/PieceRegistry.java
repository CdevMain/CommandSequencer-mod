package csequencer.core.pieces;

import com.google.gson.JsonObject;
import csequencer.core.platform.Capability;
import csequencer.core.value.CompareOp;
import csequencer.core.value.ValueType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static csequencer.core.pieces.ParamType.*;

/**
 * Built-in library of piece types, query types, logic node types and event types.
 * Everything here is identified by stable string ids that are persisted in project files.
 */
public final class PieceRegistry {
    public static final int COL_ACTION = 0xFFE94560;
    public static final int COL_LOGIC = 0xFF42A5F5;
    public static final int COL_EVENT = 0xFFFFA726;
    public static final int COL_FLOW = 0xFF66BB6A;
    public static final int COL_AUDIO = 0xFFAB47BC;
    public static final int COL_DATA = 0xFF26C6DA;
    public static final int COL_DEBUG = 0xFF9E9E9E;

    private static final Map<String, PieceType> PIECES = new LinkedHashMap<>();
    private static final Map<String, QueryType> QUERIES = new LinkedHashMap<>();
    private static final Map<String, CondNodeType> NODES = new LinkedHashMap<>();
    private static final Map<String, EventType> EVENTS = new LinkedHashMap<>();

    private PieceRegistry() {}

    public static PieceType piece(String id) { return PIECES.get(id); }
    public static QueryType query(String id) { return QUERIES.get(id); }
    public static CondNodeType node(String id) { return NODES.get(id); }
    public static EventType event(String id) { return EVENTS.get(id); }
    public static List<PieceType> pieces() { return List.copyOf(PIECES.values()); }
    public static List<QueryType> queries() { return List.copyOf(QUERIES.values()); }
    public static List<CondNodeType> nodes() { return List.copyOf(NODES.values()); }
    public static List<EventType> events() { return List.copyOf(EVENTS.values()); }

    /** Case-insensitive search over id, name, category and description. */
    public static List<PieceType> search(String query) {
        if (query == null || query.isBlank()) return pieces();
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<PieceType> out = new ArrayList<>();
        for (PieceType t : PIECES.values()) {
            String hay = (t.id() + " " + t.name() + " " + t.category() + " " + t.description()).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : q.split("\\s+")) if (!hay.contains(word)) { all = false; break; }
            if (all) out.add(t);
        }
        return out;
    }

    /** Default params object for a new piece of the given type. */
    public static JsonObject defaults(PieceType t) {
        JsonObject o = new JsonObject();
        for (ParamSpec p : t.params()) o.add(p.key, p.def.deepCopy());
        return o;
    }

    public static JsonObject defaults(List<ParamSpec> specs) {
        JsonObject o = new JsonObject();
        for (ParamSpec p : specs) o.add(p.key, p.def.deepCopy());
        return o;
    }

    // ── DSL ─────────────────────────────────────────────────────────────────

    private static ParamSpec p(String key, String label, ParamType type) { return ParamSpec.of(key, label, type).build(); }
    private static ParamSpec.Builder pb(String key, String label, ParamType type) { return ParamSpec.of(key, label, type); }

    private static ParamSpec targets() {
        return pb("targets", "Targets", TARGET).def(targetMode("tracked"))
                .help("Who is affected: tracked player, initiator, context, all, participants, player, team, filter, selector").build();
    }

    private static ParamSpec fn(String key, String label) { return p(key, label, FUNCTION); }

    private static ParamSpec await() { return pb("await", "Wait for completion", BOOL).def(true).build(); }

    private static void piece(String id, String name, String cat, PieceKind kind, int color, String desc,
                              Capability cap, boolean dispatched, boolean privileged, int dur, ParamSpec... params) {
        PIECES.put(id, new PieceType(id, name, cat, kind, color, desc, List.of(params), cap, dispatched, privileged, dur));
    }

    private static void flow(String id, String name, String cat, String desc, ParamSpec... params) {
        piece(id, name, cat, PieceKind.FLOW, COL_FLOW, desc, null, false, false, 0, params);
    }

    private static void logic(String id, String name, String desc, ParamSpec... params) {
        piece(id, name, "Logic", PieceKind.LOGIC, COL_LOGIC, desc, null, false, false, 0, params);
    }

    private static void action(String id, String name, String desc, Capability cap, boolean privileged, ParamSpec... params) {
        piece(id, name, "Actions", PieceKind.ACTION, COL_ACTION, desc, cap, true, privileged, 0, params);
    }

    private static void query(String id, String name, String cat, ValueType result, Capability cap, boolean subject, String desc, ParamSpec... params) {
        QUERIES.put(id, new QueryType(id, name, cat, result, List.of(params), cap, subject, desc));
    }

    private static void node(String id, String name, String cat, int min, int max, boolean stateful, String desc, ParamSpec... params) {
        NODES.put(id, new CondNodeType(id, name, cat, min, max, List.of(params), stateful, desc));
    }

    private static void event(String id, String name, String desc, boolean internal, String... fields) {
        EVENTS.put(id, new EventType(id, name, desc, List.of(fields), internal));
    }

    private static String[] opIds() {
        List<String> ids = new ArrayList<>();
        for (CompareOp op : CompareOp.values()) ids.add(op.id);
        return ids.toArray(new String[0]);
    }

    static {
        String X = "Execution & timing";
        String SC = "Sequence control";

        // ── Execution & timing ──────────────────────────────────────────────
        flow("flow.start", "Sequence Start", X, "Entry marker. Pieces before it on the same track still run in tick order.");
        flow("flow.end", "Sequence End", X, "Ends the whole sequence instance.",
                pb("result", "Result", ENUM).options("complete", "fail").build(),
                p("message", "Message", TEXT));
        flow("flow.stop_branch", "Stop Branch", X, "Ends the current branch (track) only.");
        flow("flow.delay", "Delay", X, "Pauses this branch; every later piece on the track is shifted by the delay.",
                pb("ticks", "Delay", DURATION).def(20).range(0, 20 * 60 * 60 * 24).build());
        flow("flow.wait_until_tick", "Execute At Tick", X, "Waits until the sequence has run for the given number of ticks.",
                pb("tick", "Sequence tick", DURATION).def(0).range(0, Integer.MAX_VALUE).build());
        piece("flow.wait_until", "Wait Until", "Logic", PieceKind.LOGIC, COL_LOGIC,
                "Pauses this branch until the condition is true, with optional timeout.", null, false, false, 0,
                p("condition", "Condition", CONDITION),
                pb("timeout", "Timeout (0 = none)", DURATION).def(0).range(0, Integer.MAX_VALUE).build(),
                pb("interval", "Check interval (ticks)", INT).def(1).range(1, 1200).build(),
                fn("onTimeout", "On timeout"),
                pb("failOnTimeout", "Fail branch on timeout", BOOL).def(false).build());
        flow("flow.call", "Call Function", X, "Runs a reusable function/subsequence with typed inputs.",
                fn("function", "Function"), p("args", "Arguments", JSON), await(), p("resultVar", "Store outputs in local prefix", VARIABLE));
        flow("flow.parallel", "Parallel Branches", X, "Starts several functions at once and joins them.",
                p("functions", "Functions", FUNCTIONS),
                pb("join", "Join", ENUM).options("all", "any", "none").build(),
                pb("cancelOthers", "Cancel others when 'any' finishes", BOOL).def(true).build());
        flow("flow.repeat", "Repeat Loop", X, "Runs a function a fixed number of times (bounded).",
                fn("function", "Function"),
                pb("count", "Iterations", INT).def(2).range(1, 100000).build(),
                pb("indexVar", "Index variable", VARIABLE).def("i").build(),
                pb("interval", "Pause between iterations", DURATION).def(0).range(0, 72000).build());
        piece("flow.while", "Conditional Loop", "Logic", PieceKind.LOGIC, COL_LOGIC,
                "Repeats a function while (or until) a condition holds, bounded by an iteration limit.", null, false, false, 0,
                p("condition", "Condition", CONDITION), fn("function", "Function"),
                pb("mode", "Mode", ENUM).options("while", "do_while", "until").build(),
                pb("maxIterations", "Max iterations", INT).def(100).range(1, 100000).build(),
                pb("indexVar", "Index variable", VARIABLE).def("i").build());
        flow("flow.foreach", "For Each", X, "Iterates over a list (players, entities, values).",
                pb("source", "List", QUERY).def(queryExpr("players.online")).build(), fn("function", "Function"),
                pb("itemVar", "Item variable", VARIABLE).def("item").build(),
                pb("mode", "Mode", ENUM).options("sequential", "parallel").build(),
                pb("maxItems", "Max items", INT).def(100).range(1, 10000).build(),
                pb("asSubject", "Item becomes the subject", BOOL).def(true).build());
        flow("flow.jump", "Jump To Tick", X, "Moves this branch's playhead (loops a section), bounded by a jump limit.",
                pb("tick", "Target tick", DURATION).def(0).build(),
                pb("maxJumps", "Max jumps", INT).def(10).range(1, 100000).build());
        flow("flow.timeout", "Timed Window", X, "Runs a function; cancels it when the window closes.",
                fn("function", "Function"), pb("window", "Window", DURATION).def(100).range(1, Integer.MAX_VALUE).build(),
                fn("onTimeout", "On timeout"), await());
        flow("flow.random", "Weighted Random", X, "Picks one function using weights.",
                p("outcomes", "Outcomes", OUTCOMES), p("seed", "Seed (empty = random)", TEXT), await());
        flow("flow.cooldown", "Cooldown Gate", X, "Runs a function unless it ran within the cooldown.",
                fn("function", "Function"), pb("ticks", "Cooldown", DURATION).def(100).build(),
                p("key", "Key (shared cooldown name)", TEXT), pb("perPlayer", "Per subject", BOOL).def(false).build());
        flow("flow.debounce", "Debounce", X, "Runs a function after calls stop arriving for the given time.",
                fn("function", "Function"), pb("ticks", "Quiet time", DURATION).def(20).build(), p("key", "Key", TEXT));
        flow("flow.mutex", "Exclusive Section", X, "Runs a function while holding a named lock (other branches wait).",
                fn("function", "Function"), pb("lock", "Lock name", TEXT).def("main").build(),
                pb("timeout", "Wait timeout (0 = forever)", DURATION).def(0).build());

        // ── Sequence control ────────────────────────────────────────────────
        flow("seq.control", "Sequence Control", SC, "Starts, pauses, resumes, restarts or cancels sequences.",
                pb("action", "Action", ENUM).options("start", "pause", "resume", "restart", "cancel").build(),
                p("project", "Sequence (empty = this one)", PROJECT),
                pb("which", "Instances", ENUM).options("self", "all", "same_scope").build());
        flow("seq.phase", "Set Phase", SC, "Sets the phase name shown in the overlay and monitor.",
                p("name", "Phase", TEXT), p("next", "Next phase", TEXT));

        // ── Logic ───────────────────────────────────────────────────────────
        logic("logic.if", "If / Else If / Else", "Evaluates branches in order and runs the first matching function.",
                p("branches", "Branches", BRANCHES), fn("else", "Else"), await());
        logic("logic.switch", "Switch / Case", "Runs the function whose case matches the value.",
                p("value", "Value", QUERY), p("cases", "Cases", CASES), fn("default", "Default"), await());
        logic("logic.gate", "Condition Gate", "Continues only if the condition is true; otherwise stops the branch.",
                p("condition", "Condition", CONDITION),
                pb("otherwise", "Otherwise", ENUM).options("stop_branch", "skip_next", "fail").build());

        // ── Events ──────────────────────────────────────────────────────────
        piece("event.listen", "Event Listener", "Events", PieceKind.EVENT, COL_EVENT,
                "Runs a function every time a matching event happens while the sequence runs.", null, false, false, 0,
                pb("eventType", "Event", EVENT_TYPE).def("player.death").build(), p("signal", "Signal name (signal events)", TEXT),
                p("filter", "Filter", CONDITION), fn("function", "Function"),
                pb("once", "Only once", BOOL).def(false).build(),
                pb("maxFires", "Max fires (0 = unlimited)", INT).def(0).range(0, 1000000).build(),
                pb("cooldown", "Cooldown", DURATION).def(0).build(),
                pb("perPlayer", "Cooldown per player", BOOL).def(true).build(),
                pb("keepAlive", "Keep sequence alive while listening", BOOL).def(false).build());
        piece("event.wait", "Wait For Event", "Events", PieceKind.EVENT, COL_EVENT,
                "Pauses this branch until a matching event happens.", null, false, false, 0,
                pb("eventType", "Event", EVENT_TYPE).def("player.interact_block").build(), p("signal", "Signal name", TEXT),
                p("filter", "Filter", CONDITION), pb("timeout", "Timeout (0 = none)", DURATION).def(0).build(),
                fn("onTimeout", "On timeout"), pb("storeAs", "Store event subject in variable", VARIABLE).def("").build());
        piece("event.on_condition", "Condition Trigger", "Events", PieceKind.EVENT, COL_EVENT,
                "Watches a condition and runs a function on a transition (entered/exited/changed) or while true.", null, false, false, 0,
                p("condition", "Condition", CONDITION),
                pb("edge", "When", ENUM).options("entered", "exited", "changed", "level").build(),
                pb("over", "Evaluate for", ENUM).options("each_player", "participants", "global").build(),
                fn("function", "Function"),
                pb("interval", "Check interval (ticks)", INT).def(5).range(1, 1200).build(),
                pb("once", "Only once per subject", BOOL).def(false).build(),
                pb("fireInitial", "Fire if already true at start", BOOL).def(false).build(),
                pb("keepAlive", "Keep sequence alive while watching", BOOL).def(false).build());

        // ── Data & state ────────────────────────────────────────────────────
        String D = "Data & state";
        piece("var.set", "Set Variable", D, PieceKind.DATA, COL_DATA, "Sets or updates a local, sequence, player or global variable.",
                null, false, false, 0,
                pb("scope", "Scope", VAR_SCOPE).options("sequence", "local", "player", "global").build(),
                pb("name", "Name", VARIABLE).def("x").required().build(),
                pb("op", "Operation", ENUM).options("set", "add", "subtract", "multiply", "divide", "append", "remove", "toggle", "clear").build(),
                p("value", "Value", QUERY));
        piece("counter.step", "Counter", D, PieceKind.DATA, COL_DATA, "Increments a counter and runs a function when it reaches a threshold.",
                null, false, false, 0,
                pb("name", "Counter", VARIABLE).def("counter").build(),
                pb("scope", "Scope", VAR_SCOPE).options("sequence", "local", "player", "global").build(),
                pb("step", "Step", NUMBER).def(1).build(), pb("threshold", "Threshold (0 = none)", NUMBER).def(0).build(),
                fn("function", "On threshold"), pb("reset", "Reset at threshold", BOOL).def(true).build());
        piece("state.set", "Set State", D, PieceKind.DATA, COL_DATA, "Changes a state machine's state (fires state.changed).",
                null, false, false, 0, pb("machine", "Machine", TEXT).def("main").build(), p("state", "State", TEXT));
        piece("state.machine", "State Machine", D, PieceKind.DATA, COL_DATA,
                "Runs the function for each state when the machine enters it. Lives until the sequence ends.", null, false, false, 0,
                pb("machine", "Machine", TEXT).def("main").build(), p("states", "States", CASES), p("initial", "Initial state", TEXT),
                pb("keepAlive", "Keep sequence alive", BOOL).def(false).build());
        piece("signal.emit", "Emit Signal", D, PieceKind.DATA, COL_DATA, "Emits an internal signal; listeners use the 'signal' event.",
                null, false, false, 0, pb("name", "Signal", TEXT).def("go").build(), p("payload", "Payload", QUERY),
                pb("global", "Deliver to all sequences", BOOL).def(false).build());

        // ── Audio ───────────────────────────────────────────────────────────
        String A = "Audio";
        piece("audio.play", "Play Audio", A, PieceKind.AUDIO, COL_AUDIO,
                "Plays an imported WAV/MP3 on clients, synchronized to the server timeline.", Capability.AUDIO_STREAM, false, false, 0,
                p("audio", "Audio", AUDIO), pb("targets", "Listeners", TARGET).def(targetMode("all")).build(),
                pb("volume", "Volume", NUMBER).def(1).range(0, 2).build(),
                pb("offsetMs", "Start offset (ms)", NUMBER).def(0).range(0, Integer.MAX_VALUE).build(),
                pb("loop", "Loop", BOOL).def(false).build(), pb("channel", "Channel", TEXT).def("main").build(),
                pb("fadeInMs", "Fade in (ms)", NUMBER).def(0).range(0, 600000).build(),
                pb("fadeOutMs", "Fade out before the end (ms)", NUMBER).def(0).range(0, 600000).build());
        piece("audio.stop", "Stop Audio", A, PieceKind.AUDIO, COL_AUDIO, "Stops audio on a channel (optionally fading out).", Capability.AUDIO_STREAM, false, false, 0,
                pb("channel", "Channel", TEXT).def("main").build(), pb("fadeMs", "Fade out (ms)", NUMBER).def(0).range(0, 600000).build());
        piece("audio.volume", "Change Volume", A, PieceKind.AUDIO, COL_AUDIO, "Fades the volume of the audio on a channel (e.g. music ducking).",
                Capability.AUDIO_STREAM, false, false, 0,
                pb("channel", "Channel", TEXT).def("main").build(), pb("volume", "Volume", NUMBER).def(0.3).range(0, 2).build(),
                pb("fadeMs", "Fade time (ms)", NUMBER).def(1000).range(0, 600000).build());
        piece("audio.pause", "Pause Audio", A, PieceKind.AUDIO, COL_AUDIO, "Pauses audio on a channel.", Capability.AUDIO_STREAM, false, false, 0,
                pb("channel", "Channel", TEXT).def("main").build());
        piece("audio.resume", "Resume Audio", A, PieceKind.AUDIO, COL_AUDIO, "Resumes audio on a channel.", Capability.AUDIO_STREAM, false, false, 0,
                pb("channel", "Channel", TEXT).def("main").build());
        piece("audio.seek", "Seek Audio", A, PieceKind.AUDIO, COL_AUDIO, "Moves the audio playhead.", Capability.AUDIO_STREAM, false, false, 0,
                pb("channel", "Channel", TEXT).def("main").build(), pb("positionMs", "Position (ms)", NUMBER).def(0).build());
        piece("audio.sync", "Audio Sync Point", A, PieceKind.AUDIO, COL_AUDIO,
                "Waits until the audio on a channel reaches a position (server clock).", Capability.AUDIO_STREAM, false, false, 0,
                pb("channel", "Channel", TEXT).def("main").build(), pb("atMs", "Audio position (ms)", NUMBER).def(0).build());

        // ── Actions (dispatched to the platform) ────────────────────────────
        action("action.command", "Run Command", "Runs a Minecraft command (validated against the server's command policy).",
                Capability.COMMANDS, true,
                pb("command", "Command", LONG_TEXT).def("say Hello {player}").required().build(),
                pb("as", "Run as", ENUM).options("server", "subject").build());
        action("action.sound", "Sound Effect", "Plays a vanilla/resource-pack sound.", Capability.SOUNDS, false,
                pb("sound", "Sound", SOUND).def("minecraft:entity.experience_orb.pickup").build(), targets(),
                p("pos", "Position (empty = at listener)", VEC3), pb("volume", "Volume", NUMBER).def(1).range(0, 10).build(),
                pb("pitch", "Pitch", NUMBER).def(1).range(0.5, 2).build(),
                pb("source", "Category", ENUM).options("master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice").build());
        action("action.chat", "Chat Message", "Sends a chat message.", Capability.CHAT, false,
                pb("message", "Message", LONG_TEXT).def("Hello {player}!").build(), targets(),
                pb("color", "Colour", ENUM).options("white", "gray", "gold", "yellow", "aqua", "green", "red", "light_purple", "blue", "dark_red").build(),
                pb("bold", "Bold", BOOL).def(false).build());
        action("action.title", "Title / Subtitle", "Shows a title and subtitle.", Capability.TITLES, false,
                pb("title", "Title", TEXT).def("Title").build(), p("subtitle", "Subtitle", TEXT), targets(),
                pb("fadeIn", "Fade in", INT).def(10).range(0, 600).build(), pb("stay", "Stay", INT).def(70).range(0, 6000).build(),
                pb("fadeOut", "Fade out", INT).def(20).range(0, 600).build(),
                pb("color", "Colour", ENUM).options("white", "gold", "yellow", "aqua", "green", "red", "light_purple", "gray").build());
        action("action.actionbar", "Action Bar", "Shows an action bar message.", Capability.TITLES, false,
                pb("message", "Message", TEXT).def("...").build(), targets());
        action("action.teleport", "Teleport", "Teleports targets.", Capability.TELEPORT, false, targets(),
                pb("pos", "Position", VEC3).def("0 64 0").build(), p("dimension", "Dimension (empty = current)", DIMENSION),
                pb("relative", "Relative to target", BOOL).def(false).build(),
                pb("keepRotation", "Keep rotation", BOOL).def(true).build(),
                p("yaw", "Yaw", NUMBER), p("pitch", "Pitch", NUMBER));
        action("action.spawn_entity", "Spawn Entity", "Spawns entities (optionally removed when the sequence ends).", Capability.ENTITIES, false,
                pb("entity", "Entity", ENTITY_TYPE).def("minecraft:zombie").build(), pb("pos", "Position", VEC3).def("0 64 0").build(),
                p("dimension", "Dimension", DIMENSION), pb("count", "Count", INT).def(1).range(1, 64).build(),
                p("nbt", "NBT", TEXT), p("tag", "Tag", TEXT), pb("removeOnEnd", "Remove when sequence ends", BOOL).def(false).build(),
                p("storeAs", "Store first entity in variable", VARIABLE));
        action("action.remove_entities", "Remove Entities", "Removes non-player entities by type, tag and radius.", Capability.ENTITIES, false,
                p("entity", "Entity type (empty = any)", ENTITY_TYPE), p("tag", "Tag", TEXT), pb("center", "Center", VEC3).def("0 64 0").build(),
                pb("radius", "Radius", NUMBER).def(16).range(0, 256).build(), p("dimension", "Dimension", DIMENSION),
                pb("spawnedBySequence", "Only entities spawned by this sequence", BOOL).def(false).build());
        action("action.particles", "Particles", "Spawns particles.", Capability.PARTICLES, false,
                pb("particle", "Particle", PARTICLE).def("minecraft:flame").build(), p("pos", "Position (empty = at targets)", VEC3),
                pb("delta", "Spread", VEC3).def("0.5 0.5 0.5").build(), pb("speed", "Speed", NUMBER).def(0).range(0, 10).build(),
                pb("count", "Count", INT).def(20).range(0, 5000).build(), targets(), p("dimension", "Dimension", DIMENSION),
                pb("force", "Force (long range)", BOOL).def(false).build());
        action("action.effect", "Status Effect", "Gives or clears a status effect.", Capability.EFFECTS, false, targets(),
                pb("effect", "Effect", EFFECT).def("minecraft:speed").build(), pb("duration", "Duration", DURATION).def(200).build(),
                pb("amplifier", "Amplifier", INT).def(0).range(0, 255).build(), pb("hideParticles", "Hide particles", BOOL).def(false).build(),
                pb("mode", "Mode", ENUM).options("give", "clear", "clear_all").build());
        action("action.player_state", "Player State", "Changes compatible player state.", Capability.PLAYER_STATE, false, targets(),
                pb("property", "Property", ENUM).options("health", "heal_full", "food", "saturation", "xp_levels", "fire_ticks", "extinguish", "gamemode", "air").build(),
                p("value", "Value", TEXT));
        action("action.set_block", "Set Block", "Places a block state.", Capability.BLOCKS, false,
                pb("pos", "Position", VEC3).def("0 64 0").build(), pb("block", "Block", BLOCK).def("minecraft:stone").build(),
                p("dimension", "Dimension", DIMENSION), pb("mode", "Mode", ENUM).options("replace", "destroy", "keep").build());
        action("action.fill", "Fill Region", "Fills a region (bounded by the server's block limit).", Capability.BLOCKS, false,
                pb("from", "From", VEC3).def("0 64 0").build(), pb("to", "To", VEC3).def("4 68 4").build(),
                pb("block", "Block", BLOCK).def("minecraft:stone").build(), p("filter", "Only replace (empty = all)", BLOCK),
                pb("mode", "Mode", ENUM).options("replace", "hollow", "outline", "keep", "destroy").build(), p("dimension", "Dimension", DIMENSION));
        action("action.clone", "Clone Region", "Copies a region of blocks.", Capability.STRUCTURES, false,
                pb("from", "From", VEC3).def("0 64 0").build(), pb("to", "To", VEC3).def("4 68 4").build(),
                pb("dest", "Destination", VEC3).def("10 64 0").build(),
                pb("mode", "Mode", ENUM).options("replace", "masked", "move").build(), p("dimension", "Dimension", DIMENSION));
        action("action.structure", "Place Structure", "Places a saved structure template.", Capability.STRUCTURES, false,
                pb("structure", "Structure id", TEXT).def("minecraft:village/plains/houses/plains_small_house_1").build(),
                pb("pos", "Position", VEC3).def("0 64 0").build(),
                pb("rotation", "Rotation", ENUM).options("none", "clockwise_90", "180", "counterclockwise_90").build(),
                pb("mirror", "Mirror", ENUM).options("none", "left_right", "front_back").build(), p("dimension", "Dimension", DIMENSION));
        action("action.scoreboard", "Scoreboard", "Updates a scoreboard value.", Capability.SCOREBOARD, false,
                pb("objective", "Objective", TEXT).def("csq").build(), pb("holder", "Holder (empty = targets)", TEXT).def("").build(),
                targets(), pb("op", "Operation", ENUM).options("set", "add", "remove", "reset").build(), pb("value", "Value", INT).def(1).build(),
                pb("create", "Create objective if missing", BOOL).def(true).build());
        action("action.give_item", "Give Item", "Gives items to targets.", Capability.ITEMS, false, targets(),
                pb("item", "Item", ITEM).def("minecraft:diamond").build(), pb("count", "Count", INT).def(1).range(1, 6400).build(),
                p("nbt", "NBT", TEXT));
        action("action.clear_item", "Clear Item", "Removes items from targets' inventories.", Capability.ITEMS, false, targets(),
                p("item", "Item (empty = any)", ITEM), pb("count", "Max count (-1 = all)", INT).def(-1).build());

        // ── Debug ───────────────────────────────────────────────────────────
        String DBG = "Debug";
        piece("debug.log", "Log", DBG, PieceKind.DEBUG, COL_DEBUG, "Writes to the task log (and optionally to monitoring admins).",
                null, false, false, 0, pb("message", "Message", LONG_TEXT).def("tick {tick}").build(),
                pb("level", "Level", ENUM).options("info", "warn", "error").build(), pb("notify", "Notify admins", BOOL).def(false).build());
        piece("debug.breakpoint", "Breakpoint", DBG, PieceKind.DEBUG, COL_DEBUG, "Pauses the sequence for step-by-step debugging.",
                null, false, false, 0, p("condition", "Only when (empty = always)", CONDITION),
                pb("onlyInDebug", "Only in debug runs", BOOL).def(true).build());
        piece("debug.assert", "Assert", DBG, PieceKind.DEBUG, COL_DEBUG, "Fails or logs when a condition is false.",
                null, false, false, 0, p("condition", "Condition", CONDITION), p("message", "Message", TEXT),
                pb("failTask", "Fail sequence", BOOL).def(false).build());

        // ── Screen & HUD ───────────────────────────────────────────────────
        String SCR = "Screen";
        piece("hud.popup", "Show Popup", SCR, PieceKind.ACTION, COL_ACTION, "Shows a side notification card with an icon, title and message.",
                Capability.HUD, false, false, 0,
                pb("title", "Title", TEXT).def("Heads up!").build(), pb("message", "Message", LONG_TEXT).def("Something is happening, {player}").build(),
                pb("icon", "Icon (character)", TEXT).def("⚔").build(),
                pb("style", "Style", ENUM).options("info", "success", "warning", "danger", "boss", "gold").build(),
                p("color", "Custom colour (#RRGGBB)", TEXT),
                pb("position", "Position", ENUM).options("right", "left", "top", "center").build(),
                pb("duration", "Show for", DURATION).def(100).range(10, 20 * 60 * 10).build(),
                pb("sound", "Play a notification sound", BOOL).def(true).build(), targets());
        piece("hud.overlay", "Show Overlay", SCR, PieceKind.ACTION, COL_ACTION,
                "Shows a custom on-screen overlay: banner, progress bar (e.g. boss health), countdown timer or text. Stays until hidden.",
                Capability.HUD, false, false, 0,
                pb("id", "Overlay id", TEXT).def("main").help("showing the same id again replaces it").build(),
                pb("style", "Style", ENUM).options("banner", "bar", "timer", "text").build(),
                pb("title", "Title", TEXT).def("{seq}").build(), p("subtitle", "Subtitle", TEXT),
                pb("value", "Bar value (empty = no bar)", QUERY).def(new com.google.gson.JsonPrimitive("")).build(),
                pb("max", "Bar maximum", QUERY).def(new com.google.gson.JsonPrimitive(100)).build(),
                pb("countdown", "Countdown (0 = none)", DURATION).def(0).build(),
                pb("color", "Colour", ENUM).options("info", "success", "warning", "danger", "boss", "gold").build(),
                p("customColor", "Custom colour (#RRGGBB)", TEXT),
                pb("position", "Position", ENUM).options("top", "bottom", "left", "right", "center").build(),
                pb("duration", "Hide after (0 = keep)", DURATION).def(0).build(),
                pb("live", "Update live (texts and values)", BOOL).def(true).build(), targets());
        piece("hud.marker", "Waypoint Marker", SCR, PieceKind.ACTION, COL_ACTION,
                "Shows a marker on the player's screen pointing to a place (or following an entity), with the distance.",
                Capability.HUD, false, false, 0,
                pb("id", "Marker id", TEXT).def("goal").build(), pb("pos", "Position", VEC3).def("0 64 0").build(),
                pb("follow", "Follow entity (optional)", QUERY).def(new com.google.gson.JsonPrimitive("")).build(),
                pb("label", "Label", TEXT).def("Go here").build(), pb("icon", "Icon (character)", TEXT).def("◆").build(),
                pb("color", "Colour", ENUM).options("gold", "info", "success", "warning", "danger", "boss").build(),
                p("customColor", "Custom colour (#RRGGBB)", TEXT),
                pb("showDistance", "Show distance", BOOL).def(true).build(),
                pb("hideWithin", "Hide when closer than (blocks, 0 = never)", NUMBER).def(0).range(0, 1000).build(),
                p("dimension", "Dimension", DIMENSION),
                pb("duration", "Hide after (0 = keep)", DURATION).def(0).build(), targets());
        piece("hud.screen", "Screen Effect", SCR, PieceKind.ACTION, COL_ACTION,
                "Cinematic effects: fade to/from a colour, flash, camera shake, letterbox bars, vignette.", Capability.HUD, false, false, 0,
                pb("effect", "Effect", ENUM).options("fade_out", "fade_in", "flash", "shake", "letterbox_on", "letterbox_off", "vignette_on", "vignette_off").build(),
                pb("color", "Colour (#RRGGBB)", TEXT).def("#000000").build(),
                pb("duration", "Transition time", DURATION).def(20).range(1, 20 * 60).build(),
                pb("intensity", "Intensity", NUMBER).def(1).range(0.1, 3).build(), targets());
        piece("hud.bossbar", "Boss Bar", SCR, PieceKind.ACTION, COL_ACTION,
                "Shows a vanilla boss bar. Follow an entity to show its health automatically, or bind any value.", Capability.HUD, false, false, 0,
                pb("id", "Bar id", TEXT).def("boss").build(), pb("name", "Name", TEXT).def("The Boss").build(),
                pb("entity", "Follow entity health (optional)", QUERY).def(new com.google.gson.JsonPrimitive("")).build(),
                pb("value", "Value (when not following)", QUERY).def(new com.google.gson.JsonPrimitive(100)).build(),
                pb("max", "Maximum", QUERY).def(new com.google.gson.JsonPrimitive(100)).build(),
                pb("color", "Colour", ENUM).options("red", "pink", "blue", "green", "yellow", "purple", "white").build(),
                pb("style", "Segments", ENUM).options("progress", "notched_6", "notched_10", "notched_12", "notched_20").build(),
                pb("darkenSky", "Darken sky", BOOL).def(false).build(),
                pb("hideWhenDead", "Hide when the entity dies", BOOL).def(true).build(),
                pb("targets", "Shown to", TARGET).def(targetMode("all")).build());
        piece("hud.hide", "Hide Screen Element", SCR, PieceKind.ACTION, COL_ACTION, "Removes overlays, markers, boss bars or screen effects shown by this sequence.",
                Capability.HUD, false, false, 0,
                pb("kind", "What", ENUM).options("any", "overlay", "marker", "bossbar", "screen", "effect", "camera", "look", "controls", "anim").build(),
                pb("id", "Id (empty = all)", TEXT).def("").build());
        // ── Camera & player control ───────────────────────────────────────
        String CAM = "Camera";
        com.google.gson.JsonPrimitive NONE = new com.google.gson.JsonPrimitive("");
        piece("camera.fixed", "Camera: Fixed Shot", CAM, PieceKind.ACTION, COL_ACTION,
                "Moves the player's camera to a fixed point. It can look in a fixed direction, at a point, at an entity, or keep "
                        + "looking at the player's own body (a corner shot). Stays until Camera Reset (or the time below).",
                Capability.HUD, false, false, 0,
                pb("pos", "Camera position", VEC3).def("~4 ~3 ~4").help("~ = relative to the tracked player").build(),
                pb("look", "Look", ENUM).options("at_player", "at_point", "at_entity", "rotation").build(),
                pb("lookAt", "Look at point", VEC3).def("~ ~1 ~").build(),
                pb("entity", "Look at entity", QUERY).def(NONE).build(),
                pb("yaw", "Yaw (rotation)", NUMBER).def(0).range(-360, 360).build(),
                pb("pitch", "Pitch (rotation)", NUMBER).def(20).range(-90, 90).build(),
                pb("blend", "Smooth move from the current view", DURATION).def(0).range(0, 20 * 60).build(),
                pb("fov", "Field of view (0 = normal)", NUMBER).def(0).range(0, 150).build(),
                pb("lockPlayer", "Freeze the player while filming", BOOL).def(true).build(),
                pb("hideHud", "Hide the HUD", BOOL).def(true).build(),
                pb("duration", "Back to the player after (0 = keep)", DURATION).def(0).build(),
                pb("returnBlend", "Smooth return time", DURATION).def(10).range(0, 20 * 60).build(), targets());
        piece("camera.path", "Camera: Cinematic Path", CAM, PieceKind.ACTION, COL_ACTION,
                "Animates the camera through keyframes (one per line: x y z [yaw pitch]) with smooth curves and easing. "
                        + "Use it for fly-throughs, intros and boss reveals.",
                Capability.HUD, false, false, 0,
                pb("points", "Keyframes (x y z yaw pitch, one per line)", LONG_TEXT)
                        .def("~8 ~4 ~ 90 20\n~ ~6 ~8 180 30\n~-8 ~4 ~ 270 20\n~ ~3 ~-8 360 15").build(),
                pb("duration", "Duration", DURATION).def(100).range(1, 20 * 60 * 10).build(),
                pb("easing", "Easing", ENUM).options("ease_in_out", "linear", "ease_in", "ease_out").build(),
                pb("smooth", "Smooth curve between keyframes", BOOL).def(true).build(),
                pb("look", "Look", ENUM).options("keyframes", "at_player", "at_point", "at_entity", "forward").build(),
                pb("lookAt", "Look at point", VEC3).def("~ ~1 ~").build(),
                pb("entity", "Look at entity", QUERY).def(NONE).build(),
                pb("loop", "Loop", BOOL).def(false).build(),
                pb("afterEnd", "When it ends", ENUM).options("return", "hold").build(),
                pb("blend", "Smooth move to the first keyframe", DURATION).def(0).range(0, 20 * 60).build(),
                pb("returnBlend", "Smooth return time", DURATION).def(15).range(0, 20 * 60).build(),
                pb("fov", "Field of view (0 = normal)", NUMBER).def(0).range(0, 150).build(),
                pb("lockPlayer", "Freeze the player while filming", BOOL).def(true).build(),
                pb("hideHud", "Hide the HUD", BOOL).def(true).build(),
                pb("wait", "Wait until the path ends", BOOL).def(false).build(), targets());
        piece("camera.orbit", "Camera: Orbit", CAM, PieceKind.ACTION, COL_ACTION,
                "Circles the camera around the player, a point or an entity while looking at it.",
                Capability.HUD, false, false, 0,
                pb("center", "Around", ENUM).options("player", "point", "entity").build(),
                pb("pos", "Point", VEC3).def("~ ~ ~").build(),
                pb("entity", "Entity", QUERY).def(NONE).build(),
                pb("radius", "Radius (blocks)", NUMBER).def(6).range(0.5, 256).build(),
                pb("height", "Height above (blocks)", NUMBER).def(3).range(-64, 256).build(),
                pb("startAngle", "Start angle (degrees)", NUMBER).def(0).range(-360, 360).build(),
                pb("speed", "Speed (degrees per second)", NUMBER).def(20).range(-720, 720).build(),
                pb("blend", "Smooth move from the current view", DURATION).def(10).range(0, 20 * 60).build(),
                pb("fov", "Field of view (0 = normal)", NUMBER).def(0).range(0, 150).build(),
                pb("lockPlayer", "Freeze the player while filming", BOOL).def(true).build(),
                pb("hideHud", "Hide the HUD", BOOL).def(true).build(),
                pb("duration", "Back to the player after (0 = keep)", DURATION).def(0).build(),
                pb("returnBlend", "Smooth return time", DURATION).def(10).range(0, 20 * 60).build(), targets());
        piece("camera.reset", "Camera: Back To Player", CAM, PieceKind.ACTION, COL_ACTION,
                "Returns the camera to the player's eyes (smoothly) and gives control back.", Capability.HUD, false, false, 0,
                pb("blend", "Smooth return time", DURATION).def(10).range(0, 20 * 60).build(), targets());
        piece("camera.look_at", "Force Look At", CAM, PieceKind.ACTION, COL_ACTION,
                "Turns the player's own view towards an entity, block or place. Strength 1 locks the view; lower values only pull it.",
                Capability.HUD, false, false, 0,
                pb("target", "Look at", ENUM).options("entity", "block", "point").build(),
                pb("entity", "Entity", QUERY).def(queryVar("boss")).build(),
                pb("pos", "Block / point", VEC3).def("~ ~ ~5").build(),
                pb("strength", "Strength (1 = locked)", NUMBER).def(1).range(0.02, 1).build(),
                pb("duration", "For (0 = until released)", DURATION).def(60).build(), targets());
        piece("camera.release_look", "Release Look", CAM, PieceKind.ACTION, COL_ACTION,
                "Stops Force Look At.", Capability.HUD, false, false, 0, targets());
        piece("player.controls", "Player Controls", CAM, PieceKind.ACTION, COL_ACTION,
                "Locks what the player can do: walk, jump, look around, attack/use, open the inventory or any menu; hides the HUD or hand.",
                Capability.HUD, false, false, 0,
                pb("movement", "Freeze movement", BOOL).def(true).build(),
                pb("jump", "Block jumping and sneaking", BOOL).def(true).build(),
                pb("look", "Lock looking around", BOOL).def(false).build(),
                pb("actions", "Block attack / use / drop / hotbar", BOOL).def(true).build(),
                pb("inventory", "Block the inventory", BOOL).def(true).build(),
                pb("screens", "Block every menu (pause and chat stay)", BOOL).def(false).build(),
                pb("hideHud", "Hide the HUD", BOOL).def(false).build(),
                pb("hideHand", "Hide the hand", BOOL).def(false).build(),
                pb("duration", "For (0 = until released)", DURATION).def(0).build(), targets());
        piece("player.animation", "Player Animation", CAM, PieceKind.ACTION, COL_ACTION,
                "Animates the players' bodies, seen by everyone (and by themselves through a camera shot): insanity "
                        + "(convulsions, head snapping, flailing arm), shiver (cold) or sway (dizzy).", Capability.HUD, false, false, 0,
                pb("animation", "Animation", ENUM).options("insanity", "shiver", "sway").build(),
                pb("intensity", "Intensity", NUMBER).def(1).range(0.05, 1).build(),
                pb("duration", "For (0 = until stopped)", DURATION).def(100).build(), targets());
        piece("player.stop_animation", "Stop Player Animation", CAM, PieceKind.ACTION, COL_ACTION,
                "Stops Player Animation.", Capability.HUD, false, false, 0,
                pb("animation", "Animation", ENUM).options("all", "insanity", "shiver", "sway").build());
        piece("player.release_controls", "Release Controls", CAM, PieceKind.ACTION, COL_ACTION,
                "Gives the player full control back (undoes Player Controls).", Capability.HUD, false, false, 0, targets());

        piece("screen.effect", "Screen Effect+", SCR, PieceKind.ACTION, COL_ACTION,
                "Full-screen effects: dizziness, sleep (eyes closing), heat, cold, insanity (glitches and words all over the screen), "
                        + "poison, inverted colours, melting screen, black and white, blood running down. Several can be combined.",
                Capability.HUD, false, false, 0,
                pb("effect", "Effect", ENUM).options("dizzy", "sleep", "heat", "cold", "insanity", "poison", "invert", "melt", "grayscale", "blood").build(),
                pb("intensity", "Intensity", NUMBER).def(1).range(0.05, 1).build(),
                pb("words", "Insanity words (comma separated)", TEXT).def("")
                        .help("shown scattered on screen by 'insanity'; empty = the list in the player's client config").build(),
                pb("fadeIn", "Fade in", DURATION).def(20).range(0, 20 * 60).build(),
                pb("duration", "For (0 = until cleared)", DURATION).def(100).build(),
                pb("fadeOut", "Fade out", DURATION).def(20).range(0, 20 * 60).build(), targets());
        piece("screen.clear_effects", "Clear Screen Effects", SCR, PieceKind.ACTION, COL_ACTION,
                "Fades out Screen Effect+ effects.", Capability.HUD, false, false, 0,
                pb("effect", "Effect", ENUM).options("all", "dizzy", "sleep", "heat", "cold", "insanity", "poison", "invert", "melt", "grayscale", "blood").build(),
                pb("fadeOut", "Fade out", DURATION).def(20).range(0, 20 * 60).build(), targets());

        piece("flow.wait_death", "Wait For Death", "Events", PieceKind.EVENT, COL_EVENT,
                "Waits until an entity (e.g. the spawned boss) dies or despawns, or runs a function when it does.", null, false, false, 0,
                pb("entity", "Entity", QUERY).def(queryVar("boss")).help("e.g. the variable set by Spawn Entity → 'Store first entity in variable'").build(),
                pb("mode", "Then", ENUM).options("wait", "run_function").build(), fn("function", "Function"));

        piece("zone.keep_inside", "Keep Inside Area", "Players", PieceKind.EVENT, COL_EVENT,
                "Arena barrier: players who leave the area are sent back (with a warning) until the area is released or the sequence ends.",
                Capability.TELEPORT, false, false, 0,
                pb("id", "Area id", TEXT).def("arena").build(),
                pb("from", "Corner 1", VEC3).def("0 60 0").build(), pb("to", "Corner 2", VEC3).def("20 80 20").build(),
                p("returnTo", "Send back to (empty = centre)", VEC3),
                pb("message", "Warning", TEXT).def("You can't leave the arena!").build(),
                pb("interval", "Check every (ticks)", INT).def(10).range(1, 200).build(),
                pb("targets", "Applies to", TARGET).def(targetMode("participants")).build());
        piece("zone.release", "Release Area", "Players", PieceKind.ACTION, COL_ACTION, "Stops a 'Keep Inside Area' barrier.", null, false, false, 0,
                pb("id", "Area id (empty = all)", TEXT).def("arena").build());

        // ── Tracking & player sessions ─────────────────────────────────────
        piece("track.player", "Track Player", "Players", PieceKind.DATA, COL_DATA,
                "Chooses the player that the following pieces act on (targets set to 'Tracked player' and player conditions).",
                null, false, false, 0,
                pb("source", "Player", ENUM).options("named", "initiator", "event_player", "subject", "random_participant", "random_online").build(),
                pb("player", "Player name", PLAYER).help("used when Player = named; any online player or a {placeholder}").build(),
                pb("scope", "Applies to", ENUM).options("sequence", "branch").build());
        piece("event.player_join", "On Player Join", "Events", PieceKind.EVENT, COL_EVENT,
                "Runs a function, or waits, when a player logs in.", null, false, false, 0,
                pb("who", "Which player", ENUM).options("any", "tracked", "named").build(),
                pb("player", "Player name", PLAYER).help("used when Which player = named").build(),
                pb("mode", "Then", ENUM).options("run_function", "wait").build(),
                fn("function", "Function"),
                pb("trackJoined", "Track the player who joined", BOOL).def(false).build(),
                pb("once", "Only once", BOOL).def(false).build(),
                pb("timeout", "Wait timeout (0 = none)", DURATION).def(0).build(),
                pb("keepAlive", "Keep sequence alive while listening", BOOL).def(true).build());
        piece("event.player_leave", "On Player Leave", "Events", PieceKind.EVENT, COL_EVENT,
                "Runs a function, or waits, when a player logs out.", null, false, false, 0,
                pb("who", "Which player", ENUM).options("any", "tracked", "named").build(),
                pb("player", "Player name", PLAYER).help("used when Which player = named").build(),
                pb("mode", "Then", ENUM).options("run_function", "wait").build(),
                fn("function", "Function"),
                pb("once", "Only once", BOOL).def(false).build(),
                pb("timeout", "Wait timeout (0 = none)", DURATION).def(0).build(),
                pb("keepAlive", "Keep sequence alive while listening", BOOL).def(true).build());

        registerQueries();
        registerNodes();
        registerEvents();
    }

    // ── Categories ─────────────────────────────────────────────────────────

    private static final List<PieceCategory> CATEGORIES = List.of(
            new PieceCategory("timing", "Timing", "◷", 0xFF66BB6A, "Start, end, delays and timed windows"),
            new PieceCategory("flow", "Flow", "⇉", 0xFF9CCC65, "Functions, loops, parallel branches, random"),
            new PieceCategory("logic", "Logic", "◆", 0xFF42A5F5, "If / else, switch, gates and waiting for conditions"),
            new PieceCategory("events", "Events", "⚡", 0xFFFFA726, "React to players, the world and signals"),
            new PieceCategory("variables", "Variables", "≡", 0xFF26C6DA, "Variables, counters and state machines"),
            new PieceCategory("messages", "Messages", "✉", 0xFFF06292, "Chat, titles and action bar"),
            new PieceCategory("players", "Players", "☺", 0xFFE94560, "Teleport, effects, health, items"),
            new PieceCategory("world", "World", "▦", 0xFFA1887F, "Blocks, regions and structures"),
            new PieceCategory("entities", "Entities", "♞", 0xFFEF5350, "Spawn and remove entities"),
            new PieceCategory("effects", "Effects", "✦", 0xFFFFD54F, "Particles and sound effects"),
            new PieceCategory("screen", "Screen", "▣", 0xFF5C6BC0, "Popups, overlays, waypoint markers, boss bars and cinematic effects"),
            new PieceCategory("camera", "Camera", "◎", 0xFF26A69A, "Cinematic camera, forced look and player controls"),
            new PieceCategory("audio", "Audio", "♪", 0xFFAB47BC, "Imported music and audio sync"),
            new PieceCategory("commands", "Commands", "›", 0xFFB0BEC5, "Minecraft commands and scoreboard"),
            new PieceCategory("sequence", "Sequence", "▶", 0xFF7E57C2, "Start/stop other sequences, phases"),
            new PieceCategory("debug", "Debug", "⚙", 0xFF9E9E9E, "Logs, breakpoints and assertions"));

    private static final Map<String, String> CATEGORY_OF = new java.util.HashMap<>();

    static { organize(); } // after CATEGORIES/CATEGORY_OF are initialised (static order is textual)

    private static void cat(String category, String... ids) { for (String id : ids) CATEGORY_OF.put(id, category); }

    /** Assigns every piece to a category, colours it by category and orders the library by category. */
    private static void organize() {
        cat("timing", "flow.start", "flow.end", "flow.delay", "flow.wait_until_tick", "flow.timeout", "flow.jump");
        cat("flow", "flow.call", "flow.parallel", "flow.repeat", "flow.foreach", "flow.random", "flow.cooldown", "flow.debounce", "flow.mutex", "flow.stop_branch");
        cat("logic", "logic.if", "logic.switch", "logic.gate", "flow.wait_until", "flow.while");
        cat("events", "event.player_join", "event.player_leave", "flow.wait_death", "event.listen", "event.wait", "event.on_condition", "signal.emit");
        cat("variables", "var.set", "counter.step", "state.set", "state.machine");
        cat("messages", "action.chat", "action.title", "action.actionbar");
        cat("players", "track.player", "zone.keep_inside", "zone.release", "action.teleport", "action.effect", "action.player_state", "action.give_item", "action.clear_item");
        cat("world", "action.set_block", "action.fill", "action.clone", "action.structure");
        cat("entities", "action.spawn_entity", "action.remove_entities");
        cat("effects", "action.particles", "action.sound");
        cat("screen", "hud.popup", "hud.overlay", "hud.marker", "hud.bossbar", "hud.screen", "screen.effect", "screen.clear_effects", "hud.hide");
        cat("camera", "camera.fixed", "camera.path", "camera.orbit", "camera.reset", "camera.look_at", "camera.release_look",
                "player.controls", "player.release_controls", "player.animation", "player.stop_animation");
        cat("audio", "audio.play", "audio.stop", "audio.volume", "audio.pause", "audio.resume", "audio.seek", "audio.sync");
        cat("commands", "action.command", "action.scoreboard");
        cat("sequence", "seq.control", "seq.phase");
        cat("debug", "debug.log", "debug.breakpoint", "debug.assert");
        Map<String, PieceType> ordered = new LinkedHashMap<>();
        for (PieceCategory c : CATEGORIES) {
            for (PieceType t : PIECES.values()) {
                if (!c.id().equals(CATEGORY_OF.get(t.id()))) continue;
                ordered.put(t.id(), new PieceType(t.id(), t.name(), c.name(), t.kind(), c.color(), t.description(), t.params(),
                        t.capability(), t.dispatched(), t.privileged(), t.defaultDuration()));
            }
        }
        for (PieceType t : PIECES.values()) ordered.putIfAbsent(t.id(), t); // anything uncategorised stays visible
        PIECES.clear();
        PIECES.putAll(ordered);
    }

    public static List<PieceCategory> pieceCategories() { return CATEGORIES; }

    /** Category of a piece type (null if the type is unknown). */
    public static PieceCategory categoryOf(PieceType t) {
        if (t == null) return null;
        for (PieceCategory c : CATEGORIES) if (c.name().equals(t.category())) return c;
        return null;
    }

    public static List<PieceType> inCategory(PieceCategory c) {
        List<PieceType> out = new ArrayList<>();
        for (PieceType t : PIECES.values()) if (t.category().equals(c.name())) out.add(t);
        return out;
    }

    public static JsonObject targetMode(String mode) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        return o;
    }

    public static JsonObject queryVar(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("q", "var");
        JsonObject args = new JsonObject();
        args.addProperty("name", name);
        o.add("args", args);
        return o;
    }

    public static JsonObject queryExpr(String q) {
        JsonObject o = new JsonObject();
        o.addProperty("q", q);
        o.add("args", new JsonObject());
        return o;
    }

    private static ParamSpec who() { return pb("who", "Player (empty = subject)", TEXT).build(); }

    private static void registerQueries() {
        String C = "Core", P = "Player", PS = "Players", W = "World", E = "Entities", S = "Sequence";
        Capability PQ = Capability.PLAYER_QUERIES, WQ = Capability.WORLD_QUERIES, EQ = Capability.ENTITY_QUERIES;

        query("literal", "Value", C, ValueType.TEXT, null, false, "A constant value.", p("value", "Value", TEXT));
        query("template", "Text Template", C, ValueType.TEXT, null, false, "Text with {placeholders}.", p("text", "Text", TEXT));
        query("vec.offset", "Offset Position", C, ValueType.VEC3, null, false, "A position moved by dx, dy, dz (e.g. a checkpoint 20 blocks east of the start).",
                p("base", "Position", QUERY), pb("dx", "dx", NUMBER).def(0).build(), pb("dy", "dy", NUMBER).def(0).build(), pb("dz", "dz", NUMBER).def(0).build());
        query("var", "Variable", C, ValueType.TEXT, null, false, "Reads a variable.",
                pb("scope", "Scope", VAR_SCOPE).options("auto", "local", "sequence", "player", "global").build(), p("name", "Name", VARIABLE));
        query("math", "Math", C, ValueType.NUMBER, null, false, "Arithmetic on two values.",
                pb("op", "Op", ENUM).options("add", "sub", "mul", "div", "mod", "min", "max", "pow", "abs", "floor", "ceil", "round", "neg").build(),
                p("a", "A", QUERY), p("b", "B", QUERY));
        query("random.int", "Random Integer", C, ValueType.NUMBER, null, false, "Uniform random integer in [min, max].",
                pb("min", "Min", INT).def(1).build(), pb("max", "Max", INT).def(6).build());
        query("random.chance", "Random Chance", C, ValueType.BOOL, null, false, "True with the given probability.",
                pb("probability", "Probability", NUMBER).def(0.5).range(0, 1).build());
        query("event.field", "Event Field", C, ValueType.TEXT, null, false, "Field of the event that started this branch.",
                pb("field", "Field", TEXT).def("player").build());
        query("state.current", "Current State", C, ValueType.TEXT, null, false, "Current state of a state machine.",
                pb("machine", "Machine", TEXT).def("main").build());
        query("list.size", "List Size", C, ValueType.NUMBER, null, false, "Number of elements.", p("source", "List", QUERY));
        query("list.filter", "Filter List", C, ValueType.LIST, null, false, "Elements for which the condition holds (element is the subject).",
                p("source", "List", QUERY), p("filter", "Filter", CONDITION));
        query("list.get", "List Element", C, ValueType.TEXT, null, false, "Element at index (0-based).",
                p("source", "List", QUERY), pb("index", "Index", INT).def(0).build());
        query("subject", "Subject", C, ValueType.ENTITY, null, false, "The current subject (context player or entity).");

        query("seq.tick", "Sequence Tick", S, ValueType.NUMBER, null, false, "Ticks since the sequence started.");
        query("seq.branch_tick", "Branch Position", S, ValueType.NUMBER, null, false, "Timeline position of the current branch.");
        query("seq.name", "Sequence Name", S, ValueType.TEXT, null, false, "Name of this sequence.");
        query("seq.initiator", "Initiator", S, ValueType.ENTITY, null, false, "Player who started the sequence (null for server).");
        query("seq.tracked", "Tracked Player", S, ValueType.ENTITY, null, false, "The player this sequence currently tracks.");
        query("seq.participants", "Participants", S, ValueType.LIST, null, false, "Players participating in this sequence.");
        query("seq.phase", "Current Phase", S, ValueType.TEXT, null, false, "Current phase name.");

        query("player.name", "Name", P, ValueType.TEXT, PQ, true, "Player name.", who());
        query("player.uuid", "UUID", P, ValueType.TEXT, PQ, true, "Player UUID.", who());
        query("player.online", "Online", P, ValueType.BOOL, PQ, true, "Whether the player is online.", who());
        query("player.health", "Health", P, ValueType.NUMBER, PQ, true, "Current health.", who());
        query("player.max_health", "Max Health", P, ValueType.NUMBER, PQ, true, "Maximum health.", who());
        query("player.health_pct", "Health %", P, ValueType.NUMBER, PQ, true, "Health as a percentage (0-100).", who());
        query("player.pos", "Position", P, ValueType.VEC3, PQ, true, "Coordinates.", who());
        query("player.x", "X", P, ValueType.NUMBER, PQ, true, "X coordinate.", who());
        query("player.y", "Y", P, ValueType.NUMBER, PQ, true, "Y coordinate.", who());
        query("player.z", "Z", P, ValueType.NUMBER, PQ, true, "Z coordinate.", who());
        query("player.dimension", "Dimension", P, ValueType.TEXT, PQ, true, "Dimension id, e.g. minecraft:overworld.", who());
        query("player.distance_to", "Distance To", P, ValueType.NUMBER, PQ, true, "Distance to a coordinate, player or entity uuid.",
                who(), p("pos", "Coordinate", VEC3), p("other", "Or player/entity", TEXT));
        query("player.in_region", "In Region", P, ValueType.BOOL, PQ, true, "Inside an axis-aligned box.",
                who(), pb("from", "Corner 1", VEC3).def("0 0 0").build(), pb("to", "Corner 2", VEC3).def("10 10 10").build(), p("dimension", "Dimension", DIMENSION));
        query("player.in_radius", "Within Radius", P, ValueType.BOOL, PQ, true, "Within radius of a point.",
                who(), pb("center", "Center", VEC3).def("0 64 0").build(), pb("radius", "Radius", NUMBER).def(8).build(), p("dimension", "Dimension", DIMENSION));
        query("player.has_item", "Has Item", P, ValueType.BOOL, PQ, true, "Has at least N of an item.",
                who(), pb("item", "Item", ITEM).def("minecraft:diamond").build(), pb("count", "Count", INT).def(1).build());
        query("player.item_count", "Item Count", P, ValueType.NUMBER, PQ, true, "Total of an item in inventory.",
                who(), pb("item", "Item", ITEM).def("minecraft:diamond").build());
        query("player.equipment", "Equipment", P, ValueType.TEXT, PQ, true, "Item id in an equipment slot.",
                who(), pb("slot", "Slot", ENUM).options("mainhand", "offhand", "head", "chest", "legs", "feet").build());
        query("player.durability", "Durability Left", P, ValueType.NUMBER, PQ, true, "Remaining durability of an equipped item.",
                who(), pb("slot", "Slot", ENUM).options("mainhand", "offhand", "head", "chest", "legs", "feet").build());
        query("player.durability_pct", "Durability %", P, ValueType.NUMBER, PQ, true, "Remaining durability percentage.",
                who(), pb("slot", "Slot", ENUM).options("mainhand", "offhand", "head", "chest", "legs", "feet").build());
        query("player.state", "State", P, ValueType.BOOL, PQ, true, "Movement / life state.",
                who(), pb("state", "State", ENUM).options("sneaking", "sprinting", "swimming", "burning", "on_ground", "dead", "flying",
                        "sleeping", "in_water", "gliding", "creative", "spectator", "invisible").build());
        query("player.gamemode", "Game Mode", P, ValueType.TEXT, PQ, true, "survival/creative/adventure/spectator.", who());
        query("player.team", "Team", P, ValueType.TEXT, Capability.TEAMS, true, "Scoreboard team name.", who());
        query("player.score", "Score", P, ValueType.NUMBER, Capability.SCOREBOARD, true, "Scoreboard value of the player.",
                who(), pb("objective", "Objective", TEXT).def("csq").build());
        query("player.xp_level", "XP Level", P, ValueType.NUMBER, PQ, true, "Experience level.", who());
        query("player.food", "Food", P, ValueType.NUMBER, PQ, true, "Food level (0-20).", who());
        query("player.permission_level", "Permission Level", P, ValueType.NUMBER, PQ, true, "Operator permission level (0-4).", who());
        query("player.has_permission", "Has Permission", P, ValueType.BOOL, Capability.PERMISSION_NODES, true, "Permission node check where supported.",
                who(), pb("node", "Node", TEXT).def("csequencer.run").build());

        query("players.online", "Online Players", PS, ValueType.LIST, PQ, false, "All online players.");
        query("players.count", "Online Count", PS, ValueType.NUMBER, PQ, false, "Number of online players.");
        query("players.in_radius", "Players In Radius", PS, ValueType.LIST, PQ, false, "Players within a radius.",
                pb("center", "Center", VEC3).def("0 64 0").build(), pb("radius", "Radius", NUMBER).def(16).build(), p("dimension", "Dimension", DIMENSION));
        query("players.in_region", "Players In Region", PS, ValueType.LIST, PQ, false, "Players inside a box.",
                pb("from", "Corner 1", VEC3).def("0 0 0").build(), pb("to", "Corner 2", VEC3).def("10 10 10").build(), p("dimension", "Dimension", DIMENSION));
        query("players.near_subject", "Players Near Subject", PS, ValueType.LIST, PQ, true, "Other players within radius of the subject.",
                who(), pb("radius", "Radius", NUMBER).def(8).build());
        query("players.nearest", "Nearest Player", PS, ValueType.ENTITY, PQ, false, "Nearest player to a point.",
                pb("center", "Center", VEC3).def("0 64 0").build(), p("dimension", "Dimension", DIMENSION));
        query("players.in_team", "Team Members (online)", PS, ValueType.LIST, Capability.TEAMS, false, "Online players in a team.",
                p("team", "Team", TEXT));

        query("block.type", "Block Type", W, ValueType.TEXT, WQ, false, "Block id at a position.",
                pb("pos", "Position", VEC3).def("0 64 0").build(), p("dimension", "Dimension", DIMENSION));
        query("block.state", "Block State Property", W, ValueType.TEXT, WQ, false, "Value of a block state property (e.g. 'powered').",
                pb("pos", "Position", VEC3).def("0 64 0").build(), pb("property", "Property", TEXT).def("powered").build(), p("dimension", "Dimension", DIMENSION));
        query("block.count", "Block Count In Region", W, ValueType.NUMBER, WQ, false, "Counts a block type in a box (bounded).",
                pb("from", "Corner 1", VEC3).def("0 64 0").build(), pb("to", "Corner 2", VEC3).def("4 68 4").build(),
                pb("block", "Block", BLOCK).def("minecraft:stone").build(), p("dimension", "Dimension", DIMENSION));
        query("world.time", "Day Time", W, ValueType.NUMBER, WQ, false, "Time of day (0-23999).", p("dimension", "Dimension", DIMENSION));
        query("world.game_time", "Game Time", W, ValueType.NUMBER, WQ, false, "Total world ticks.");
        query("world.weather", "Weather", W, ValueType.TEXT, WQ, false, "clear / rain / thunder.", p("dimension", "Dimension", DIMENSION));
        query("score.get", "Score Of Holder", W, ValueType.NUMBER, Capability.SCOREBOARD, false, "Scoreboard value of any holder.",
                pb("objective", "Objective", TEXT).def("csq").build(), p("holder", "Holder", TEXT));

        query("entities.list", "Entities", E, ValueType.LIST, EQ, false, "Entities by type/tag within a radius.",
                p("entity", "Type (empty = any)", ENTITY_TYPE), p("tag", "Tag", TEXT), pb("center", "Center", VEC3).def("0 64 0").build(),
                pb("radius", "Radius", NUMBER).def(16).range(0, 256).build(), p("dimension", "Dimension", DIMENSION));
        query("entities.count", "Entity Count", E, ValueType.NUMBER, EQ, false, "Number of entities by type/tag within a radius.",
                p("entity", "Type (empty = any)", ENTITY_TYPE), p("tag", "Tag", TEXT), pb("center", "Center", VEC3).def("0 64 0").build(),
                pb("radius", "Radius", NUMBER).def(16).range(0, 256).build(), p("dimension", "Dimension", DIMENSION));
        query("entity.health", "Entity Health", E, ValueType.NUMBER, EQ, false, "Health of an entity (uuid or variable).", p("entity", "Entity", QUERY));
        query("entity.max_health", "Entity Max Health", E, ValueType.NUMBER, EQ, false, "Maximum health of an entity.", p("entity", "Entity", QUERY));
        query("entity.health_pct", "Entity Health %", E, ValueType.NUMBER, EQ, false, "Health of an entity as a percentage (0-100).", p("entity", "Entity", QUERY));
        query("entity.alive", "Entity Alive", E, ValueType.BOOL, EQ, false, "Whether the entity exists and is alive.", p("entity", "Entity", QUERY));
        query("entity.type", "Entity Type", E, ValueType.TEXT, EQ, false, "Type id of the entity.", p("entity", "Entity", QUERY));
        query("entity.pos", "Entity Position", E, ValueType.VEC3, EQ, false, "Position of the entity.", p("entity", "Entity", QUERY));
        query("entity.distance", "Entity Distance", E, ValueType.NUMBER, EQ, false, "Distance from an entity to a point (or subject).",
                p("entity", "Entity", QUERY), p("pos", "Point (empty = subject)", VEC3));
        query("entity.has_tag", "Entity Has Tag", E, ValueType.BOOL, EQ, false, "Whether the entity has a scoreboard tag.",
                p("entity", "Entity", QUERY), p("tag", "Tag", TEXT));
    }

    private static void registerNodes() {
        String L = "Operators", G = "Structure", V = "Values", T = "Transitions";
        int U = CondNodeType.UNBOUNDED;
        node("logic.and", "AND", L, 1, U, false, "True when all inputs are true.");
        node("logic.or", "OR", L, 1, U, false, "True when any input is true.");
        node("logic.xor", "XOR", L, 1, U, false, "True when an odd number of inputs is true.");
        node("logic.not", "NOT", L, 1, 1, false, "Inverts its input.");
        node("logic.nand", "NAND", L, 1, U, false, "NOT AND.");
        node("logic.nor", "NOR", L, 1, U, false, "NOT OR.");
        node("logic.xnor", "XNOR", L, 1, U, false, "True when an even number of inputs is true.");
        node("logic.true", "TRUE", V, 0, 0, false, "Always true.");
        node("logic.false", "FALSE", V, 0, 0, false, "Always false.");
        node("logic.group", "Group ( )", G, 1, U, false, "Named, collapsible parenthesised group with its own operator.",
                pb("name", "Name", TEXT).def("Group").build(),
                pb("op", "Operator", ENUM).options("and", "or", "xor", "nand", "nor", "xnor").build(),
                pb("collapsed", "Collapsed", BOOL).def(false).build());
        node("logic.ref", "Reusable Condition", G, 0, 0, false, "References a named condition of the project.",
                p("condition", "Condition", TEXT));
        node("cmp", "Compare", V, 0, 0, false, "Compares two values.",
                pb("left", "Left", QUERY).def(queryExpr("player.health")).build(),
                pb("op", "Operator", ENUM).options(opIds()).build(), p("right", "Right", QUERY), p("right2", "Max (ranges)", QUERY));
        node("quant", "For Players / List", G, 1, 1, false, "Evaluates its input for each element (as subject): any, all, none or count.",
                pb("source", "List", QUERY).def(queryExpr("players.online")).build(),
                pb("mode", "Mode", ENUM).options("any", "all", "none", "count").build(),
                pb("op", "Count op", ENUM).options("gte", "eq", "gt", "lt", "lte", "neq").build(), pb("n", "Count", NUMBER).def(1).build());
        node("edge", "Transition", T, 1, 1, true, "Detects a change of its input per subject: entered (became true), exited (became false) or changed.",
                pb("mode", "Detect", ENUM).options("entered", "exited", "changed").build());
        node("chance", "Random Chance", V, 0, 0, false, "True with a probability.", pb("probability", "Probability", NUMBER).def(0.5).build());
    }

    private static void registerEvents() {
        event("player.join", "Player Joined", "A player logged in.", false, "player", "dimension", "pos");
        event("player.leave", "Player Left", "A player logged out.", false, "player", "dimension", "pos");
        event("player.death", "Player Died", "A player died.", false, "player", "dimension", "pos", "killer", "cause");
        event("player.respawn", "Player Respawned", "A player respawned.", false, "player", "dimension", "pos");
        event("player.change_dimension", "Changed Dimension", "A player changed dimension.", false, "player", "from", "to", "pos");
        event("player.interact_block", "Interacted With Block", "Right-clicked a block.", false, "player", "block", "pos", "dimension", "hand");
        event("player.interact_entity", "Interacted With Entity", "Right-clicked an entity.", false, "player", "entity", "entityType", "pos", "dimension");
        event("player.attack_entity", "Attacked Entity", "Attacked an entity.", false, "player", "entity", "entityType", "pos", "dimension");
        event("player.break_block", "Broke Block", "Broke a block.", false, "player", "block", "pos", "dimension");
        event("player.place_block", "Placed Block", "Placed a block.", false, "player", "block", "pos", "dimension");
        event("player.use_item", "Used Item", "Used (right-clicked) an item.", false, "player", "item", "pos", "dimension");
        event("entity.death", "Entity Died", "A non-player living entity died.", false, "entity", "entityType", "pos", "dimension", "killer");
        event("signal", "Signal", "Internal signal emitted by a sequence.", true, "name", "payload", "source");
        event("state.changed", "State Changed", "A state machine changed state.", true, "machine", "from", "to");
        event("sequence.started", "Sequence Started", "Any sequence instance started.", true, "sequence", "task", "initiator");
        event("sequence.finished", "Sequence Finished", "Any sequence instance ended.", true, "sequence", "task", "result");
    }

    public static List<String> categories() {
        List<String> out = new ArrayList<>();
        for (PieceCategory c : CATEGORIES) out.add(c.name());
        for (PieceType t : PIECES.values()) if (!out.contains(t.category())) out.add(t.category());
        return Collections.unmodifiableList(out);
    }
}
