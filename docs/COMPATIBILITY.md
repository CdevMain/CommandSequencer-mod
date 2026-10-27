# Compatibility matrix

| Minecraft | Loader | Java | Build | Core tests | In-world tests | Single-player | Dedicated server | Multiplayer (2+ clients) |
|---|---|---|---|---|---|---|---|---|
| 1.20.1 | Forge 47.4.18 | 17 | ✔ `gradlew build` | ✔ 79/79 | ✔ 5/5 GameTests (dedicated-server environment) | client starts to title screen; screens/overlay/audio not exercised by hand | ✔ mod loads, engine runs (GameTest server) | not verified |
| any | Fabric | — | not implemented | core is loader-free | — | — | — | — |
| any | NeoForge | — | not implemented | core is loader-free | — | — | — | — |

"Not verified" means the code exists but nobody has run it in that setup yet. Do not treat it as supported until a row is ✔.

## Project file portability

Project files (`*.csq.json`, schema 1) contain no loader- or version-specific identifiers.
Piece types are stable string ids (`action.command`, `flow.delay`, ...). Block, item and entity ids are
plain Minecraft resource locations, so they only work where those resources exist. Unknown piece
types and unknown fields are preserved on load and save, and reported by validation.

## Verification log (Forge 1.20.1)

- `gradlew build`: release jar contains the core and JLayer relocated to `csequencer.shadow.javazoom` (checked with javap).
- `gradlew :core:test`: 79 tests covering logic and transitions, scheduling and ordering, loops and limits, cancellation
  cleanup, error isolation, policies, recovery, serialization and migration, validation, and per-viewer sync filtering.
- `gradlew runGameTestServer`: 5/5 in-world tests (timed set-block, command piece, IF on a block query,
  entity cleanup on cancel, fill limit).
- `gradlew runClient`: reached the title screen with the mod loaded, with no errors from the mod.
