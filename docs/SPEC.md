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
| `containers_nearby` | `{"radius":int}` | `{"containers":[{"pos","dim","block":id,"signText":str?,"frameItem":id?}]}` — every chest, trapped chest, barrel, shulker box, furnace, blast furnace, smoker, crafting table, hopper, dispenser, dropper within radius (loaded block entities + crafting tables by block scan); `signText` / `frameItem` as in `ContainerSnapshot` (§2.5) |
| `bom` | `{"file":abs path,"origin":pos,"rotation":0|90|180|270,"mirror":"none|front_back|left_right","box":Box?}` | `{"items":{id:count},"blocks":int}` — items needed for the (masked) schematic, using MC rules (double slab = 2 slabs, upper door/bed head/upper tall-plant halves count 0, etc.) |
| `progress` | same args as `bom` | `{"total":int,"correct":int,"missing":int,"wrong":int,"unloaded":int,"remaining":{id:count},"sectorBox":Box?}` — compares world with schematic for positions in loaded chunks only |
| `block_at` | `{"pos"}` | `{"block":"minecraft:oak_stairs[facing=north,…]","loaded":bool}` |
| `player` | `{"name":str}` | `{"found":bool,"pos":pos?,"dim":id?}` (only players in the bot's tracked range); without `name`: `{"online":[name]}` = the server's player list (tab list), for owner detection (§5.7e) |
| `recipe_book` | `{"item":id}` | `{"recipes":[{"displayId":int,"craftingTable":bool}]}` known to the bot's client recipe book |
| `owner` | `{"name":str?}` (default `config.owner.player`) | `{"found":bool,"pos":pos?,"dim":id?,"yaw":float?,"pitch":float?,"lookBlock":pos?,"lookBlockId":id?}` — the player's tracked entity: block position, head yaw / pitch, and the first block outline hit by a ray from the eyes along yaw / pitch within 64 blocks (`ClipContext.Block.OUTLINE`, fluids ignored), else `null` |
| `scan_blocks` | `{"center":pos?,"radius":int ≤ 96,"dy":int?,"ids":[glob]?,"tags":[block tag]?,"gap":int 1..8 = 2,"maxClusters":int ≤ 256 = 32}` | `{"clusters":[{"box":Box,"count":int,"ids":{id:count}}],"total":int,"truncated":bool,"unloadedChunks":int}` — matching blocks in the loaded chunks of `center ± radius` (vertically `± dy`, default radius; centre default = the bot), grouped into clusters: cubic cells of `gap` blocks joined when they touch (26-neighbourhood); largest first. Computed across ticks within 15 ms per tick (same queue as `bom` / `progress`); at most 50 000 cells (`truncated`) |
| `heightmap` | `{"center":pos?,"radius":int ≤ 96 = 32}` | `{"x0","z0","size":int,"heights":[int|null],"surface":str,"unloaded":int}` — row-major (z outer, x inner) `size × size` grid from `x0,z0`: y of the top block of the client's WORLD_SURFACE heightmap, below replaceable plants / snow layers; `null` = chunk not loaded; `surface` one char per column: `.` ground, `w` liquid, `t` leaves / logs, `?` not loaded |

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
  protection{ enabled:bool, noBreak:[glob], zones:[{dim, box}], built:[glob] },   // §5.7g
  baritone { settingName: value, … },       // applied by reflection on BaritoneAPI.getSettings(); unknown names logged and skipped
  client   { lowPower:bool, skipRender:bool, muteSounds:bool, maxFps:int, renderDistance:int },
  status   { activeIntervalTicks:int, idleIntervalTicks:int },
  owner    { player:str ("" = off), prefix:"!b", patterns:[regex] }   // §5.7e; from general.ownerPlayer / commandPrefix / ownerChatPatterns
}

TaskSpec   { id, type, args:{…}, timeoutSec:int (0 = none), label:str?, origin:str? }   // origin: "panel", "scenario:<runId>", "project:<id>"
TaskResult { id, type, ok:bool, reason:str?, message:str?, data:{…}?, durationMs:long }
BotStatus  { botId, username, state, server:str?, dim:str?, pos:{x,y,z doubles}?, yaw, pitch,
             health, maxHealth, food, saturation, xpLevel, armor:[id|null ×4], mainHand:id?, offhand:id?,
             freeSlots:int, items:{id:count} (whole inventory summed),
             task:{id,type,label,state:"running|paused",step:str,progress:double (-1 unknown)}?,
             baritone:{process:str?, pathing:bool, goal:str?, eta:double?},
             perf:{heapUsedMb, heapMaxMb, cpu:double 0..1, fps:int, pingMs:int}, uptimeSec, time, dayTime:long? (world day time 0..23999, overworld clock) }
             state ∈ starting|menu|connecting|logging_in|online|dead|disconnected
BotEvent   { kind, level:"info|warn|error", message, data:{…}?, time }
ContainerSnapshot { dim, pos, block:id, size:int, free:int, items:[{slot,item,count}], time, open:bool,
                    signText:str? (lower-cased text of the signs on / at the container, null = none), frameItem:id? (item in a frame on it) }
