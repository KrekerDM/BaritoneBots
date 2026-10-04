package io.github.krekerdm.baritonebots.manager.planner;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deficits and how to cover them (SPEC §5.7 step 4): deficit = remaining − supply − builders' inventories − in transit;
 * each deficit resolves to haul from storage → mine (blocks whose loot drops it) → craft (ingredients recurse) →
 * smelt → manual. Ingredients may use storage stock and supply stock beyond what the build itself still needs.
 * Pure: the same inputs give the same plan. Reused by any work source that keeps items in stock.
 */
public final class Resolver {
    public static final String STORAGE = "storage";
    public static final String MINE = "mine";
    public static final String CRAFT = "craft";
    public static final String SMELT = "smelt";
    public static final String MANUAL = "manual";
    public static final String HAUL = "haul";
    static final int MAX_DEPTH = 8;
    private static final Set<String> NEVER_MINE = Set.of("minecraft:farmland", "minecraft:dirt_path");

    /** Item counts known to the planner. {@code storage} already excludes amounts reserved by running work. */
    public record Stock(Map<String, Integer> supply, Map<String, Integer> storage, Map<String, Integer> inBots,
                        Map<String, Integer> inTransit) {
        public Stock {
            supply = Map.copyOf(supply == null ? Map.of() : supply);
            storage = Map.copyOf(storage == null ? Map.of() : storage);
            inBots = Map.copyOf(inBots == null ? Map.of() : inBots);
            inTransit = Map.copyOf(inTransit == null ? Map.of() : inTransit);
        }
    }

    /**
     * What the world offers. {@code furnaceTypes}: recipe types a known furnace can run ({@code minecraft:smelting} for
     * furnaces, {@code minecraft:blasting} / {@code minecraft:smoking} for blast furnaces / smokers).
     */
    public record Env(boolean hasSupply, boolean craftingTable, Set<String> furnaceTypes) {
        public Env {
            furnaceTypes = Set.copyOf(furnaceTypes == null ? Set.of() : furnaceTypes);
        }
    }

    /**
     * One step that covers (part of) a deficit.
     *
     * @param kind   {@link #HAUL}, {@link #MINE}, {@link #CRAFT}, {@link #SMELT}
     * @param count  items to produce / move
     * @param ready  all inputs are in stock now (haul and mine are always ready)
     * @param blocks mine: blocks to break, best first
     * @param recipe craft / smelt: the chosen recipe
     * @param inputs craft / smelt: chosen ingredient id → total count (smelt: input and fuel)
     * @param fuel   smelt: fuel id (also in {@code inputs})
     * @param crafts craft: how many times the recipe runs
     */
    public record Action(String kind, String item, int count, boolean ready, List<String> blocks,
                         GameData.Recipe recipe, Map<String, Integer> inputs, String fuel, int crafts) {
        public Action {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            inputs = inputs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }

        public JsonObject view() {
            JsonObject o = Json.obj("kind", kind, "item", item, "count", count, "ready", ready);
            if (!blocks.isEmpty()) {
                o.add("blocks", Json.arrOf(blocks));
            }
            if (!inputs.isEmpty()) {
                o.add("inputs", Json.toTree(new TreeMap<>(inputs)));
            }
            if (recipe != null) {
                o.addProperty("recipe", recipe.id());
            }
            return o;
        }
    }

    /** A BOM row for the panel. */
    public record Row(String item, int needed, int remaining, int inSupply, int inBots, int inTransit, int deficit,
                      String source) {
        public JsonObject view() {
            return Json.obj("item", item, "needed", needed, "remaining", remaining, "inSupply", inSupply,
                    "inBots", inBots, "inTransit", inTransit, "deficit", deficit, "source", source);
        }
    }

    public record Plan(List<Row> rows, List<Action> actions, Map<String, Integer> manual) {
    }

    private final GameData data;
    private final Env env;
    private final Map<String, Integer> pool = new HashMap<>();
    private final Map<String, Integer> storageLeft = new HashMap<>();
    private final List<Action> actions = new ArrayList<>();
    private final Map<String, Integer> manual = new TreeMap<>();
    private final Map<String, Integer> rankMemo = new HashMap<>();

    private Resolver(GameData data, Env env) {
        this.data = data == null ? GameData.empty() : data;
        this.env = env;
    }

