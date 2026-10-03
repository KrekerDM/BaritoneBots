# Design decisions

Short records of where the code had to pick a behaviour SPEC.md leaves open. Format: `## <module>: <topic>`.

## common: schematic local origin
`Schematic` local (0,0,0) is the minimum corner of the whole schematic (for Litematica: of the union of all regions, negative region sizes resolved). This is the convention Baritone's own loaders use for the build origin, so `build`/`bom`/`progress` origins mean the same thing in the manager and the mod.
`Schematic.offset()` keeps the file's own origin (Litematica placement origin, WorldEdit copy origin: Sponge v3 `Offset`, v1/v2 `Metadata.WEOffset*`) for display only.

## common: transform order and pivot
`SchematicTransform` mirrors first, then rotates clockwise about local (0,0,0), then adds `origin` — exactly `StructureTemplate.transform` / Litematica. A rotated schematic therefore extends to the negative side of the origin; consumers must use `footprint()` (world box) instead of assuming `origin` is the min corner.
Block-state properties are not rotated in common (no Minecraft classes); the mod applies `state.mirror(m).rotate(r)` using `Mirror.name()` / `mcRotationName()`.

## common: canonical block-state strings
Both readers store states as `Ids.canonicalState`: namespace added, id lower-cased, properties sorted by name (`minecraft:oak_stairs[facing=north,half=bottom]`). Sponge files keep whatever order the writer used and Litematica has no string form, so sorting is the only way counts from different formats agree. Compare with MC state strings only after canonicalising both.

## common: overlapping Litematica regions
When regions overlap, a later region's air does not erase an earlier region's block (non-air wins). Litematica itself pastes regions one after another; for counting and BOM purposes losing blocks to an empty region is the worse error.

## common: legacy and vanilla structure files
`SchematicLoader` rejects MCEdit `.schematic` files (numeric ids) and vanilla `.nbt` structures with reason `unsupported`: numeric ids cannot be mapped to 26.2 states without game data. Sponge files named `.schematic` load fine because detection is by NBT content, not extension.

## common: BotConfig partial input
`BotConfig.parse(json)` merge-patches (RFC 7396) the input onto `BotConfig.defaults(botId, username)`, so a partial config or one from an older manager gets defaults for missing fields; a `null` member removes a default (e.g. a Baritone setting). Record constructors also replace null sections/collections with defaults/empty values, so plain `Json.fromJson(..., BotConfig.class)` never yields null sections, but missing scalars there are 0/false — use `parse`.

## common: glob matching details
`Ids.matches`: a glob without a namespace is in `minecraft:` (`*_pickaxe` does not match `othermod:steel_pickaxe`; use `*:*_pickaxe`), a lone `*` matches every namespace, and when the glob has no `[...]` the id's block-state suffix is ignored. SPEC §1 only says "matched against the full id after normalisation".

## mod: MVP scope
Implemented task types: goto, goto_player, follow, explore, baritone, mine, farm, selection (op `clear` only), collect_drops, recover, eat, idle, take, deposit, inspect, equip. The other §3 types (craft, smelt_load, smelt_collect, breed, slaughter, shear, build, transfer, drop, guard, attack) and selection ops fill/walls/shell/replace finish with `unsupported`. Queries bom, progress and recipe_book answer `ok:false, error:"unsupported"`. `plugin` messages are dropped with a rate-limited `log` warning (companion bridge and protection guard are phase 2).

## mod: low-power activation
Before the first config, `ClientOpts.defaults()` apply only when the client was started by the manager (`baritonebots.link` set) or with `-Dbaritonebots.headless=true`; a hand-started client without either is not touched (SPEC "dormant except low-power" would otherwise cap a human's game at 10 fps). The three flags are independent: `lowPower` = forced options + loop cap (headless only), `skipRender` = render skip (headless only), `muteSounds` = sound engine cancel. 26.2 splits `GameRenderer` into `extract` and `render`; both are skipped with one per-frame decision. `SoundEngine#playDelayed` is cancelled too.

## mod: kicked vs disconnected
Both come from the `DisconnectedScreen` reason. A reason whose translation key starts with `disconnect.` or `connect.` (timeout, connection lost/refused, end of stream) is `disconnected`; anything else (literal text from plugins, bans, whitelist, server shutdown message) is `kicked`. A manager-requested `disconnect` emits `disconnected` with reason = the request text.

## mod: auto-login heuristics
Matching runs only between join and login success/failure. One command per 10 s, at most 4. If no success/failure line arrives within 10 s after sending, `login_ok` is sent with `data.assumed=true`; if no prompt arrives within 15 s after joining, the server is treated as auth-free (no event). `joinCommands` run once logged in, 1 s apart. Action-bar lines are checked for prompts but never forwarded; `chat` events are system lines only (≤10 per 10 s), whispers sent as signed player chat are not forwarded.

## mod: Baritone settings
`BotConfig.baritone` is applied with Baritone's own `SettingsUtil.parseAndApply` (JSON value rendered to Baritone's text syntax: numbers without trailing `.0`, arrays comma-joined, objects as `k->v`), which handles every setting type including block/item lists. Unknown or java-only names are skipped with a `log` warning. Settings set by a previous config but absent now are reset. `protection.noBreak` (when `protection.enabled`) is merged into `blocksToDisallowBreaking`.

