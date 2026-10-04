# Bot mod internals

How the Fabric client mod (`:bot-mod`, package `io.github.krekerdm.baritonebots.mod`) is put together. The contract is `docs/SPEC.md` (§2 link, §3 tasks, §4 internals); deviations are in `DECISIONS.md` under `mod:`.

## Packages

| package | contents |
|---|---|
| `mod` | `BaritoneBotsMod` (client entrypoint), `BotRuntime` (owns everything, tick loop, message dispatch), `LaunchProps` (`-Dbaritonebots.*`), `ModInfo` |
| `mod.link` | `LinkClient`: TCP JSON lines, reader + writer threads, backoff 1→15 s, hello/welcome/reject; `CompanionBridge`: `baritonebots:main` plugin channel (SPEC §7) |
| `mod.baritone` | `PauseProcess` (prio 1000, claims by owner), `DefenseProcess` (prio 900, hold or path), `BaritoneSettingsApplier`, `LogForwarder` (`log` messages, ≤10/s), `SettingsOverride` (temporary per-task setting changes, restored in `cleanup`) |
| `mod.behaviour` | `ConnectionBehaviour`, `LoginBehaviour`, `LifeBehaviour` (death/respawn/damaged/tool_low), `Eater` + `EatBehaviour`, `DefenseBehaviour`, `ContainerSensor` (snapshots incl. sign / item-frame labels), `StatusReporter`, `ProtectionGuard` (called by the `MultiPlayerGameMode` mixin), `OwnerWatcher` (owner chat commands → `owner_command`, the `owner` query) + pure `OwnerChat` (line recognition, unit-tested) |
| `mod.task` | `TaskExecutor`, `TaskContext`, `TaskRegistry`, `TaskManager`, helpers `PathStep`, `ContainerSession`, `ClickQueue`, `TaskArgs`, `SubTask` |
| `mod.task.impl` | one class per task type, plus `Fighter` (approach + melee for attack/guard/slaughter, approach only for breed/shear), `DropCollector`, `AnimalPen` (pen box, animals inside, item into main hand, fence protection), `FakeOreWatcher` (mine `fakeOres`) |
| `mod.task.plan` | pure planning code with unit tests (`bot-mod/src/test`): `SlotMoves` (count-accurate PICKUP/THROW plans), `CraftGrid` (manual craft pattern, per-round set planning), `AnimalFood` (default breeding food), `LegitMining` (branch-mining Y per ore) |
| `mod.schematic` | `SchematicStore` (async Baritone parse + 2-entry cache), `SchematicArgs` (`file/origin/rotation/mirror/box`), `Placement` (pure, allocation-free form of common's `SchematicTransform`), `BomRules` (pure block → items rules), `StateTable` (per-scan state cache: placed state, cost, counters, world comparison), `SchematicScan` (time-sliced bom/progress/missing scan), `PlacedSchematic` (Baritone `ISchematic` for `build`) |
| `mod.query` | `QueryHandler` (bom/progress/scan_blocks via a job queue, see below; heightmap, owner), `BlockScan` (time-sliced section scan for `scan_blocks`), pure `Clusters` (cell-based clustering, incremental build, unit-tested) |
| `mod.lowpower` + `mod.mixin` | `LowPower` state + the `require = 0` mixins: Options, GameRenderer, SoundEngine, Minecraft (low power) and MultiPlayerGameMode (protection guard) |
| `mod.util` | `Inv` (inventory/menu slots, clicks), `Interact` (look, use block, attack, close menu, release keys), `McIds`, `Positions`, `Weapons` (id-tier ranking), `Recipes` (client recipe book lookups), `Reflect` (private fields by name), `RateLimiter`, `Signs` (sign texts / item frames attached to a container) |

## Threading

* Link reader/writer threads only touch `LinkClient` queues. `BotRuntime.send*`/`event` are thread-safe (Baritone logs from its own threads).
* Everything else runs on the client thread: `END_CLIENT_TICK` drains up to 200 inbound envelopes, then ticks connection → login → companion → life → eat → defense → tasks → queries → container sensor → status, each step in its own try/catch.
* Schematics are parsed on the `BaritoneBots schematic loader` daemon thread (`SchematicStore`); everything that reads the world stays on the client thread. `PlacedSchematic.desiredState` is also called from Baritone's path-calculation thread, so it only uses immutable data and a `ConcurrentHashMap`.
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
* `Fighter`: `approach` walks with `GoalNear(target,1)` (re-planned when the target moves 3+ blocks) and holds pause claim `<owner>:fight` once in reach (eyes→hitbox ≤ 3) → `IN_REACH`; unreachable after 3 failed paths. `engage` = approach + best weapon via `DefenseBehaviour.equipWeapon` + hit at charge ≥ 0.9.
* `AnimalPen`: `box` → AABB, animals of one type whose block position is inside the box (nearest first), `holdInMainHand(predicate)` (select on the hotbar or one SWAP click, 1/0/-1), `protectFences()` adds fences/gates/walls to `blocksToDisallowBreaking` for the task.
* `SettingsOverride`: `set(setting, value)` remembers the original once; `restore()` in `cleanup`.
* `DropCollector(AABB, globs)` collects inside an arbitrary area (the pen) besides the center/radius form.
* `Interact.useOnEntity(mc, player, entity, hand)`: right-click an entity (`MultiPlayerGameMode#interact(player, entity, EntityHitResult, hand)`).
* `Eater`: shared by auto-eat and the `eat` task; ticked by `EatBehaviour` only.
* `ctx.owner()` = pause-claim owner for the task.

## Adding a query

Add a `case` in `QueryHandler.answer` (and to `needsGame` if it reads the world). Kinds in `QueryKinds.ALL` that are not handled answer `ok:false, error:"unsupported"`.

Slow queries (`bom`, `progress`, `scan_blocks`) do not answer from `handle`: they are queued as `Job`s (`SchematicJob`, `ScanJob`; max 8 together, 120 s / 60 s timeout) and `QueryHandler.tick` advances the head job within 15 ms per tick (the first slice runs right away, so small jobs still answer in the same tick). A schematic job waits for `SchematicStore.load`, builds a `SchematicScan` over `footprint ∩ box` and scans whole chunk columns until the deadline; a scan job (`BlockScan`) resolves ids / block tags to a `Block` set once, walks chunk sections (skipping `hasOnlyAir` and sections whose palette cannot contain a target, `maybeHas`), feeds `Clusters`, then builds the clusters incrementally against the same deadline. The reply is sent when the job is done. Errors use the `"<reason>: <message>"` form (`bad_args: …`, `not_found: …`, `unsupported: …`, `not_in_game`, `busy: …`, `timeout`). `heightmap` and `owner` answer right away (≤ 37k columns: one chunk lookup per column run, cached).

## Owner commands and labels (SPEC §5.7e)

* `OwnerWatcher` is fed by Fabric's `ClientReceiveMessageEvents.CHAT` (player chat with a `GameProfile` sender; body = `PlayerChatMessage.signedContent()`, whisper = chat type `MSG_COMMAND_INCOMING`) and `GAME` (system lines, matched with `config.owner.patterns` before `LoginBehaviour` sees them; an owner line is never forwarded as `chat`). `OwnerChat` decides: the first pattern that matches names the speaker (so `<Mallory> Owner: !b x` is Mallory's), the prefix must be followed by a space or the end, `(?<dm>)` in a pattern marks whispers. 5 events per second.
* Owner position = the tracked `Player` entity (`level.players()`); look = `level.clip(new ClipContext(eye, eye + view·64, Block.OUTLINE, Fluid.NONE, player))` with `calculateViewVector(xRot, yHeadRot)`; `lookBlockId` from the hit block state.
* `Signs.near(level, pos, frames)`: both halves of a double chest (`ChestBlock.getConnectedBlockPos` when `TYPE != SINGLE`); `WallSignBlock.FACING` / opposite = support block; standing sign above, ceiling hanging sign below, wall hanging sign on a side, wall sign fixed to the block above; text = `SignBlockEntity.getText(front).getMessages(false)` (front + back). `Signs.frames(level, aabb)` maps `ItemFrame.getPos().relative(getDirection().getOpposite())` → item id (glow frames included).
* `drop` / `deposit` `slots:[{slot,item,count}]`: counts are relative to the stack size when the task started (`pickStart`), a slot whose item changed is skipped, so a pick list computed from an `inventory` answer never takes the wrong stack.

