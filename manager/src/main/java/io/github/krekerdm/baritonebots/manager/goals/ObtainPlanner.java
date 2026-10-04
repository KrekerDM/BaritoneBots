package io.github.krekerdm.baritonebots.manager.goals;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.ItemStacks;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.planner.Resolver;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The next step towards "have {@code count} × {@code item}" for one bot (SPEC §5.7b2), planned from its live
 * inventory. A full simulation runs every time: the inventory is the pool; a missing item is taken from storage,
 * else crafted (ingredients recurse; a crafting table is placed when none is near), else smelted (furnace placed when
 * none is near, fuel chosen from what is at hand, else coal, else logs), else mined with the required tool tier
 * (missing tool → that tool is obtained first), else it is "manual". Actions are recorded dependencies first and
 * merged per kind + item, so one round mines all the logs the whole tree needs. The first <em>ready</em> action (all
 * inputs in the inventory now) is the next step; ready takes are batched. Pure.
 */
public final class ObtainPlanner {
    public static final String TAKE = "take";
    public static final String CRAFT = "craft";
    public static final String SMELT = "smelt";
    public static final String MINE = "mine";
    public static final String PLACE = "place";
    public static final String CRAFTING_TABLE = "minecraft:crafting_table";
    public static final String FURNACE = "minecraft:furnace";
    static final int MAX_DEPTH = 10;
    static final double INF = 1e9;
    private static final List<String> STRONG_FUELS = List.of("minecraft:coal_block", "minecraft:coal",
            "minecraft:charcoal");

    /** A container the bot may take from, with what is there now ({@code null} contents are skipped). */
    public record Source(WorldDoc.Container container, Map<String, Integer> available) {
    }

    /**
     * What the bot has and what is around it.
     *
     * @param inventory item totals including armor
     * @param storage   containers to take from, nearest first
     * @param table     an indexed crafting table near the bot (null = none within reach)
     * @param furnaces  recipe type ({@link GameData#SMELTING}, ...) → a furnace near the bot
     */
    public record World(Map<String, Integer> inventory, List<Source> storage, Pos table, Map<String, Pos> furnaces,
                        GameData data) {
        public World {
            inventory = inventory == null ? Map.of() : Map.copyOf(inventory);
            storage = storage == null ? List.of() : List.copyOf(storage);
            furnaces = furnaces == null ? Map.of() : Map.copyOf(furnaces);
            data = data == null ? GameData.empty() : data;
        }
    }

    /**
     * One planned action.
     *
     * @param container take: where
     * @param take      take: item → count
     * @param inputs    craft / smelt: chosen ingredient → total count (smelt: input and fuel)
     * @param blocks    mine: blocks to break
     * @param block     place: the block to place
     */
    public record Action(String kind, String item, int count, boolean ready, WorldDoc.Container container,
                         Map<String, Integer> take, GameData.Recipe recipe, int crafts, Map<String, Integer> inputs,
                         List<String> blocks, String input, String fuel, int fuelCount, String block) {
        public String key() {
            return switch (kind) {
                case TAKE -> TAKE + ":" + container.dim() + ":" + container.pos();
                case CRAFT -> CRAFT + ":" + item + ":" + recipe.id();
                case SMELT -> SMELT + ":" + item + ":" + input;
                case PLACE -> PLACE + ":" + block;
                default -> kind + ":" + item;
            };
        }

        public JsonObject view() {
            JsonObject o = Json.obj("kind", kind, "item", item, "count", count, "ready", ready);
            if (!take.isEmpty()) {
                o.add("take", Json.toTree(new TreeMap<>(take)));
            }
            if (!inputs.isEmpty()) {
                o.add("inputs", Json.toTree(new TreeMap<>(inputs)));
            }
            if (!blocks.isEmpty()) {
                o.add("blocks", Json.arrOf(blocks));
            }
            if (block != null) {
                o.addProperty("block", block);
            }
            return o;
        }
    }

