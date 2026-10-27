# Platform adapter contract

The engine in `core/` (package `csequencer.core`) contains no Minecraft, Forge, Fabric or NeoForge
classes. Each loader + Minecraft version gets an adapter that implements
`csequencer.core.platform.PlatformAdapter`. Forge 1.20.1 is the first implementation
(`src/main/java/csequencer/forge`).

## Threading

- `Engine.tick()` must be called exactly once per server tick, on the server thread, at the end of the tick.
- Every adapter method is called on the server thread. Adapters must not block (no file or network I/O).
- Events may be posted with `Engine.post(GameEvent)` from server-thread event handlers. The engine
  queues them and processes them at the start of the next tick, in order.

## Lifecycle

| Platform moment | Call |
|---|---|
| Server started | `new Engine(adapter, settings, catalog)`, then `importRuntime(json)`, then `serverStarted()` |
| Every tick (end) | `engine.tick()` |
| Player logged in | `engine.onPlayerJoin(ref)`, then post `player.join` |
| Player logged out | post `player.leave`, then `engine.onPlayerLeave(ref)` |
| Server stopping | `json = engine.shutdown()`, then persist `json` (runtime state for recovery) |

`shutdown()` cancels every task with full cleanup: branches, listeners, timers, locks, audio, and
`ActionAdapter.onTaskEnded`.

## Interfaces

| Interface | Must |
|---|---|
| `PlayerDirectory` | Return stable `EntityRef`s (keyed by UUID). `select()` resolves vanilla selectors; return an empty list on bad input. |
| `WorldQueryAdapter` | Implement every non-core query id in `PieceRegistry` whose capability is declared. Be side-effect free. Return `Value.NULL` (never throw) for missing data. Arguments arrive already resolved. |
| `ActionAdapter` | Implement every dispatched piece type. **Re-validate all arguments.** Respect `ActionContext.commandPermissionLevel()`, `commandBlacklist()` and `fillBlockLimit()`. Never throw; return `ActionResult.fail`. Track task-owned resources and release them in `onTaskEnded(taskId, removeSpawned)`. |
| `PermissionAdapter` | Check `Permission`s using the platform permission API where available, falling back to op level. |
| `ClientChannel` | Deliver JSON payloads for each `Topic` to one player. Respect packet size limits (chunk if needed). |
| `PlatformInfo` | Declare exactly the `Capability`s implemented. Pieces needing a missing capability are reported by the validator and fail with a clear message at runtime. |

## Execution semantics (identical on every platform)

- Each track of a timeline is a branch. Pieces run in (tick, placement order). Waiting pieces freeze the branch clock.
- Within a tick: tasks are processed by priority (descending), then start order; branches by creation order.
- Budgets (actions per tick, condition evaluations, branch steps) defer work to the next tick. Work is never dropped.
- Transition (`edge`) nodes record a baseline on the first observation of a subject and never fire on it.
- Event ids are de-duplicated. Start requests with the same dedupe key within 200 ticks are ignored.
- Clients never execute sequence logic. They render what `SyncService` sends.

## Porting checklist

1. Create a loader module that depends on `:core`.
2. Implement `PlatformAdapter` and the network channel (four packet types; see `csequencer.net.CSQNetwork`).
3. Port the client layer (screens, overlay, audio). It depends only on core and the network messages.
4. Run `:core:test` and the platform's integration tests (see `CSQGameTests` for the Forge equivalents).
5. Update `docs/COMPATIBILITY.md`.
