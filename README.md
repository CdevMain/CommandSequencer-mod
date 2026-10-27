# Command Sequencer (Forge 1.20.1)

Command Sequencer is an in-game visual timeline and logic system for building automated events,
cutscenes, boss fights and multiplayer minigames. Everything is edited and controlled from inside
Minecraft. Execution runs only on the server.

## Quick start

1. Press **K** to open the hub. Go to **Projects → New**.
2. In the editor, press **+ Piece** (or double-click a track) and choose a piece, for example *Run Command* or *Title*.
3. Drag pieces along the timeline. Add phase markers with **M**. They become the phases shown in the top overlay.
4. Click **Save**, then **▶ Run**. Watch it in the **Task Manager** (**J**) or the **Monitor**.

### Built-in tutorials and examples

Every world gets seven ready-made sequences (public, owned by "CommandSequencer"). Open them in the editor to
see how they are built, run them, or duplicate them as a starting point:

| Sequence | Shows |
|---|---|
| Tutorial 1 · Basics | Messages, titles, waits, sounds, variables, `{player}` placeholders |
| Tutorial 2 · Logic & Events | IF/ELSE, waiting for a condition (sneak), counting events (5 clicks), logic graph |
| Example · Boss Fight | Spawning a boss next to you, boss bar, marker, arena that keeps players inside, phase 2 below 50 % HP, time limit, victory/defeat |
| Example · Checkpoint Race | Three checkpoints with markers, a countdown timer, rewards when you finish, "out of time" otherwise |
| Example · Cinematic Camera | Keyframe fly-around, a corner shot that watches you while you walk, an orbit, smooth return, Force Look At |
| Example · Screen Effects | Every Screen Effect+ one after another (sleep also freezes the player), then two combined |
| Example · Madness | Insanity with custom words, the body animation seen from an orbiting camera, then blood |

The examples use **relative coordinates**: in any position field, `~5 ~ ~-3` means "5 blocks east and 3 north of the
tracked player" (the player who ran the sequence, or the one set with *Track Player*). The query **Offset position**
(`vec.offset`) does the same inside the logic and variables.

Examples are installed once per world. Deleted ones don't come back unless an admin runs `/csq examples`.
Turn it off with `"installExamples": false` in `config/commandsequencer-server.json`.

## Screens

| Screen | What it does |
|---|---|
| Home | Entry points and your permissions |
| Projects | Server projects (open, run, duplicate, share, export, delete) and local files (import, including 1.0 files) |
| Editor | Multi-track timeline: drag/drop, multi-select (Ctrl-click, box drag), zoom/scroll, snapping, markers, groups (collapsible), functions, audio waveform, undo/redo, copy/paste, validation, live server playheads |
| Logic graph | Opened from any logic piece. Nodes: AND/OR/XOR/NOT/NAND/NOR/XNOR, groups, comparisons, transitions (entered/exited/changed), for-players quantifiers, reusable conditions |
| Pieces | Searchable library of every piece, with parameters and platform support |
| Tasks | Live Task Manager: filter/sort, details, branches, log, cost; pause, resume, step, restart, cancel; open a run in the editor |
| Monitor | Every sequence on the server and its state (available, queued, running, paused, waiting, finishing, failed) |
| History | Past runs with result, duration, errors and log |
| Settings | Overlay and audio preferences. Admins also edit permission levels, limits and visibility |

### Editor

- **Pieces panel** (left): `P` or ◧ cycles it between **full list → icon rail → hidden**; « and » do the same.
  In the icon rail each category is one icon: click it to open a small list of its pieces, then drag or click a piece.
  The choice is remembered. Categories — Timing, Flow, Logic, Events, Variables,
  Messages, Players, World, Entities, Effects, Screen, Camera, Audio, Commands, Sequence, Debug. Click a category to open it,
  type to search (`Ctrl+F`), then **drag a piece onto a track** (or click it to add it at the playhead).