## Task notes (phase 2A)

* `craft`: recipe book first (`Recipes.forItem` reads `ClientRecipeBook.known` by reflection, falls back to `getCollections()`; only shaped/shapeless displays; `RecipeDisplayEntry.resultItems(SlotDisplayContext.fromLevel(level))`). `handlePlaceRecipe(containerId, displayId, false)` adds one set per request when the same recipe is already placed, so the task asks set by set (≥ 2 ticks apart, Paper's recipe spam limiter) and uses `useMaxItems` only when ≥ 64 crafts remain; one `QUICK_MOVE` on the result then crafts exactly what is placed. Manual `grid` fallback: `CraftGrid.planRound` + one `movePartial` per idle tick; the first round places one set to learn the yield. The grid is shift-clicked empty before each round and at the end; for the 2×2 grid a close packet for menu 0 is the last resort (the server's `InventoryMenu.removed` returns grid + cursor).
* `smelt_load`/`smelt_collect`: `AbstractFurnaceMenu` slots 0 input, 1 fuel, 2 result. Loaded/fuel/collected counts come from inventory totals before/after each phase, not from furnace slots (the furnace consumes while we click). A placement the slot refuses (non-fuel) is detected by the unchanged slot count.
* `drop`: `THROW` button 1 = whole stack, 0 = one item (checked in `AbstractContainerMenu.doClick`); `SlotMoves.throwMode` picks whole / singles / split-then-whole.
* `selection`: like Baritone's `#sel`: `FillSchematic(w,h,l, BlockOptionalMeta)`, wrapped in `WallsSchematic`/`ShellSchematic`/`ReplaceSchematic(…, BlockOptionalMetaLookup)`, `build(name, schematic, boxMin)`.
* `attack`/`guard` yield (release the hold) while the defense behaviour is engaged or the eater runs.

