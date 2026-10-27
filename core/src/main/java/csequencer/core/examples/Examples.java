package csequencer.core.examples;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import csequencer.core.editor.TimelineOps;
import csequencer.core.model.*;
import csequencer.core.pieces.PieceRegistry;

import java.util.List;

/**
 * Built-in tutorials and example sequences, installed on a server the first time it runs.
 * They are generated in code (not hand-written JSON) so they always use valid, current pieces;
 * the unit tests validate them and play them through.
 *
 * <p>Ids are stable: bump {@link #VERSION} when an example changes so servers can offer the update.
 */
public final class Examples {
    public static final int VERSION = 1;

    private Examples() {}

    public static List<Project> all() { return List.of(basics(), logic(), bossFight(), checkpointRace(), camera(), screenEffects(), madness()); }

    // ── Builder helpers ────────────────────────────────────────────────────

    private static final class B {
        final Project p;
        Timeline tl;

        B(String id, String name, String description, String... tags) {
            p = Project.create(name);
            p.id = id;
            p.description = description;
            p.tags.addAll(List.of(tags));
            tl = p.main;
        }

        B on(Timeline t) { tl = t; return this; }

        Timeline fn(String name, String description) {
            Timeline f = Timeline.create(name, "fn");
            f.description = description;
            p.functions.add(f);
            return f;
        }

        Track track(int i, String name) {
            while (tl.tracks.size() <= i) tl.tracks.add(new Track("Track " + (tl.tracks.size() + 1)));
            Track t = tl.tracks.get(i);
            t.name = name;
            return t;
        }

        Piece add(int track, int tick, String type, String label, Object... kv) {
            while (tl.tracks.size() <= track) tl.tracks.add(new Track("Track " + (tl.tracks.size() + 1)));
            Piece x = TimelineOps.create(tl, type, tl.tracks.get(track).id, tick);
            x.label = label;
            for (int i = 0; i + 1 < kv.length; i += 2) x.params.add((String) kv[i], json(kv[i + 1]));
            return x;
        }

        void phase(String name, int tick) { tl.markers.add(new Marker(name, tick, Policies.MarkerKind.PHASE)); }
    }

    static JsonElement json(Object v) {
        if (v instanceof JsonElement e) return e;
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        return new JsonPrimitive(String.valueOf(v));
    }