- **Right-click** a piece, an empty spot on a track, a track header or a marker for every option.
- **Inspector** (right): sections *General*, *Settings*, *Actions* for the selected piece; dropdowns list every option.
- Toolbar: Save · Run (Shift = from playhead) · Debug · pause/step/stop the live run · undo/redo ·
  timeline switcher (main + functions) · **+ Insert** · ⚙ Project.

Shortcuts: `Ctrl+S` save · `Ctrl+Z/Y` undo/redo · `Ctrl+C/X/V/D` copy/cut/paste/duplicate · `Ctrl+A` select all ·
`Del` delete · `G` / `Shift+G` group / ungroup · `M` marker · `B` breakpoint · `L`/`Enter` edit logic · `I` insert ·
`P` pieces panel · `Space` local preview · arrows nudge (Shift = snap step) · scroll zoom · Shift+scroll pan ·
right-drag pan · Ctrl+scroll or scroll over track headers to scroll tracks · Shift while dragging disables snapping ·
`Esc` clears the selection.

## Home screen art

The Home screen shows only the big buttons, with no top bar. Each button is an image you can replace with your own
pixel art (any size in 4:3; 128×96 recommended; drawn pixel-perfect, transparency supported):

`src/main/resources/assets/csequencer/textures/gui/home/` → `projects.png`, `editor.png`, `library.png`, `tasks.png`,
`monitor.png`, `history.png`, `settings.png`. The button name is drawn below each image, so the art doesn't need text.

## Concepts

- **Tracks are parallel branches.** Pieces on a track run in tick order. *Delay*, *Wait Until* and similar
  pieces pause only their branch and shift its later pieces.
- **Functions** are reusable timelines with typed inputs and outputs. Call them with *Call Function*,
  loops, *If*, *Switch*, *Weighted Random*, *Parallel*, *For Each*, listeners and state machines.
- **State checks versus transitions:** *player is inside the arena* is a comparison. *Player just entered the arena* wraps it in a
  **Transition** node (or uses a *Condition Trigger* with "entered"). A transition fires once per change, per player. The first time a
  player is observed only records a baseline.
- **Scopes:** PLAYER (one instance per player), ENTITY, SEQUENCE (one shared instance), DIMENSION, SERVER
  (ignores disconnects). Concurrency policy: ignore, restart, queue or parallel.
- **Tracking:** every sequence tracks one player. New pieces target the **Tracked player** by default, and player
  conditions read from that player. By default it is whoever started the sequence. Set a fixed one in *⚙ Project → Tracking*
  (pick from the online players), or change it while running with the **Track Player** piece. *On Player Join* can also
  track the player who joined. Use `{tracked}` in text.
- **Login / logout:** the **On Player Join** and **On Player Leave** pieces (Events) run a function, or wait, when any player,
  the tracked player or a named player logs in or out.
- **Variables:** local (function), sequence, player (persists per player) and global (persists server-wide).
  Use them in text as `{name}`, `{var:name}`, `{player}`, `{initiator}`, `{event.x}`, `{tick}`, `{phase}`.
- **Audio editing:** an audio piece's length is the clip. Drag its right edge to cut the end and its left edge to cut
  the start (the start offset follows). Use right-click → *Split here* (or `S` at the cursor) to cut it in two, and set
  *Fade in / Fade out* in the inspector. **Change Volume** fades the music (e.g. lowering it during dialogue),
  and **Stop Audio** can fade out.
- **Screen pieces** (category *Screen*):
  - **Show Popup**: a side notification card with an icon, title, message and style.
  - **Show Overlay**: a banner, a bar (e.g. boss health from any value), a countdown timer or text. Updates live.
  - **Waypoint Marker**: an on-screen point showing where to go, with the distance. It can follow an entity.
  - **Boss Bar**: the vanilla boss bar. It follows an entity's health and hides when the entity dies.
  - **Screen Effect**: fade to/from a colour, flash, camera shake, letterbox bars, vignette.
  - **Hide Screen Element** removes any of these. Everything is removed automatically when the sequence ends.
