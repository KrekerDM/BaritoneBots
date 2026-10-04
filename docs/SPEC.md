# BaritoneBots — specification (v1)

This file is the contract between the four modules. Code that disagrees with it is a bug in the code or a bug here; fix one of them, never both silently.

## 0. Modules and versions

| Module | Gradle path | Artifact | Runs on | Java pkg |
|---|---|---|---|---|
| common | `:common` | bundled into the others | — | `io.github.krekerdm.baritonebots.common` |
| bot mod | `:bot-mod` | `baritonebots-botmod-<v>.jar` | each bot client: MC 26.2 + Fabric Loader 0.19.5 + Fabric API 0.161.0+26.2 + Baritone API 1.19.0 | `io.github.krekerdm.baritonebots.mod` |
| manager | `:manager` | `baritonebots-manager-<v>.jar` (fat, runnable) | the PC that hosts the bots, Java 25 | `io.github.krekerdm.baritonebots.manager` |
| companion plugin | `:companion-plugin` | `baritonebots-companion-<v>.jar` | Paper/Purpur 26.2 server, optional | `io.github.krekerdm.baritonebots.plugin` |

Toolchain: Gradle 9.8.0 wrapper, Java 25 (`options.release = 25`), Loom plugin `net.fabricmc.fabric-loom` 1.18.2 (26.x is unobfuscated: no mappings line, plain `implementation`/`compileOnly`, task `jar` not `remapJar`). Paper API `io.papermc.paper:paper-api:26.2.build.129-stable`. JSON everywhere is Gson (`com.google.gson`), which the MC client and Paper already ship.

```
 browser ──HTTP+SSE──▶ manager ──TCP JSON lines (127.0.0.1)──▶ bot mod ──Baritone API──▶ the bot
                          │  starts/stops bot JVMs via HeadlessMC          │
                          │                                                 └──plugin channel baritonebots:main──▶ companion plugin (optional)
```

The manager is the brain: queues, scenarios, projects, role assignment, world knowledge. The bot mod is an executor + sensor: it runs one task at a time, reports status, container contents and events. The companion plugin is optional and only adds server-side conveniences; every feature except those in §7 must work without it, on any server.

Not in scope (say so in docs): running several bots in one JVM (Baritone cannot), in-game GUI, teleporting bots, anticheat evasion. The README documents the LuckPerms one-liner for GrimAC exemption on servers you administrate.

## 1. Shared conventions

* Positions: `{"x":int,"y":int,"z":int}`. Boxes: `{"a":pos,"b":pos}` (inclusive corners, any order). Dimension ids: `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`.
* Item / block ids: full ids with namespace in JSON (`minecraft:diamond_pickaxe`). Inputs from users may omit `minecraft:`; normalise with `Ids.normalize(String)`.
* Glob patterns for items: `*` wildcard only, matched against the full id after normalisation (`minecraft:*_pickaxe`). `Ids.matches(glob, id)`.
* Times: epoch milliseconds (`long`). Durations: seconds unless the field name says `Ms` / `Ticks`.
* Every enum-ish string value is lower_snake_case.

## 2. Link protocol (manager ⇄ bot mod)

Transport: TCP, manager listens on `127.0.0.1:<linkPort>` (default 25590, configurable; bind address configurable for bots on another PC). One connection per bot client. UTF-8, one JSON object per line (`\n`), max line 4 MiB. Either side may close; the bot reconnects with backoff 1 s → 2 s → … → 15 s.

Envelope:
```json
{"t":"status","id":"m-12","re":null,"d":{...}}
```
`t` type (required), `id` sender-unique message id (optional; required for `query`), `re` id of the message this replies to (only on `result`/`reject`), `d` payload object.

The bot learns how to reach the manager from JVM system properties, set by the manager in HeadlessMC's `hmc.jvmargs`:

| property | example |
|---|---|
| `baritonebots.link` | `127.0.0.1:25590` |
| `baritonebots.secret` | random 32-char string |
| `baritonebots.botId` | `bot1` |
| `baritonebots.headless` | `true` |

If `baritonebots.link` is absent the mod stays dormant except for low-power options (useful when a human launches the mod by hand).

### 2.1 Handshake
Bot → `hello` d:
```json
{"protocol":1,"botId":"bot1","secret":"…","username":"Bot1","modVersion":"0.1.0","mcVersion":"26.2","baritoneVersion":"1.19.0","pid":1234}
```
Manager → `welcome` d: `{"config": BotConfig}` or → `reject` d: `{"reason":"bad_secret|unknown_bot|protocol"}` then closes.

### 2.2 Manager → bot