    /** Query expression {"q": id, "args": {...}}. */
    static JsonObject q(String id, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("q", id);
        JsonObject a = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) a.add((String) kv[i], json(kv[i + 1]));
        o.add("args", a);
        return o;
    }

    static JsonObject v(String name) { return q("var", "name", name); }

    static JsonObject offset(String var, double dx, double dy, double dz) { return q("vec.offset", "base", v(var), "dx", dx, "dy", dy, "dz", dz); }

    static JsonObject target(String mode, Object... kv) {
        JsonObject o = PieceRegistry.targetMode(mode);
        for (int i = 0; i + 1 < kv.length; i += 2) o.add((String) kv[i], json(kv[i + 1]));
        return o;
    }

    /** Single comparison condition graph. */
    static JsonObject cond(JsonObject left, String op, Object right) {
        ConditionGraph.Node n = new ConditionGraph.Node("cmp");
        n.params.add("left", left);
        n.params.addProperty("op", op);
        if (right != null) n.params.add("right", json(right));
        n.x = 40;
        n.y = 20;
        return ConditionGraph.single(n).toJson();
    }

    static JsonArray branch(JsonObject condition, String targetFn) {
        JsonArray a = new JsonArray();
        JsonObject b = new JsonObject();
        b.add("condition", condition);
        b.addProperty("target", targetFn);
        a.add(b);
        return a;
    }

    // ── Example: cinematic camera ──────────────────────────────────────────

    public static Project camera() {
        B b = new B("example_cinematic_camera", "Example · Cinematic Camera",
                "Camera pieces (category Camera): a keyframe fly-around, a fixed corner shot that keeps watching you while you walk, "
                        + "an orbit, the smooth return, then Force Look At locking (and later only pulling) your view to an entity. "
                        + "Everything is relative to the player who runs it (~), so run it anywhere with some open space.",
                "example", "camera");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        b.track(0, "Camera");
        b.track(1, "Text");
        b.track(2, "Effects");
        b.phase("Fly-around", 0);
        b.phase("Corner shot", 140);
        b.phase("Orbit", 260);
        b.phase("Forced look", 380);
        b.add(2, 0, "hud.screen", "Letterbox bars on", "effect", "letterbox_on", "duration", 20);
        b.add(0, 0, "camera.path", "① Cinematic path (4 keyframes, smooth curve)",
                "points", "~8 ~4 ~ 90 20\n~ ~6 ~8 180 35\n~-8 ~4 ~ 270 20\n~ ~3 ~-8 360 15",
                "duration", 120, "blend", 15, "look", "at_player", "easing", "ease_in_out", "smooth", true, "afterEnd", "hold");
        b.add(1, 5, "action.title", "Title", "title", "Cinematic Camera", "subtitle", "Camera: Cinematic Path", "color", "aqua");
        b.add(0, 140, "camera.fixed", "② Fixed corner shot that follows you (you can walk)",
                "pos", "~6 ~4 ~6", "look", "at_player", "blend", 20, "lockPlayer", false, "hideHud", false);
        b.add(1, 145, "action.chat", "Explain", "message", "Camera: Fixed Shot with 'Look: at_player'. Walk around: the camera keeps watching you (Freeze player is off here).", "color", "aqua");
        b.add(2, 140, "hud.screen", "Letterbox off", "effect", "letterbox_off", "duration", 20);
        b.add(0, 260, "camera.orbit", "③ Orbit around the player", "center", "player", "radius", 5, "height", 2, "speed", 45, "blend", 20);
        b.add(1, 265, "action.actionbar", "Explain", "message", "Camera: Orbit circles around you, an entity or a point");
        b.add(0, 360, "camera.reset", "④ Smooth return to the player", "blend", 20);
        b.add(2, 380, "action.spawn_entity", "Armor stand to look at (stored as 'target')", "entity", "minecraft:armor_stand", "pos", "~6 ~ ~",
                "removeOnEnd", true, "storeAs", "target");
        b.add(0, 385, "camera.look_at", "⑤ Force Look At: view locked (strength 1)", "target", "entity", "entity", v("target"), "strength", 1, "duration", 60);
        b.add(1, 385, "action.chat", "Explain", "message", "Force Look At (strength 1): try to move your mouse, your view stays on the armor stand.", "color", "yellow");
        b.add(0, 450, "camera.look_at", "⑥ Only pulls the view (strength 0.15)", "target", "entity", "entity", v("target"), "strength", 0.15, "duration", 80);
        b.add(1, 450, "action.chat", "Explain", "message", "Strength 0.15: you can look away, but it keeps pulling you back.", "color", "yellow");
        b.add(1, 540, "action.title", "End", "title", "That's the camera!", "subtitle", "Open this sequence in the editor to see each piece", "color", "green");
        b.add(0, 560, "flow.end", "End", "message", "Camera demo finished");
        return b.p;
    }

    // ── Example: madness ───────────────────────────────────────────────────

    public static Project madness() {
        B b = new B("example_madness", "Example · Madness",
                "Player Animation + Screen Effect+ together: the player starts to lose their mind. Glitches and words in disorder "
                        + "(set your own in the 'Insanity words' field), the body convulses (watch it from the orbiting camera), "
                        + "then blood runs down the screen.",
                "example", "effects", "camera");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        b.track(0, "Screen");
        b.track(1, "Body & camera");
        b.track(2, "Text & sound");
        b.phase("Something is wrong", 0);
        b.phase("Losing it", 80);
        b.phase("Look at yourself", 200);
        b.phase("Blood", 330);
        b.add(2, 0, "action.sound", "Heartbeat-like thump", "sound", "minecraft:entity.warden.heartbeat");
        b.add(2, 0, "action.actionbar", "Whisper", "message", "...did you hear that?");
        b.add(0, 0, "screen.effect", "Slight dizziness", "effect", "dizzy", "intensity", 0.35, "fadeIn", 40, "duration", 300, "fadeOut", 40);
        b.add(1, 20, "player.animation", "The body starts to shiver", "animation", "shiver", "intensity", 0.6, "duration", 60);
        b.add(0, 80, "screen.effect", "Insanity: glitches + words", "effect", "insanity", "intensity", 0.9, "fadeIn", 40, "duration", 240, "fadeOut", 40,
                "words", "RUN,THEY SEE YOU,{player},WAKE UP,NOT REAL,BEHIND YOU,IT'S IN YOUR HEAD,DON'T LOOK BACK");
        b.add(1, 80, "player.animation", "Insanity body animation", "animation", "insanity", "intensity", 1, "duration", 250);
        b.add(2, 80, "action.sound", "Ambient cave noise", "sound", "minecraft:ambient.cave");
        b.add(2, 85, "action.title", "Title", "title", "§kxx§r WAKE UP §kxx", "subtitle", "Insanity effect + Player Animation", "color", "red");
        b.add(1, 200, "camera.orbit", "Orbit to watch your own body", "center", "player", "radius", 4, "height", 1.5, "speed", 35, "blend", 20,
                "lockPlayer", true, "hideHud", false);
        b.add(1, 320, "camera.reset", "Back to the eyes", "blend", 15);
        b.add(0, 330, "screen.effect", "Blood running down", "effect", "blood", "intensity", 1, "fadeIn", 20, "duration", 120, "fadeOut", 40);
        b.add(2, 330, "action.sound", "Hurt", "sound", "minecraft:entity.player.hurt");
        b.add(2, 470, "action.title", "End", "title", "...it was just a dream?", "subtitle", "Example · Madness", "color", "gray");
        b.add(0, 500, "flow.end", "End", "message", "Madness demo finished");
        return b.p;
    }

    // ── Example: screen effects ────────────────────────────────────────────

    public static Project screenEffects() {
        B b = new B("example_screen_effects", "Example · Screen Effects",
                "Every Screen Effect+ (category Screen) for a few seconds each: dizzy, sleep, heat, cold, poison, insanity, inverted colours, "
                        + "melting, black & white and blood, then two combined. Sleep also freezes the player with Player Controls.",
                "example", "effects");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        b.track(0, "Effects");
        b.track(1, "Text");
        b.track(2, "Controls");
        String[][] fx = {
                {"dizzy", "Dizzy", "the world sways and doubles"},
                {"sleep", "Sleep", "your eyes are closing"},
                {"heat", "Heat", "shimmering hot air"},
                {"cold", "Cold", "frost creeps in from the edges"},
                {"poison", "Poison", "sick green pulses"},
                {"insanity", "Insanity", "colours shift, the screen glitches"},
                {"invert", "Invert", "inverted colours"},
                {"melt", "Melt", "the screen melts down"},
                {"grayscale", "Black & white", "no colours"},
                {"blood", "Blood", "blood runs down the screen"}};
        int t = 0;
        for (String[] e : fx) {
            b.phase(e[1], t);
            b.add(0, t, "screen.effect", e[1], "effect", e[0], "intensity", 1, "fadeIn", 20, "duration", 60, "fadeOut", 20);
            b.add(1, t, "action.actionbar", e[1] + " text", "message", "Screen Effect+: " + e[1] + ", " + e[2]);
            if (e[0].equals("sleep")) b.add(2, t, "player.controls", "Freeze while falling asleep", "movement", true, "jump", true, "look", true,
                    "actions", true, "inventory", true, "duration", 100);
            t += 110;
        }
        b.phase("Combined", t);
        b.add(0, t, "screen.effect", "Heat", "effect", "heat", "intensity", 0.8, "fadeIn", 20, "duration", 80, "fadeOut", 30);
        b.add(0, t + 10, "screen.effect", "+ Dizzy", "effect", "dizzy", "intensity", 0.6, "fadeIn", 30, "duration", 70, "fadeOut", 30);
        b.add(1, t, "action.title", "Combined", "title", "Heat + Dizzy", "subtitle", "Effects can be stacked", "color", "gold");
        b.add(1, t + 140, "action.title", "End", "title", "Done!", "subtitle", "Use Clear Screen Effects to fade them out early", "color", "green");
        b.add(0, t + 150, "flow.end", "End", "message", "Effects demo finished");
        return b.p;
    }

    // ── Tutorial 1: basics ─────────────────────────────────────────────────

    static Project basics() {
        B b = new B("example_tutorial_basics", "Tutorial 1 · Basics",
                "Start here. Every block is a piece that runs at its tick, from left to right; each track runs in parallel. "
                        + "This shows titles, chat, popups, an overlay, sounds, particles, screen effects and a delay. Press ▶ Run and watch.",
                "tutorial");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        b.p.settings.announce = Policies.AnnounceMode.PARTICIPANTS;
        b.track(0, "Messages");
        b.track(1, "Effects");
        b.phase("Intro", 0);
        b.phase("Popups & overlays", 60);
        b.phase("Effects", 140);
        b.phase("Finish", 220);
        b.add(0, 0, "action.title", "① Title: a big title on screen", "title", "Welcome, {player}!", "subtitle", "This is Tutorial 1", "color", "gold");
        b.add(0, 20, "action.chat", "② Chat: a message in chat", "message", "Each block is a piece. It runs at its tick, left to right. Tracks run at the same time.", "color", "aqua");
        b.add(0, 60, "hud.popup", "③ Popup: a side notification", "title", "This is a popup", "message", "Create your own with the Show Popup piece (category Screen).", "icon", "✉", "style", "info");
        b.add(0, 80, "hud.overlay", "④ Overlay: banner with a countdown", "id", "demo", "style", "banner", "title", "Custom overlay",
                "subtitle", "Text, a bar or a countdown", "countdown", 100, "duration", 100, "live", true);
        b.add(0, 140, "action.actionbar", "⑤ Action bar: text above the hotbar", "message", "Look at the Effects track: it runs in parallel");
        b.add(0, 220, "action.title", "⑥ The end", "title", "Done!", "subtitle", "Projects → Duplicate to make your own", "color", "green");
        b.add(1, 0, "action.sound", "Sound effect", "sound", "minecraft:ui.toast.challenge_complete");
        b.add(1, 60, "action.particles", "Particles at the player (~ = relative)", "particle", "minecraft:happy_villager", "pos", "~ ~1 ~", "count", 30);
        b.add(1, 140, "hud.screen", "Screen flash", "effect", "flash", "color", "#FFFFFF", "duration", 10);
        b.add(1, 150, "flow.delay", "Delay: pauses only this track", "ticks", 20);
        b.add(1, 150, "action.sound", "Runs 20 ticks later because of the delay", "sound", "minecraft:entity.experience_orb.pickup");
        b.add(1, 190, "hud.screen", "Cinematic bars on", "effect", "letterbox_on", "duration", 20);
        b.add(1, 230, "hud.screen", "Cinematic bars off", "effect", "letterbox_off", "duration", 20);
        return b.p;
    }

    // ── Tutorial 2: logic & events ─────────────────────────────────────────

    static Project logic() {
        B b = new B("example_tutorial_logic", "Tutorial 2 · Logic & Events",
                "Conditions, variables, functions and events. Sneak during the first 3 seconds, then right-click blocks to count clicks. "
                        + "Double-click a logic piece to open its condition in the Logic Graph.", "tutorial");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        b.p.settings.announce = Policies.AnnounceMode.PARTICIPANTS;
        Timeline sneaking = b.fn("Sneaking", "Runs when the IF condition is true");
        Timeline standing = b.fn("Standing", "Runs when the IF condition is false (ELSE)");
        Timeline click = b.fn("Count click", "Runs every time the listener sees a block right-click");
        Timeline enough = b.fn("Enough clicks", "Five or more clicks");
        Timeline few = b.fn("Too few clicks", "Fewer than five clicks");
        b.on(sneaking).add(0, 0, "action.chat", "Chat", "message", "✔ You were sneaking!", "color", "green");
        b.on(standing).add(0, 0, "action.chat", "Chat", "message", "You were standing. Try sneaking next time.", "color", "yellow");
        b.on(click).add(0, 0, "var.set", "clicks = clicks + 1", "name", "clicks", "op", "add", "value", 1);
        b.on(click).add(0, 0, "action.sound", "Click sound", "sound", "minecraft:block.note_block.hat");
        b.on(enough).add(0, 0, "hud.popup", "Success popup", "title", "{clicks} clicks!", "message", "The IF saw clicks ≥ 5.", "style", "success", "icon", "✔");
        b.on(few).add(0, 0, "hud.popup", "Warning popup", "title", "Only {clicks} clicks", "message", "The IF went to ELSE.", "style", "warning", "icon", "!");

        b.on(b.p.main);
        b.track(0, "Logic");
        b.phase("Sneak now!", 0);
        b.phase("Click blocks", 80);
        b.phase("Result", 280);
        b.add(0, 0, "action.title", "① Instructions", "title", "Tutorial 2", "subtitle", "Sneak in the next 3 seconds…");
        b.add(0, 0, "var.set", "② Variable: clicks = 0", "name", "clicks", "value", 0);
        Piece iff = b.add(0, 60, "logic.if", "③ IF you are sneaking → Sneaking, ELSE → Standing",
                "branches", branch(cond(q("player.state", "state", "sneaking"), "is_true", null), sneaking.id), "else", standing.id);
        iff.duration = 10;
        b.add(0, 80, "event.listen", "④ Listener: every block right-click runs 'Count click'", "eventType", "player.interact_block", "function", click.id);
        b.add(0, 80, "action.chat", "Instructions", "message", "Right-click any block, as many times as you can (10 s)!", "color", "aqua");
        b.add(0, 80, "hud.overlay", "⑤ Live overlay bar bound to the variable", "id", "clicks", "style", "bar", "title", "Clicks",
                "value", v("clicks"), "max", 5, "live", true);
        b.add(0, 280, "logic.if", "⑥ IF clicks ≥ 5 → Enough, ELSE → Too few",
                "branches", branch(cond(v("clicks"), "gte", 5), enough.id), "else", few.id);
        b.add(0, 300, "hud.hide", "⑦ Hide the overlay", "kind", "overlay", "id", "clicks");
        b.add(0, 300, "action.title", "The end", "title", "Done", "subtitle", "Clicks: {clicks}");
        return b.p;
    }

    // ── Example: boss fight ────────────────────────────────────────────────

    static Project bossFight() {
        B b = new B("example_boss_fight", "Example · Boss Fight",
                "A complete boss fight around the player who starts it: cinematic intro, a boss with a boss bar that follows its health, "
                        + "a waypoint on the boss, an arena barrier, a second phase below 50% health with minions, a 3-minute timer, "
                        + "victory rewards and a defeat ending. Use it in an open area.", "example", "boss");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.IGNORE_NEW;
        b.p.settings.announce = Policies.AnnounceMode.PARTICIPANTS;
        Timeline waitBoss = b.fn("Wait for the boss", "Finishes when the boss dies");
        Timeline phase2 = b.fn("Phase 2", "Runs once when the boss drops below 50% health");
        Timeline victory = b.fn("Victory", "The boss died in time");
        Timeline defeat = b.fn("Defeat", "The timer ran out");

        b.on(waitBoss).add(0, 0, "flow.wait_death", "Wait until the boss is dead", "entity", v("boss"));

        b.on(phase2).add(0, 0, "seq.phase", "Overlay phase: Enraged", "name", "Enraged", "next", "Victory");
        b.on(phase2).add(0, 0, "hud.popup", "Popup", "title", "Enraged!", "message", "The Bone King calls for help", "style", "danger", "icon", "☠",
                "targets", target("participants"));
        b.on(phase2).add(0, 0, "hud.screen", "Camera shake", "effect", "shake", "duration", 20, "intensity", 1.5, "targets", target("participants"));
        b.on(phase2).add(0, 0, "action.sound", "Roar", "sound", "minecraft:entity.wither.ambient", "targets", target("participants"));
        b.on(phase2).add(0, 0, "action.effect", "Boss gets faster", "targets", target("variable", "var", "boss"), "effect", "minecraft:speed",
                "duration", 2400, "amplifier", 1);
        b.on(phase2).add(0, 5, "action.spawn_entity", "3 minions", "entity", "minecraft:skeleton", "pos", "{spawnAt}", "count", 3,
                "tag", "csq_minion", "removeOnEnd", true);

        b.on(victory).add(0, 0, "hud.hide", "Hide the timer", "kind", "overlay", "id", "timer");
        b.on(victory).add(0, 0, "zone.release", "Open the arena", "id", "arena");
        b.on(victory).add(0, 0, "hud.screen", "Golden flash", "effect", "flash", "color", "#FFD54F", "duration", 20, "targets", target("participants"));
        b.on(victory).add(0, 0, "action.title", "Victory title", "title", "Victory!", "subtitle", "The Bone King is defeated", "color", "gold",
                "targets", target("participants"));
        b.on(victory).add(0, 0, "action.give_item", "Reward: 3 diamonds", "targets", target("participants"), "item", "minecraft:diamond", "count", 3);
        b.on(victory).add(0, 0, "hud.popup", "Reward popup", "title", "Reward", "message", "3 diamonds for every participant", "style", "gold", "icon", "◆",
                "targets", target("participants"));
        b.on(victory).add(0, 0, "action.sound", "Fanfare", "sound", "minecraft:ui.toast.challenge_complete", "targets", target("participants"));
        b.on(victory).add(0, 0, "action.remove_entities", "Remove minions", "tag", "csq_minion", "center", "{spawnAt}", "radius", 48);
        b.on(victory).add(0, 40, "flow.end", "End: success", "result", "complete", "message", "Victory");

        b.on(defeat).add(0, 0, "hud.hide", "Hide everything", "kind", "any");
        b.on(defeat).add(0, 0, "action.title", "Defeat title", "title", "Defeat", "subtitle", "Time ran out", "color", "red", "targets", target("participants"));
        b.on(defeat).add(0, 0, "hud.popup", "Popup", "title", "Time's up", "message", "The Bone King escaped…", "style", "danger", "icon", "✖",
                "targets", target("participants"));
        b.on(defeat).add(0, 0, "hud.screen", "Fade to black", "effect", "fade_out", "duration", 20, "targets", target("participants"));
        b.on(defeat).add(0, 40, "hud.screen", "Fade back in", "effect", "fade_in", "duration", 40, "targets", target("participants"));
        b.on(defeat).add(0, 80, "flow.end", "End: failure", "result", "fail", "message", "Time ran out");

        b.on(b.p.main);
        b.track(0, "Fight");
        b.track(1, "Intro effects");
        b.phase("Awakening", 0);
        b.phase("Fight", 40);
        b.add(0, 0, "var.set", "① Remember where the fight starts", "name", "origin", "value", q("player.pos"));
        b.add(0, 0, "var.set", "Arena corner A", "name", "arenaA", "value", offset("origin", -14, -4, -14));
        b.add(0, 0, "var.set", "Arena corner B", "name", "arenaB", "value", offset("origin", 14, 12, 14));
        b.add(0, 0, "var.set", "Boss spawn point (8 blocks south)", "name", "spawnAt", "value", offset("origin", 0, 0, 8));
        b.add(1, 0, "hud.screen", "Cinematic bars", "effect", "letterbox_on", "duration", 20, "targets", target("participants"));
        b.add(1, 0, "hud.popup", "Warning popup", "title", "A boss approaches!", "message", "Stay inside the arena", "style", "boss", "icon", "☠",
                "position", "center", "targets", target("participants"));
        b.add(1, 20, "action.particles", "Explosion where the boss appears", "particle", "minecraft:explosion_emitter", "pos", "{spawnAt}", "count", 1);
        b.add(1, 20, "action.sound", "Spawn sound", "sound", "minecraft:entity.wither.spawn", "targets", target("participants"));
        b.add(1, 40, "hud.screen", "Bars off", "effect", "letterbox_off", "duration", 20, "targets", target("participants"));
        b.add(0, 20, "action.spawn_entity", "② Spawn the boss (stored in 'boss')", "entity", "minecraft:zombie", "pos", "{spawnAt}",
                "nbt", "{CustomName:'{\"text\":\"Bone King\",\"color\":\"red\"}',Health:120f,Attributes:[{Name:\"generic.max_health\",Base:120d}],PersistenceRequired:1b}",
                "tag", "csq_boss", "removeOnEnd", true, "storeAs", "boss");
        b.add(0, 21, "hud.bossbar", "③ Boss bar follows the boss's health", "id", "boss", "name", "Bone King", "entity", v("boss"),
                "color", "red", "style", "notched_10", "targets", target("participants"));
        b.add(0, 21, "hud.marker", "④ Waypoint on the boss", "id", "boss", "follow", v("boss"), "label", "Bone King", "icon", "☠", "color", "danger",
                "targets", target("participants"));
        b.add(0, 21, "zone.keep_inside", "⑤ Arena barrier", "id", "arena", "from", "{arenaA}", "to", "{arenaB}",
                "message", "You can't leave the arena!", "targets", target("participants"));
        b.add(0, 21, "event.on_condition", "⑥ When the boss drops below 50% → Phase 2 (once)",
                "condition", cond(q("entity.health_pct", "entity", v("boss")), "lt", 50), "edge", "entered", "over", "global",
                "function", phase2.id, "once", true, "interval", 5);
        b.add(0, 40, "action.title", "Fight title", "title", "Fight!", "subtitle", "Defeat the Bone King in 3 minutes", "color", "red",
                "targets", target("participants"));
        b.add(0, 40, "hud.overlay", "⑦ 3-minute timer", "id", "timer", "style", "timer", "title", "Time left", "countdown", 3600,
                "position", "right", "targets", target("participants"));
        Piece window = b.add(0, 40, "flow.timeout", "⑧ Wait for the boss, at most 3 minutes (otherwise Defeat)",
                "function", waitBoss.id, "window", 3600, "onTimeout", defeat.id, "await", true);
        window.duration = 60;
        b.add(0, 41, "logic.if", "⑨ IF the boss is dead → Victory",
                "branches", branch(cond(q("entity.alive", "entity", v("boss")), "is_false", null), victory.id));
        return b.p;
    }

    // ── Example: checkpoint race ───────────────────────────────────────────

    static Project checkpointRace() {
        B b = new B("example_checkpoint_race", "Example · Checkpoint Race",
                "Reach three checkpoints before the timer runs out. Waypoint markers show the way, the player gets food and speed "
                        + "at the start and a reward at the end. Checkpoints are placed relative to where the player starts.",
                "example", "race");
        b.p.settings.concurrency = Policies.ConcurrencyPolicy.RESTART;
        b.p.settings.scope = Policies.ExecutionScope.PLAYER;
        b.p.settings.announce = Policies.AnnounceMode.PARTICIPANTS;
        Timeline run = b.fn("Run the checkpoints", "Shows each checkpoint and waits until the player reaches it");
        Timeline done = b.fn("Finished", "All checkpoints reached in time");
        Timeline late = b.fn("Out of time", "The timer ran out first");

        b.on(run);
        b.track(0, "Checkpoints");
        for (int i = 1; i <= 3; i++) {
            String cp = "{cp" + i + "}";
            b.add(0, 0, "hud.marker", "Marker → checkpoint " + i, "id", "cp", "pos", cp, "label", "Checkpoint " + i, "icon", "⚑", "color", "gold");
            b.add(0, 0, "hud.popup", "Popup", "title", "Checkpoint " + i + " of 3", "message", "Follow the marker", "icon", "⚑", "style", "gold", "sound", false);
            b.add(0, 0, "flow.wait_until", "Wait until the player is near checkpoint " + i,
                    "condition", condRadius(cp, 4), "interval", 2);
            b.add(0, 0, "action.sound", "Ding", "sound", "minecraft:entity.experience_orb.pickup");
            b.add(0, 0, "action.particles", "Sparkles", "particle", "minecraft:happy_villager", "pos", cp, "count", 40);
        }
        b.add(0, 0, "hud.hide", "Remove the marker", "kind", "marker", "id", "cp");
        b.add(0, 0, "var.set", "finished = true", "name", "finished", "value", true);

        b.on(done).add(0, 0, "hud.hide", "Hide the timer", "kind", "overlay", "id", "timer");
        b.on(done).add(0, 0, "action.title", "Title", "title", "You made it!", "subtitle", "All checkpoints in time", "color", "gold");
        b.on(done).add(0, 0, "action.give_item", "Reward: 2 diamonds", "item", "minecraft:diamond", "count", 2);
        b.on(done).add(0, 0, "action.give_item", "Reward: golden apple", "item", "minecraft:golden_apple", "count", 1);
        b.on(done).add(0, 0, "hud.popup", "Reward popup", "title", "Reward", "message", "2 diamonds and a golden apple", "style", "success", "icon", "◆");
        b.on(done).add(0, 0, "action.sound", "Fanfare", "sound", "minecraft:ui.toast.challenge_complete");
        b.on(done).add(0, 20, "flow.end", "End: success", "message", "Finished in time");

        b.on(late).add(0, 0, "hud.hide", "Hide everything", "kind", "any");
        b.on(late).add(0, 0, "action.title", "Title", "title", "Out of time!", "subtitle", "Try again", "color", "red");
        b.on(late).add(0, 0, "hud.popup", "Popup", "title", "Too slow", "message", "Run it again from the Projects tab", "style", "danger", "icon", "✖");
        b.on(late).add(0, 20, "flow.end", "End: failure", "result", "fail", "message", "Out of time");

        b.on(b.p.main);
        b.track(0, "Race");
        b.phase("Get ready", 0);
        b.phase("Race!", 40);
        b.add(0, 0, "var.set", "① Start position", "name", "origin", "value", q("player.pos"));
        b.add(0, 0, "var.set", "Checkpoint 1 (20 east)", "name", "cp1", "value", offset("origin", 20, 0, 0));
        b.add(0, 0, "var.set", "Checkpoint 2", "name", "cp2", "value", offset("origin", 20, 0, 20));
        b.add(0, 0, "var.set", "Checkpoint 3", "name", "cp3", "value", offset("origin", 0, 0, 20));
        b.add(0, 0, "var.set", "finished = false", "name", "finished", "value", false);
        b.add(0, 0, "action.title", "② Instructions", "title", "Checkpoint Race", "subtitle", "Reach 3 checkpoints in 60 seconds");
        b.add(0, 0, "action.give_item", "③ Items: 8 cooked beef", "item", "minecraft:cooked_beef", "count", 8);
        b.add(0, 0, "action.effect", "Speed for 60 s", "effect", "minecraft:speed", "duration", 1200, "amplifier", 1);
        b.add(0, 40, "hud.overlay", "④ 60-second timer", "id", "timer", "style", "timer", "title", "Time left", "countdown", 1200, "position", "right");
        b.add(0, 40, "action.sound", "Start sound", "sound", "minecraft:block.note_block.pling");
        Piece window = b.add(0, 40, "flow.timeout", "⑤ Run the checkpoints, at most 60 s (otherwise Out of time)",
                "function", run.id, "window", 1200, "onTimeout", late.id, "await", true);
        window.duration = 60;
        b.add(0, 41, "logic.if", "⑥ IF finished → Finished", "branches", branch(cond(v("finished"), "is_true", null), done.id));
        return b.p;
    }

    static JsonObject condRadius(String center, double radius) {
        return cond(q("player.in_radius", "center", center, "radius", radius), "is_true", null);
    }
}
