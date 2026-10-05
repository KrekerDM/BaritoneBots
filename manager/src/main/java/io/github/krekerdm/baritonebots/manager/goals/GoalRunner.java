package io.github.krekerdm.baritonebots.manager.goals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.autopilot.Inventory;
import io.github.krekerdm.baritonebots.manager.autopilot.SupplyPlanner;
import io.github.krekerdm.baritonebots.manager.autopilot.TaskNeeds;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.ItemStacks;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manager-side goal steps (SPEC §5.7b2). {@code obtain {item, count}} plans one step with {@link ObtainPlanner} from
 * the bot's live inventory, queues it (take / craft / place / smelt / mine) followed by the same {@code obtain} again,
 * and so re-plans after every finished sub-task until the inventory holds the items, a cycle guard of
 * {@link #MAX_STEPS} planning steps trips, or the same action failed {@link #MAX_SAME_FAILURES} times.
 * {@code progress {tier}} expands into {@code obtain} steps for the tier's tools, armor, torches and food, then
 * {@code equip}. Blocks are placed with a one-block {@code selection fill} (the mod has no place task) at a free spot
 * next to the bot found with {@code block_at} queries; the placed table / furnace is added to the world index.
 * Loop-owned.
 */
public final class GoalRunner {
    public static final int MAX_STEPS = 200;
    static final int MAX_SAME_FAILURES = 3;
    static final int MAX_FAILURES = 8;
    static final int TABLE_RADIUS = 16;
    static final int FURNACE_RADIUS = 48;
    static final int STORAGE_RADIUS = 128;
    static final int MAX_SMELT = 64;
    /** {@code obtain} item meaning "any food" (the soft food part of {@code progress}). */
    public static final String FOOD = "@food";
    public static final List<String> TIERS = List.of("wood", "stone", "iron", "diamond");
    private static final Set<String> REPLACEABLE = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:grass", "minecraft:fern",
            "minecraft:large_fern", "minecraft:snow", "minecraft:dead_bush", "minecraft:short_dry_grass",
            "minecraft:tall_dry_grass", "minecraft:bush");

    private static final class Run {
        final String id;
        final String botId;
        final String item;
        final int count;
        final boolean soft;
        int steps;
        int failures;
        final Map<String, Integer> actionFailures = new HashMap<>();
        String lastAction;
        String lastFailure;
        Map<String, Integer> lastMissing = Map.of();
        /** The obtain entry planning right now. */
        String entryId;

        Run(String id, String botId, String item, int count, boolean soft) {
            this.id = id;
            this.botId = botId;
            this.item = item;
            this.count = count;
            this.soft = soft;
        }
    }

    /** A placement in flight: where and what. */
    private record Placement(String goalId, String serverId, String dim, Pos pos, String block) {
    }

    private final Manager m;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, String> childOf = new HashMap<>();
    private final Map<String, Placement> placements = new HashMap<>();

    public GoalRunner(Manager m) {
        this.m = m;
    }

    private Dispatcher d() {
        return m.dispatcher;
    }

    // ------------------------------------------------------------------ progress

    /** {@code progress {tier}}: obtain the tier's tools, armor, torches and food, then equip. */
    public void progress(BotState b, QueueEntry e) {
        String tier = Json.getString(e.args(), "tier", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!TIERS.contains(tier)) {
            d().failStep(b, e, Reasons.BAD_ARGS, "tier must be one of " + TIERS);
            return;
        }
        List<QueueEntry> children = new ArrayList<>();
        for (JsonObject goal : preset(tier)) {
            children.add(d().childEntry(e, Dispatcher.STEP_OBTAIN, goal, 0));
        }
        children.add(d().childEntry(e, TaskTypes.EQUIP, Json.obj("armor", true), 60));
        m.event("goal_started", Levels.INFO, b.id, "event.goal.progress", Map.of("bot", b.id, "tier", tier));
        d().expandStep(b, children);
    }

    /** Goals of a {@code progress} tier: tools (hard), armor (hard for iron / diamond, leather otherwise soft), torches and food (soft). */
    public static List<JsonObject> preset(String tier) {
        String p = switch (tier) {
            case "stone" -> "stone_";
            case "iron" -> "iron_";
            case "diamond" -> "diamond_";
            default -> "wooden_";
        };
        List<JsonObject> out = new ArrayList<>();
        for (String tool : List.of("pickaxe", "axe", "shovel", "sword")) {
            out.add(Json.obj("item", "minecraft:" + p + tool, "count", 1));
        }
        boolean metal = "iron".equals(tier) || "diamond".equals(tier);
        String armor = metal ? p : "leather_";
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            JsonObject g = Json.obj("item", "minecraft:" + armor + piece, "count", 1);
            if (!metal) {
                g.addProperty("soft", true);
            }
            out.add(g);
        }
        out.add(Json.obj("item", "minecraft:torch", "count", 32, "soft", true));
        out.add(Json.obj("item", FOOD, "count", 32, "soft", true));
        return out;
    }

    // ------------------------------------------------------------------ obtain

    public void obtain(BotState b, QueueEntry e) {
        String item = Json.getString(e.args(), "item", "").trim();
        int count = Math.max(1, Json.getInt(e.args(), "count", 1));
        if (item.isEmpty()) {
            d().failStep(b, e, Reasons.BAD_ARGS, "obtain needs an item");
            return;
        }
        String norm = FOOD.equals(item) ? FOOD : Ids.normalize(item);
        String goalId = Json.getString(e.args(), "_goal", null);
        Run run = goalId == null ? null : runs.get(goalId);
        if (run == null) {
            run = new Run(goalId != null ? goalId : Tokens.id("g"), b.id, norm, count, Json.getBool(e.args(), "soft", false));
            runs.put(run.id, run);
        }
        run.steps++;
        run.entryId = e.id();
        d().startStep(b, e);
        if (run.steps > MAX_STEPS) {
            fail(b, e, run, "cycle guard: " + MAX_STEPS + " planning steps; still missing " + describe(run.lastMissing));
            return;
        }
        Run r = run;
        m.autopilot.inventory(b, inv -> plan(b, e, r, inv));
    }

    private void plan(BotState b, QueueEntry e, Run run, Inventory inv) {
        if (!d().isRunning(b, e)) {
            return;
        }
        if (run.failures >= MAX_FAILURES || run.lastAction != null
                && run.actionFailures.getOrDefault(run.lastAction, 0) >= MAX_SAME_FAILURES) {
            fail(b, e, run, "repeated failures (" + run.lastFailure + ") at " + run.lastAction);
            return;
        }
        try {
            if (FOOD.equals(run.item)) {
                planFood(b, e, run, inv);
                return;
            }
            ObtainPlanner.World w = world(b, inv);
            ObtainPlanner.Step s = ObtainPlanner.next(run.item, run.count, w);
            if (s.done()) {
                done(b, e, run, inv.count(run.item));
                return;
            }
            if (s.failed()) {
                run.lastMissing = s.missing();
                fail(b, e, run, "cannot obtain " + describe(s.missing()) + " (not in storage, no recipe, nothing to mine)");
                return;
            }
            run.lastMissing = Map.of(run.item, Math.max(0, run.count - inv.count(run.item)));
            ObtainPlanner.Action first = s.actions().getFirst();
            run.lastAction = first.key();
            if (ObtainPlanner.PLACE.equals(first.kind())) {
                place(b, e, run, first.block());
                return;
            }
            List<QueueEntry> children = new ArrayList<>();
            for (ObtainPlanner.Action a : s.actions()) {
                children.addAll(entries(b, e, a, w, inv));
            }
            if (children.isEmpty()) {
                fail(b, e, run, "no step possible for " + Ids.path(run.item));
                return;
            }
            continueWith(b, e, run, children);
        } catch (RuntimeException ex) {
            Log.error("obtain planning failed for " + b.id, ex);
            fail(b, e, run, "planning error: " + ex.getMessage());
        }
    }

    /** Queues the step's entries and the same goal again; the obtain step itself reports nothing. */
    private void continueWith(BotState b, QueueEntry e, Run run, List<QueueEntry> children) {
        for (QueueEntry c : children) {
            childOf.put(c.id(), run.id);
        }
        JsonObject again = e.args().deepCopy();
        again.addProperty("_goal", run.id);
        List<QueueEntry> all = new ArrayList<>(children);
        all.add(d().childEntry(e, Dispatcher.STEP_OBTAIN, again, 0));
        d().finishStep(b, e, true, null, null, null, all, true);
    }

    private void done(BotState b, QueueEntry e, Run run, int have) {
        runs.remove(run.id);
        String label = FOOD.equals(run.item) ? "food" : Ids.path(run.item);
        m.event("goal_done", Levels.INFO, b.id, "event.goal.done", Map.of("bot", b.id, "item", label, "count", have));
        d().finishStep(b, e, true, null, "have " + have + " × " + label, Json.obj("item", run.item, "have", have,
                "steps", run.steps), null, false);
    }

    private void fail(BotState b, QueueEntry e, Run run, String why) {
        runs.remove(run.id);
        String label = FOOD.equals(run.item) ? "food" : Ids.path(run.item);
        JsonObject data = Json.obj("item", run.item, "count", run.count, "steps", run.steps,
                "missing", Json.toTree(run.lastMissing));
        if (run.soft) {
            m.event("goal_skipped", Levels.WARN, b.id, "event.goal.skipped", Map.of("bot", b.id, "item", label,
                    "reason", why), data);
            d().finishStep(b, e, true, null, "skipped: " + why, data, null, false);
        } else {
            m.event("goal_failed", Levels.WARN, b.id, "event.goal.failed", Map.of("bot", b.id, "item", label,
                    "reason", why), data);
            d().finishStep(b, e, false, Reasons.MISSING_MATERIALS, why, data, null, false);
        }
    }

    private static String describe(Map<String, Integer> missing) {
        if (missing == null || missing.isEmpty()) {
            return "?";
        }
        List<String> parts = new ArrayList<>();
        missing.forEach((k, v) -> parts.add(v + " × " + Ids.path(k)));
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ entries for one action

    private List<QueueEntry> entries(BotState b, QueueEntry e, ObtainPlanner.Action a, ObtainPlanner.World w, Inventory inv) {
        List<QueueEntry> out = new ArrayList<>();
        switch (a.kind()) {
            case ObtainPlanner.TAKE -> {
                JsonArray items = new JsonArray();
                a.take().forEach((id, n) -> items.add(Json.obj("item", id, "count", n)));
                out.add(d().childEntry(e, TaskTypes.TAKE, Json.obj("container", a.container().pos(), "items", items), 180));
            }
            case ObtainPlanner.CRAFT -> {
                JsonObject args = Json.obj("item", a.item(), "count", Math.min(2304, a.count()),
                        "grid", GameData.craftingGridJson(a.recipe()));
                if (GameData.needsTable(a.recipe()) && w.table() != null) {
                    args.add("table", Json.toTree(w.table()));
                }
                out.add(d().childEntry(e, TaskTypes.CRAFT, args, 600));
            }
            case ObtainPlanner.SMELT -> {
                Pos furnace = w.furnaces().get(a.recipe().type());
                if (furnace == null) {
                    return out;
                }
                int count = Math.min(MAX_SMELT, a.count());
                int fuelCount = (int) Math.ceil(count / ItemStacks.burnItems(a.fuel()));
                boolean fast = !GameData.SMELTING.equals(a.recipe().type());
                out.add(d().childEntry(e, TaskTypes.SMELT_LOAD, Json.obj("furnace", furnace, "input", a.input(),
                        "count", count, "fuel", a.fuel(), "fuelCount", fuelCount), 300));
                out.add(d().childEntry(e, Dispatcher.STEP_WAIT, Json.obj("sec", count * (fast ? 5 : 10) + 5), 0));
                out.add(d().childEntry(e, TaskTypes.SMELT_COLLECT, Json.obj("furnace", furnace, "all", false), 300));
            }
            case ObtainPlanner.MINE -> {
                int budget = Math.max(1, inv.freeSlots() - 1) * ItemStacks.maxStack(a.item());
                String ax = b.def.serverId() == null ? null : m.config.get().server(b.def.serverId())
                        .map(ManagerConfig.ServerProfile::antiXray).orElse(null);
                out.add(d().childEntry(e, TaskTypes.MINE, ProductionWork.mineArgs(Json.arrOf(a.blocks()),
                        Math.min(a.count(), budget), ax), 1800));
            }
            default -> {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ placing a table / furnace

    /** Finds a free spot next to the bot ({@code block_at} on 8 candidates and the block below), then places. */
    private void place(BotState b, QueueEntry e, Run run, String block) {
        Pos at = Planner.posOf(b);
        if (at == null || b.def.serverId() == null) {
            fail(b, e, run, "bot position unknown");
            return;
        }
        String botDim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        List<Pos> candidates = placeSpots(at, m.zoneBoxes(b.def.serverId(), botDim));
        Map<Pos, String> blocks = new HashMap<>();
        int[] pending = {candidates.size() * 2};
        Runnable decide = () -> {
            if (--pending[0] > 0 || !d().isRunning(b, e)) {
                return;
            }
            Pos spot = candidates.getFirst();
            for (Pos c : candidates) {
                if (replaceable(blocks.get(c)) && solid(blocks.get(c.offset(0, -1, 0)))) {
                    spot = c;
                    break;
                }
            }
            String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
            QueueEntry sel = d().childEntry(e, TaskTypes.SELECTION, Json.obj("op", "fill",
                    "box", Json.toTree(new Box(spot, spot)), "block", block), 120);
            placements.put(sel.id(), new Placement(run.id, b.def.serverId(), dim, spot, block));
            continueWith(b, e, run, List.of(sel));
        };
        for (Pos c : candidates) {
            for (Pos p : List.of(c, c.offset(0, -1, 0))) {
                m.planner.queryVia(b, QueryKinds.BLOCK_AT, Json.obj("pos", p), 3_000, (r, t) -> {
                    if (r != null && r.ok() && r.data() != null && Json.getBool(r.data(), "loaded", true)) {
                        blocks.put(p, Ids.stripState(Json.getString(r.data(), "block", "")));
                    }
                    decide.run();
                });
            }
        }
    }

    /**
     * Spots for a table / furnace: 8 around the bot, 2 blocks away, outside protection zones (SPEC §5.7g: a bot never
     * places anything in the user's house); when the bot stands inside a zone, the spots just outside that zone's walls
     * at the bot's height instead.
     */
    static List<Pos> placeSpots(Pos at, List<Box> zones) {
        List<Pos> out = new ArrayList<>();
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}}) {
            Pos p = at.offset(o[0], 0, o[1]);
            if (zones.stream().noneMatch(z -> z.contains(p))) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            for (Box z : zones) {
                if (z.contains(at)) {
                    for (Pos p : List.of(new Pos(z.min().x() - 2, at.y(), at.z()), new Pos(z.max().x() + 2, at.y(), at.z()),
                            new Pos(at.x(), at.y(), z.min().z() - 2), new Pos(at.x(), at.y(), z.max().z() + 2))) {
                        if (zones.stream().noneMatch(o -> o.contains(p))) {
                            out.add(p);
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            out.add(at.offset(2, 0, 0)); // nowhere free: the guard refuses it inside a zone and the goal reports it
        }
        return out;
    }

    static boolean replaceable(String block) {
        return block != null && REPLACEABLE.contains(Ids.normalize(block));
    }

    static boolean solid(String block) {
        if (block == null || replaceable(block)) {
            return false;
        }
        String p = Ids.path(block);
        return !p.equals("water") && !p.equals("lava") && !p.endsWith("_leaves") && !p.contains("torch");
    }

    // ------------------------------------------------------------------ food (soft)

    private void planFood(BotState b, QueueEntry e, Run run, Inventory inv) {
        TaskNeeds.Context ctx = m.autopilot.needsContext(b);
        int have = inv.count(ctx::isFood);
        if (have >= run.count) {
            done(b, e, run, have);
            return;
        }
        int missing = run.count - have;
        run.lastMissing = Map.of("food", missing);
        TaskNeeds.Need need = new TaskNeeds.Need(TaskNeeds.Kind.FOOD, List.of(), missing, missing, null, null, false);
        SupplyPlanner.Plan plan = SupplyPlanner.plan(List.of(need), m.autopilot.supplySources(b), ctx,
                Math.max(1, inv.freeSlots() - 1));
        if (!plan.isEmpty()) {
            run.lastAction = "food:take";
            List<QueueEntry> children = new ArrayList<>();
            for (SupplyPlanner.Take t : plan.takes()) {
                JsonArray items = new JsonArray();
                t.items().forEach((id, n) -> items.add(Json.obj("item", id, "count", n)));
                children.add(d().childEntry(e, TaskTypes.TAKE, Json.obj("container", t.container().pos(), "items", items), 180));
            }
            continueWith(b, e, run, children);
            return;
        }
        // cook raw food the bot carries
        GameData data = m.gameData.current() == null ? GameData.empty() : m.gameData.current();
        for (Map.Entry<String, Integer> it : inv.items().entrySet()) {
            for (GameData.Recipe r : data.cookingRecipesUsing(it.getKey())) {
                if (GameData.SMELTING.equals(r.type()) && ctx.isFood(r.result()) && !ctx.isFood(it.getKey())) {
                    ObtainPlanner.World w = world(b, inv);
                    int n = Math.min(missing, it.getValue());
                    ObtainPlanner.Step s = ObtainPlanner.next(r.result(), inv.count(r.result()) + n, w);
                    if (!s.failed() && !s.done()) {
                        ObtainPlanner.Action first = s.actions().getFirst();
                        run.lastAction = "food:" + first.key();
                        if (ObtainPlanner.PLACE.equals(first.kind())) {
                            place(b, e, run, first.block());
                            return;
                        }
                        List<QueueEntry> children = new ArrayList<>();
                        for (ObtainPlanner.Action a : s.actions()) {
                            children.addAll(entries(b, e, a, w, inv));
                        }
                        if (!children.isEmpty()) {
                            continueWith(b, e, run, children);
                            return;
                        }
                    }
                }
            }
        }
        fail(b, e, run, "no food in storage and nothing raw to cook");
    }

    /**
     * A {@code craft} (manual, scenario, rule) without {@code grid}: the grid of the item's first crafting recipe (the
     * one auto-supply fetches for) and, when it needs a table, the nearest known one, like the obtain goal's craft.
     */
    public void fillCraftGrid(BotState b, JsonObject args) {
        JsonArray grid = Json.getArr(args, "grid");
        boolean given = args.has("grid") && !args.get("grid").isJsonNull() && (grid == null || !grid.isEmpty());
        String item = Json.getString(args, "item", null);
        GameData data = m.gameData.current();
        if (given || item == null || item.isBlank() || data == null) {
            return;
        }
        List<GameData.Recipe> recipes = data.craftingRecipesFor(item);
        if (recipes.isEmpty()) {
            return;
        }
        GameData.Recipe r = recipes.getFirst();
        args.add("grid", GameData.craftingGridJson(r));
        if (GameData.needsTable(r) && !args.has("table")) {
            Pos table = world(b, Inventory.fromStatus(b.status)).table();
            if (table != null) {
                args.add("table", Json.toTree(table));
            }
        }
    }

    // ------------------------------------------------------------------ the world around the bot

    ObtainPlanner.World world(BotState b, Inventory inv) {
        String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        Pos at = Planner.posOf(b);
        List<ObtainPlanner.Source> storage = new ArrayList<>();
        for (SupplyPlanner.Source s : m.autopilot.supplySources(b)) {
            if (s.available() != null && (at == null || s.container().pos().distance(at) <= STORAGE_RADIUS)) {
                storage.add(new ObtainPlanner.Source(s.container(), s.available()));
            }
        }
        Pos table = null;
        Map<String, Pos> furnaces = new LinkedHashMap<>();
        WorldDoc doc = b.def.serverId() == null ? null : m.worlds.get(b.def.serverId());
        if (doc != null) {
            List<WorldDoc.Container> near = doc.containers.stream()
                    .filter(c -> Dims.normalize(c.dim()).equals(dim))
                    .sorted(Comparator.comparingDouble(c -> at == null ? 0 : c.pos().distance(at))).toList();
            Map<String, Pos> busy = new LinkedHashMap<>();
            for (WorldDoc.Container c : near) {
                double dist = at == null ? 0 : c.pos().distance(at);
                String block = c.block() == null ? "" : c.block();
                if (table == null && dist <= TABLE_RADIUS && (block.endsWith("crafting_table") || c.hasRole("crafting"))) {
                    table = c.pos();
                }
                if (dist <= FURNACE_RADIUS && (block.endsWith("furnace") || block.endsWith("smoker") || c.hasRole("furnace"))) {
                    // a furnace nobody else works at and with nothing in its input slot first
                    boolean free = m.planner.locks().count(Planner.containerKey(c)) == 0 && (c.snapshot() == null
                            || c.snapshot().items().stream().noneMatch(s -> s.slot() == 0 && s.count() > 0));
                    (free ? furnaces : busy).putIfAbsent(recipeType(block), c.pos());
                }
            }
            busy.forEach(furnaces::putIfAbsent);
        }
        return new ObtainPlanner.World(inv.items(), storage, table, furnaces, m.gameData.current());
    }

    static String recipeType(String block) {
        if (block.endsWith("blast_furnace")) {
            return GameData.BLASTING;
        }
        if (block.endsWith("smoker")) {
            return GameData.SMOKING;
        }
        return GameData.SMELTING;
    }

    // ------------------------------------------------------------------ bookkeeping

    /** Every finished entry (from the dispatcher): counts failures of goal sub-tasks, indexes placed blocks. */
    public void onOutcome(BotState b, QueueEntry e, boolean requeued, boolean ok, String reason) {
        Placement pl = placements.remove(e.id());
        if (pl != null && ok) {
            m.worlds.mergeDiscovered(pl.serverId(), Json.arr(Json.obj("pos", pl.pos(), "dim", pl.dim(), "block", pl.block())));
            m.broadcastWorld(pl.serverId());
        }
        String gid = childOf.remove(e.id());
        if (gid == null && pl != null) {
            gid = pl.goalId();
        }
        Run run = gid == null ? null : runs.get(gid);
        if (run == null || requeued || ok || Reasons.CANCELLED.equals(reason)) {
            return;
        }
        run.failures++;
        run.lastFailure = reason == null ? Reasons.ERROR : reason;
        if (run.lastAction != null) {
            run.actionFailures.merge(run.lastAction, 1, Integer::sum);
        }
    }

    /** A sub-task was re-queued under a new id (stuck retry). */
    public void remap(String oldId, String newId) {
        String gid = childOf.remove(oldId);
        if (gid != null) {
            childOf.put(newId, gid);
        }
        Placement pl = placements.remove(oldId);
        if (pl != null) {
            placements.put(newId, pl);
        }
    }

    public void forgetBot(String botId) {
        List<String> ids = runs.values().stream().filter(r -> r.botId.equalsIgnoreCase(botId)).map(r -> r.id).toList();
        ids.forEach(runs::remove);
        childOf.values().removeIf(ids::contains);
        placements.values().removeIf(p -> ids.contains(p.goalId()));
    }

    /** Drops runs whose {@code obtain} step left the queue (scenario stopped, entry removed by hand). */
    public void sweep() {
        for (Run r : List.copyOf(runs.values())) {
            BotState b = m.bots.get(r.botId);
            QueueEntry cur = b == null ? null : b.queue.current();
            boolean alive = b != null && (cur != null && cur.id().equals(r.entryId) || hasGoal(cur, r.id)
                    || b.queue.anyMatch(x -> hasGoal(x, r.id)));
            if (!alive) {
                runs.remove(r.id);
                childOf.values().removeIf(r.id::equals);
                placements.values().removeIf(p -> p.goalId().equals(r.id));
            }
        }
    }

    private static boolean hasGoal(QueueEntry e, String goalId) {
        return e != null && Dispatcher.STEP_OBTAIN.equals(e.type()) && goalId.equals(Json.getString(e.args(), "_goal", null));
    }

    public JsonObject view() {
        JsonArray a = new JsonArray();
        runs.values().forEach(r -> a.add(Json.obj("id", r.id, "botId", r.botId, "item", r.item, "count", r.count,
                "steps", r.steps, "failures", r.failures, "lastAction", r.lastAction, "soft", r.soft)));
        return Json.obj("goals", a);
    }
}