```
A sign counts for a container when it is a wall (or wall hanging) sign fixed to it, a standing sign on top of it, a ceiling hanging sign under it, or a wall sign fixed to the block right above it; both halves of a double chest count, and front and back texts are joined. An item frame counts when it hangs on the container.

### 2.6 Event kinds (`BotEvent.kind`)
`joined` · `disconnected` (data.reason) · `kicked` (data.reason) · `login_ok` · `login_failed` · `death` (data.pos, dim, cause) · `respawned` · `damaged` (only when health drops ≥ 4 in 1 s; data.source) · `threat` (hostile/boss nearby; data.type, pos) · `inventory_full` · `tool_low` (data.item, durabilityLeft) · `food_low` (no food in inventory) · `chat` (system or whisper lines matching nothing else; data.text) · `companion` (data.state = `verified|rejected|absent`) · `error` · `protected` (§5.7g: the guard refused to break or place; first refusal of a streak, at most one per 30 s; data.action `break|place`, pos, block, why `zone|noBreak|built`) · `owner_command` (§5.7e: a chat line of `config.owner.player` starting with the prefix — signed player chat by its sender, system lines through `owner.patterns`; data `{text (after the prefix), player, via:"chat|whisper|system", pos, dim, yaw, pitch, lookBlock, lookBlockId}` computed like the `owner` query, `pos` null when the owner is not tracked; at most 5 per second; such lines are never also sent as `chat`).

## 3. Task catalog (executed by the bot mod)

Common result reasons when `ok:false`: `cancelled`, `timeout`, `path_failed`, `not_found`, `inventory_full`, `container_failed`, `missing_materials`, `stuck`, `protected` (§5.7g), `died`, `disconnected`, `bad_args`, `unsupported`, `error`.

Every task: start on the client thread, update `step`/`progress`, never block the thread, release keys and close menus in `cancel`. A task that needs exclusive control while Baritone is idle uses the mod's pause process (§4.3).

| type | args | done / result |
|---|---|---|
| `goto` | `x`,`z`,`y?`,`range?=2` (the manager also accepts `pos` = a position reference and sends it as x/y/z) | in goal → ok; process stopped elsewhere → `path_failed` |
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
| `deposit` | `containers:[pos]`, `keep?:[glob]`, `keepCounts?:{glob:count}`, `only?:[glob]`, `slots?:[{slot,item,count}]` (exactly these inventory stacks, 0..35; a stack whose item changed is skipped; the filters are then ignored) | ok with data `{moved:{id:count}, left:{id:count}}` |
| `transfer` | `from:pos`, `to:[pos]`, `items:[{item,count}]` | take + deposit in one task |
| `inspect` | `containers:[pos]` | opens each, snapshots (§2.3 `container`), closes; ok with data `{seen:int,failed:[pos]}` |
| `equip` | `armor?=true`, `offhand?:glob` | equips best armor (by protection then durability) from inventory |
| `drop` | `items?:[{item:glob,count}]`, `slots?:[{slot,item,count}]` (one of them; slots as for `deposit`) | throws items |
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
general  { language:"ru|en", ownerPlayer:str, commandPrefix:"!b", ownerChatPatterns:[regex] (§5.7e), ownerReplyCommand:"/msg {player} {text}",
           panel{bind:"127.0.0.1", port:8765, openBrowser:true}, link{bind:"127.0.0.1", port:25590},
           tray:bool, autoStartBots:bool=true, eventLogLimit:int }
runtime  { minecraftVersion:"26.2", fabricLoader:"0.19.5", headlessmcVersion:"2.10.0", headlessmcUrl, javaPath:str? (null = the manager's own java if ≥ 25, else HeadlessMC auto-download),
           mods:[{id, name, url, sha512?, enabled}],   // fabric-api, baritone-api, ferritecore; our mod is always added from the manager jar
           startStaggerSec:int=20, priority:"normal|below_normal|idle", restart{enabled, delaySec, maxPer10Min},
           memoryPreset:"eco|normal|performance|custom", memoryMb:int, jvmArgs:str, gcThreads:int, maxHeavyTasks:int (concurrent mine/explore/build, 0 = unlimited),
           gameDataPath:str? (client jar or folder with data/ for game data; null = the game jar under runtime/mc/versions) }
servers  [ { id, name, address, login:{mode, loginCommand, registerCommand, loginPatterns, registerPatterns, successPatterns, failurePatterns, delayMs, joinCommands},
             companion:{enabled, token}, protection:{enabled:true, noBreak, zones, autoHouse:true, houseRadius:int=48 (8..96), builtNoBreak:true} (§5.7g),
             baritone:{overrides}, antiXray:"none|hide|fake",
             dayStart:"07:00", nightStart:"22:00" (local HH:MM for day/night schedules) } ]
bots     [ { id, username, account:{type:"offline|microsoft"}, serverId, enabled, autoStart, memoryMb?, jvmArgs?, homeWaypoint?,
             roles:[role] (empty = any), behaviour:{overrides}, baritone:{overrides},
             autopilot:{overrides: supply, sort, idleWork, discovery, foodMin, blocksMin, toolMinDurability, stuckSec} } ]
behaviour{ defaults for BotConfig.behaviour }
baritone { defaults for BotConfig.baritone }   // shipped defaults = the low-load set from docs/RESEARCH-NOTES
client   { defaults for BotConfig.client }
planner  { tickSec:int=5, roleSwitchCooldownSec:int=120, maxBuildersPerSector:int=1, restockFreeSlotsTarget:int=4, autoDepositWhenFull:bool }
autopilot{ supply:bool=true, sort:bool=true, idleWork:bool=true, discovery:bool=true, discoveryRadius:int=32, discoveryIntervalSec:int=60,
           inspectMaxAgeMin:int=120 (0 = only never-seen), maxInspectPerTick:int=2, inspectMaxDistance:int=48 (0 = no limit), homeRadius:int=24, useFound:bool=false,
           foodMin:int=8, blocksMin:int=32, toolMinDurability:double=0.1, stuckSec:int=60 (0 = off), idleHomeSec:int=60, holdAfterOwnerSec:int=300 (0 = off),
           throwaway:[glob], smeltInputs:[glob], categories:[{name, globs:[glob|#tag]}],      // §5.7a
           signWords:{word: role|sorted:<category>} (§5.7e), autoTrash:{enabled:true, junk:[glob], keepCounts:{glob:count}} (§5.7f) }
keepProfiles { name → {armor:"worn|none", weapon:"best|none", tools:[pickaxe|axe|shovel|hoe|sword|shears], food:{max}, blocks:{globs, count}, extra:[glob]} }   // §5.7f, default «снаряжение»
orders   [ { id, enabled:bool=true, serverId, item, min:int, max:int?, into:"storage|supply|kit|fuel|inbox|sorted:<category>|<containerId>" } ]
schedules[ { id, name, enabled:bool=true, serverId?, when:"<5-field cron>|day|night", botIds:"any|all"|[botId], steps:[TaskTemplate|managerStep], priority:"normal|high" } ]   // §5.7b
rules    [ { id, name, enabled:bool=true, serverId?, if:{one trigger}, then:[steps], botIds:"any|all"|[botId], cooldownSec:int=300, priority:"normal|high" } ]          // §5.7b   // §5.7b
ai       { enabled:bool=false, endpoint:"http://127.0.0.1:11434" (http(s) URL), model:"qwen2.5:7b-instruct", timeoutSec:int=30 (1..600),
           superviseSec:int=90 (15..3600), mode:"suggest|auto" }                                                                  // §5.7c, §5.7d
```
Memory presets: eco 768 MB, normal 1024 MB, performance 2048 MB (Xmx per bot). Roles: `builder, miner, lumberjack, farmer, smelter, crafter, sorter, rancher, hauler, guard`.