- **Boss tools:** **Wait For Death** (waits for, or reacts to, an entity's death — use it with Spawn Entity → *Store first entity
  in variable*), **Keep Inside Area** (an arena barrier with a warning; **Release Area** turns it off), and random
  targets in **Track Player** (random participant or random online player).
- **Audio:** put WAV or MP3 files in `.minecraft/commandsequencer/audio` and use **Audio** in the editor.
  Files are uploaded to the server and identified by SHA-1. Clients download them, and the server owns the
  playback clock (heartbeats correct drift above 150 ms, and late joiners start at the current position).
  Sequences with audio should use **REALTIME** timing (the editor switches it on when you add audio).
- **Triggers** (Project… in the editor) start saved sequences on events (join, death, interact, …) or on
  condition transitions, with cooldowns and once-per-player/server options. These options survive restarts.
- **Area triggers** (kind `AREA`): a box in the world that starts the sequence when a player walks into it.
  Fastest way: the **▣ Area** button in the editor toolbar (or **+ Insert → Area trigger**, or **▣ + Area trigger** in
  ⚙ Project). To redraw an existing one, open ⚙ Project → triggers and press **✎ Draw area in the world**: the screen closes, **left-click** a block for corner 1,
  **right-click** for corner 2 (looking at air uses your feet), then **K** to confirm (**J** cancels). You can also type
  the corners or use **Here**. **Extra conditions** (a logic graph) must also be true, so the area isn't set off by
  anyone or anything: e.g. *has item*, *in team*, *variable = x*, *not sneaking*. The player who entered becomes the
  tracked player. *Fire when* chooses entered / exited / changed / every check while inside. Cooldown and
  once-per-player/server apply as for other triggers. Save the sequence to arm the area.
- **Area viewer** (key **O**, or the button in the trigger editor): shows the trigger areas of the sequences you can
  edit as wireframe boxes with their names (blue = armed, grey = off, green = you are inside). It is always on while drawing.

## Camera and screen effects

Category **Camera**:

| Piece | What it does |
|---|---|
| Camera: Fixed Shot | Puts the camera at a point. *Look*: at the player's body (a corner shot that follows you), at a point, at an entity, or a fixed yaw/pitch |
| Camera: Cinematic Path | Flies through keyframes, one per line: `x y z [yaw pitch]` (`~` = relative). Smooth curve, easing, loop, look at keyframes / player / point / entity / forward |
| Camera: Orbit | Circles around the player, a point or an entity |
| Camera: Back To Player | Smooth return to the player's eyes |
| Force Look At | Turns the player's own view to an entity, block or point. Strength 1 locks it, lower values only pull |
| Release Look | Stops Force Look At |
| Player Controls | Freeze movement, jump/sneak, looking around, attack/use/drop/hotbar, the inventory, or every menu (pause and chat stay); hide the HUD or the hand |
| Release Controls | Gives everything back |
| Player Animation | Animates players' bodies for everyone: `insanity` (convulsions, head snapping, flailing arm), `shiver`, `sway` |
| Stop Player Animation | Stops it |

Camera pieces have *Freeze the player while filming* (on by default), *Hide the HUD*, *field of view*, and smooth
blend in/out times. The player's body is visible while the camera is away. Everything is undone automatically
when the sequence ends, fails or is cancelled. Late joiners get the current camera.

**Screen Effect+** (category Screen): `dizzy` (sway, double vision), `sleep` (eyelids closing and blinking),
`heat` (shimmer, orange), `cold` (frost from the edges), `poison` (green pulses, wobble), `insanity` (glitches,
colour shifts, chromatic split, glitch blocks, scanlines and words in disorder all over the screen), `invert`,
`melt` (the screen drips down), `grayscale`, `blood` (a bloody rim and drips running down the screen).
Insanity words come from the piece's *Insanity words* field (comma separated, placeholders like `{player}` work)
or, when empty, from `insanityWords` in `config/csequencer-client.json`. Each has an intensity,
fade in, duration and fade out, and several can run together. **Clear Screen Effects** fades them out early.
They are drawn by a full-screen shader (`assets/csequencer/shaders/core/screen_fx.*`).

## Overlay

