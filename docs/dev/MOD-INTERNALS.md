# Bot mod internals

How the Fabric client mod (`:bot-mod`, package `io.github.krekerdm.baritonebots.mod`) is put together. The contract is `docs/SPEC.md` (§2 link, §3 tasks, §4 internals); deviations are in `DECISIONS.md` under `mod:`.

## Packages

| package | contents |
|---|---|
| `mod` | `BaritoneBotsMod` (client entrypoint), `BotRuntime` (owns everything, tick loop, message dispatch), `LaunchProps` (`-Dbaritonebots.*`), `ModInfo` |
| `mod.link` | `LinkClient`: TCP JSON lines, reader + writer threads, backoff 1→15 s, hello/welcome/reject; `CompanionBridge`: `baritonebots:main` plugin channel (SPEC §7) |
| `mod.baritone` | `PauseProcess` (prio 1000, claims by owner), `DefenseProcess` (prio 900, hold or path), `BaritoneSettingsApplier`, `LogForwarder` (`log` messages, ≤10/s) |
| `mod.behaviour` | `ConnectionBehaviour`, `LoginBehaviour`, `LifeBehaviour` (death/respawn/damaged/tool_low), `Eater` + `EatBehaviour`, `DefenseBehaviour`, `ContainerSensor`, `StatusReporter`, `ProtectionGuard` (called by the `MultiPlayerGameMode` mixin) |
| `mod.task` | `TaskExecutor`, `TaskContext`, `TaskRegistry`, `TaskManager`, helpers `PathStep`, `ContainerSession`, `ClickQueue`, `TaskArgs`, `SubTask` |
| `mod.task.impl` | one class per task type, plus `Fighter` (melee for attack/guard) |
| `mod.task.plan` | pure planning code with unit tests (`bot-mod/src/test`): `SlotMoves` (count-accurate PICKUP/THROW plans), `CraftGrid` (manual craft pattern, per-round set planning) |
| `mod.query` | `QueryHandler` |
| `mod.lowpower` + `mod.mixin` | `LowPower` state + the `require = 0` mixins: Options, GameRenderer, SoundEngine, Minecraft (low power) and MultiPlayerGameMode (protection guard) |
| `mod.util` | `Inv` (inventory/menu slots, clicks), `Interact` (look, use block, attack, close menu, release keys), `McIds`, `Positions`, `Weapons` (id-tier ranking), `Recipes` (client recipe book lookups), `Reflect` (private fields by name), `RateLimiter` |

## Threading

* Link reader/writer threads only touch `LinkClient` queues. `BotRuntime.send*`/`event` are thread-safe (Baritone logs from its own threads).
* Everything else runs on the client thread: `END_CLIENT_TICK` drains up to 200 inbound envelopes, then ticks connection → login → companion → life → eat → defense → tasks → container sensor → status, each step in its own try/catch.
* Companion payloads arrive on Fabric's receiver and are only queued; `CompanionBridge.tick` handles them.
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
* `ContainerSession`: path next to a block, open it, wait for the menu, settle 3 ticks, then `click(...)`/`movePartial(...)` (≤2 clicks per tick); `close(ctx)` releases the pause claim. Container slots = menu slots whose container is not the player inventory; `mainSlots` = player main + hotbar only. `ContainerSession.playerInventory()` drives the player's own `InventoryMenu` (id 0: 2×2 crafting, dropping) without walking or closing anything; it waits for a running eater and also claims `<owner>:inventory`, which keeps auto-eat and defense weapon swaps out while it clicks (`PauseProcess.inventoryBusy`). `returnCarried` puts a cursor stack back. Plan one move per `idle()` tick: the client predicts each click, so slot counts are current once the queue is empty.
* `SubTask`: run another executor inside a task (`transfer` = take + deposit); same task id/pause owner, steps show on the parent, result read with `result()`, `end(cancelled)` runs its cancel/cleanup once.
* `Fighter`: approach with `GoalNear(target,1)` (re-planned when the target moves 3+ blocks), hold pause claim `<owner>:fight` in reach (eyes→hitbox ≤ 3), best weapon via `DefenseBehaviour.equipWeapon`, hit at charge ≥ 0.9; unreachable after 3 failed paths.
* `Eater`: shared by auto-eat and the `eat` task; ticked by `EatBehaviour` only.
* `ctx.owner()` = pause-claim owner for the task.

## Adding a query

Add a `case` in `QueryHandler.answer` (and to `needsGame` if it reads the world). Kinds in `QueryKinds.ALL` that are not handled answer `ok:false, error:"unsupported"`.

## Task notes (phase 2A)

* `craft`: recipe book first (`Recipes.forItem` reads `ClientRecipeBook.known` by reflection, falls back to `getCollections()`; only shaped/shapeless displays; `RecipeDisplayEntry.resultItems(SlotDisplayContext.fromLevel(level))`). `handlePlaceRecipe(containerId, displayId, false)` adds one set per request when the same recipe is already placed, so the task asks set by set (≥ 2 ticks apart, Paper's recipe spam limiter) and uses `useMaxItems` only when ≥ 64 crafts remain; one `QUICK_MOVE` on the result then crafts exactly what is placed. Manual `grid` fallback: `CraftGrid.planRound` + one `movePartial` per idle tick; the first round places one set to learn the yield. The grid is shift-clicked empty before each round and at the end; for the 2×2 grid a close packet for menu 0 is the last resort (the server's `InventoryMenu.removed` returns grid + cursor).
* `smelt_load`/`smelt_collect`: `AbstractFurnaceMenu` slots 0 input, 1 fuel, 2 result. Loaded/fuel/collected counts come from inventory totals before/after each phase, not from furnace slots (the furnace consumes while we click). A placement the slot refuses (non-fuel) is detected by the unchanged slot count.
* `drop`: `THROW` button 1 = whole stack, 0 = one item (checked in `AbstractContainerMenu.doClick`); `SlotMoves.throwMode` picks whole / singles / split-then-whole.
* `selection`: like Baritone's `#sel`: `FillSchematic(w,h,l, BlockOptionalMeta)`, wrapped in `WallsSchematic`/`ShellSchematic`/`ReplaceSchematic(…, BlockOptionalMetaLookup)`, `build(name, schematic, boxMin)`.
* `attack`/`guard` yield (release the hold) while the defense behaviour is engaged or the eater runs.

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
* `ItemStack#getMaxStackSize` lives on the `ItemInstance` interface (default method); `Item#getDefaultMaxStackSize` for an id.
* Crafting menus: `AbstractCraftingMenu#getResultSlot`/`getInputGridSlots` (row-major)/`getGridWidth`; `InventoryMenu` result 0, grid 1–4; `CraftingMenu` result 0, grid 1–9. The client never computes crafting results; wait for the server's slot update.
* Networking: `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()`, `CustomPacketPayload.Type(Identifier)`, `StreamCodec.of(encoder, decoder)`; `ClientPlayNetworking.send` does not check `canSend`.