    /** {@code done}: the inventory has it; {@code act}: run {@code actions}; {@code fail}: {@code missing} is manual. */
    public record Step(String kind, List<Action> actions, Map<String, Integer> missing, List<Action> plan) {
        public static final String DONE = "done";
        public static final String ACT = "act";
        public static final String FAIL = "fail";

        public boolean done() {
            return DONE.equals(kind);
        }

        public boolean failed() {
            return FAIL.equals(kind);
        }
    }

    private ObtainPlanner() {
    }

    /** The next step towards {@code count} × {@code item}. */
    public static Step next(String item, int count, World w) {
        Sim s = new Sim(w);
        boolean have = s.need(Ids.normalize(item), Math.max(1, count), 0, new HashSet<>());
        List<Action> plan = s.actions();
        if (have) {
            return new Step(Step.DONE, List.of(), Map.of(), List.of());
        }
        if (!s.manual.isEmpty()) {
            return new Step(Step.FAIL, List.of(), Map.copyOf(s.manual), plan);
        }
        for (Action a : plan) {
            if (!a.ready()) {
                continue;
            }
            if (TAKE.equals(a.kind())) {
                return new Step(Step.ACT, plan.stream().filter(x -> TAKE.equals(x.kind()) && x.ready()).toList(),
                        Map.of(), plan);
            }
            return new Step(Step.ACT, List.of(a), Map.of(), plan);
        }
        return new Step(Step.FAIL, List.of(), Map.of(Ids.normalize(item), count), plan);
    }

    /** Mutable action while simulating. */
    private static final class Acc {
        final String kind;
        final String item;
        int count;
        boolean ready;
        WorldDoc.Container container;
        final Map<String, Integer> take = new LinkedHashMap<>();
        GameData.Recipe recipe;
        int crafts;
        final Map<String, Integer> inputs = new LinkedHashMap<>();
        final Set<String> blocks = new LinkedHashSet<>();
        String input;
        String fuel;
        int fuelCount;
        String block;

        Acc(String kind, String item) {
            this.kind = kind;
            this.item = item;
            this.ready = true;
        }

