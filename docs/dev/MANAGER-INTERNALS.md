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
| `manager.http` | `HttpApi` (JDK HttpServer, auth, error mapping, static panel), `ApiRoutes` (every endpoint), `Router`, `Req`, `ApiException`, `Schematics` |
| `manager.util` | `AtomicFiles`, `Log` (manager.log), `Tokens`, `Hashing`, `Os`, `Ring`, `LineTail` |

## Threading

* **One state thread.** All mutable manager state (config, registry, queues, world, kits, scenarios, installer state, event log) belongs to `ManagerLoop`. Nothing else is synchronised.
* IO threads post work: link readers (`loop.post(handler.onMessage)`), process exit callbacks (`Process.onExit` → post), the installer thread (`report`/`finished` → post), launch threads (prepare files + `ProcessBuilder.start` off the loop, result posted back).
* HTTP handlers run on virtual threads and call `loop.await(...)` (10 s limit → 503 `busy`). Bot queries: the future is created on the loop, the HTTP thread waits for it (9 s → 504 `timeout`).
* Thread-safe by design: `LinkSession.send` (enqueue only), `SseHub.broadcast`, `BotState.log` (`LineTail`), `Schematics`.
* Loop code never blocks on the network; small JSON file writes happen on the loop.

## Data flow

* Bot → manager: `status` updates `BotState.status` (+ SSE `bot`; going online resumes the queue), `task_done` → `Dispatcher.onTaskDone`, `event` → event log (+ death/respawn hooks), `container` → `WorldStore.onSnapshot`, `log` → bot log tail + SSE `log`, `result` completes the query future on the reader thread.
* Config change: `ConfigStore.patch` → listener `Manager.onConfigChanged` → registry sync (removed bots are stopped), `config` re-sent to linked bots whose effective `BotConfig` changed, SSE `bot` for every bot.
* SSE names: `bot` (`{botId,status}` or `{botId,bot}` or `{botId,deleted}`), `process` (`processView` + `botId`), `queue` (`{botId,queue:{current,queued,waiting,runs}}`), `event` (`ManagerEvent`), `runtime` (installer view), `log`, plus `world` (`{serverId}`, not in SPEC).

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

## Extension points for the phase-2 planner

* **Work source:** a `Planner` component ticking every `planner.tickSec` on the loop can read `bots.all()` (idle = `queue.isIdle() && online()`), the world (`worlds.get(serverId)`: containers with roles and snapshots) and push entries with `dispatcher.add(b, entries, mode)` using `origin = TaskSpec.projectOrigin(id)`.
* **Manual tasks pause project work:** check `b.queue.anyMatch(e -> !e.origin().startsWith("project:"))` before assigning.
* **Results:** `Dispatcher.outcome` is the single place every finished entry passes; add a listener there (e.g. `Consumer<FinishedEntry>`) to feed project progress.
* **Locks / sectors:** `Box.split` in common; the heavy-task limiter in `Dispatcher.heavyLimitReached` shows where admission checks go.
* **Queries the planner needs** (`bom`, `progress`, `recipe_book`) are already routed (`LinkSession.query`); the mod answers `unsupported` today.
* **HTTP:** `/api/projects*` is registered in `ApiRoutes.projects` and answers 501; replace those handlers. The state snapshot already has `projects: []`, and SSE has the `project` name reserved.
* **Microsoft accounts:** `ProcessSupervisor.start` rejects them; the HeadlessMC `login` flow and `/api/bots/{id}/microsoft-login` (501 today) go there.
* **Companion plugin:** `Manager.onMessage` drops `plugin` messages; `/api/bots/{id}/plugin` already forwards to the bot.

## Tests

`TaskQueueTest`, `KitPlannerTest`, `ConfigTest` (merge patch, defaults, validation, presets, layering), `CatalogI18nTest` (catalog vs SPEC, every label in RU+EN, every event key used in code), `LaunchFilesTest` (HeadlessMC properties, options.txt, Baritone settings) and `LinkDispatchTest` (fake bots over a real TCP link: handshake, inventory_full → deposit + retry, scenario + death recovery, heavy limit, container snapshots).