The top-of-screen overlay shows each sequence's name, phase, next phase with a countdown, state, audio track and progress.
It never covers boss bars. Personal sequences are marked **YOU**, global ones **SERVER**. The overlay only
moves ahead of the last server update for running sequences, by at most 10 ticks. If updates stop for 3 s, it says
the status may be outdated. Who sees announcements is set per project (all, participants, teams, permission) and by the server.

## Permissions

Each action needs a node `csequencer.<id>`. Permission mods can override them. By default they map to op levels,
which admins can edit in Settings:

`edit` 2 · `upload` 2 · `run` 2 · `control.own` 2 · `control.all` 3 · `monitor` 0 · `monitor.all` 2 ·
`announcements` 0 · `audio.upload` 2 · `admin` 4

Projects have an owner, editors and a visibility (private, shared, public). **Command pieces run only if the
project was last saved by a player at or above the server's command trust level** (default 2). A blocklist
(op, stop, ban, …) also applies inside `execute … run`. Clients never send privileged actions: they send
requests, which the server validates (rate limits, size limits, ownership, permissions).

## Commands

`/csq run <name> [fromTick]` · `/csq stop [task]` · `/csq pause|resume <task>` · `/csq list` · `/csq tasks` ·
`/csq reload` · `/csq import` (imports 1.0 files from `.minecraft/commandsequencer/csq`) ·
`/csq examples` (reinstalls missing built-in examples).
The 1.0 aliases `/csq_run`, `/csq_stop` and `/csq_list` still work.

## Files

| Path | Content |
|---|---|
| `config/commandsequencer-server.json` | Server settings (1.0 keys are migrated) |
| `config/csequencer-client.json` | Your client preferences |
| `<world>/commandsequencer/projects/*.csq.json` | Project definitions (versioned JSON) |
| `<world>/commandsequencer/registry.json` | Ownership, sharing, trust |
| `<world>/commandsequencer/audio/` | Audio files by hash |
| `<world>/commandsequencer/history.json`, `runtime.json` | History, persistent variables, trigger state, recoverable tasks |
| `.minecraft/commandsequencer/csq/` | Local exports and 1.0 files |

## Architecture

- `core/`: loader-independent engine, models, logic, persistence, validation, sync and editor operations.
  No Minecraft classes. Unit tests run headless.
- `src/main/java/csequencer/forge`: the Forge 1.20.1 adapter (queries, actions, permissions, events,
  commands, server networking, storage).
- `src/main/java/csequencer/net`: the protocol (4 packet types, GZIP JSON, chunked transfers).
- `src/main/java/csequencer/client`: screens, overlay, audio. Client only.

See `docs/ADAPTER_CONTRACT.md` for porting and `docs/COMPATIBILITY.md` for what has been verified.

## Building and testing

Visual check (development only): `./gradlew runClient -PcsqQuickPlay="<world folder>" -PcsqDevTest` joins the world,
shows every screen effect, camera mode and the body animation, saves `run/screenshots/csq_*.png` and closes the game.

```bash
./gradlew build                 # mod jar: build/libs/csequencer-1.0.jar (core + relocated JLayer included)
./gradlew :core:test            # engine, logic, scheduling, cancellation, serialization, sync tests
./gradlew runGameTestServer     # in-world tests of the Forge adapter (dedicated-server environment)
```

## Known limitations

- Multiplayer with several real clients, the GUI and audio playback have not been verified in a live game in this
  version. The server logic and synchronization are covered by headless tests only.
- RESUME recovery restarts from the saved position on the main timeline. Branches inside running functions
  restart, and listeners, watchers and audio placed before that position are re-created.
- Per-sequence announcement nodes (`csequencer.announcements.<project>`) are approximated by `monitor.all`
  on Forge.
- Audio uses Java Sound on the client (not OpenAL). Volume follows the Master × Records sliders.
- Fabric and NeoForge adapters are not implemented. The core is ready for them.
- The legacy 1.0 classes were moved to `removed-legacy/` and the MDK template to `removed-template/`.
  Neither folder is compiled.
