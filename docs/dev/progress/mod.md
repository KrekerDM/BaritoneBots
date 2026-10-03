# bot-mod progress

- done: MVP complete, `:bot-mod:build` passes (2026-10-03).
- done: fabric.mod.json + baritonebots.mixins.json; LowPower + Options/GameRenderer/SoundEngine/Minecraft mixins (require = 0)
- done: BaritoneBotsMod, BotRuntime (dispatch, tick order), LinkClient (kept), Baritone hooks (pause/defense processes, logger, CALC_FAILED counter), settings applier (kept)
- done: behaviours Connection, Login, Life (death/respawn/damaged/tool_low), Eater/Eat, Defense, ContainerSensor, StatusReporter
- done: task framework (TaskExecutor/Context/Registry/Manager, PathStep, ContainerSession, ClickQueue, TaskArgs) + tasks goto, goto_player, follow, explore, baritone, mine, farm, selection(clear), collect_drops, recover, eat, idle, take, deposit, inspect, equip
- done: QueryHandler (inventory, entities, player, block_at, containers_nearby)
- done: docs/dev/MOD-INTERNALS.md, DECISIONS.md "mod:" entries
- next (phase 2): craft, smelt_*, breed, slaughter, shear, build, transfer, drop, guard, attack, selection fill/walls/shell/replace, bom/progress/recipe_book queries, companion plugin bridge, protection guard; runtime test against a real 26.2 server