## mod: task end
Every task end (success included) releases `task:*` pause claims, closes any open container, calls `cancelEverything()` and clears Baritone input overrides; keys are released unless auto-eat is mid-bite. Death fails the task with `died`, losing the server with `disconnected`. Tasks need to be in game and Baritone ready (else `disconnected` / `error`), except `idle`.

## mod: task details
* `goto` without `y` uses a horizontal-radius goal (`range`) or `GoalXZ` for range 0.
* `mine`: `data.collected` = every item gained during the task (ore drops differ from block ids); inactive with nothing gained = `not_found`. `minY`/`maxY` temporarily set `minYLevelWhileMining`/`maxYLevelWhileMining`.
* `selection clear`: builder paused = `stuck`.
* `take` adds `data.inventoryFull` when it stopped because the inventory was full; partial counts use right-click splitting.
* `deposit` keeps `keepCounts` items from the hotbar first, moves on when a container is full, adds `data.failed:[pos]`, and fails `container_failed` only if no container could be opened.
* `inspect` relies on the container sensor's snapshot; a container whose snapshot did not arrive within 20 ticks counts as failed.
* `equip` ranks armor and weapons by vanilla material tier from the id (wooden < leather < golden < stone < chainmail < copper < iron < turtle < diamond < netherite), then durability; unknown wearables (elytra, heads) are not replaced.
* `follow` sets `followRadius` = `radius` for the task and restores it after.

## mod: behaviours
* Defense moves with a temporary `DefenseProcess` (priority 900, below the pause process): `GoalRunAway(radius+8)` to flee, `GoalNear(target,1)` to close in, hold (pause) to melee within 3 blocks. It does nothing while eating or while a container menu is open. `retaliatePlayers` treats a player that hurt the bot (hurt animation running) as hostile. `threat`: once per entity type per 30 s.
* `damaged`: at most every 3 s; `tool_low`: main hand at or below `lowToolDurability`, once per item per 2 min; `food_low`: once per 2 min.
* Status `baritone.eta` is in seconds (`estimatedTicksToGoal / 20`).
* Container sensing only knows positions the mod itself right-clicked (`Interact.useOnBlock` → `ContainerSensor.noteUse`), within 100 ticks before the menu opened; no mixin on `useItemOn`.

## manager: MVP scope
Implemented: everything in SPEC §5/§6 except projects and the automatic planner (`/api/projects*` answers 501 `not_implemented`, the state snapshot has `projects: []`), Microsoft accounts (starting such a bot fails with 400 `unsupported`, `/api/bots/{id}/microsoft-login` answers 501 `unsupported`), the manager steps `sort_storage` and `smelt_all` (fail with `unsupported`), and the companion bridge (`plugin` messages from bots are dropped; `/api/bots/{id}/plugin` still forwards to the bot, which drops it too).

## manager: catalog flags and early rejection
`catalog.json` marks each task and manager step with `supported`; the eleven task types the mod answers `unsupported` are `false`. Queueing such a task over HTTP fails at once with 400 `unsupported` instead of reaching the bot. Arguments may carry `supportedEnum` (`selection.op` = `clear` only); other values fail with `bad_args`/`unsupported`. Required arguments that have a catalog default (`selection.op`, `recover.dim`, `wait.sec`) are filled in by the manager, optional ones are left to the executor. Scenario steps are validated when the scenario is saved; unsupported ones fail when they are dispatched and stop the run.

## manager: runtime settings beyond SPEC §5.3
`runtime.mods[]` entries take either `url` (+ optional `sha512`) or `modrinth` (project slug) + `version` (preferred version number). Modrinth builds are looked up with `loaders=["fabric"]` and `game_versions=[minecraftVersion]`; when the preferred version is not listed the newest compatible build is used (logged), and Modrinth's sha512 is verified. Added `runtime.headlessmcSha256` (GitHub digest of 2.10.0, ignored for other versions) and `runtime.fabricInstallerUrl` (installer 1.1.2, written as `hmc.fabric.url`, because HeadlessMC's default installer is 1.0.3). `planner.depositKeep` (globs kept by `deposit_storage`) is new too.

## manager: runtime install
One install thread at a time; bots started meanwhile wait in state `installing`. `runtime/installed.json` records a core key (MC, loader, HeadlessMC version) and a mods key (enabled mods + bot mod sha256). A changed mods key re-downloads only what changed; the Fabric install + `-prepare` run again only when the core key changed, the Fabric version folder is missing, or the panel forces a reinstall. HeadlessMC runs in `runtime/setup/` with its own config.properties; output goes to `runtime/install.log`. Success requires exit code 0 and a `versions/*fabric*<mc>` folder.

