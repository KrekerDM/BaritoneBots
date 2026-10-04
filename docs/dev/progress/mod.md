# bot-mod progress

- done: MVP complete, `:bot-mod:build` passes (2026-10-03).
- done: fabric.mod.json + baritonebots.mixins.json; LowPower + Options/GameRenderer/SoundEngine/Minecraft mixins (require = 0)
- done: BaritoneBotsMod, BotRuntime (dispatch, tick order), LinkClient (kept), Baritone hooks (pause/defense processes, logger, CALC_FAILED counter), settings applier (kept)
- done: behaviours Connection, Login, Life (death/respawn/damaged/tool_low), Eater/Eat, Defense, ContainerSensor, StatusReporter
- done: task framework (TaskExecutor/Context/Registry/Manager, PathStep, ContainerSession, ClickQueue, TaskArgs) + tasks goto, goto_player, follow, explore, baritone, mine, farm, selection(clear), collect_drops, recover, eat, idle, take, deposit, inspect, equip
- done: QueryHandler (inventory, entities, player, block_at, containers_nearby)
- done: docs/dev/MOD-INTERNALS.md, DECISIONS.md "mod:" entries
- done: MVP tasks goto, take, equip, mine, deposit live-tested on a Paper 26.2 server.
- done (phase 2A, 2026-10-04): craft (recipe book + manual grid, 2×2 and table), smelt_load, smelt_collect, transfer (SubTask take+deposit), drop, attack, guard (Fighter), selection fill/walls/shell/replace, recipe_book query, protection guard mixin, companion bridge; ContainerSession.playerInventory/returnCarried/mainSlots; JUnit tests for mod.task.plan; catalog.json flags updated; `:bot-mod:build` and `:manager:build` pass.
- done (phase 2B, 2026-10-04): build (Baritone schematic system + `PlacedSchematic`, rotation/mirror like common, box = crop mask, missing_materials scan), bom and progress queries (async parse, time-sliced chunk-column scans), breed / slaughter / shear (`AnimalPen`, `Fighter.approach`), mine `strategy=legit` / `y` / `fakeOres` (`FakeOreWatcher`); JUnit tests for Placement, BomRules, AnimalFood, LegitMining; catalog flags + mine/build args + i18n; `:bot-mod:build` and `:manager:build` pass.
- next: live test of the phase 2A/2B tasks (build with a rotated litematic, bom/progress on a 100k+ schematic, breed/shear in a fenced pen, legit mining on an anti-xray server) and the companion handshake on the Paper server; manager side of the companion bridge (Manager drops bot 'plugin' messages).