| t | d | effect |
|---|---|---|
| `config` | BotConfig | replace config, apply immediately (Baritone settings, behaviours, login) |
| `task` | TaskSpec | start task; replaces the current one (old one reports `task_done` with `reason:"cancelled"`) |
| `cancel` | `{"taskId":"…"}` | cancel if it is the current task |
| `stop` | `{}` | cancel task, `cancelEverything()`, release keys, close container |
| `chat` | `{"text":"…"}` | send chat; text starting with `/` is sent as a command |
| `connect` | `{"address":"host:port"?}` | connect to server (config address when absent) |
| `disconnect` | `{}` | leave the server, stay on title screen, disable auto-reconnect until next `connect` |
| `quit` | `{}` | graceful shutdown of the client (`Minecraft#stop`) |
| `query` | `{"kind":"…","args":{…}}` | answer with `result` (§2.4) |
| `plugin` | `{"payload":{…}}` | forward payload to the companion plugin (§7) |

### 2.3 Bot → manager

| t | d | when |
|---|---|---|
| `status` | BotStatus | every `status.activeIntervalTicks` while a task runs (default 20), every `status.idleIntervalTicks` otherwise (default 100), and immediately on state change |
| `task_done` | TaskResult | exactly once per started task |
| `event` | BotEvent | see §2.6 |
| `container` | ContainerSnapshot | 3 ticks after any container menu opens (contents synced) and again when it closes |
| `result` | `{"ok":bool,"error":"…"?,"data":{…}}` with `re` | reply to `query` |
| `plugin` | `{"payload":{…}}` | message received from the companion plugin |
| `log` | `{"level":"info|warn|error","message":"…","source":"baritone|mod"}` | Baritone log lines (via `Settings.logger`) and mod warnings; max 10 per second |

### 2.4 Queries