### 5.4 Bot lifecycle
`stopped → installing → starting (process up) → linked (hello ok) → online (joined server) → …`; `crashed` when the process dies without a `quit`. Restart policy from `runtime.restart`. Stop = send `quit`, wait 15 s, then kill the process tree (`ProcessHandle.descendants()`). Starting several bots staggers them by `startStaggerSec`. HeadlessMC launch: working dir `bots/<id>`, `java -Xmx64m -XX:+UseSerialGC -jar <hmc jar> --command launch fabric:<mc> -lwjgl -offline -noout` for offline accounts (HMC per-bot `config.properties` carries `hmc.mcdir`, `hmc.gamedir`, `hmc.offline=true`, `hmc.offline.username`, `hmc.always.lwjgl.flag=true`, `hmc.assets.dummy=true`, `hmc.jvmargs` incl. the §2 system properties, `hmc.gameargs=--quickPlayMultiplayer <address>`, `hmc.java.versions`). One-time install (`fabric <mc> --uid <loader>`, then `launch fabric:<mc> -lwjgl -offline -prepare`) runs before the first start and never in parallel. Microsoft accounts: `POST /api/bots/{id}/microsoft-login` runs `java -jar <hmc jar> --command login` in `bots/<id>` (HeadlessMC's device-code flow; the process stays up until the code is confirmed or 15 min pass), answers `{url, code}` as soon as HeadlessMC prints `Go to https://www.microsoft.com/link?otc=<code>`, and reports `microsoft_login_ok` (line `Logged into account <name> successfully!`) or `microsoft_login_failed` as events. HeadlessMC stores the account in `bots/<id>/HeadlessMC/auth/.accounts.json`; a Microsoft bot starts only when that list is non-empty (else 409 `login_required`) and launches with `hmc.offline=false` and without `-offline` (HeadlessMC refreshes the token on every game launch).

### 5.5 Queues, scenarios, kits
* Per bot: `current` + `queue` of TaskSpec templates. Add modes `append|front|replace`. When a task finishes the next one is dispatched. Manual tasks pause project work for that bot until the queue is empty.
* Manager-side step types (expanded at dispatch time, never sent to the bot): `kit <kitId>`, `deposit_storage`, `home`, `wait <sec>`, `goto_waypoint <name>`, `sort_storage` (one sorting round over the bot's inboxes, §5.7a), `smelt_all {inputs?, fuel?}` (collect every result and load every empty furnace with role `furnace` within 64 blocks: input of most stock from `inputs` — default `autopilot.smeltInputs` — and fuel from the source containers, fuel role first; then `deposit_storage`; never-inspected furnaces are inspected first), `obtain {item, count}` and `progress {tier}` (§5.7b2), `trash {profile?, to?}` (§5.7f); `supply` (inserted by auto-supply, §5.7a) and `resolve` (inserted in front of a task whose arguments hold position references, §5.7e) are internal. `deposit_storage` deposits into `inbox` containers first, then `storage`; with auto-sort on and no inbox it first deposits each category into its `sorted:<category>` chests.
* Scenario: `{id, name, steps:[TaskTemplate | managerStep], repeat:bool}`; a run expands into the queue with `origin = scenario:<runId>`; on failure with `inventory_full` the manager inserts `deposit_storage` and retries the step (max 50 per run); other failures stop the run.
* Kit: `{id, name, slots:{slotName:{any:[glob], count:int}}}` planned against the container index (containers with role `kit`, then `storage`): skip slots already satisfied by the bot's inventory/armor (position in `any` = rank), produce `take` tasks grouped per container ordered nearest-neighbour, then `equip`. Unknown container contents → `inspect` first.

### 5.6 World knowledge (per server profile)
Waypoints `{name, dim, pos}` (`home` is special) · Areas `{name, dim, box}` · Containers `{id, dim, pos, block, roles:[kit|storage|supply|fuel|inbox|sorted:<category>|furnace|crafting|found|trash], label, snapshot, lastSeen, signText?, frameItem?, roleSource:"manual|sign|auto"?}` — snapshots from any bot's `container` message update it; `found` = discovered outside the home area (a source only with `autopilot.useFound`); `trash` = where `trash {to:"trash_chest"}` unloads (never a source); `roleSource` records who set the roles (precedence manual > sign > auto, §5.7e; roles changed through `PUT /api/world` become `manual`; older entries without it count as manual when they carry inbox / kit / supply / fuel / trash) · Protected zones `{name, dim, box, source:"manual|auto", off:bool}` (§5.7g; `auto` = a house the manager found, `off` = switched off by the user, kept so a scan does not bring it back, never sent to the bots; zones without `source` are manual) · Death log.

### 5.7 Projects and automatic roles
Project kinds: `build`, `gather` (item quotas into storage), `clear` (area), `farm` (field + deposit), `ranch` (pen), `sort` (inbox → categories), `smelt` (furnace array). A project has `bots:[botId] | "any"`, status `draft|running|paused|done|failed`, kind-specific config and progress (`progress.done/total/percent` where it makes sense, otherwise counters and a rate), plus its live `assignments`; problems are `project_blocked` events (10 min throttle per key) and `blocked[]` in the view, completion is `project_done`.

Kind configs (all take `dim`; `box | area` = a box or the name of a world area, resolved to its box when the project is saved; positions are container positions):
* `gather {quotas:{item:count}, into:"storage"|role|containerId, storage?:[pos], repeat:false}` — baseline = indexed stock of each item in the targets when the project starts, target = baseline + count; deficits resolve like build deficits (haul from other containers → mine → craft → smelt → manual) and are delivered into the targets; done when every target is reached (`repeat`: new baseline, next round).
* `clear {box|area, deposit?:[pos], verify:true}` — split into slabs (`Box.split`, one per allowed bot, ≥ 4 wide), each a work item (role `miner`) running `selection clear`; `inventory_full` → `deposit_storage` + retry (dispatcher); after a slab the drops go to `deposit` (else `deposit_storage`); `verify` samples `block_at` at the slab centre and corners (air/fluids = cleared; 3 tries, then blocked); done when every slab is done.
* `farm {box|area|center, range?, durationSec:600, farmers:1, cycles:0, deposit?:[pos]}` — `farm` for `durationSec` (Baritone replants), then deposit; offered again while running; `cycles > 0` = done after that many rounds; progress: rounds, harvested items, items/hour.
* `ranch {box|area, animal, max:20, keep:4, food?, shear:false (sheep only), intervalSec:300, deposit?:[pos]}` — every interval: `shear` (option), `breed` up to `max`; when the breed result's population ≥ `max`: `slaughter` down to `keep`; loot → deposit.
* `sort {inbox?:[pos], continuous:false}` — the autopilot sorter (§5.7a) over the chosen inboxes (default role `inbox`); one-shot projects are done when nothing movable is left; items without room → `project_blocked`.
* `smelt {inputs:[glob], fuel?:[glob], furnaces?:[pos], output?:[pos], continuous:false}` — per furnace (default role `furnace`, lock = the furnace): collect when the snapshot shows a result or our load is cooked (`smelt_collect` + deposit into `output` / storage), load when empty (input of most stock + fuel, fuel role first: `take`s + `smelt_load`; the same choice as `smelt_all` and the autopilot's refuel); one-shot projects are done when no input is left and our furnaces are empty.

Planner tick (`planner.tickSec`): every running project emits work items `{kind, role, priority, project, needs:{items,tools}, location, lock?}`; idle bots (empty queue, online, allowed role) are matched to the best item by `priority − distance/64 − (roleChanged ? 2 : 0)`; a bot keeps its role at least `roleSwitchCooldownSec`. Locks: a container is used by one bot at a time; a build sector by `maxBuildersPerSector` bots.

`build` (the 2b2t-style flow):
1. Load schematic, placement `{origin, rotation, mirror}`, compute BOM via a bot `bom` query (cached).
2. Split footprint into sectors (vertical columns, longer axis first) — count = number of bots allowed to build, min sector width 4, re-split when bots join/leave; idle builders steal unfinished sectors.
3. Builder loop: `progress` query for its sector → materials needed for the sector's remaining blocks (bottom layers first) ∩ supply containers → `take` from supply up to free slots minus `restockFreeSlotsTarget` → `build` with `box` = sector → on `missing_materials` restock; on ok verify with `progress`; final pass over the whole build.
4. Deficit per item = remaining BOM − supply stock − builders' inventories − in transit. Each deficit resolves to: haul from storage → mine (blocks whose loot drops the item, from game data) → craft (recipe from game data, ingredients recurse) → smelt → else `manual` (shown in the panel with the count needed; honest mode only, nothing is spawned).
5. Gather/craft/smelt/haul work items deliver into the project's `supply` containers.

Game data: parsed from the client jar `runtime/mc/versions/<mc>/<mc>.jar` (HeadlessMC's Fabric install keeps it as `versions/fabric-loader-<loader>-<mc>/fabric-loader-<loader>-<mc>.jar`; `runtime.gameDataPath` overrides) (`data/minecraft/recipe/*.json`, `data/minecraft/tags/item/**`, `data/minecraft/loot_table/blocks/*.json`), so recipes and drops follow the actual game version. Without the jar, the planner only knows "item id = mineable block id".

### 5.7a Autopilot (auto-supply, auto-sort, idle work)
The user wants bots to fetch what they need and keep storage sorted without being told. Settings section `autopilot` (§5.3), per-bot override `bots[].autopilot`. All autopilot work except auto-supply runs through the planner as work sources with origins `auto:<serverId>` / `order:<serverId>` (planner origins: a manual or scenario entry still cancels it), role-neutral (no role change, no role cooldown) and with negative priorities so project and order items win.

* **Container discovery.** With `discovery`, every online bot that is not mining/building/exploring runs a `containers_nearby {radius: discoveryRadius}` query at most every `discoveryIntervalSec` (and only after moving `discoveryRadius/2` blocks, or every 10 min from the same place); bots that walked home scan there too. New containers are merged into the index: furnaces/smokers/blast furnaces → `furnace`, crafting tables → `crafting`, chests/barrels/shulker boxes → `storage` within `homeRadius` of the `home` waypoint (or any bot's home waypoint), else `found`. Containers with unknown contents or a snapshot older than `inspectMaxAgeMin` become `inspect` work items (unknown first, up to 8 containers within 16 blocks per item, at most `maxInspectPerTick` items per tick); containers an inspection could not open are skipped for an hour. `POST /api/world/{id}/discover` uses the same default roles.
* **Auto-supply.** Before dispatching any bot task of type `mine`, `explore`, `farm`, `selection`, `build`, `guard`, `follow`, `attack`, `slaughter`, `shear`, `smelt_load` or `craft` (any type while a refill is pending), the dispatcher queues an internal `supply` step in front (once per entry; never for `auto:`, `recovery` or supply entries). It reads the live inventory (`inventory` query, 3 s; the last status as fallback), computes the needs — the tool for `mine` blocks by `GameData.toolFor` (required tier when the block drops nothing without it, otherwise optional), hoe for `farm`, pickaxe/shovel/axe for `selection clear` and `build`, the `replace` source blocks' tools, a sword for fights, shears for `shear`; food for long tasks when below `foodMin` (refilled to 2 × `foodMin`, harmful food and `autoEat.avoid` excluded); throwaway blocks (`autopilot.throwaway`) below `blocksMin` for `mine`/`explore`/`selection clear` (refilled to 2 ×); and, for manual and scenario work only (projects restock themselves), the block of `selection fill|walls|shell|replace`, input and fuel of `smelt_load`, recipe ingredients of `craft`, and for `build` the `remaining` items of a `progress` query — and inserts `take`s (origin `supply`: a failed take never stops the task, its scenario or project) from containers with role `storage`, `kit`, `supply`, `inbox`, `fuel`, `sorted:*` (and `found` with `useFound`) within 160 blocks, nearest first, the best tool tier on offer, at most the bot's free slots − 1. Unknown contents → `inspect` first, then plan again. A tool below `toolMinDurability` counts as missing. Events `tool_low` / `food_low` mark a refill: done in front of the next task, or as a `refill` item when the bot is idle. Missing required items → event `manual` with the list (once per bot, task type and list per 10 min), the task still runs.
* **Auto-sort.** Containers with role `inbox` receive loot (`deposit_storage` targets inboxes first when any exist). Category chests carry role `sorted:<category>`; categories come from `autopilot.categories` (defaults: ores_ingots, wood, food, tools_armor, redstone, farming, mob_drops, stone_building, misc; first match wins, `#tag` entries use game-data item tags). Unassigned chests with role `storage` (not inbox/kit/supply/fuel) adopt the category holding more than half of their occupied slots (event `container_category`). Sorting: per inbox with a non-empty snapshot, per category, targets are the category's chests with room (free slots + partial stacks), then empty unassigned storage chests (they adopt the category once filled), then `sorted:misc`; one `transfer` per category, sized to the bot's free slots; items with no room anywhere stay and raise `sort_full` (throttled). Any idle bot (roles empty, `sorter` or `hauler`) takes `sort` items. Manager step `sort_storage` runs one round for the queuing bot.
* **Idle work.** With `idleWork`, an idle bot picks, in priority order: `refill` (pending tool/food wish), `sort`, `collect` (a furnace whose snapshot shows output, or whose load we put in is done: `smelt_collect` + `deposit_storage`), `refuel` (an empty `furnace` gets the `smeltInputs` item with the most stock — smeltable there per game data — up to 64, plus fuel from role `fuel` first; `take`s + `smelt_load`), `inspect`, and after `idleHomeSec` without work `home` (then a discovery scan). Idle `inspect` only targets containers within `homeRadius` of a home or with a usable role (storage, kit, supply, inbox, trash, fuel, furnace, `sorted:*`; `found` ones outside home only with `useFound`), at most `inspectMaxDistance` blocks from the bot. **Attention hold:** after the owner's `come` / `follow` (or a `goto` to an `owner` / `owner_look` reference) the bot gets no idle work and no inspections for `holdAfterOwnerSec`, or until the next manual task or owner `stop`; `follow` holds while it follows. A bot whose username equals `general.ownerPlayer` never executes owner commands.

### 5.7b Standing orders, schedules, rules
* **Standing orders** ("keep in stock"): `{item, min, max?, into: role|container}` — the planner keeps the stock between min and max by emitting gather / craft / smelt work items whenever the indexed stock drops below min (e.g. keep 128 torches, 2 stacks of iron ingots, 64 bread, furnace fuel). Same resolution chain as build deficits (§5.7 step 4). Stored in `config.json` → `orders[]` (§5.3; edited in the settings or through `/api/orders`, §6). Each planner tick per enabled order: stock = the item in the `into` containers (a role → all containers with it in the first such container's dimension, or one container id; never-inspected ones are inspected first); below `min` the order turns active and stays active until stock + in transit reaches `max` (or `min`). Deficit = target − stock − in transit → `Resolver` with the other source containers (not `supply`) as storage → `haul` / `mine` / `craft` / `smelt` items (priority 0.5) delivering into `into`; manual items → event `order_blocked` (throttled 10 min).
* **Schedules** (`schedules[]`, §5.3; `/api/schedules`): `{when:"*/30 * * * *" | "night" | "day", botIds:"any"|"all"|[ids], steps:[…], priority}` — e.g. harvest the farm every 30 min, sort storage hourly, go home and stop at night. Evaluated on the manager loop once per minute (missed minutes up to 10 back are caught up). Cron = 5 fields as in Vixie cron (`*`, lists, ranges, steps, `JAN..DEC`, `SUN..SAT`, 0/7 = Sunday; restricted day-of-month and day-of-week match either). `day` / `night` fire when the day or night starts (a transition, never at manager start); they follow the world day time an online bot of that server reports (`BotStatus.dayTime`, night = 13000..22999), else the server profile's local `dayStart` / `nightStart`. `botIds`: `any` = one free online bot (idle first, then without planner work, then the shortest queue), `all` = every online bot, a list = each listed bot (queued even while offline). A bot that still has entries of the schedule is skipped (no pile-up). Events `schedule_fired` / `schedule_skipped` / `schedule_failed`.
* **Rules** ("if → then", `rules[]`, §5.3; `/api/rules`): `{if:{event:kind | containerFull:containerId|"x,y,z" | itemBelow:{item,count} | playerOnline:name | healthBelow:n}, then:[steps], botIds, cooldownSec, priority}`. Examples: container full → empty it into storage; health below 6 → go home; tool_low → fetch a new tool. `event` fires on every matching event of the log (bot and manager events; events of rules and schedules never trigger rules); `healthBelow` on bot status; `containerFull` (snapshot `free == 0`) and `itemBelow` (indexed stock in storage/kit/supply/inbox/fuel/sorted containers; unknown while nothing is inspected) on snapshots and once a minute; `playerOnline` once a minute through a `player` query of an online bot (tracking range) plus join/leave chat lines. State triggers fire on the rising edge (a true condition at manager start counts); `cooldownSec` per rule and scope (bot for bot triggers / bot events, else server); an edge inside the cooldown stays armed and fires when the cooldown is over if the condition still holds. Steps of bot events and `healthBelow` run on that bot (if `botIds` allows it), others on the bots `botIds` picks. Events `rule_fired` / `rule_skipped` / `rule_failed`.
* **Priority of schedule and rule steps**: origins `schedule:<id>` / `rule:<id>` are user intent (auto-supply plans materials for them, they survive restarts in queues.json) but "soft": with `priority:"normal"` they are appended and run when the bot's current planner batch ends — project / autopilot / order work is not cancelled; `priority:"high"` releases the bot's planner assignment (its entries are cancelled) and puts the steps at the queue front. A running manual task is never interrupted.
* **Stuck recovery**: no position change and no Baritone progress for `stuckSec` (default 60) while a task runs → cancel, step back 3 blocks, retry once, then fail with `stuck`. Progress = moved ≥ 1 block, or the task's `step` / `progress` or Baritone's `goal` changed (status samples); paused tasks and `guard`, `follow`, `idle`, `eat` are never stuck. The step back is a `goto` 3 blocks behind the bot (opposite its yaw, origin `recovery`); events `stuck_retry`, then `stuck` + `task_failed` with reason `stuck`.

### 5.7b2 Goals: `obtain` and tech progression
Manager-side step `obtain {item, count}` for one bot, re-planned after every finished task from the bot's live inventory: have it → done; else take from storage → craft (ingredients recurse) → smelt (needs furnace + fuel; furnace itself is an `obtain minecraft:furnace` if none indexed nearby) → mine blocks whose loot drops it **with the required tool tier** (block tags `mineable/pickaxe|axe|shovel|hoe`, `needs_stone_tool`, `needs_iron_tool`, `needs_diamond_tool` from the client jar's `data/minecraft/tags/block/`; missing tool → `obtain` that tool first). Crafting tables are placed when none is within 16 blocks. Presets built on it: `progress wood|stone|iron|diamond` = tools + armor of that tier + 32 food + torches. A cycle guard stops after 200 planning steps with the missing items listed.
Implementation: each `obtain` round queries the inventory, simulates the whole tree (inventory as the pool; storage = indexed source containers within 128 blocks; table = indexed crafting table within 16 blocks; furnaces within 48 blocks, free ones first), merges actions per kind + item (one round mines every log the tree needs) and queues the first ready action (all ready `take`s together) followed by the same `obtain` again; intermediate rounds report nothing, the last one reports `goal_done` / `goal_failed`. Precedence craft → smelt → mine; among recipes / ingredient options the lowest per-item effort estimate wins (ingredients × count ÷ yield, one-time tool/table/furnace at a quarter; at hand 0, storage 1), ties stay with what the plan already uses; log mining accepts any log. A good-enough tool in storage is taken instead of crafting one. Fuel: enough at hand, else coal/charcoal in storage, else coal if obtainable, else logs. Blocks are placed with a one-block `selection fill` (the mod has no place task; Baritone's builder places it) at the first of 8 spots 2 blocks around the bot whose `block_at` is air-like with a solid block below; the placed table/furnace is added to the index. The same action failing 3 times (or 8 failures in all) ends the goal. `progress` tools and iron/diamond armor are required; leather armor (wood/stone tiers have no armor of their own), 32 torches and 32 food (`obtain @food`: food from storage, else raw food the bot carries cooked) are soft (skipped with `goal_skipped`); the step ends with `equip`.

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

Implementation (contract details, §5.7c and §5.7d; user guide in `docs/AI.md`):
* **Model calls.** `POST {endpoint}/api/chat` with `stream:false`, `options:{temperature:0.1, num_ctx:8192}`, a system prompt and `format` = a JSON schema. Both are built per request from the live state (`AiContext`): supported, non-internal task types and manager steps with their arguments (`baritone` and `recover` are never offered), project kinds, roles, reference kinds, enabled bots (online, roles, current task), waypoints and areas per server, kits, keep profiles, schematics, sorting categories, the owner's name. The plan prompt lists `obtain` / `progress` first, asks for high-level work and carries four examples (RU + EN, among them «развиться до железки» → `progress iron` and «построй замок из castle.schem у дома всеми ботами» → a `build` draft with `origin {"ref":"auto"}` and `bots:"any"`). Requests run on the HTTP client's virtual threads, never on the manager loop; results are posted back. `GET {endpoint}/api/tags` serves the connection check.
* **Validation** (`PlanValidator`, on the loop, against the live state; nothing unvalidated reaches a queue): the type must be known, supported, not internal and not excluded; every argument must be declared (`text` arguments such as craft grids are refused), of its declared type and within `min`/`max`/`enum`; item ids are normalised (`iron_ingot` → `minecraft:iron_ingot`); position / box / container arguments take references only to waypoints, areas and bots that exist, and literal coordinates only when every number appears in the request text (`invented_coordinates`; the supervisor has no text, so it can never send coordinates); kit, keep profile, waypoint and schematic names must exist (case-insensitive, a unique schematic prefix is accepted); bots are matched by id or username and must be in `botIds` when the request is limited. Limits: 20 steps, 10 orders, one project per plan, 5 actions per supervisor round. Every dropped item is returned as `{kind, what, reason, detail}` with a reason code (`unknown_type`, `not_allowed_type`, `unknown_arg`, `bad_arg`, `missing_arg`, `unknown_bot`, `bot_not_allowed`, `bad_ref`, `unknown_waypoint|area|kit|profile|schematic`, `invented_coordinates`, `bad_item`, `bad_order`, `unknown_kind`, `unknown_role`, `role_not_allowed`, `unknown_action`, `bad_priority`, `too_many`).
* **Command box.** Model output `{steps:[{bot, type, args}], orders:[{item, min, max?, into}], project:{kind, name, bots, config}|null, notes}`; `POST /api/ai/plan {text, botIds?}` → `{id, plan:[{botId|"any", step:{type,args}}], orders, project, notes, rejected, model, ms}`. Plans are kept 30 min. `POST /api/ai/run {id}` (or `{plan, orders?, project?, text?, botIds?}` when the id is gone) checks the plan again on the live state and then uses the normal paths: steps via `Dispatcher.addTemplate` (append, origin `ai:<id>` — user intent like a panel task: auto-supply plans materials and a planner assignment of that bot is released); all `any` steps go in order to one free online bot picked like a schedule's `any` (`no_free_bot` otherwise); standing orders into `orders[]` (an existing order for the same item, target and server is raised instead of duplicated); the project is resolved (§5.7e) and created with `start:true`. A plan runs once (a second `run {id}` is 404). Event `ai_plan_run`.
* **Supervisor.** A 5 s loop timer: per running project a round when `superviseSec` passed since the last round (the first one `superviseSec` after the supervisor first saw the project running); events `project_blocked`, `death` of a bot assigned to the project (or last assigned) and a second `stuck` / `stuck_retry` of one bot within 10 min mark the project urgent: a round runs at once unless the last one was less than 30 s ago (then when the 30 s are over). One round per project at a time. Digest: project (id, name, kind, status, priority, bots, minutes running), `progress` as the project view has it (percent, rate, ETA), up to 15 BOM deficits `{item, deficit, source}`, `manual`, `blocked`, assignments `{bot, role, work, phase, forSec}`, up to 16 allowed bots `{id, online, roles, hp, food, freeSlots, task}`, the server's orders, the last 30 events of the project and its bots. Answer `{summary, actions:[{type, reason, …}]}`. Action arguments: `set_priority {priority 0..100}` (project priority, merge-patched), `reassign {bot, role}` (the bot's planner assignment is released and the role hysteresis is set to `role`, so for `roleSwitchCooldownSec` the planner gives it only work of that role or role-neutral work; the role must be in the bot's configured roles when it has any), `add_standing_order {item, min, max?, into}`, `pause_project` / `resume_project` (this project only), `add_task {bot, step}` (origin `ai:<action id>`), `notify {text}` (event `ai_notify`, warn: panel toast and tray). Bots must belong to the project. In `suggest` mode valid actions are `pending`; a new round marks older pending ones `expired`; Apply checks the action again on the live state. In `auto` mode they are applied at once. Every applied action is an `ai_action` event with the reason and `data.projectId`. Statuses: `pending | applied | dismissed | rejected | failed | expired`.
* **Failures.** Codes `ai_disabled` (409), `ai_unreachable`, `ai_model_missing`, `ai_http`, `ai_invalid_json` (502), `ai_timeout` (504, `timeoutSec`). In the supervisor a failure becomes a feed entry `{kind:"error", code, message, count}`; the same failure again raises `count` on that entry. Nothing else changes: the planner keeps working.
* **Feed** (memory only, 60 entries per project, newest first in the API): `{id, time, projectId, kind:"summary"|"error", trigger:"timer|blocked|stuck|death|manual", summary, actions:[{id, type, args, reason, status, why?, detail?, by?, decidedAt?}], model}`; every new or changed entry is sent as SSE `ai {type:"feed", projectId, entry}`; a settings change sends `ai {type:"status", enabled, mode, model, timeoutSec, superviseSec}`. `GET /api/state` carries the same status as `ai`.

### 5.7e No coordinates in daily use
The user does not want to type coordinates. Every place that takes a position or a box must also accept a *reference* that the manager resolves at dispatch time, and the panel forms default to references; raw x/y/z stays available behind "вручную".

References: `{"ref":"home"}`, `{"ref":"waypoint","name":…}`, `{"ref":"owner"}` (the owner player's position — `general.ownerPlayer`, seen by any online bot through the `owner` query, §2.4), `{"ref":"owner_look"}` (block the owner is looking at: the mod ray-casts from the owner entity's synced eye position + yaw/pitch, max 64 blocks), `{"ref":"bot","id":…}`, `{"ref":"auto"}` (kind-specific detection below). Boxes: `{"ref":"area","name":…}`, `{"ref":"auto"}`, or two references.

* **In-game commands from the owner.** The mod forwards chat lines from `ownerPlayer` that start with `general.commandPrefix` (default `!b`), public or whispered (`/msg Bot1 !b …`), as event `owner_command {text, pos, dim, yaw, pitch, lookBlock}`. Manager commands: `here <name>` (waypoint at owner), `home` (set home at owner), `pos1` / `pos2` → `area <name>` (box from looked-at blocks), `chest storage|inbox|kit|<category>` (role/category for the looked-at container), `come`, `follow`, `stop`, `build <schematic> [rotate]` (origin = looked-at block, rotation from the owner's facing), `farm`, `ranch`, `progress iron`, `obtain <item> [n]`, `protect [name]` / `unprotect` (§5.7g). Replies go back as a whisper from one bot (rate-limited). The AI command box (§5.7c) accepts the same intents.
* **Signs on chests.** The mod reports sign texts on/next to containers in container snapshots and `containers_nearby` (`signText`). Words map to roles/categories (configurable dictionary, RU + EN defaults: «склад/storage», «приём/inbox», «кит/kit», «руда/ores», «дерево/wood», «камень/stone», «еда/food», «инструменты/tools», «редстоун/redstone», «ферма/farming», «мобы/mob», «разное/misc»). An item frame on a chest showing an item = that item's category.
* **Auto-detection** (mod query `scan_blocks {center, radius, ids|tags}` returning compact clusters): farm fields = clusters of farmland/crops near home; pens = fence/wall-enclosed regions containing animals of a type; furnaces/crafting tables already come from discovery; build site for `{"ref":"auto"}` = the nearest flat spot (heightmap variance ≤ 1) that fits the footprint, outside other projects and protected zones, within `homeRadius`.
* **Panel**: every position/box field is a picker — «Дом», «Где я стою», «Куда я смотрю», «Возле бота», «Точка…», «Область…», «Найти автоматически», «Вручную (x y z)».

Implementation (contract details):
* **Where references are accepted.** Catalog arguments marked `"refs": true` (types `pos`, `box`, `container`, `containers` of tasks and project kinds; `goto` gains `pos`); `/api/catalog` → `refs` lists the reference kinds per type, labels `ref.<kind>` in both catalogs. Bot tasks are resolved at dispatch time: the dispatcher puts the internal step `resolve` in front of a task whose reference arguments hold references, the bot only ever receives coordinates, and an unresolvable reference fails the task with reason `ref_no_owner|ref_no_look|ref_no_home|ref_no_waypoint|ref_no_bot|ref_no_area|ref_auto_none|ref_wrong_dim|bad_ref` (event `ref_failed`); scenarios, schedules and rules keep their references and resolve on every run. Project configs (`POST/PUT /api/projects`, `placement` included) and world edits (`PUT /api/world/{id}`: `waypoints[].pos`, `areas[].box`, `zones[].box`; a missing `dim` is taken from the reference) are resolved when saved; failures answer 400 with the reason as `error`. `home` = the bot's home waypoint for bot tasks, else `home` / any bot's home of the server; references must lie in the bot's (project's) dimension.
* **Owner** = the first online bot of the server whose `owner` query finds the owner (cached 1.5 s). `owner_command` events are handled once per server and text within 2.5 s (every bot hears a public line). Targets: a whispered command addresses the bot it was whispered to; otherwise `come`, `follow`, `stop`, `trash` go to every online bot of the server and `progress`, `obtain` to the best free bot; a word `@<bot>` / `@all` / `@any` (`@все`, `@любой`) overrides. Russian aliases exist for every verb (`сюда`/«ко мне», «за мной», `стоп`, `точка`, `дом`, `поз1`, `поз2`, `область`, `сундук`, `строй`, `ферма`, `ранчо`, `мусор`, `прогресс`, `добудь`). `here <name>` / `home` = waypoint at the owner's block; `pos1` / `pos2` = looked-at block (else the owner's position) remembered per server, `area <name>` saves the box; `chest <word>` = role from the sign dictionary, a role name or a category for the looked-at container (added to the index when it is a container block; source `manual`); `come` = `goto` to the owner (range 2), `follow` = `follow` the owner, both replacing the queue; `stop` = clear queue + cancel; `build <schematic> [0|90|180|270]` = a `build` project (`bots:"any"`, started) with origin = looked-at block + 1 up and, without an explicit angle, rotation from the owner's yaw so the schematic extends away from the owner (yaw 0 → 0°, 90 → 90°, ±180 → 180°, −90 → 270°); `farm` / `ranch [animal]` = start the server's project of that kind, else create one with `box: {"ref":"auto"}`; `trash [store|chest]`, `progress <tier>`, `obtain <item> [n]` = those steps (origin `owner`, manual). Replies: `general.ownerReplyCommand` from the bot that heard the command, `owner.reply.*` texts in `general.language`, at most one per second per server, unknown commands get the help line.
* **Owner detection.** While `general.ownerPlayer` is empty the mod passes on every player's prefixed line (`owner_command` with `data.candidate:true`), and once a minute the manager asks one online bot per server for the player list (`player {}` → `online`). The first player who is not one of the bots and writes a command, or the only such player online, becomes the candidate: event `owner_candidate` (warn), a whisper «подтвердите в панели», `owner.candidate` in `GET /api/state`. Nothing the candidate writes runs before the user confirms (`POST /api/owner/confirm`); «Нет» (`/api/owner/dismiss`) hides that name until the manager restarts.
* **Signs.** Labels are stored on the container (`signText`, `frameItem`) from snapshots and `containers_nearby`, then mapped with `autopilot.signWords`: a dictionary word matches the same word or (4+ letters) a longer word starting with it, `ё` = `е`; all roles of the sign plus its first category; without sign words a framed item gives that item's category. Manual roles are never touched; sign roles replace automatic ones (source `sign`; category adoption skips them); when the label disappears the container gets its automatic default roles back.
* **Auto-detection** scans through the online bot nearest to the place (≤ 128 blocks; a client only knows its loaded chunks): the place is the home waypoint (else the bot). Farm field = nearest farmland cluster (`scan_blocks ids:[farmland] gap 2`, ≥ 4 blocks) within `max(48, min(96, 2·homeRadius))`, box = cluster + 1 layer up (a `farm` task without `range` gets one covering it). Pen = the fence / wall / gate cluster (`gap 1`, ≥ 8 blocks) holding the most animals of the type (any farm animal when none is given; an `entities` query), box + 1 up; a ranch project without `animal` takes the most frequent one. Build site = the nearest `w × l` footprint (schematic loaded by the manager, rotated) on a `heightmap` with ground only (no liquid / trees / unloaded columns), heights within 1, centre within `max(16, min(96, homeRadius))` of home, not touching (1-block margin) other projects' boxes / footprints, protected zones or indexed containers; origin y = highest ground + 1. Container lists: `deposit` / storage-like arguments → inbox + storage containers, `supply` → role supply, `furnaces` → role furnace, `inbox` → role inbox, `inspect` → never-inspected containers within 32 blocks (nearest first, at most 16).

### 5.7f Inventory hygiene: keep profiles and trash
* Settings `keepProfiles {name → {armor:"worn", weapon:"best", tools:["pickaxe","axe","shovel"?…] (best one of each kind by tier, then durability), food:{max}, blocks:{globs, count}, extra:[glob]}}`; default profile «снаряжение» = worn armor + best sword + best axe + best pickaxe + food up to 64 + 64 throwaway blocks. Duplicates of a kept kind beyond the best one count as junk.
* Manager step `trash {profile, to: "drop"|"store"|"trash_chest"}` (labels «Выбросить мусор» / «Сдать лишнее»): from the live inventory, everything outside the profile is thrown (`drop`), stored (`deposit_storage`) or put into a container with role `trash`. Available from the panel (one button on the bot page), in scenarios, rules and as owner chat command `!b trash`.
* Autopilot option `autoTrash {enabled, junk:[globs] (defaults: dirt, coarse_dirt, gravel, sand overflow, granite, diorite, andesite, tuff, cobbled_deepslate overflow, rotten_flesh, poisonous_potato, spider_eye, seeds beyond 64), keepCounts}`: when the inventory is full during mining/clearing, junk is thrown on the spot instead of walking to storage.

Implementation (contract details): the step reads the live `inventory`, plans with the profile in this order — `extra` globs keep whole stacks; inventory armor is kept only when it beats the worn piece of its slot (best one per slot); `weapon:"best"` keeps the best sword; each listed tool kind keeps its best tool by tier, then durability (other tools of the kind are duplicates; a tool kept by `extra` counts as that best one); food (no harmful food, no `autoEat.avoid`) up to `food.max`, best food first; `blocks.globs` (empty = `autopilot.throwaway`) up to `blocks.count`, largest stacks first; everything else is surplus. Worn armor and the offhand are never touched; for `drop` and `trash_chest` shulker boxes, named items and rare loot (diamonds, emeralds, netherite, elytra, totems, …) are never surplus. It expands into one `drop` / `deposit` with `slots` (store = inbox then storage containers, trash chest = role `trash` within 160 blocks; none → `not_found`). Auto-trash applies to `inventory_full` of `mine`, `selection` (clear projects) and `explore` when the last status shows at least two junk stacks (one when there is no storage); it queues `trash` (junk only, `drop`) + the task again instead of `deposit_storage` + the task (event `trash_retry`, same 50-retry limit), otherwise the usual deposit.

### 5.7g The bots never break the user's house
The user's words: bots sometimes break the house to reach a goal. Protection is on by default (`servers[].protection.enabled`) and works in three layers:
* **Built blocks.** With `protection.builtNoBreak` (default on) the bot config carries `protection.built` = the manager's list of player-built blocks: planks, stairs, slabs, glass and panes, doors, trapdoors, beds, wool, carpets, concrete, glazed terracotta, bricks / `*_bricks` / `*_tiles`, polished / smooth / cut / chiseled variants, quartz, fences, gates, walls, lanterns, torches, signs, banners, ladders, iron bars, bookshelves and workstations. They are merged into Baritone's `blocksToDisallowBreaking`, so Baritone plans around them (and walks through wooden doors), and the guard refuses them too. Natural blocks stay breakable: stone, dirt, ores, logs, leaves, sand, gravel, plain and colored terracotta (badlands), cobblestone (the bots' own pillars), smooth basalt.
* **Zones.** Inside an active zone (world zones and `protection.zones`) nothing is broken and nothing is placed: the guard is a client-side mixin on `MultiPlayerGameMode` (`startDestroyBlock` / `continueDestroyBlock` and `useItemOn`, all `require = 0`). A right click that opens or toggles a block (container, wooden door or trapdoor, gate, button, lever, bed) without sneaking is a use, not a placement. Baritone 1.19.0 has no setting or API for areas (checked: `blocksToAvoid`, `blocksToAvoidBreaking`, `avoidance`, `buildIgnoreBlocks`, `allowPlace`, `IPathingBehavior`; `CalculationContext.isPossiblyProtected` is not reachable), so the zones stay client-side: when the guard refuses a block inside a zone during a task, that block type is added to `blocksToDisallowBreaking` until the task ends (at most 8 types, never a block the task mines) and the current path is dropped, so Baritone plans around the wall. Refusals less than 3 s apart form a streak; 20 s of streak end the task with reason `protected` and the last refusal in the message, and a stuck task with a `protected` event in the last 90 s fails with `protected` at once (no step-back retry). A mine target inside a zone is never broken: the task fails with `protected` naming the position.
* **Automatic house zones.** With `protection.autoHouse` (default on) the manager scans, after planner ticks and at most every 15 min per place (1 min after a failed scan), the home waypoints, the other waypoints and groups of indexed containers (roles other than `found`, 16 blocks) with `scan_blocks {radius: houseRadius, dy: 24, gap: 2}` for built blocks plus terracotta and cobblestone, through the online bot nearest to the place within `houseRadius`, one scan per server at a time. A cluster is a house when it has ≥ 10 blocks, ≥ 2 of them player-only (doors, beds, glass, carpets, wool, concrete, glazed terracotta, lanterns, torches, signs, banners, chests, barrels, furnaces, crafting tables, bookshelves, flower pots, …) and is at most 128 blocks wide; the zone is the cluster + 2 blocks around, from 1 block under the floor to the roof + 2. Touching houses become one zone. New houses become zones with `source:"auto"`; an automatic zone a house touches grows to the union (never shrinks on its own); a house that touches a switched-off zone or lies inside a manual one is skipped. A change is saved, logged (`house_zone`), shown on the World page and sent to the bots at once. The World page lists every zone with its source and state: automatic zones can be kept («Оставить» → `source:"manual"`) or switched off («Не защищать» → `off:true`, «Защищать снова»); «Найти дома сейчас» = `POST /api/world/{id}/houses`.
* **Owner commands.** `protect [name]` (RU «защити», «приват»): the structure the owner looks at (else stands in) becomes a manual zone — the biggest built-block cluster whose zone comes within 4 blocks of the point, scanned by the nearest bot within 64 blocks; without one, 9 × 8 × 9 blocks around the point. Switched-off zones it touches are dropped. `unprotect` («сними защиту», «не защищай»): zones at the looked-at block or the owner's position — automatic ones are switched off, manual ones removed.
* **Exceptions (the user's own area).** A running `selection` or `build` task may break and place anything except `noBreak` blocks inside its own box, even inside a zone; Baritone's list loses the built blocks for that task (the guard still refuses them outside the box). A `farm` task may only harvest and replant crops inside its range. Everything else keeps the rules above. Tables and furnaces that `obtain` places go to spots outside zones (just outside the zone's walls when the bot stands inside one); auto-detected build sites keep a 1-block margin from zones.

### 5.8 Notifications
Events go to `events.jsonl`, the panel (SSE) and the tray tooltip/balloon for `warn`/`error` (bot died, crashed, project blocked on manual materials, project done).

## 6. HTTP API (manager)

Base `http://<panel.bind>:<panel.port>`. Static panel at `/`. JSON API under `/api`. Auth: header `Authorization: Bearer <panelToken>` on every `/api` call (SSE: `?token=` is accepted only for `/api/stream` because EventSource cannot set headers). Errors: `{"error":"code","message":"…"}` with 4xx/5xx.

```
GET    /api/state                         full snapshot: version, bots[], projects[], servers[], runtime, eventsTail[], owner {player, candidate?} (§5.7e)
GET    /api/stream?token=                 SSE: event names bot | queue | process | event | project | runtime | log | ai (§5.7d)
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
POST   /api/bots/{id}/microsoft-login     → {url, code, expiresInSec} (waits ≤ 45 s for HeadlessMC's device code); completion arrives as an event
GET    /api/bots/{id}/microsoft-login     {state: idle|starting|waiting|ok|failed|cancelled, url, code, account, error, hasAccount}
DELETE /api/bots/{id}/microsoft-login     cancel a running login
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
POST   /api/bots/quick                    {count:1..20=2, serverId?, start:true} → {bots:[view]}: names Bot1..BotN not used yet, the only
                                           server when serverId is missing (else 400 server_required), started at once (first-run screen)
GET    /api/owner                         {player, candidate?:{player, serverId, via:"command|online", time}} (§5.7e)
POST   /api/owner/confirm                 {player} → general.ownerPlayer = player (400 bad_player / bot_player)
POST   /api/owner/dismiss                 drop the candidate; not offered again until the manager restarts

GET/POST/PUT/DELETE  /api/scenarios[/{id}]     POST /api/scenarios/{id}/run {botIds, repeat}
GET/POST/PUT/DELETE  /api/kits[/{id}]
GET/PUT  /api/world/{serverId}                 waypoints, areas, containers, zones, deaths; PUT accepts position references (§5.7e) in waypoints[].pos, areas[].box, zones[].box (400 ref_* when unresolvable) and marks containers whose roles changed as roleSource "manual"
POST     /api/world/{serverId}/discover        {botId, radius} → containers_nearby via a bot, merged into the index
POST     /api/world/{serverId}/houses          scan every place of the server for houses now (§5.7g); changes arrive as SSE world
GET      /api/schematics                       list with dims + block count
POST     /api/schematics?name=<file>           raw body upload
DELETE   /api/schematics/{name}
GET/POST/PUT/DELETE  /api/projects[/{id}]      POST /api/projects/{id}/start|pause|resume|stop; POST/PUT resolve position references in the config (§5.7e) before validation
GET      /api/autopilot                        autopilot state: settings, per-server offered work + notes, scans, refills, stuck timers, goals, categories
GET      /api/orders                           standing orders (config.json orders[]) with live status {state, stock, target, inTransit, rows, actions, manual}
POST     /api/orders                           create (id from the item when absent, serverId defaults to the only server)
PUT/DELETE /api/orders/{id}                    merge-patch / delete one order
GET/POST /api/schedules                        schedules (config.json schedules[]) with {next | night, last:{lastFiredAt, why, bots, error?}}; POST: id from the name when absent
PUT/DELETE /api/schedules/{id}                 merge-patch / delete;  POST /api/schedules/{id}/run → {bots} (fire now)
GET/POST /api/rules                            rules (config.json rules[]) with {trigger, state:{scope: bool}, last}
PUT/DELETE /api/rules/{id}                     merge-patch (a new `if` replaces the old trigger) / delete;  POST /api/rules/{id}/run → {bots}
GET      /api/ai/status                        {enabled, mode, model, endpoint, reachable, models[], modelPresent, error?} (GET {endpoint}/api/tags, 5 s; works while ai is off)
POST     /api/ai/plan                          {text, botIds?} → {id, plan:[{botId|"any", step}], orders, project?, notes, rejected:[{kind, what, reason, detail}]} (409 ai_disabled, 502/504 ai_*)
POST     /api/ai/run                           {id} | {plan, orders?, project?, text?, botIds?} → {id, origin:"ai:<id>", queued:[{botId, taskId, type}], orders:[id], project?, failed:[…]}
GET      /api/ai/feed?project=                 {projectId, enabled, mode, inFlight, nextAt, pending, entries:[…newest first]}
POST     /api/ai/supervise                     {project} → one supervisor round now, returns its feed entry
POST     /api/ai/suggestions/{id}/apply|dismiss  decide a pending supervisor action (409 not_pending) → the feed entry
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
