# Manager internals

How the manager (`:manager`, package `io.github.krekerdm.baritonebots.manager`) is put together. The contract is `docs/SPEC.md` (§2 link, §3 tasks, §5 manager, §6 HTTP API); deviations are in `DECISIONS.md` under `manager:`.

Run: `java -jar baritonebots-manager-<v>.jar [--data <dir>] [--no-tray] [--no-browser]` (Java 25). The jar carries the bot mod (`/botmod/baritonebots-botmod.jar`), the task catalog (`/catalog.json`), the manager texts (`/i18n-catalog/{ru,en}.json`) and the panel (`/panel/`).

## Packages

| package | contents |
|---|---|
| `manager` | `ManagerMain` (args, shutdown hook), `Manager` (application context: owns every component, link callbacks, config-change handling, views for the panel), `ManagerLoop` (the state thread), `Tray` |
| `manager.config` | `ConfigStore` (config.json: merge patch → normalise → validate → atomic save → listeners), `SettingsSchema` + `SchemaField` (every field with type/default/limits/unit/i18n keys; the only source of defaults), `ConfigValidator`, `ManagerConfig` (typed immutable view), `Secrets` (secrets.json), `BotConfigFactory` (global → server → bot layering into `BotConfig`) |
| `manager.events` | `EventLog` (events.jsonl, rotation, in-memory tail, listeners), `ManagerEvent`, `SseHub` (per-client bounded queues, 15 s heartbeat), `I18n` (catalog + panel texts, placeholders) |
| `manager.link` | `LinkServer` (TCP JSON lines, hello/secret check, reader/writer threads), `LinkSession` (send queue, queries with futures) |
| `manager.bots` | `BotState` (config + process + link + status + queue of one bot), `BotRegistry` |
| `manager.process` | `ProcessSupervisor` (per-bot HeadlessMC config, staggered launch, launcher log, priority, restart policy, stop/kill) |
| `manager.runtime` | `RuntimeInstaller` (HeadlessMC jar, mods, bot mod, one-time Fabric install + prepare), `Downloader` (HTTPS + Modrinth), `HmcFiles` |
| `manager.tasks` | `TaskQueue` + `QueueEntry` (pure queue bookkeeping), `Dispatcher` (dispatch, manager steps, scenarios, retries, death recovery, heavy limit, queues.json), `KitPlanner` (pure), `TaskCatalog` (catalog.json, argument coercion), `Validators` (kits, scenarios, task templates), `JsonItemStore` (kits.json, scenarios.json) |
| `manager.world` | `WorldDoc` (one server's waypoints/areas/containers/zones/deaths), `WorldStore` (world/<serverId>.json, delayed saves) |
| `manager.gamedata` | `GameData` (immutable: recipes, resolved item/block tags, simplified block loot, `toolFor`, `craftingGrid`, `needsTable`), `GameDataParser` (jar / zip / folder → GameData, pure), `GameDataService` (finds the client jar, loads it on a worker thread, publishes on the loop) |
| `manager.planner` | `Planner` (tick, idle bots, matching, assignments, batches, locks, retries, dispatcher hooks), `WorkSource` (what offers work), `WorkItem`, `Assignment`, pure parts `Matcher`, `RoleTracker`, `Locks`, `RetryBook`, `Sectors`, `Resolver` (deficits → haul/mine/craft/smelt/manual), `Restock` (take planning), `ItemStacks`; `ProductionWork` (runs haul/mine/craft/smelt items for any source) |
| `manager.projects` | `ProjectService` (CRUD, start/pause/resume/stop, projects/<id>.json, SSE `project`, `project_*` events), `Project`, `ProjectKind` + `ProjectRuntime` (per-kind hooks), `BuildKind` + `BuildRuntime` (the build flow) |
| `manager.http` | `HttpApi` (JDK HttpServer, auth, error mapping, static panel), `ApiRoutes` (every endpoint), `Router`, `Req`, `ApiException`, `Schematics` |
| `manager.util` | `AtomicFiles`, `Log` (manager.log), `Tokens`, `Hashing`, `Os`, `Ring`, `LineTail` |

## Threading

* **One state thread.** All mutable manager state (config, registry, queues, world, kits, scenarios, installer state, event log) belongs to `ManagerLoop`. Nothing else is synchronised.
* IO threads post work: link readers (`loop.post(handler.onMessage)`), process exit callbacks (`Process.onExit` → post), the installer thread (`report`/`finished` → post), launch threads (prepare files + `ProcessBuilder.start` off the loop, result posted back).
* HTTP handlers run on virtual threads and call `loop.await(...)` (10 s limit → 503 `busy`). Bot queries: the future is created on the loop, the HTTP thread waits for it (9 s → 504 `timeout`).
* Thread-safe by design: `LinkSession.send` (enqueue only), `SseHub.broadcast`, `BotState.log` (`LineTail`), `Schematics`, `GameData` (immutable; `GameDataService.current()` is volatile).
* Worker threads that post back: `gamedata-loader` (client jar parse), `schematic-prepare-<project>` (hash + footprint + lowest-Y table of a build schematic). Planner queries (`bom`, `progress`) complete on the link reader thread and are posted to the loop (`Planner.query` / `queryVia`).
* Loop code never blocks on the network; small JSON file writes happen on the loop.

## Data flow

* Bot → manager: `status` updates `BotState.status` (+ SSE `bot`; going online resumes the queue), `task_done` → `Dispatcher.onTaskDone`, `event` → event log (+ death/respawn hooks), `container` → `WorldStore.onSnapshot`, `log` → bot log tail + SSE `log`, `plugin` → `BotState.plugin` (last payload per type) + `plugin_*` events, `result` completes the query future on the reader thread.
* Planner: `Dispatcher.add` → `Planner.onQueued` (manual entry on an assigned bot → release + cancel its project entries); `Dispatcher.outcome` → `Planner.onFinished` (result goes into the assignment's batch; when no entry with the source's origin is left, `WorkSource.onBatchDone`). Ticks: `planner.tickSec` timer + `tickSoon()` after an assignment ends; each tick ends with `ProjectService.afterTick` (SSE `project` for running projects, delayed save).
* Config change: `ConfigStore.patch` → listener `Manager.onConfigChanged` → registry sync (removed bots are stopped), `config` re-sent to linked bots whose effective `BotConfig` changed, SSE `bot` for every bot.
* SSE names: `bot` (`{botId,status}` or `{botId,bot}` or `{botId,deleted}`), `process` (`processView` + `botId`), `queue` (`{botId,queue:{current,queued,waiting,runs}}`), `event` (`ManagerEvent`), `runtime` (installer view), `log`, `project` (`{project:view}` or `{id,deleted:true}`), plus `world` (`{serverId}`, not in SPEC).

## Adding an endpoint

1. Add a line to `ApiRoutes.register` (or one of its section methods): `r.post("/api/foo/{id}", q -> ...)`. Register literal paths before `{param}` paths of the same shape.
2. Read input with `q.json()` / `q.param()` / `q.query()` **before** entering the loop, then touch state only inside `loop(() -> ...)`.
3. Return any JSON-able value (`null` = `{"ok":true}`), `new HttpApi.Status(code, body)` for another status, or throw `ApiException` / `ValidationException` (mapped to JSON errors). Auth is automatic for `/api/*`.
4. Add the new error codes to `i18n-catalog` (`error.<code>`).

## Adding a manager-side step

1. Add it to `catalog.json` → `managerSteps` with its args (and `step.<name>.title` / `step.<name>.arg.<arg>` in both catalogs; `CatalogI18nTest` checks this).
2. Handle it in `Dispatcher.runStep`: either expand into bot tasks with `expand(b, List.of(child(e, type, args)))` (children keep the step's origin, so scenario runs track them), run it on the manager (set `b.queue.start(e, now)` and finish later via `outcome` + `dispatch`, like `wait`), or `stepFailed(...)`.
3. Steps never reach the bot; `outcome()` handles failures the same way as bot task failures.

## Adding a setting

Add a `SchemaField` in `SettingsSchema` (defaults come from there; behaviour/client/status defaults come from the common `BotConfig` records), cross-field rules in `ConfigValidator`, a typed accessor in `ManagerConfig` if Java code needs it, and `settings.<path>` + `.desc` (+ `.option.<value>` for enums) in both catalogs.

## Planner and projects

* **Tick** (`Planner.tick`, loop): `gameData.ensureLoaded()` → housekeeping (release assignments of bots offline/dead ≥ 60 s, close batches whose entries vanished) → every registered `WorkSource.workItems(now)` → drop items in backoff or without capacity (`capacity` minus assignments on the same key, minus holders of `lock`) → idle bots (`Planner.isIdle`) → `Matcher.match` (priority − distance/64 − 2 for a role change; allowed roles; strict role cooldown; same server; `WorkSource.allows` + `eligible`) → new `Assignment`, item lock taken, `RoleTracker.assign`, `WorkSource.begin`.
* **Driving an assignment** (source code): `planner.push(a, entries)` queues one batch (`Planner.entry(a, type, args, timeoutSec, label)` builds entries with the source's origin; manager steps like `wait` / `deposit_storage` work too); `planner.query(a, kind, args, timeoutMs, handler)` asks the assigned bot (`queryVia(bot, …)` for any bot); `planner.later(a, ms, r)` retries later; `planner.holdLock(a, key, cap)` / `releaseLock` / `releaseContainers(a)` for container locks; `a.reserved` (container key → items about to be taken) is subtracted by `planner.available(container, a)`; `a.promised` counts as "in transit". End with `planner.finish(a)` (clears the item's failures) or `planner.fail(a, reason, message)` (backoff; 3rd failure → `WorkSource.onItemFailed`). `planner.release(a, why, cancelEntries)` ends it without judging. `onReleased` always runs once.
* **Manual wins / cancels:** any queued entry whose origin is neither `project:*` nor `recovery` releases the bot's assignment (`Planner.onQueued`). A planner task cancelled from the panel releases it too and rests the bot 60 s. `Planner.isPlannerOrigin` decides what counts as planner work — a non-project source must use a `project:`-style origin or extend that method.
* **Projects:** `ProjectService` keeps `Project`s (definition + status + kind `state`) and one `ProjectRuntime` per project (created at load/create, never null). Running = registered with the planner (`addSource`); pause/stop/done/failed = `removeSource` (cancels its assignments) + `runtime.stop()`. Runtimes report with `svc.done(p)`, `svc.failed(p, msg)`, `svc.blocked(p, key, reason, data)` (event `project_blocked`, throttled 10 min per key; `unblocked` re-arms it) and `svc.changed(p)` (save + SSE). `runtime.persist()` is written into `projects/<id>.json` on every save.
* **Build flow** (`BuildRuntime`): prepare (worker: hash, footprint, lowest-Y table) → `bom` query (cached by hash + placement) → sectors (`Sectors.split`, debounced `Sectors.resplit` when the builder count changes) → survey of never-checked sectors → work items `build_sector` (role builder, lock `sector:<id>:<i>`, capacity `maxBuildersPerSector`, priority + 1) and production items from `Resolver.resolve(bom, remaining, stock, gameData, env)` via `ProductionWork.items` → builder state machine `check → [inspect] → restock (take) → build → check …` with a sweep (`goto` + `progress` per ≤ 32-block piece) when only unloaded positions remain; final pass = every sector `verify`. See DECISIONS `manager: build projects` / `deficit resolution`.

## Adding a project kind

1. Implement `ProjectKind` (`kind()`, `normalizeConfig(config, serverId)` throwing `ValidationException` with paths relative to `config`, `runtime(p, svc)`) and a `ProjectRuntime` (a `WorkSource` plus `start(restart)`, `stop()`, `view(full)`, `persist()`); read saved state from `p.state` in the constructor.
2. Register it in the `ProjectService` constructor (`register(new FooKind(m))`).
3. Add `{"kind": "foo", "supported": true, "args": [...]}` to `catalog.json` → `projectKinds` (the panel builds the create form from it; field types as in `panel/js/forms.js`: `pos`, `dim`, `box`, `containers`, `items`, `item_counts`, `enum`, `int`, ...) and labels `pfield.<arg>` (or `project.foo.arg.<arg>`) + `kind.foo` / `kind.foo.desc` in both panel catalogs.
4. Reuse `ProductionWork` (haul/mine/craft/smelt into the runtime's `ProductionWork.Host` containers) and `Resolver` for anything "keep N of item X": pass `remaining` = what is still wanted and the stock from `planner.available(...)`.

## Hooks for the next phase (autopilot, standing orders, goals, other kinds)

* **Standing orders** (SPEC §5.7b): a `WorkSource` (or a project kind `orders`) whose `workItems` runs `Resolver.resolve(min-stock targets, missing amounts, stock, gameData, env)` and returns `ProductionWork.items(...)`; delivery target = the order's `into` containers via `ProductionWork.Host.supply()`.
* **Goals / `obtain`** (SPEC §5.7b2): `Resolver` already walks storage → mine → craft → smelt with tool needs available from `GameData.toolFor`; `ProductionWork.hasTool` / `toolTier` give the bot-side check. A manager step `obtain` can be added in `Dispatcher.runStep` that re-plans from the bot's live inventory after every finished task (the step-expansion pattern of `kit`).
* **Autopilot** (SPEC §5.7a): auto-supply belongs in front of `Dispatcher.dispatch` (insert `take` before tasks); auto-sort and idle work fit a `WorkSource` that only offers items to bots with nothing else to do (low priority) — `Planner.isIdle` already excludes bots with manual or project work.
* **Other project kinds:** gather = Resolver with quotas into storage; clear = `selection clear` per `Sectors.split` piece (reuse sector locks); farm/ranch = one long-running item per field/pen with the matching role; sort = `transfer` items from inbox containers; smelt = `ProductionWork` smelt items over a furnace array.
* **Game data** for sorting categories: `GameData.itemTag(...)`; for anything else `GameData.describe(item)` (also `GET /api/gamedata/item/{id}`).
* **Microsoft accounts:** `ProcessSupervisor.start` rejects them; the HeadlessMC `login` flow and `/api/bots/{id}/microsoft-login` (501 today) go there.

## Tests

`TaskQueueTest`, `KitPlannerTest`, `ConfigTest` (merge patch, defaults, validation, presets, layering), `CatalogI18nTest` (catalog vs SPEC, every label in RU+EN, every event key used in code), `LaunchFilesTest` (HeadlessMC properties, options.txt, Baritone settings), `LinkDispatchTest` (fake bots over a real TCP link: handshake, inventory_full → deposit + retry, scenario + death recovery, heavy limit, container snapshots, companion relay), `GameDataTest` (fixture tree in `src/test/resources/gamedata`: tags, recipes, loot, tools, jar lookup), `SectorsTest`, `ResolverTest` (deficits with the fixture game data), `MatchingTest` (scores, capacity, role hysteresis, locks, retries, restock planning) and `BuildProjectFlowTest` (a scripted bot: BOM → restock → build → verify → done, manual task wins, hauler fills supply, API validation).