## manager: bot launch details
Game JVM args in `hmc.jvmargs`: `-Xms min(256, Xmx)`, `-Xmx` from the bot / preset, G1 with `ParallelGCThreads=runtime.gcThreads`, `ConcGCThreads=1`, `G1PeriodicGCInterval=60000`, `UseCompactObjectHeaders`, `UseStringDeduplication`, `java.awt.headless=true`, the four `-Dbaritonebots.*` properties (link host is 127.0.0.1 when the link binds a wildcard), then `runtime.jvmArgs`, then the bot's `jvmArgs`. `hmc.gameargs=--quickPlayMultiplayer <address>` only when the server has `autoConnect`. `options.txt` is seeded only when absent (the game owns it afterwards); `baritone/settings.txt` is rewritten from the effective config on every start. Mods are copied into `game/mods/`; jars the manager put there earlier and no longer wants are removed (tracked in `mods/.baritonebots-managed`), other jars are left alone. `logs/launcher.log` rotates to `.1` at 5 MB at launch.

## manager: process lifecycle
Internal phases `stopped|installing|starting|running|stopping|crashed`; the panel's state adds `linked`/`online` derived from the link and the last status. A client that links without a manager-started process (started by hand, or still running after a manager restart) is accepted; stop sends `quit`, kill uses the `hello` pid. Priority (`below_normal`/`idle`) is applied to the launcher and every descendant process as they appear during the first 2 minutes (PowerShell `PriorityClass` on Windows, `renice` elsewhere). Restart policy counts crashes in a sliding 10-minute window; at `maxPer10Min` the bot stays crashed (event `crash_loop`). Starts are staggered globally by `startStaggerSec`, restarts included.

## manager: retries and death recovery
A task that fails with `died` or `disconnected`, or whose link drops while it runs, is re-queued at the head (at most 3 dispatches in total). On a `death` event the manager logs the death in the world document and, when `deathRecovery.enabled`, queues `recover {pos, dim, radius 6}` (timeout = `deathRecovery.timeoutSec`) at the front right away; nothing is dispatched while the bot is dead (death event, `died` result or `dead` status until `respawned` or an online status). Retried tasks are inserted after a pending recovery. `maxDistance` is checked when the recovery is dispatched (distance from the respawn point, same dimension only); too far = skipped with a warning.

## manager: inventory_full rule
For scenario steps: `deposit_storage` + the same step again, at most 50 times per run. For other tasks the same happens when `planner.autoDepositWhenFull` is on (50 per task chain). It needs at least one container with role `storage` in the bot's dimension; otherwise the failure is reported normally. `deposit_storage` deposits into all storage containers of the dimension in nearest-neighbour order, keeping `planner.depositKeep` plus the step's `keep`.

## manager: scenarios and queues
Cancelling a scenario step (or clearing the queue, or adding with mode `replace`) stops the run and removes its remaining entries. A failed step (other than the retried reasons) stops the run. Runs live in memory only: `queues.json` keeps queued entries (and the running bot task, re-queued first) across restarts but drops scenario entries. A cancel the bot never answers is dropped after 10 s.

## manager: kit planning details
Possessed items = status `items` (main + hotbar + offhand) plus worn armor. A slot is satisfied by any of its globs; one item counts for one slot only. Missing counts are taken from containers with role `kit`, then `storage` (same dimension, nearest first within a role), trying globs in rank order, exact ids from the snapshot. If any candidate container has never been seen, one `inspect` round runs first (the kit step is re-queued with `_inspected: true`, after which unknown containers are skipped). Takes are grouped per container and visited nearest-neighbour from the bot; `equip {armor: true, offhand: <first glob of a slot named "offhand">}` always follows. Shortfalls produce a `kit_missing` warning, not a failure.

## manager: world index
Every `container` snapshot updates the index; containers the index does not know are added without roles. `discover` adds `containers_nearby` results, giving furnaces/smokers role `furnace` and crafting tables `crafting`. `PUT /api/world/{id}` replaces the sections present in the body (validated on a copy first); changed zones are pushed to the server's bots as part of `protection.zones`.

## manager: HTTP extras
`GET /api/bots/{id}` exists; bot views include `password` (the AuthMe password from secrets.json, settable via `password` in POST/PUT `/api/bots`), `process`, `status`, `queue`, `client` (hello data) and the redacted `config`. JSON bodies are limited to 2 MiB, schematic uploads to 64 MiB, names to `[A-Za-z0-9][A-Za-z0-9 _.()+-]{0,95}` with a schematic extension; uploads must parse, and an existing name needs `?overwrite=true`. Validation errors are `{"error":"validation","fields":{path:code}}`. SSE also emits `world` (`{serverId}`) after world changes.

## manager: Windows AF_UNIX temp folder
Every NIO selector on Windows (JDK HttpServer, HttpClient) opens a loopback pipe through an AF_UNIX socket in the temp folder, whose real path must stay under ~100 characters; redirected temp folders (app containers) break it even when `%TEMP%` looks short. `ManagerMain` therefore sets `jdk.net.unixdomain.tmpdir` to `%USERPROFILE%\.baritonebots-tmp` unless it is already set.