## Task notes (phase 2B)

* Schematic pipeline (`build`, `bom`, `progress`): `SchematicStore.load(file)` → Baritone `getSchematicSystem().getByFile(file)` → `format.parse(FileInputStream)` → `IStaticSchematic` (local 0,0,0 = min corner, same as common). `SchematicArgs.placement(s)` → `Placement` (mirror, then clockwise rotation about local 0,0,0, then origin — identical to common's `SchematicTransform`, unit-tested against it); block states are placed with `state.mirror(Mirror).rotate(Rotation)`. `Placement.region(box)` = footprint ∩ mask.
* Scans iterate world columns of the region and map back with `localX/localZ` (no per-block allocation); per distinct schematic state `StateTable` caches the placed state, the `BomRules` cost and the counters. Only states that occur are ever costed.
* `build` builds `PlacedSchematic(source, placement, region)` at `region.min`: Baritone only considers positions inside the schematic box, so cropping to the sector is the mask. For the task it forces `schematicOrientationX/Y/Z=false`, `buildOnlySelection=false`, `startAtLayer=0` and applies `buildInLayers`/`layerOrder` from the args. Builder paused → `SchematicScan` (progress mode, 10 ms per tick) → `missingVs(inventory)`.
* `breed`/`slaughter`/`shear` use `AnimalPen` + `Fighter.approach`; the food/shears go into the hand only once in reach (while walking Baritone may select tools). Feeding is verified by the food count dropping within 10 ticks, shearing by `Sheep#isSheared` flipping.
* `mine` with `strategy=legit` / `fakeOres` sets `legitMine`, `legitMineYLevel`, `legitMineIncludeDiagonals` through `SettingsOverride`; `FakeOreWatcher` ticks inside `MineTask.tick`.

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
* Entity type constants moved to `EntityTypes` (`EntityTypes.SHEEP`); animals live in sub-packages (`animal.sheep.Sheep`, `animal.equine.AbstractHorse`, `animal.cow.Cow`).
* `MultiPlayerGameMode#interact(Player, Entity, EntityHitResult, InteractionHand)` (one method, no separate `interactAt`); it sends the carried item first.
* Love mode is server-only: `Animal.inLove` is never synced (entity event 18 only spawns hearts), and the client's `AgeableMob#getAge` is just -1/1 from the baby flag, so the breeding cooldown is invisible. `Sheep#isSheared` (entity data) and `isBaby` are synced. `Animal#isFood` works client-side (item tags such as `ItemTags.COW_FOOD` are synced).
* `BlockStateBase#liquid()` is deprecated; check `getBlock() instanceof LiquidBlock`. `StateHolder#getValues()` is a `Stream<Property.Value<?>>`; read properties with `getProperties()` + `getValue(p)`.
* Baritone's implementation classes are obfuscated in the API jar (only `baritone.api.*` keeps names), so its internals cannot be reached by reflection by name.
* `ResourceLocation` is `net.minecraft.resources.Identifier`; block tags: `TagKey.create(Registries.BLOCK, Identifier)`, `BlockState.is(TagKey)` (tags are synced to the client). `ChunkAccess.getHeight(Heightmap.Types, x, z)` = y of the top block (WORLD_SURFACE / MOTION_BLOCKING are sent to clients). `BlockStateBase.blocksMotion()` is deprecated; use `getCollisionShape(level, pos).isEmpty()`.
* Fabric message API 7.0.8: `ClientReceiveMessageEvents.CHAT (Component, PlayerChatMessage?, GameProfile?, ChatType.Bound, Instant)`, `GAME (Component, boolean overlay)`.