        Action build() {
            return new Action(kind, item, count, ready, container,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(take)), recipe, crafts,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs)), List.copyOf(blocks), input,
                    fuel, fuelCount, block);
        }
    }

    private static final class Sim {
        final World w;
        final GameData data;
        final Map<String, Integer> pool = new HashMap<>();
        final LinkedHashMap<WorldDoc.Container, Map<String, Integer>> storage = new LinkedHashMap<>();
        final LinkedHashMap<String, Acc> actions = new LinkedHashMap<>();
        final Map<String, Integer> manual = new TreeMap<>();
        final Map<String, Double> costMemo = new HashMap<>();
        final Set<String> chosen = new HashSet<>();
        boolean tablePlanned;
        final Set<String> furnacePlanned = new HashSet<>();

        Sim(World w) {
            this.w = w;
            this.data = w.data();
            w.inventory().forEach((k, v) -> {
                if (v > 0) {
                    pool.merge(Ids.normalize(k), v, Integer::sum);
                }
            });
            for (Source s : w.storage()) {
                if (s.available() != null) {
                    storage.put(s.container(), new LinkedHashMap<>(s.available()));
                    s.available().forEach((k, v) -> {
                        if (v > 0) {
                            initialStore.merge(k, v, Integer::sum);
                        }
                    });
                }
            }
            initialPool.putAll(pool);
        }

        /** Costs only look at the state before planning, so memoised costs stay valid while the pool changes. */
        final Map<String, Integer> initialPool = new HashMap<>();
        final Map<String, Integer> initialStore = new HashMap<>();

        List<Action> actions() {
            return actions.values().stream().map(Acc::build).toList();
        }

        Acc acc(String kind, String item, String keyExtra) {
            return actions.computeIfAbsent(kind + ":" + item + ":" + keyExtra, k -> new Acc(kind, item));
        }

        int pool(String id) {
            return pool.getOrDefault(id, 0);
        }

        int stored(String id) {
            int n = 0;
            for (Map<String, Integer> m : storage.values()) {
                n += Math.max(0, m.getOrDefault(id, 0));
            }
            return n;
        }

        /** Makes {@code qty} of {@code item} available and consumes it. True when the inventory already had it. */
        boolean need(String item, int qty, int depth, Set<String> path) {
            if (qty <= 0) {
                return true;
            }
            int use = Math.min(pool(item), qty);
            if (use > 0) {
                pool.merge(item, -use, Integer::sum);
                qty -= use;
            }
            if (qty == 0) {
                return true;
            }
            qty -= take(item, qty);
            if (qty <= 0) {
                return false;
            }
            if (depth > MAX_DEPTH || path.contains(item)) {
                manual.merge(item, qty, Integer::sum);
                return false;
            }
            path.add(item);
            try {
                produce(item, qty, depth, path);
            } finally {
                path.remove(item);
            }
            return false;
        }

        private int take(String item, int qty) {
            int got = 0;
            for (Map.Entry<WorldDoc.Container, Map<String, Integer>> e : storage.entrySet()) {
                int there = Math.max(0, e.getValue().getOrDefault(item, 0));
                int n = Math.min(there, qty - got);
                if (n <= 0) {
                    continue;
                }
                e.getValue().merge(item, -n, Integer::sum);
                WorldDoc.Container c = e.getKey();
                Acc a = acc(TAKE, "", c.dim() + ":" + c.pos());
                a.container = c;
                a.take.merge(item, n, Integer::sum);
                a.count += n;
                got += n;
                if (got >= qty) {
                    break;
                }
            }
            return got;
        }

        private void produce(String item, int qty, int depth, Set<String> path) {
            GameData.Recipe best = null;
            double bestCost = INF;
            for (GameData.Recipe r : data.craftingRecipesFor(item)) {
                double c = recipeCost(r, depth, path);
                if (c < bestCost) {
                    best = r;
                    bestCost = c;
                }
            }
            if (best != null) {
                craft(best, item, qty, depth, path);
                return;
            }
            for (GameData.Recipe r : data.smeltingSources(item)) {
                if (smeltable(r) && smeltCost(r, depth, path) < bestCost) {
                    best = r;
                    bestCost = smeltCost(r, depth, path);
                }
            }
            if (best != null) {
                smelt(best, item, qty, depth, path);
                return;
            }
            List<String> blocks = mineBlocks(item);
            if (!blocks.isEmpty() && mineCost(item, depth, path) < INF) {
                mine(item, qty, blocks, depth, path);
                return;
            }
            manual.merge(item, qty, Integer::sum);
        }

        // ---------------------------------------------------------- actions

        private void craft(GameData.Recipe r, String item, int qty, int depth, Set<String> path) {
            boolean ready = true;
            if (GameData.needsTable(r) && w.table() == null) {
                ready = false; // the table has to be placed first
                if (!tablePlanned) {
                    tablePlanned = true;
                    boolean have = need(CRAFTING_TABLE, 1, depth + 1, path);
                    Acc place = acc(PLACE, CRAFTING_TABLE, "");
                    place.block = CRAFTING_TABLE;
                    place.count = 1;
                    place.ready &= have;
                }
            }
            int crafts = (qty + r.count() - 1) / r.count();
            Map<String, Integer> inputs = new LinkedHashMap<>();
            for (Map.Entry<List<String>, Integer> slot : slotCounts(r).entrySet()) {
                int n = slot.getValue() * crafts;
                String id = choose(slot.getKey(), depth + 1, path);
                inputs.merge(id, n, Integer::sum);
                ready &= need(id, n, depth + 1, path);
            }
            Acc a = acc(CRAFT, item, r.id());
            a.recipe = r;
            a.crafts += crafts;
            a.count += crafts * r.count();
            a.ready &= ready;
            inputs.forEach((k, v) -> a.inputs.merge(k, v, Integer::sum));
            int surplus = crafts * r.count() - qty;
            if (surplus > 0) {
                pool.merge(item, surplus, Integer::sum);
            }
        }

        private void smelt(GameData.Recipe r, String item, int qty, int depth, Set<String> path) {
            boolean ready = true;
            if (!w.furnaces().containsKey(r.type())) {
                ready = false;
                if (furnacePlanned.add(r.type())) {
                    String block = furnaceBlock(r.type());
                    boolean have = need(block, 1, depth + 1, path);
                    Acc place = acc(PLACE, block, "");
                    place.block = block;
                    place.count = 1;
                    place.ready &= have;
                }
            }
            String input = choose(r.slots().getFirst(), depth + 1, path);
            ready &= need(input, qty, depth + 1, path);
            String fuel = chooseFuel(qty, input, depth, path);
            int fuelCount = (int) Math.ceil(qty / ItemStacks.burnItems(fuel));
            ready &= need(fuel, fuelCount, depth + 1, path);
            Acc a = acc(SMELT, item, input);
            a.recipe = r;
            a.input = input;
            a.fuel = a.fuel == null ? fuel : a.fuel;
            a.count += qty;
            a.fuelCount += fuelCount;
            a.inputs.merge(input, qty, Integer::sum);
            a.inputs.merge(fuel, fuelCount, Integer::sum);
            a.ready &= ready;
        }

        private void mine(String item, int qty, List<String> blocks, int depth, Set<String> path) {
            GameData.ToolReq req = easiestTool(blocks);
            boolean ready = true;
            List<String> usable = new ArrayList<>(blocks);
            if (req.required()) {
                if (!poolHasTool(req.kind(), req.minTier())) {
                    String stored = storedTool(req.kind(), req.minTier());
                    String tool = stored != null ? stored : ProductionWork.toolItem(req.kind(), req.minTier());
                    if (stored != null) {
                        take(stored, 1); // a good enough tool in storage beats crafting one
                        ready = false;
                    } else {
                        ready = need(tool, 1, depth + 1, path);
                    }
                    pool.merge(tool, 1, Integer::sum); // tools are not used up
                }
                usable.removeIf(b -> {
                    GameData.ToolReq t = data.toolFor(b);
                    return t.required() && (!t.kind().equals(req.kind())
                            || ProductionWork.tierRank(t.minTier()) > ProductionWork.tierRank(req.minTier()));
                });
            }
            if (isLog(item)) {
                usable.addAll(data.blockTag("minecraft:logs")); // any tree will do; the next round picks the planks
            }
            Acc a = acc(MINE, item, "");
            a.count += qty;
            a.ready &= ready;
            for (String b : usable) {
                if (a.blocks.size() < 16) {
                    a.blocks.add(b);
                }
            }
        }

        // ---------------------------------------------------------- choices and costs

        /** The option with the most in the inventory, else the cheapest to obtain, else the first. */
        private String choose(List<String> options, int depth, Set<String> path) {
            if (options.size() == 1) {
                return options.getFirst();
            }
            String best = options.getFirst();
            int bestPool = 0;
            for (String o : options) {
                if (pool(o) > bestPool) {
                    best = o;
                    bestPool = pool(o);
                }
            }
            if (bestPool > 0) {
                return best;
            }
            double bestCost = INF + 1;
            for (String o : options) {
                double c = cost(o, depth, path);
                // equal cost: stay with what this plan already uses (one kind of planks, one trip for logs)
                if (c < bestCost || c == bestCost && chosen.contains(o) && !chosen.contains(best)) {
                    best = o;
                    bestCost = c;
                }
            }
            chosen.add(best);
            return best;
        }

        /** Fuel for {@code qty} items: enough at hand (best first), then in storage, then coal, then logs. */
        private String chooseFuel(int qty, String input, int depth, Set<String> path) {
            List<String> known = new ArrayList<>(STRONG_FUELS);
            pool.keySet().stream().filter(k -> ItemStacks.burnItems(k) > 0 && !known.contains(k)).sorted().forEach(known::add);
            known.sort(Comparator.comparingDouble(ItemStacks::burnItems).reversed());
            for (String f : known) {
                if (!f.equals(input) && pool(f) >= Math.ceil(qty / ItemStacks.burnItems(f))) {
                    return f;
                }
            }
            for (String f : STRONG_FUELS) {
                if (stored(f) >= Math.ceil(qty / ItemStacks.burnItems(f))) {
                    return f;
                }
            }
            if (cost("minecraft:coal", depth + 1, path) < INF) {
                return "minecraft:coal";
            }
            String bestLog = null;
            double bestCost = INF;
            for (String log : data.itemTag("minecraft:logs")) {
                if (ItemStacks.burnItems(log) <= 0 || log.equals(input)) {
                    continue;
                }
                double c = cost(log, depth + 1, path);
                if (c < bestCost) {
                    bestLog = log;
                    bestCost = c;
                }
            }
            return bestLog != null ? bestLog : "minecraft:coal";
        }

        /**
         * Estimated effort per item: 0 at hand, 1 in storage, else what {@link #produce} would do — the cheapest
         * obtainable crafting recipe, else smelting, else mining (same precedence, so a stray guaranteed drop such
         * as sticks from dead bushes never makes an item look cheap); {@link #INF} unobtainable. Crafting costs its
         * ingredients per result item (2 planks → 4 sticks beats 2 bamboo → 1 stick); one-time things (tool, table,
         * furnace) count a quarter. Looks only at the state before planning, so the memo stays valid.
         */
        double cost(String id, int depth, Set<String> path) {
            if (initialPool.getOrDefault(id, 0) > 0) {
                return 0;
            }
            if (initialStore.getOrDefault(id, 0) > 0) {
                return 1;
            }
            if (depth > MAX_DEPTH || path.contains(id)) {
                return INF;
            }
            Double memo = costMemo.get(id);
            if (memo != null) {
                return memo;
            }
            path.add(id);
            double best = INF;
            try {
                for (GameData.Recipe r : data.craftingRecipesFor(id)) {
                    best = Math.min(best, recipeCost(r, depth, path));
                }
                if (best >= INF) {
                    for (GameData.Recipe r : data.smeltingSources(id)) {
                        if (smeltable(r)) {
                            best = Math.min(best, smeltCost(r, depth, path));
                        }
                    }
                }
                if (best >= INF && !mineBlocks(id).isEmpty()) {
                    best = mineCost(id, depth, path);
                }
            } finally {
                path.remove(id);
            }
            if (best < INF) {
                costMemo.put(id, best);
            }
            return best;
        }

        /** 1 + table share + Σ (ingredients per craft × cheapest option) / items per craft. */
        private double recipeCost(GameData.Recipe r, int depth, Set<String> path) {
            double sum = 0;
            for (Map.Entry<List<String>, Integer> slot : slotCounts(r).entrySet()) {
                double min = INF;
                for (String o : slot.getKey()) {
                    min = Math.min(min, cost(o, depth + 1, path));
                }
                if (min >= INF) {
                    return INF;
                }
                sum += min * slot.getValue();
            }
            double table = 0;
            if (GameData.needsTable(r) && w.table() == null && initialPool.getOrDefault(CRAFTING_TABLE, 0) == 0) {
                double t = path.contains(CRAFTING_TABLE) ? INF : cost(CRAFTING_TABLE, depth + 1, path);
                if (t >= INF) {
                    return INF;
                }
                table = (1 + t) / 4;
            }
            return 1 + table + sum / r.count();
        }

        /** 2 + furnace share + input; without a furnace near, charcoal is not "cheap". */
        private double smeltCost(GameData.Recipe r, int depth, Set<String> path) {
            double min = INF;
            for (String o : r.slots().getFirst()) {
                min = Math.min(min, cost(o, depth + 1, path));
            }
            if (min >= INF) {
                return INF;
            }
            double furnace = 0;
            String block = furnaceBlock(r.type());
            if (!w.furnaces().containsKey(r.type()) && initialPool.getOrDefault(block, 0) == 0) {
                double f = path.contains(block) ? INF : cost(block, depth + 1, path);
                if (f >= INF) {
                    return INF;
                }
                furnace = (1 + f) / 4;
            }
            return 2 + furnace + min;
        }

        /** 3 + tool share (1 when a good enough tool is in storage). */
        private double mineCost(String id, int depth, Set<String> path) {
            GameData.ToolReq req = easiestTool(mineBlocks(id));
            if (!req.required() || hasTool(initialPool, req.kind(), req.minTier())) {
                return 3;
            }
            if (hasTool(initialStore, req.kind(), req.minTier())) {
                return 4;
            }
            double tool = cost(ProductionWork.toolItem(req.kind(), req.minTier()), depth + 1, path);
            return tool >= INF ? INF : 3 + (1 + tool) / 4;
        }

        /** Only furnace recipes; blast furnaces and smokers when one is indexed near the bot. */
        private boolean smeltable(GameData.Recipe r) {
            return r.slots() != null && !r.slots().isEmpty() && r.slots().getFirst() != null
                    && (GameData.SMELTING.equals(r.type()) || w.furnaces().containsKey(r.type())
                    && !GameData.CAMPFIRE.equals(r.type()));
        }

        private GameData.ToolReq easiestTool(List<String> blocks) {
            GameData.ToolReq best = null;
            for (String b : blocks) {
                GameData.ToolReq t = data.toolFor(b);
                if (!t.required()) {
                    return GameData.ToolReq.NONE;
                }
                if (best == null || ProductionWork.tierRank(t.minTier()) < ProductionWork.tierRank(best.minTier())) {
                    best = t;
                }
            }
            return best == null ? GameData.ToolReq.NONE : best;
        }

        /** The best tool of the kind with at least the tier in storage, or null. */
        private String storedTool(String kind, String minTier) {
            int need = ProductionWork.tierRank(minTier);
            String best = null;
            int bestTier = -1;
            for (Map<String, Integer> m : storage.values()) {
                for (Map.Entry<String, Integer> e : m.entrySet()) {
                    int tier = ProductionWork.toolTier(e.getKey(), kind);
                    if (e.getValue() > 0 && tier >= need && tier > bestTier) {
                        best = e.getKey();
                        bestTier = tier;
                    }
                }
            }
            return best;
        }

        private boolean poolHasTool(String kind, String minTier) {
            return hasTool(pool, kind, minTier);
        }

        private static boolean hasTool(Map<String, Integer> items, String kind, String minTier) {
            int need = ProductionWork.tierRank(minTier);
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                if (e.getValue() > 0 && ProductionWork.toolTier(e.getKey(), kind) >= need) {
                    return true;
                }
            }
            return false;
        }

        private List<String> mineBlocks(String item) {
            return Resolver.mineBlocks(data, item);
        }

        private boolean isLog(String item) {
            return data.itemTag("minecraft:logs").contains(item) || data.blockTag("minecraft:logs").contains(item);
        }
    }

    static String furnaceBlock(String type) {
        if (GameData.BLASTING.equals(type)) {
            return "minecraft:blast_furnace";
        }
        if (GameData.SMOKING.equals(type)) {
            return "minecraft:smoker";
        }
        return FURNACE;
    }

    /** Identical ingredient lists counted together: {@code [planks] → 2} for sticks. */
    static Map<List<String>, Integer> slotCounts(GameData.Recipe r) {
        Map<List<String>, Integer> out = new LinkedHashMap<>();
        for (List<String> ing : r.ingredients()) {
            out.merge(ing, 1, Integer::sum);
        }
        return out;
    }
}