| kind | args | data |
|---|---|---|
| `inventory` | — | `{"slots":[{"slot":int,"item":id,"count":int,"damage":int,"maxDamage":int,"name":str?}], "armor":[id|null ×4 head..feet], "offhand":id|null, "selected":int}` |
| `entities` | `{"radius":int,"types":[id]?}` | `{"entities":[{"type":id,"name":str?,"x","y","z","health":float?,"baby":bool?,"player":bool}]}` |
| `containers_nearby` | `{"radius":int}` | `{"containers":[{"pos","dim","block":id}]}` — every chest, trapped chest, barrel, shulker box, furnace, blast furnace, smoker, crafting table, hopper, dispenser, dropper within radius (loaded block entities + crafting tables by block scan) |
| `bom` | `{"file":abs path,"origin":pos,"rotation":0|90|180|270,"mirror":"none|front_back|left_right","box":Box?}` | `{"items":{id:count},"blocks":int}` — items needed for the (masked) schematic, using MC rules (double slab = 2 slabs, upper door/bed head/upper tall-plant halves count 0, etc.) |
| `progress` | same args as `bom` | `{"total":int,"correct":int,"missing":int,"wrong":int,"unloaded":int,"remaining":{id:count},"sectorBox":Box?}` — compares world with schematic for positions in loaded chunks only |
| `block_at` | `{"pos"}` | `{"block":"minecraft:oak_stairs[facing=north,…]","loaded":bool}` |
| `player` | `{"name":str}` | `{"found":bool,"pos":pos?,"dim":id?}` (only players in the bot's tracked range) |
| `recipe_book` | `{"item":id}` | `{"recipes":[{"displayId":int,"craftingTable":bool}]}` known to the bot's client recipe book |

### 2.5 Payload types (Java records in `common.msg`)

```text
BotConfig {
  botId, username,
  server   { address, autoConnect:bool, reconnect{enabled:bool, delaySec:int, maxDelaySec:int} },
  login    { mode:"none|auto", password, loginCommand:"/login {password}", registerCommand:"/register {password} {password}",
             loginPatterns:[regex], registerPatterns:[regex], successPatterns:[regex], failurePatterns:[regex],
             delayMs:int, joinCommands:[str] },
  companion{ enabled:bool, token },
  behaviour{ autoRespawn:bool,
             autoEat{ enabled, belowFood:int, belowHealth:float, avoid:[glob] },
             defense{ mode:"fight|flee|ignore", radius:int, fleeBelowHealth:float, avoidEntities:[id], avoidNamePatterns:[regex], retaliatePlayers:bool },
             deathRecovery{ enabled, maxDistance:int, timeoutSec:int },
             inventoryFullFreeSlots:int, lowToolDurability:float, pickupRadius:int },
  protection{ enabled:bool, noBreak:[glob], zones:[{dim, box}] },
  baritone { settingName: value, … },       // applied by reflection on BaritoneAPI.getSettings(); unknown names logged and skipped
  client   { lowPower:bool, skipRender:bool, muteSounds:bool, maxFps:int, renderDistance:int },
  status   { activeIntervalTicks:int, idleIntervalTicks:int }
}

TaskSpec   { id, type, args:{…}, timeoutSec:int (0 = none), label:str?, origin:str? }   // origin: "panel", "scenario:<runId>", "project:<id>"
TaskResult { id, type, ok:bool, reason:str?, message:str?, data:{…}?, durationMs:long }
BotStatus  { botId, username, state, server:str?, dim:str?, pos:{x,y,z doubles}?, yaw, pitch,
             health, maxHealth, food, saturation, xpLevel, armor:[id|null ×4], mainHand:id?, offhand:id?,
             freeSlots:int, items:{id:count} (whole inventory summed),
             task:{id,type,label,state:"running|paused",step:str,progress:double (-1 unknown)}?,
             baritone:{process:str?, pathing:bool, goal:str?, eta:double?},
             perf:{heapUsedMb, heapMaxMb, cpu:double 0..1, fps:int, pingMs:int}, uptimeSec, time }
             state ∈ starting|menu|connecting|logging_in|online|dead|disconnected
BotEvent   { kind, level:"info|warn|error", message, data:{…}?, time }
ContainerSnapshot { dim, pos, block:id, size:int, free:int, items:[{slot,item,count}], time, open:bool }
```

### 2.6 Event kinds (`BotEvent.kind`)
`joined` · `disconnected` (data.reason) · `kicked` (data.reason) · `login_ok` · `login_failed` · `death` (data.pos, dim, cause) · `respawned` · `damaged` (only when health drops ≥ 4 in 1 s; data.source) · `threat` (hostile/boss nearby; data.type, pos) · `inventory_full` · `tool_low` (data.item, durabilityLeft) · `food_low` (no food in inventory) · `chat` (system or whisper lines matching nothing else; data.text) · `companion` (data.state = `verified|rejected|absent`) · `error`.

## 3. Task catalog (executed by the bot mod)

Common result reasons when `ok:false`: `cancelled`, `timeout`, `path_failed`, `not_found`, `inventory_full`, `container_failed`, `missing_materials`, `stuck`, `died`, `disconnected`, `bad_args`, `unsupported`, `error`.

Every task: start on the client thread, update `step`/`progress`, never block the thread, release keys and close menus in `cancel`. A task that needs exclusive control while Baritone is idle uses the mod's pause process (§4.3).

| type | args | done / result |
|---|---|---|
| `goto` | `x`,`z`,`y?`,`range?=2` | in goal → ok; process stopped elsewhere → `path_failed` |
| `goto_player` | `player`,`range?=3` | ok on arrival; `not_found` if the player is not tracked |
| `follow` | `player`,`radius?=3` | continuous until cancelled/timeout |
| `explore` | `x?`,`z?` | continuous (Baritone explore) |
| `baritone` | `command` (no `#`) | ok once no Baritone process has been in control for 40 ticks after start; `bad_args` if `execute` returns false |
| `mine` | `blocks:[id]`, `amount?=0` (0 = until stopped), `minY?`, `maxY?` | MineProcess inactive → ok (data.collected); `not_found`; `inventory_full` when free slots ≤ `inventoryFullFreeSlots` |
| `farm` | `center:pos`, `range?=20`, `durationSec?=0` | duration elapsed / nothing to do → ok; `inventory_full` |
| `selection` | `op:"clear|fill|walls|shell|replace"`, `box`, `block?`, `from?:[id]` | Baritone builder with `baritone.api.schematic.*`; inactive → ok; paused → `missing_materials` (data.missing) or `stuck` |
| `build` | `file`, `origin`, `rotation?=0`, `mirror?="none"`, `box?` (world-space mask = this bot's sector), `name?` | builder inactive & not paused → ok; paused → `missing_materials` with data.missing `{id:count}` computed for the masked region vs inventory |
| `collect_drops` | `radius?=8`, `items?:[glob]` | no matching item entities left → ok |
| `take` | `container:pos`, `items:[{item:glob,count:int(-1 = all)}]` | ok with data `{taken:{id:count}, missing:{glob:count}}` (missing is not a failure) |
| `deposit` | `containers:[pos]`, `keep?:[glob]`, `keepCounts?:{glob:count}`, `only?:[glob]` | ok with data `{moved:{id:count}, left:{id:count}}` |
| `transfer` | `from:pos`, `to:[pos]`, `items:[{item,count}]` | take + deposit in one task |
| `inspect` | `containers:[pos]` | opens each, snapshots (§2.3 `container`), closes; ok with data `{seen:int,failed:[pos]}` |
| `equip` | `armor?=true`, `offhand?:glob` | equips best armor (by protection then durability) from inventory |
| `drop` | `items:[{item:glob,count}]` | throws items |
| `craft` | `item`, `count`, `table?:pos`, `grid?:[[ [id…] or null ]×3]×3` | recipe book first (`handlePlaceRecipe`), else manual placement from `grid` (each cell = list of acceptable ids). ok data `{crafted:int}`; `missing_materials` |
| `smelt_load` | `furnace:pos`, `input:glob`, `count`, `fuel:glob`, `fuelCount` | ok data `{loaded:int, fuel:int, collected:{id:count}}` |
| `smelt_collect` | `furnace:pos`, `all?=false` | ok data `{collected:{id:count}}` |
| `breed` | `box`, `animal:id`, `food?:id`, `max?=20` | no eligible pair left or population ≥ max → ok data `{fed:int, population:int}` |
| `slaughter` | `box`, `animal:id`, `keep?=4` | ok data `{killed:int}`; then `collect_drops` inside box |
| `shear` | `box` | ok data `{sheared:int}` |
| `guard` | `center:pos`, `radius?=12` | continuous: stay inside radius, attack hostiles |
| `attack` | `radius?=8`, `types?:[id]` | no matching hostile left → ok |
| `recover` | `pos`, `dim`, `radius?=6` | goto then collect_drops; ok data `{picked:{id:count}}` |
| `eat` | — | eats if any allowed food; `not_found` otherwise |
| `idle` | — | ok immediately |

Rotation/mirror for `build`, `bom`, `progress`: rotate the schematic about its origin corner the way Litematica/WorldEdit paste does (clockwise when viewed from above), rotating block states with `BlockState#rotate(Rotation)` / `#mirror(Mirror)`. Schematics are loaded with Baritone's `BaritoneAPI.getProvider().getSchematicSystem()` (formats `.schem`, `.schematic`, `.litematic`).

## 4. Bot mod internals

### 4.1 Threads
* Link reader thread → `ConcurrentLinkedQueue<Envelope>` → drained on `ClientTickEvents.END_CLIENT_TICK` (client thread).
* Link writer thread with its own queue; `LinkClient.send(...)` is thread-safe and never blocks.
* Everything touching Minecraft or Baritone runs on the client thread.

### 4.2 Task framework (package `mod.task`)
```java
public interface TaskExecutor {
    void start(TaskContext ctx);   // client thread, once
    void tick(TaskContext ctx);    // client thread, every tick until ctx.isFinished()
    void cancel(TaskContext ctx);  // client thread; must leave the client in a neutral state
}
```
`TaskContext` exposes `spec()`, `args()`, `mc()`, `baritone()`, `ticks()`, `step(String, double)`, `succeed(String msg, JsonObject data)`, `fail(String reason, String msg, JsonObject data)`, `isFinished()`, `bot()` (the BotRuntime: link, config, helpers). `TaskRegistry.register(type, Supplier<TaskExecutor>)`. Timeout handled by the framework.

### 4.3 Behaviours (always on, ordered)
Login → reconnect → respawn → death recovery hint → eat → defense → protection guard → status reporter. A behaviour that needs exclusive control (eat, fight, open container) claims the `PauseProcess`: a custom `IBaritoneProcess` registered with `getPathingControlManager().registerProcess(...)`, `priority()` higher than Baritone's built-ins, `isActive()` while claimed, `onTick` returns `new PathingCommand(null, PathingCommandType.REQUEST_PAUSE)`.

### 4.4 Low power (`client.lowPower`)
Mixins (all `require = 0` so a signature change disables the optimisation instead of crashing): skip `GameRenderer#render` when headless and no screen/overlay; skip `SoundEngine` tick/play when `muteSounds`; force options in `Options.<init>` RETURN: renderDistance, framerate limit, particles minimal, clouds off, sound volumes 0, `pauseOnLostFocus=false`, `onboardAccessibility=false`, tutorial off. If the frame limiter does not sleep headless, cap the loop with a sleep in `Minecraft#runTick` TAIL to `maxFps`.

### 4.5 Container sensing
Every time `player.containerMenu` changes to a non-inventory menu that belongs to a block the bot just interacted with (remember the last `useItemOn` position), send `container` 3 ticks later and on close.

## 5. Manager

### 5.1 Process
`java -jar baritonebots-manager.jar [--data <dir>] [--no-tray] [--no-browser]`. Data dir default: `./BaritoneBots-data`. All state mutations happen on ONE thread, the manager loop (`ManagerLoop`, a single-thread `ScheduledExecutorService`). IO threads (HTTP, link sockets, process watchers) post work to it; HTTP handlers wait for results with a 10 s timeout. No other locking.

### 5.2 Data directory
```
config.json        settings (§5.3) — written atomically (tmp + move)
secrets.json       panel token, link secret, companion tokens per server, bot passwords
world/<serverId>.json   waypoints, areas, containers, zones, deaths
projects/<id>.json
scenarios.json  kits.json  queues.json
schematics/        uploaded .schem / .litematic
events.jsonl       event log, rotated at 5 MB, 3 files kept
runtime/headlessmc/headlessmc-launcher-<v>.jar
runtime/mc/        shared hmc.mcdir (versions, libraries, assets)
runtime/mods/      downloaded mod jars (cache, verified by sha512 when known)
bots/<botId>/HeadlessMC/config.properties
bots/<botId>/game/{mods/, options.txt, baritone/settings.txt}
bots/<botId>/logs/launcher.log
```

### 5.3 Settings (`config.json`) — every field has a default; the panel edits all of them
```text
general  { language:"ru|en", ownerPlayer:str, panel{bind:"127.0.0.1", port:8765, openBrowser:true}, link{bind:"127.0.0.1", port:25590},
           tray:bool, autoStartBots:bool, eventLogLimit:int }
runtime  { minecraftVersion:"26.2", fabricLoader:"0.19.5", headlessmcVersion:"2.10.0", headlessmcUrl, javaPath:str? (null = the manager's own java if ≥ 25, else HeadlessMC auto-download),
           mods:[{id, name, url, sha512?, enabled}],   // fabric-api, baritone-api, ferritecore; our mod is always added from the manager jar
           startStaggerSec:int=20, priority:"normal|below_normal|idle", restart{enabled, delaySec, maxPer10Min},
           memoryPreset:"eco|normal|performance|custom", memoryMb:int, jvmArgs:str, gcThreads:int, maxHeavyTasks:int (concurrent mine/explore/build, 0 = unlimited) }
servers  [ { id, name, address, login:{mode, loginCommand, registerCommand, loginPatterns, registerPatterns, successPatterns, failurePatterns, delayMs, joinCommands},
             companion:{enabled, token}, protection:{enabled, noBreak, zones}, baritone:{overrides} } ]
bots     [ { id, username, account:{type:"offline|microsoft"}, serverId, enabled, autoStart, memoryMb?, jvmArgs?, homeWaypoint?,
             roles:[role] (empty = any), behaviour:{overrides}, baritone:{overrides} } ]
behaviour{ defaults for BotConfig.behaviour }
baritone { defaults for BotConfig.baritone }   // shipped defaults = the low-load set from docs/RESEARCH-NOTES
client   { defaults for BotConfig.client }
planner  { tickSec:int=5, roleSwitchCooldownSec:int=120, maxBuildersPerSector:int=1, restockFreeSlotsTarget:int=4, autoDepositWhenFull:bool }
```
Memory presets: eco 768 MB, normal 1024 MB, performance 2048 MB (Xmx per bot). Roles: `builder, miner, lumberjack, farmer, smelter, crafter, sorter, rancher, hauler, guard`.

### 5.4 Bot lifecycle
`stopped → installing → starting (process up) → linked (hello ok) → online (joined server) → …`; `crashed` when the process dies without a `quit`. Restart policy from `runtime.restart`. Stop = send `quit`, wait 15 s, then kill the process tree (`ProcessHandle.descendants()`). Starting several bots staggers them by `startStaggerSec`. HeadlessMC launch: working dir `bots/<id>`, `java -Xmx64m -XX:+UseSerialGC -jar <hmc jar> --command launch fabric:<mc> -lwjgl -offline -noout` for offline accounts (HMC per-bot `config.properties` carries `hmc.mcdir`, `hmc.gamedir`, `hmc.offline=true`, `hmc.offline.username`, `hmc.always.lwjgl.flag=true`, `hmc.assets.dummy=true`, `hmc.jvmargs` incl. the §2 system properties, `hmc.gameargs=--quickPlayMultiplayer <address>`, `hmc.java.versions`). One-time install (`fabric <mc> --uid <loader>`, then `launch fabric:<mc> -lwjgl -offline -prepare`) runs before the first start and never in parallel. Microsoft accounts: run HeadlessMC's `login` flow in the bot dir, surface the device URL/code to the panel.

### 5.5 Queues, scenarios, kits
* Per bot: `current` + `queue` of TaskSpec templates. Add modes `append|front|replace`. When a task finishes the next one is dispatched. Manual tasks pause project work for that bot until the queue is empty.
* Manager-side step types (expanded at dispatch time, never sent to the bot): `kit <kitId>`, `deposit_storage`, `home`, `wait <sec>`, `goto_waypoint <name>`, `sort_storage`, `smelt_all`.
* Scenario: `{id, name, steps:[TaskTemplate | managerStep], repeat:bool}`; a run expands into the queue with `origin = scenario:<runId>`; on failure with `inventory_full` the manager inserts `deposit_storage` and retries the step (max 50 per run); other failures stop the run.
* Kit: `{id, name, slots:{slotName:{any:[glob], count:int}}}` planned against the container index (containers with role `kit`, then `storage`): skip slots already satisfied by the bot's inventory/armor (position in `any` = rank), produce `take` tasks grouped per container ordered nearest-neighbour, then `equip`. Unknown container contents → `inspect` first.

### 5.6 World knowledge (per server profile)
Waypoints `{name, dim, pos}` (`home` is special) · Areas `{name, dim, box}` · Containers `{id, dim, pos, block, roles:[kit|storage|supply|fuel|inbox|sorted:<category>|furnace|crafting], label, snapshot, lastSeen}` — snapshots from any bot's `container` message update it · Protected zones · Death log.

### 5.7 Projects and automatic roles
Project kinds: `build`, `gather` (item quotas into storage), `clear` (area), `farm` (field + deposit), `ranch` (pen), `sort` (inbox → categories), `smelt` (furnace array). A project has `bots:[botId] | "any"`, status `draft|running|paused|done|failed`, kind-specific config and progress.

Planner tick (`planner.tickSec`): every running project emits work items `{kind, role, priority, project, needs:{items,tools}, location, lock?}`; idle bots (empty queue, online, allowed role) are matched to the best item by `priority − distance/64 − (roleChanged ? 2 : 0)`; a bot keeps its role at least `roleSwitchCooldownSec`. Locks: a container is used by one bot at a time; a build sector by `maxBuildersPerSector` bots.

`build` (the 2b2t-style flow):
1. Load schematic, placement `{origin, rotation, mirror}`, compute BOM via a bot `bom` query (cached).
2. Split footprint into sectors (vertical columns, longer axis first) — count = number of bots allowed to build, min sector width 4, re-split when bots join/leave; idle builders steal unfinished sectors.
3. Builder loop: `progress` query for its sector → materials needed for the sector's remaining blocks (bottom layers first) ∩ supply containers → `take` from supply up to free slots minus `restockFreeSlotsTarget` → `build` with `box` = sector → on `missing_materials` restock; on ok verify with `progress`; final pass over the whole build.
4. Deficit per item = remaining BOM − supply stock − builders' inventories − in transit. Each deficit resolves to: haul from storage → mine (blocks whose loot drops the item, from game data) → craft (recipe from game data, ingredients recurse) → smelt → else `manual` (shown in the panel with the count needed; honest mode only, nothing is spawned).
5. Gather/craft/smelt/haul work items deliver into the project's `supply` containers.

Game data: parsed from the client jar `runtime/mc/versions/<mc>/<mc>.jar` (`data/minecraft/recipe/*.json`, `data/minecraft/tags/item/**`, `data/minecraft/loot_table/blocks/*.json`), so recipes and drops follow the actual game version. Without the jar, the planner only knows "item id = mineable block id".

### 5.7a Autopilot (auto-supply, auto-sort, idle work)
The user wants bots to fetch what they need and keep storage sorted without being told. Settings section `autopilot { supply:bool, sort:bool, idleWork:bool, foodMin:int, blocksMin:int, toolMinDurability:float, categories:[{name, globs}] }`, per-bot override `bots[].autopilot`.

* **Auto-supply.** Before dispatching any task the manager compares the task's needs with the bot's inventory (`status.items`, armor, tool durability) and inserts `take` tasks in front: the right tool for `mine`/`farm`/`selection` (pickaxe/shovel/axe/hoe by target block; best tier available), food when below `foodMin`, throwaway blocks for building/pathing below `blocksMin`, materials for `build`/`selection fill`, fuel and inputs for `smelt_load`, ingredients for `craft`. Sources: containers in the index with role `storage`, `kit` or `supply`, nearest first; unknown contents → `inspect` first. Events `tool_low` and `food_low` trigger a refill at the next safe point. Missing items → event `manual` with the list, the task still runs if it can.
* **Auto-sort.** Containers with role `inbox` receive loot (`deposit_storage` targets inboxes first when any exist). Category chests carry role `sorted:<category>`; categories come from `autopilot.categories` (defaults: ores & ingots, wood, stone & building, food, tools & armor, redstone, farming, mob drops, misc) using item tags from game data where available. Unassigned chests with role `storage` adopt the category of the majority of what they already hold. A full category chest overflows to the next chest of the same category, then to `misc`. Sorting work items are emitted whenever an inbox snapshot is non-empty; any idle bot (or one with role `sorter`) runs `transfer` tasks.
* **Idle work.** With `idleWork`, an idle bot without manual tasks or project work picks, in order: empty inboxes (sort), collect finished furnaces, refill furnaces with queued inputs, return to `home`.

### 5.7b Standing orders, schedules, rules
* **Standing orders** ("keep in stock"): `{item, min, max?, into: role|container}` — the planner keeps the stock between min and max by emitting gather / craft / smelt work items whenever the indexed stock drops below min (e.g. keep 128 torches, 2 stacks of iron ingots, 64 bread, furnace fuel). Same resolution chain as build deficits (§5.7 step 4).
* **Schedules**: cron-like `{when:"*/30 * * * *" | "night" | "day", botIds|"any", steps:[…]}` — e.g. harvest the farm every 30 min, sort storage hourly, go home and stop at night.
* **Rules** ("if → then"), evaluated on events and snapshots: `{if:{event:kind | containerFull:pos | itemBelow:{item,count} | playerOnline:name | healthBelow:n}, then:[steps], cooldownSec}`. Examples: container full → empty it into storage; health below 6 → go home; tool_low → fetch a new tool.
* **Stuck recovery**: no position change and no Baritone progress for `stuckSec` (default 60) while a task runs → cancel, step back 3 blocks, retry once, then fail with `stuck`.

### 5.7b2 Goals: `obtain` and tech progression
Manager-side step `obtain {item, count}` for one bot, re-planned after every finished task from the bot's live inventory: have it → done; else take from storage → craft (ingredients recurse) → smelt (needs furnace + fuel; furnace itself is an `obtain minecraft:furnace` if none indexed nearby) → mine blocks whose loot drops it **with the required tool tier** (block tags `mineable/pickaxe|axe|shovel|hoe`, `needs_stone_tool`, `needs_iron_tool`, `needs_diamond_tool` from the client jar's `data/minecraft/tags/block/`; missing tool → `obtain` that tool first). Crafting tables are placed when none is within 16 blocks. Presets built on it: `progress wood|stone|iron|diamond` = tools + armor of that tier + 32 food + torches. A cycle guard stops after 200 planning steps with the missing items listed.

### 5.7b3 Anti-xray servers
Server profile `antiXray: "none" | "hide" | "fake"` (Paper anti-xray engine-mode 1 hides ores as stone, mode 2 sprinkles fake ores). When not `none`, `mine` switches Baritone to legit mining (`legitMine true`, plus `legitMineYLevel` / diagonal settings if present in 1.19.0 — verify) so a bot only goes for ores it can actually see and otherwise branch-mines at the best Y for the target (26.x ore distribution: diamond −58, redstone −58, gold −16, lapis 0, iron 16, copper 48, coal 96; emerald only in mountains → `explore`). With `fake`, an ore that turns into stone when exposed is ignored (blacklist the position for that task). `mine` results report `strategy: "legit"` so the panel can show it.

### 5.7c Optional local AI command box (off by default)
Only as a convenience layer, never in the control loop: the planner stays deterministic. A text box in the panel ("собери 64 железа и сложи в склад") is sent to a local model through Ollama's HTTP API (`http://127.0.0.1:11434/api/chat`, `format` = JSON schema built from /api/catalog), which returns a list of tasks/manager steps for chosen bots — preferably high-level ones (`obtain`, `progress`, standing orders) so the deterministic planner does the tech tree and watches the inventory, e.g. "развиться до железки" → `progress iron`. The panel shows the parsed plan and runs it only after the user presses Confirm. Settings `ai { enabled:false, endpoint, model:"qwen2.5:7b-instruct", timeoutSec:30 }`. No cloud calls, no keys. If Ollama is not running the box shows that and nothing else changes.

### 5.7d Optional AI supervisor (on top of the planner, off by default)
The user wants to hand over one project and have it distributed and watched in real time. Split of duties: the deterministic planner keeps doing second-by-second assignment (every `planner.tickSec`); the AI supervisor (same local Ollama endpoint as §5.7c) works at the human level:
* **Project from text.** "Построй замок из castle.schem у дома всеми ботами, ресурсы честно" → a project draft (kind, schematic, placement from waypoints, bots, supply containers, standing orders) shown for confirmation.
* **Watching.** Every `ai.superviseSec` (default 90) and immediately on `blocked` / repeated `stuck` / `death` events it receives a compact JSON digest (progress, rate, ETA, BOM deficits with sources, assignments, last 30 events) and returns `{summary, actions[]}`.
* **Actions** come only from a whitelist the manager validates: `set_priority`, `reassign {bot, role}`, `add_standing_order`, `pause_project` / `resume_project`, `add_task {bot, step}`, `notify {text}`. Mode `suggest` (default: actions wait for a click in the panel) or `auto` (whitelisted actions apply immediately, each logged with the reason).
* **Panel**: a "Диспетчер" feed per project with the AI's summary in plain language ("43 %, Bot2 ждёт стекло, песка нет, Bot4 переведён на песок, ETA 25 мин"), pending suggestions with Apply / Dismiss, and the action log.
If the model is unreachable or returns invalid JSON, the planner continues alone and the feed says so.

### 5.8 Notifications
Events go to `events.jsonl`, the panel (SSE) and the tray tooltip/balloon for `warn`/`error` (bot died, crashed, project blocked on manual materials, project done).

## 6. HTTP API (manager)

Base `http://<panel.bind>:<panel.port>`. Static panel at `/`. JSON API under `/api`. Auth: header `Authorization: Bearer <panelToken>` on every `/api` call (SSE: `?token=` is accepted only for `/api/stream` because EventSource cannot set headers). Errors: `{"error":"code","message":"…"}` with 4xx/5xx.

```
GET    /api/state                         full snapshot: version, bots[], projects[], servers[], runtime, eventsTail[]
GET    /api/stream?token=                 SSE: event names bot | queue | process | event | project | runtime | log
GET    /api/catalog                       task types + arg schema + manager-side steps + roles (for forms)
GET    /api/i18n/{lang}

GET    /api/settings                      config.json + schema (types, defaults, min/max, unit, description per field)
PUT    /api/settings                      partial update (JSON merge patch)

GET/POST        /api/servers              list / create
PUT/DELETE      /api/servers/{id}
GET/POST        /api/bots                 list / create
PUT/DELETE      /api/bots/{id}
POST   /api/bots/{id}/start|stop|restart|kill|connect|disconnect
POST   /api/bots/{id}/chat                {text}
POST   /api/bots/{id}/microsoft-login     → {url, code}; completion arrives as an event
GET    /api/bots/{id}/log?lines=200
GET    /api/bots/{id}/inventory           live query
POST   /api/bots/{id}/query               {kind,args} → result
POST   /api/bots/{id}/plugin              {payload}

POST   /api/bots/{id}/tasks               {task:TaskTemplate, mode}
DELETE /api/bots/{id}/tasks/{taskId}
POST   /api/bots/{id}/tasks/reorder       {ids:[…]}
POST   /api/bots/{id}/cancel              cancel current task
POST   /api/bots/{id}/clear               cancel current + clear queue
POST   /api/tasks/batch                   {botIds:[…], task, mode}
POST   /api/bots/start-all | stop-all

GET/POST/PUT/DELETE  /api/scenarios[/{id}]     POST /api/scenarios/{id}/run {botIds, repeat}
GET/POST/PUT/DELETE  /api/kits[/{id}]
GET/PUT  /api/world/{serverId}                 waypoints, areas, containers, zones, deaths
POST     /api/world/{serverId}/discover        {botId, radius} → containers_nearby via a bot, merged into the index
GET      /api/schematics                       list with dims + block count
POST     /api/schematics?name=<file>           raw body upload
DELETE   /api/schematics/{name}
GET/POST/PUT/DELETE  /api/projects[/{id}]      POST /api/projects/{id}/start|pause|resume|stop
GET      /api/runtime                          install state, versions, disk use
POST     /api/runtime/install                  (re)install HeadlessMC, Fabric, mods; progress via SSE `runtime`
GET      /api/events?limit=&bot=&level=
```

## 7. Companion plugin protocol (bot mod ⇄ Paper plugin, optional)

Channel `baritonebots:main`, raw UTF-8 JSON, no framing, same envelope as §2 (without line breaks). Client → server ≤ 32 767 bytes, server → client ≤ 1 MiB. The mod registers both directions with `PayloadTypeRegistry.serverboundPlay()/clientboundPlay()` and a raw-bytes `StreamCodec`.

| dir | t | d |
|---|---|---|
| bot → plugin | `hello` | `{"token","botId","modVersion"}` sent once after join (retry every 2 s for 30 s until `welcome`/`reject`) |
| plugin → bot | `welcome` | `{"features":["distance","login","journal","rollback","permissions"],"server":str}` |
| plugin → bot | `reject` | `{"reason"}` |
| bot → plugin | `rollback` | `{"target":username,"minutes":int}` → plugin answers `rollback_result {"ok","restored":int,"via":"journal|command","message"}` |
| bot → plugin | `journal` | `{"target":username,"minutes":int}` → `journal_result {"breaks":int,"places":int,"since":long}` |
| plugin → bot | `notice` | `{"message"}` |

Plugin config: `token`, `bots:[name]`, `protect-names{enabled, allowed-ips}` (AsyncPlayerPreLoginEvent), `distances{view, simulation, send, affects-spawning}`, `permissions:[node]` granted to verified bots with a PermissionAttachment, `force-login` (AuthMe / nLogin by reflection), `journal{enabled, retention-days}`, `rollback{mode:"journal|command", command:"..."}` (command template with `{player}` and `{minutes}`, e.g. for Prism). Commands: `/baritonebots status|reload|rollback <bot> <minutes>|journal <bot> <minutes>`, permission `baritonebots.admin`.