    /**
     * @param needed    BOM totals (only for the {@code needed} column)
     * @param remaining what is still to be placed / stocked, per item
     */
    public static Plan resolve(Map<String, Integer> needed, Map<String, Integer> remaining, Stock stock,
                               GameData data, Env env) {
        Resolver r = new Resolver(data, env);
        stock.storage().forEach((k, v) -> {
            if (v > 0) {
                r.storageLeft.put(k, v);
                r.pool.merge(k, v, Integer::sum);
            }
        });
        // supply beyond what the build still needs may feed crafting
        stock.supply().forEach((k, v) -> {
            int want = remaining.getOrDefault(k, 0) - stock.inBots().getOrDefault(k, 0)
                    - stock.inTransit().getOrDefault(k, 0);
            int surplus = Math.min(v, Math.max(0, v - Math.max(0, want)));
            if (surplus > 0) {
                r.pool.merge(k, surplus, Integer::sum);
            }
        });
        Set<String> items = new java.util.TreeSet<>(needed.keySet());
        items.addAll(remaining.keySet());
        List<Row> rows = new ArrayList<>();
        for (String item : items) {
            int rem = Math.max(0, remaining.getOrDefault(item, 0));
            int sup = stock.supply().getOrDefault(item, 0);
            int bots = stock.inBots().getOrDefault(item, 0);
            int transit = stock.inTransit().getOrDefault(item, 0);
            int deficit = Math.max(0, rem - sup - bots - transit);
            String source = deficit > 0 ? r.cover(item, deficit) : null;
            rows.add(new Row(item, needed.getOrDefault(item, rem), rem, sup, bots, transit, deficit, source));
        }
        return new Plan(List.copyOf(rows), List.copyOf(r.actions), Map.copyOf(r.manual));
    }

    /** Top-level deficit: storage first, then production. Returns the main source. */
    private String cover(String item, int qty) {
        int fromStorage = Math.min(qty, storageLeft.getOrDefault(item, 0));
        if (fromStorage > 0) {
            storageLeft.merge(item, -fromStorage, Integer::sum);
            pool.merge(item, -fromStorage, Integer::sum);
            if (env.hasSupply()) {
                actions.add(new Action(HAUL, item, fromStorage, true, null, null, null, null, 0));
            }
            qty -= fromStorage;
        }
        if (qty <= 0) {
            return STORAGE;
        }
        return produce(item, qty, 0, new HashSet<>());
    }

    /** Mine → craft → smelt → manual for {@code qty} items that are not in stock. */
    private String produce(String item, int qty, int depth, Set<String> path) {
        if (depth > MAX_DEPTH || !path.add(item)) {
            manual.merge(item, qty, Integer::sum);
            return MANUAL;
        }
        try {
            List<String> blocks = mineBlocks(item);
            if (!blocks.isEmpty()) {
                actions.add(new Action(MINE, item, qty, true, blocks, null, null, null, 0));
                return MINE;
            }
            for (GameData.Recipe r : data.craftingRecipesFor(item)) {
                if (GameData.needsTable(r) && !env.craftingTable()) {
                    continue;
                }
                if (!recipeObtainable(r, depth, path)) {
                    continue;
                }
                int crafts = (qty + r.count() - 1) / r.count();
                Map<String, Integer> inputs = new LinkedHashMap<>();
                boolean ready = true;
                for (Map.Entry<List<String>, Integer> slot : slotCounts(r).entrySet()) {
                    int need = slot.getValue() * crafts;
                    String id = choose(slot.getKey(), depth, path);
                    inputs.merge(id, need, Integer::sum);
                    ready &= consume(id, need, depth, path);
                }
                actions.add(new Action(CRAFT, item, crafts * r.count(), ready, null, r, inputs, null, crafts));
                return CRAFT;
            }
            for (GameData.Recipe r : data.smeltingSources(item)) {
                if (!env.furnaceTypes().contains(r.type()) || !recipeObtainable(r, depth, path)) {
                    continue;
                }
                String input = choose(r.slots().getFirst(), depth, path);
                Map<String, Integer> inputs = new LinkedHashMap<>();
                inputs.put(input, qty);
                boolean ready = consume(input, qty, depth, path);
                String fuel = chooseFuel();
                int fuelCount = (int) Math.ceil(qty / ItemStacks.burnItems(fuel));
                inputs.merge(fuel, fuelCount, Integer::sum);
                ready &= consume(fuel, fuelCount, depth, path);
                actions.add(new Action(SMELT, item, qty, ready, null, r, inputs, fuel, 0));
                return SMELT;
            }
            manual.merge(item, qty, Integer::sum);
            return MANUAL;
        } finally {
            path.remove(item);
        }
    }

    /** Takes {@code need} from the ingredient pool; whatever is missing is produced. True when all was in stock. */
    private boolean consume(String id, int need, int depth, Set<String> path) {
        int have = Math.max(0, pool.getOrDefault(id, 0));
        int use = Math.min(have, need);
        if (use > 0) {
            pool.merge(id, -use, Integer::sum);
            int fromStorage = Math.min(use, storageLeft.getOrDefault(id, 0));
            storageLeft.merge(id, -fromStorage, Integer::sum);
        }
        if (use < need) {
            produce(id, need - use, depth + 1, path);
            return false;
        }
        return true;
    }

