package csequencer.core.pieces;

import java.util.HashMap;
import java.util.Map;

/**
 * Longer, practical help for each piece: when to use it and a concrete example. Shown in the piece
 * library, the editor inspector and the tooltips of the pieces panel.
 */
public final class PieceHelp {
    private PieceHelp() {}

    /** How to use the piece and an example. */
    public record Help(String howTo, String example) {}

    private static final Map<String, Help> HELP = new HashMap<>();

    private static void h(String id, String howTo, String example) { HELP.put(id, new Help(howTo, example)); }

    public static Help of(String pieceId) { return HELP.get(pieceId); }

    static {
        // ── Timing ──
        h("flow.start", "Optional marker for where the story begins. Useful in functions to make the order obvious.",
                "Put it at tick 0 of a function before the pieces it runs.");
        h("flow.end", "Ends the whole sequence for everyone it runs for. Choose 'fail' to mark the run as failed in History.",
                "Last piece of a boss fight: result 'complete', message 'Victory'.");
        h("flow.delay", "Waits on this track only. Everything after it on the same track moves later; other tracks keep going.",
                "Title at tick 0, Delay 40 ticks (2 s), then a second title.");
        h("flow.wait_until_tick", "Waits until the sequence clock reaches a tick. Handy after a variable-length wait to resync.",
                "After 'Wait Until' a door opens, 'Execute At Tick 600' continues the show at 30 s.");
        h("flow.jump", "Jumps this track back (or forward) to a tick to loop a section. The jump limit stops endless loops.",
                "At tick 200 jump to tick 40, max 5 jumps: the middle part repeats 5 times.");
        h("flow.timeout", "Runs a function but stops it when the time runs out, then optionally runs 'on timeout'.",
                "Function 'Find the key' with a 60 s window; on timeout run 'You failed'.");
        // ── Flow ──
        h("flow.stop_branch", "Stops only this track. The rest of the sequence keeps running.",
                "Inside an optional side-quest track, stop it once the reward is given.");
        h("flow.call", "Runs a function (a reusable mini-timeline). Turn on 'await' to wait for it to finish before continuing.",
                "Make a function 'Explosion FX' once and call it from three places.");
        h("flow.parallel", "Starts several functions at the same time. 'Join' decides whether to wait for all, any or none.",
                "Run 'Music', 'Lights' and 'Spawn wave' together and wait for all of them.");
        h("flow.repeat", "Runs a function N times, with an optional pause between runs. The index variable counts 0, 1, 2…",
                "Repeat 'Spawn zombie' 5 times every 20 ticks.");
        h("flow.foreach", "Runs a function once for each item of a list, e.g. every online player. With 'as subject' each run acts on that item.",
                "For each participant: give 1 diamond and show 'Well done, {player}'.");
        h("flow.random", "Picks one of several functions at random; higher weight = more likely.",
                "Boss attack: 'Fireball' weight 3, 'Summon' weight 1.");
        h("flow.cooldown", "Runs a function, but ignores new calls until the cooldown ends (per player if you want).",
                "A healing fountain that can be used once every 30 s per player.");
        h("flow.debounce", "Waits until calls stop for a moment, then runs once. Avoids spamming when something fires a lot.",
                "Players pressing a button many times: save the score only once, 1 s after the last press.");
        h("flow.mutex", "Only one track at a time can run inside the named lock; the others wait.",
                "Two tracks both want to open the same gate: lock 'gate' so they never overlap.");
        // ── Logic ──
        h("flow.wait_until", "Pauses this track until the condition becomes true. Add a timeout to give up after a while.",
                "Wait until the player is inside the arena, then start the fight.");
        h("flow.while", "Repeats a function while (or until) a condition holds. The iteration limit prevents infinite loops.",
                "While the boss is alive, run 'Attack pattern' every few seconds.");
        h("logic.if", "Checks conditions in order and runs the function of the first one that is true; otherwise runs 'else'.",
                "If player health < 5 → 'Heal'; else if sneaking → 'Secret'; else → 'Normal'.");
        h("logic.switch", "Compares one value with several cases and runs the matching function.",
                "Switch on variable 'difficulty': 'easy' → small wave, 'hard' → big wave.");
        h("logic.gate", "A checkpoint: continues only if the condition is true, otherwise stops this track (or runs 'otherwise').",
                "Before the reward, check that the player has 3 keys.");
        // ── Events ──
        h("event.listen", "Keeps listening for an event (death, block break, interact…) while the sequence runs and runs a function each time.",
                "On every player death during the event: add 1 to 'deaths' and show a popup.");
        h("event.wait", "Pauses this track until an event happens once. Can store who triggered it.",
                "Wait until a player right-clicks the altar, then continue.");
        h("event.on_condition", "Watches a condition and runs a function when it becomes true, becomes false or changes.",
                "When the boss drops below 50 % health, run 'Phase 2' once.");
        h("signal.emit", "Sends a named signal that listeners (in this or other sequences) can react to.",
                "Emit 'door_open' and let another sequence play the door animation.");
        h("flow.wait_death", "Waits until an entity dies or disappears, or runs a function when it does.",
                "After spawning the boss (stored as 'boss'), wait for its death, then 'Victory'.");
        h("event.player_join", "Reacts when a player logs in, or waits for a specific player to join.",
                "Wait for 'Steve' to join, then start the cutscene for him.");
        h("event.player_leave", "Reacts when a player logs out.",
                "If the tracked player leaves, end the sequence.");
        // ── Variables ──
        h("var.set", "Stores a value. Scope: local (this run), sequence, per player or global (shared by all sequences). Use {name} in texts to show it.",
                "Set 'score' to 0 at the start; add 10 every time a target is hit.");
        h("counter.step", "Adds to a counter and runs a function when it reaches the threshold.",
                "Count kills; at 10 run 'Next wave' and reset.");
        h("state.set", "Changes the current state of a state machine.",
                "Set machine 'boss' to 'enraged'.");
        h("state.machine", "Runs a different function for each state; changing the state switches what runs.",
                "States 'idle', 'attack', 'enraged' for a boss.");
        // ── Messages ──
        h("action.chat", "Sends a chat message. Placeholders like {player} and {var:score} are filled in.",
                "'{player} found the treasure!' to all players.");
        h("action.title", "Big title and subtitle in the middle of the screen with fade times.",
                "Title 'Chapter 1', subtitle 'The Awakening'.");
        h("action.actionbar", "Short text above the hotbar. Good for hints that should not cover the screen.",
                "'Find the 3 crystals' while the quest runs.");
        // ── Players ──
        h("action.teleport", "Moves the targets to a position (~ = relative to the tracked player). Can keep or set the view direction.",
                "Teleport all participants to the arena at 100 64 200.");
        h("action.effect", "Gives or removes a status effect (speed, blindness, regeneration…).",
                "Blindness for 3 s during a jump scare.");
        h("action.player_state", "Changes a player property such as game mode, health, food or fire.",
                "Set game mode to adventure when the minigame starts.");
        h("action.give_item", "Gives items, optionally with NBT (names, enchantments).",
                "Give 1 'minecraft:diamond_sword' as a reward.");
        h("action.clear_item", "Removes items from the inventory (all of them, or a count).",
                "Remove the quest keys when the quest ends.");
        h("zone.keep_inside", "Turns a box into an arena wall: players who leave are pushed back with a warning, until released.",
                "Keep everyone inside the boss arena until the boss dies.");
        h("zone.release", "Removes a 'Keep Inside Area' barrier by its id.",
                "After the victory, release 'arena'.");
        h("track.player", "Chooses which player the following pieces act on (targets set to 'Tracked player').",
                "Pick a random participant to be 'the chosen one'.");
        // ── World ──
        h("action.set_block", "Places one block (with states). Use it to open doors, light lamps or build simple effects.",
                "Set 'minecraft:air' at the gate to open it.");
        h("action.fill", "Fills a box with a block, like /fill. The server limits how big it can be.",
                "Fill the arena exit with barrier blocks.");
        h("action.clone", "Copies a region of blocks to another place, like /clone.",
                "Copy a prebuilt 'broken bridge' over the real bridge.");
        h("action.structure", "Places a saved structure (from a structure block).",
                "Place 'mymap:boss_room' when the fight starts.");
        // ── Entities ──
        h("action.spawn_entity", "Spawns mobs or other entities. 'Store in variable' lets later pieces refer to it (boss bar, wait for death…).",
                "Spawn a zombie with NBT for 100 health, stored as 'boss', removed when the sequence ends.");
        h("action.remove_entities", "Removes entities around a point by type or tag.",
                "Remove all entities tagged 'minion' after the fight.");
        // ── Effects ──
        h("action.sound", "Plays a Minecraft or resource-pack sound to the targets or at a position.",
                "Play 'minecraft:entity.ender_dragon.growl' when the boss appears.");
        h("action.particles", "Spawns particles at a position or at each target.",
                "Flame particles around the altar for dramatic effect.");
        // ── Screen ──
        h("hud.popup", "A card that slides in from the side with an icon, title and message, then disappears.",
                "'Quest updated: talk to the guard' with style 'info'.");
        h("hud.overlay", "A custom overlay on screen: banner, bar (e.g. boss health), countdown or text. Stays until hidden; can update live.",
                "A countdown overlay 'Escape!' of 60 s at the top.");
        h("hud.marker", "A marker on the screen pointing to a place or following an entity, with the distance.",
                "Marker 'Go here' on the next checkpoint, hidden when closer than 3 blocks.");
        h("hud.screen", "Classic cinematic effects: fade to black and back, flash, camera shake, letterbox bars, vignette.",
                "Fade out (black), teleport, fade in: an invisible scene change.");
        h("hud.bossbar", "A vanilla boss bar. Follow an entity to show its health automatically, or show any value.",
                "Boss bar 'The Warden' following the variable 'boss'.");
        h("hud.hide", "Removes screen elements shown by this sequence (overlays, markers, boss bars, effects…).",
                "Hide the 'timer' overlay when the player finishes.");
        h("screen.effect", "Full-screen effects that can be stacked: dizzy, sleep, heat, cold, poison, insanity (with words), invert, melt, grayscale, blood.",
                "Poison for 5 s after drinking a potion; insanity with words 'RUN,BEHIND YOU' in a horror map.");
        h("screen.clear_effects", "Fades out one effect or all of them early.",
                "Clear 'all' when the player wakes up.");
        // ── Camera ──
        h("camera.fixed", "Puts the camera at a point. 'Look at player' makes a corner camera that follows the player; turn off 'Freeze' to let them walk.",
                "Security-camera view at ~5 ~4 ~5 looking at the player.");
        h("camera.path", "Flies the camera through keyframes (one per line: x y z yaw pitch). Smooth curve and easing make it cinematic.",
                "Intro fly-over of the village in 8 s, then back to the player.");
        h("camera.orbit", "Circles the camera around the player, a point or an entity.",
                "Slow orbit around the boss while it roars.");
        h("camera.reset", "Smoothly returns the camera to the player's eyes and gives control back.",
                "End of the cutscene: back to the player in 1 s.");
        h("camera.look_at", "Turns the player's own view to an entity, block or point. Strength 1 locks it; lower values just pull.",
                "Lock the view on the boss for 3 s when it appears.");
        h("camera.release_look", "Stops 'Force Look At'.",
                "After the reveal, release the view.");
        h("player.controls", "Locks what the player can do: walk, jump, look, attack/use, inventory or every menu; can hide the HUD or hand.",
                "Freeze everyone and hide the HUD during a dialogue.");
        h("player.animation", "Animates the player's body for everyone: insanity convulsions, shivering or swaying.",
                "Shiver while standing in the snow biome.");
        h("player.stop_animation", "Stops a 'Player Animation'.",
                "Stop 'insanity' when the effect ends.");
        h("player.release_controls", "Undoes 'Player Controls' and gives full control back.",
                "After the dialogue, release the controls.");
        // ── Audio ──
        h("audio.play", "Plays an imported WAV/MP3, in sync for everyone. Listeners decide who hears it; drag the piece edges to cut the song.",
                "Boss music on channel 'music', looping, only for participants.");
        h("audio.stop", "Stops the audio on a channel, with an optional fade-out.",
                "Fade the music out over 2 s when the boss dies.");
        h("audio.volume", "Changes the volume of a channel smoothly.",
                "Lower the music to 30 % while someone talks, then back to 100 %.");
        h("audio.pause", "Pauses the audio on a channel (resume continues from there).",
                "Pause the music during a menu.");
        h("audio.resume", "Continues paused audio.",
                "Resume the music after the menu.");
        h("audio.seek", "Jumps to a position in the song.",
                "Skip the intro: seek to 15 000 ms.");
        h("audio.sync", "Waits until the music reaches a moment, so actions hit the beat exactly.",
                "Wait until 32 000 ms (the drop), then flash and shake.");
        // ── Commands ──
        h("action.command", "Runs any Minecraft command. Only works if the sequence was saved by an operator (level 2+); dangerous commands are blocked.",
                "'give {player} minecraft:emerald 5' or 'weather clear'.");
        h("action.scoreboard", "Changes a scoreboard score (set, add, remove), creating the objective if needed.",
                "Add 1 to objective 'wins' for the winner.");
        // ── Sequence ──
        h("seq.control", "Starts, pauses, resumes, restarts or cancels another sequence (or this one).",
                "When the player reaches the end, start 'Credits'.");
        h("seq.phase", "Sets the phase name (and next phase) shown in the top overlay and the monitor.",
                "Phase 'Wave 2', next 'Boss'.");
        // ── Debug ──
        h("debug.log", "Writes a line to the task log (Task Manager) and optionally notifies admins. Great for checking values.",
                "Log 'score = {var:score}' to see the value while testing.");
        h("debug.breakpoint", "Pauses the sequence here while testing (only in Debug runs by default). Use Step to continue piece by piece.",
                "Breakpoint just before the boss spawns to check the arena.");
        h("debug.assert", "Checks that something is true; logs or fails the run if not.",
                "Assert the variable 'boss' exists after spawning.");
    }
}
