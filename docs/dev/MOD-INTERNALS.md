# Bot mod internals

How the Fabric client mod (`:bot-mod`, package `io.github.krekerdm.baritonebots.mod`) is put together. The contract is `docs/SPEC.md` (§2 link, §3 tasks, §4 internals); deviations are in `DECISIONS.md` under `mod:`.

## Packages

| package | contents |
|---|---|
| `mod` | `BaritoneBotsMod` (client entrypoint), `BotRuntime` (owns everything, tick loop, message dispatch), `LaunchProps` (`-Dbaritonebots.*`), `ModInfo` |
| `mod.link` | `LinkClient`: TCP JSON lines, reader + writer threads, backoff 1→15 s, hello/welcome/reject |
| `mod.baritone` | `PauseProcess` (prio 1000, claims by owner), `DefenseProcess` (prio 900, hold or path), `BaritoneSettingsApplier`, `LogForwarder` (`log` messages, ≤10/s) |
| `mod.behaviour` | `ConnectionBehaviour`, `LoginBehaviour`, `LifeBehaviour` (death/respawn/damaged/tool_low), `Eater` + `EatBehaviour`, `DefenseBehaviour`, `ContainerSensor`, `StatusReporter` |
| `mod.task` | `TaskExecutor`, `TaskContext`, `TaskRegistry`, `TaskManager`, helpers `PathStep`, `ContainerSession`, `ClickQueue`, `TaskArgs` |
| `mod.task.impl` | one class per task type |
| `mod.query` | `QueryHandler` |
| `mod.lowpower` + `mod.mixin` | `LowPower` state + the four `require = 0` mixins (Options, GameRenderer, SoundEngine, Minecraft) |
| `mod.util` | `Inv` (inventory/menu slots, clicks), `Interact` (look, use block, attack, close menu, release keys), `McIds`, `Positions`, `Weapons` (id-tier ranking), `Reflect` (private fields by name), `RateLimiter` |

## Threading

* Link reader/writer threads only touch `LinkClient` queues. `BotRuntime.send*`/`event` are thread-safe (Baritone logs from its own threads).
* Everything else runs on the client thread: `END_CLIENT_TICK` drains up to 200 inbound envelopes, then ticks connection → login → life → eat → defense → tasks → container sensor → status, each step in its own try/catch.
* Fabric `DISCONNECT` may arrive off-thread, so it only sets a flag handled in `ConnectionBehaviour.tick`.
* The link starts on the first client tick: Fabric calls the entrypoint inside `Minecraft`'s constructor, before the session user exists.

## Adding a task

1. Create `mod.task.impl.FooTask implements TaskExecutor`.
2. `start(ctx)`: parse `ctx.args()` with `TaskArgs`/`Json`, fail with `Reasons.BAD_ARGS` on bad input, start Baritone or set up state. May `succeed`/`fail` right away.
3. `tick(ctx)`: never block; call `ctx.step(text, progress)`; finish with `ctx.succeed(msg, data)` or `ctx.fail(reason, msg, data)` (first call wins).
4. `cleanup(ctx)` (always) / `cancel(ctx)` (cancel, timeout, death, disconnect): restore Baritone settings you changed. The framework already releases `task:<id>` pause claims, releases keys, closes any container and calls `cancelEverything()`.
5. Register it in `TaskRegistry`'s static block. Types listed in `TaskTypes.ALL` but not registered answer `unsupported`.

Helpers:
* `PathStep`: walk to a `Goal`; arrival = `goal.isInGoal(feet)`, custom-goal process inactive without arrival = failed. `PathStep.GoalNearXZ` for "within r blocks, any y".
* `ContainerSession`: path next to a block, open it, wait for the menu, settle 3 ticks, then `click(...)`/`movePartial(...)` (≤2 clicks per tick); `close(ctx)` releases the pause claim. Container slots = menu slots whose container is not the player inventory.
* `Eater`: shared by auto-eat and the `eat` task; ticked by `EatBehaviour` only.
* `ctx.owner()` = pause-claim owner for the task.

## Adding a query

Add a `case` in `QueryHandler.answer` (and to `needsGame` if it reads the world). Kinds in `QueryKinds.ALL` that are not handled answer `ok:false, error:"unsupported"`.

## 26.2 pitfalls hit

* Unobfuscated Mojang names at runtime: reflection by field name (`Reflect`) works and is how `DisconnectedScreen.details`, `DeathScreen.causeOfDeath` and `OptionInstance.value` are read.
* `mc.screen` is gone: `mc.gui.screen()`, `mc.gui.setScreen(..)`, `mc.gui.overlay()`.
* Clicks: `MultiPlayerGameMode#handleContainerInput(containerId, slot, button, ContainerInput, player)`; `ContainerInput.SWAP` with button 0–8 = hotbar, 40 = offhand. `Slot.index` is the menu slot id.
* `GameRenderer` has separate `extract(DeltaTracker, boolean)` and `render(DeltaTracker, boolean)`; both are skipped together.
* `SoundEngine#play` returns `SoundEngine.PlayResult`; there is also `playDelayed`.
* `OptionInstance#set` runs change callbacks that touch half-built game objects during `Options.<init>`, so startup forcing writes the private `value` field instead.
* `GameProfile` is a record: `getGameProfile().name()`.
* `ClientPacketListener#sendCommand(String)` (no leading `/`) and `#sendChat(String)`.
* Leaving a server: `Minecraft#disconnectFromWorld(Component)` (what the pause screen does).
* Baritone's custom goal process goes inactive both on arrival and on calc failure; check the goal yourself.