    /** Identical ingredient lists counted together: {@code [planks] → 2} for sticks. */
    private static Map<List<String>, Integer> slotCounts(GameData.Recipe r) {
        Map<List<String>, Integer> out = new LinkedHashMap<>();
        for (List<String> ing : r.ingredients()) {
            out.merge(ing, 1, Integer::sum);
        }
        return out;
    }

    /** The option with the most stock, else the easiest to obtain (mine < craft < smelt), else the first. */
    private String choose(List<String> options, int depth, Set<String> path) {
        String best = options.getFirst();
        int bestStock = -1;
        for (String o : options) {
            int s = pool.getOrDefault(o, 0);
            if (s > bestStock) {
                best = o;
                bestStock = s;
            }
        }
        if (bestStock > 0) {
            return best;
        }
        int bestRank = Integer.MAX_VALUE;
        for (String o : options) {
            int rank = rank(o, depth + 1, path);
            if (rank < bestRank) {
                bestRank = rank;
                best = o;
            }
        }
        return best;
    }

    /** Fuel with stock (best burn value first), else coal. */
    private String chooseFuel() {
        String best = null;
        double bestBurn = 0;
        for (Map.Entry<String, Integer> e : pool.entrySet()) {
            double burn = ItemStacks.burnItems(e.getKey());
            if (e.getValue() > 0 && burn > bestBurn) {
                best = e.getKey();
                bestBurn = burn;
            }
        }
        return best != null ? best : "minecraft:coal";
    }

    private boolean recipeObtainable(GameData.Recipe r, int depth, Set<String> path) {
        for (List<String> ing : r.ingredients()) {
            boolean any = false;
            for (String o : ing) {
                if (rank(o, depth + 1, path) < 9) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /** 0 in stock, 1 mineable, 2 craftable, 3 smeltable, 9 unobtainable. Memoised per plan. */
    private int rank(String id, int depth, Set<String> path) {
        if (pool.getOrDefault(id, 0) > 0) {
            return 0;
        }
        if (depth > MAX_DEPTH || path.contains(id)) {
            return 9;
        }
        Integer memo = rankMemo.get(id);
        if (memo != null) {
            return memo;
        }
        int rank = 9;
        if (!mineBlocks(id).isEmpty()) {
            rank = 1;
        } else {
            path.add(id);
            try {
                for (GameData.Recipe r : data.craftingRecipesFor(id)) {
                    if ((!GameData.needsTable(r) || env.craftingTable()) && recipeObtainable(r, depth, path)) {
                        rank = 2;
                        break;
                    }
                }
                if (rank == 9) {
                    for (GameData.Recipe r : data.smeltingSources(id)) {
                        if (env.furnaceTypes().contains(r.type()) && recipeObtainable(r, depth, path)) {
                            rank = 3;
                            break;
                        }
                    }
                }
            } finally {
                path.remove(id);
            }
        }
        rankMemo.put(id, rank);
        return rank;
    }

    /**
     * Blocks to mine for {@code item}. Without game data: the item itself (SPEC: "item id = mineable block id").
     * With game data: blocks whose normal loot drops it, except the item's own block when that block is crafted
     * (planks, bricks: those come from crafting, not from tearing down builds), chance-only drops of items that can
     * be crafted or smelted, and player-made ground (farmland, paths). When natural stone drops the item
     * (cobblestone, cobbled deepslate), the item's own block is not mined.
     */
    public List<String> mineBlocks(String item) {
        String id = Ids.normalize(item);
        if (data.isEmpty()) {
            return List.of(id);
        }
        List<String> out = new ArrayList<>();
        boolean stoneSource = false;
        boolean producible = !data.craftingRecipesFor(id).isEmpty() || !data.smeltingSources(id).isEmpty();
        for (String b : data.blocksDropping(id)) {
            if (NEVER_MINE.contains(b) || b.contains("infested")
                    || b.equals(id) && !data.craftingRecipesFor(b).isEmpty()) {
                continue;
            }
            boolean guaranteed = data.drops(b).stream().anyMatch(d -> d.item().equals(id) && d.guaranteed());
            if (!guaranteed && producible) {
                continue; // sticks come from planks, not from 2 % leaf drops
            }
            if (!b.equals(id) && (data.blockTag("minecraft:base_stone_overworld").contains(b)
                    || data.blockTag("minecraft:base_stone_nether").contains(b))) {
                stoneSource = true;
            }
            out.add(b);
        }
        if (stoneSource) {
            out.remove(id);
        }
        return out.size() > 6 ? List.copyOf(out.subList(0, 6)) : List.copyOf(out);
    }

    /** Convenience for callers outside a plan (game data may be null). */
    public static List<String> mineBlocks(GameData data, String item) {
        return new Resolver(data, new Env(false, false, Set.of())).mineBlocks(item);
    }
}
