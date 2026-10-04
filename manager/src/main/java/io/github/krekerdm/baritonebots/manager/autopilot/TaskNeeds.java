package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a task needs before it runs (SPEC §5.7a auto-supply), and what of that the bot is missing. Pure.
 *
 * <ul>
 *   <li>tools: by {@link GameData#toolFor} for {@code mine} blocks (required tier when the block drops nothing
 *   without it, else optional), hoe for {@code farm}, pickaxe/shovel/axe for {@code selection clear} and
 *   {@code build}, the {@code replace} source blocks' tools, a sword for fights, shears for {@code shear};</li>
 *   <li>food for long tasks when below {@code foodMin} (refilled to twice that);</li>
 *   <li>throwaway blocks for {@code mine}/{@code explore}/{@code selection clear} below {@code blocksMin};</li>
 *   <li>materials (only when {@code materials}: manual and scenario work, projects restock themselves): the block
 *   of {@code selection fill|walls|shell|replace}, input + fuel of {@code smelt_load}, recipe ingredients of
 *   {@code craft}; {@code build} materials come from a {@code bom} query, see {@link #fromBom}.</li>
 * </ul>
 */
public final class TaskNeeds {
    public enum Kind { TOOL, FOOD, BLOCKS, ITEM }

    /**
     * One requirement. TOOL: {@code toolKind} + {@code minTier}, count 1. FOOD / BLOCKS: {@code min} = refill
     * threshold, {@code count} = refill target. ITEM: any of {@code globs}, {@code count} items.
     *
     * @param optional fetch when available, never reported as missing
     */
    public record Need(Kind kind, List<String> globs, int count, int min, String toolKind, String minTier,
                       boolean optional) {
        public Need {
            globs = globs == null ? List.of() : List.copyOf(globs);
        }

        static Need tool(String kind, String tier, boolean optional) {
            return new Need(Kind.TOOL, List.of(), 1, 1, kind, tier == null ? "wood" : tier, optional);
        }

        static Need item(List<String> globs, int count) {
            return new Need(Kind.ITEM, globs, count, count, null, null, false);
        }

        /** Short text for events and the panel. */
        public String label() {
            return switch (kind) {
                case TOOL -> toolKind + " (" + minTier + "+)";
                case FOOD -> "food";
                case BLOCKS -> "blocks";
                case ITEM -> globs.size() == 1 ? Ids.path(globs.getFirst()) : String.join("|", globs.stream().map(Ids::path).toList());
            };
        }

        public Need withCount(int n) {
            return new Need(kind, globs, n, min, toolKind, minTier, optional);
        }
    }

    /** Lookup context: game data (may be empty), effective autopilot config, categories, autoEat avoid list. */
    public record Context(GameData data, ManagerConfig.AutopilotCfg cfg, Categories categories, Collection<String> eatAvoid) {
        public boolean isFood(String id) {
            return categories.isFood(id, eatAvoid);
        }

        public boolean isThrowaway(String id) {
            return Ids.matchesAny(cfg.throwaway(), id);
        }
    }

    /** Long tasks that get food. */
    static final Set<String> FOOD_TASKS = Set.of(TaskTypes.MINE, TaskTypes.EXPLORE, TaskTypes.FARM, TaskTypes.SELECTION,
            TaskTypes.BUILD, TaskTypes.GUARD, TaskTypes.FOLLOW, TaskTypes.SLAUGHTER, TaskTypes.ATTACK);
    /** Task types auto-supply looks at. */
    public static final Set<String> TYPES = Set.of(TaskTypes.MINE, TaskTypes.EXPLORE, TaskTypes.FARM,
            TaskTypes.SELECTION, TaskTypes.BUILD, TaskTypes.GUARD, TaskTypes.FOLLOW, TaskTypes.SLAUGHTER,
            TaskTypes.ATTACK, TaskTypes.SHEAR, TaskTypes.SMELT_LOAD, TaskTypes.CRAFT);
    /** At most this many material items are fetched for one task (9 stacks). */
    static final int MAX_MATERIALS = 576;

    private TaskNeeds() {
    }

    /** Requirements of one task (before looking at the inventory). */
    public static List<Need> of(String type, JsonObject args, Context ctx, boolean materials) {
        JsonObject a = args == null ? new JsonObject() : args;
        GameData data = ctx.data() == null ? GameData.empty() : ctx.data();
        List<Need> out = new ArrayList<>();
        switch (type) {
            case TaskTypes.MINE -> {
                out.addAll(toolsFor(ids(Json.getArr(a, "blocks")), data));
                out.add(blocks(ctx));
            }
            case TaskTypes.EXPLORE -> out.add(blocks(ctx));
            case TaskTypes.FARM -> out.add(Need.tool("hoe", "wood", true));
            case TaskTypes.SELECTION -> {
                String op = Json.getString(a, "op", "clear");
                Box box = Box.fromJson(a.get("box"));
                if ("clear".equals(op)) {
                    out.add(Need.tool("pickaxe", "wood", true));
                    out.add(Need.tool("shovel", "wood", true));
                    out.add(Need.tool("axe", "wood", true));
                    out.add(blocks(ctx));
                } else {
                    if ("replace".equals(op)) {
                        out.addAll(toolsFor(ids(Json.getArr(a, "from")), data));
                    }
                    String block = Json.getString(a, "block", null);
                    if (materials && block != null && box != null) {
                        out.add(Need.item(List.of(Ids.normalize(block)), (int) Math.min(MAX_MATERIALS, blocksOf(op, box))));
                    }
                }
            }
            case TaskTypes.BUILD -> {
                out.add(Need.tool("pickaxe", "wood", true));
                out.add(Need.tool("shovel", "wood", true));
                out.add(Need.tool("axe", "wood", true));
            }
            case TaskTypes.GUARD, TaskTypes.ATTACK, TaskTypes.SLAUGHTER -> out.add(Need.tool("sword", "wood", true));
            case TaskTypes.SHEAR -> out.add(Need.item(List.of("minecraft:shears"), 1));
            case TaskTypes.SMELT_LOAD -> {
                if (materials) {
                    String input = Json.getString(a, "input", null);
                    String fuel = Json.getString(a, "fuel", null);
                    if (input != null) {
                        out.add(Need.item(List.of(Ids.normalizeGlob(input)), Math.max(1, Json.getInt(a, "count", 1))));
                    }
                    if (fuel != null) {
                        out.add(Need.item(List.of(Ids.normalizeGlob(fuel)), Math.max(1, Json.getInt(a, "fuelCount", 1))));
                    }
                }
            }
            case TaskTypes.CRAFT -> {
                if (materials) {
                    out.addAll(craftInputs(a, data));
                }
            }
            default -> {
            }
        }
        if (FOOD_TASKS.contains(type) && ctx.cfg().foodMin() > 0) {
            out.add(new Need(Kind.FOOD, List.of(), ctx.cfg().foodMin() * 2, ctx.cfg().foodMin(), null, null, false));
        }
        return out;
    }

    /** {@code build} materials from a {@code progress} answer ({@code remaining}) or a {@code bom} one ({@code items}). */
    public static List<Need> fromBom(JsonObject bomData) {
        List<Need> out = new ArrayList<>();
        JsonObject items = Json.getObj(bomData, "remaining");
        if (items == null) {
            items = Json.getObj(bomData, "items");
        }
        if (items == null) {
            return out;
        }
        int total = 0;
        for (Map.Entry<String, JsonElement> e : items.entrySet()) {
            int n = e.getValue().isJsonPrimitive() ? e.getValue().getAsInt() : 0;
            if (n <= 0 || total >= MAX_MATERIALS) {
                continue;
            }
            n = Math.min(n, MAX_MATERIALS - total);
            total += n;
            out.add(Need.item(List.of(Ids.normalize(e.getKey())), n));
        }
        return out;
    }

    /** What the bot still has to fetch: requirements not covered by its inventory. */
    public static List<Need> missing(List<Need> needs, Inventory inv, Context ctx) {
        List<Need> out = new ArrayList<>();
        Map<String, Need> tools = new LinkedHashMap<>();
        for (Need n : needs) {
            switch (n.kind()) {
                case TOOL -> {
                    if (!inv.hasTool(n.toolKind(), n.minTier(), ctx.cfg().toolMinDurability())) {
                        Need prev = tools.get(n.toolKind());
                        // one need per tool kind: the highest tier, required wins over optional
                        if (prev == null || ProductionWork.tierRank(n.minTier()) > ProductionWork.tierRank(prev.minTier())
                                || prev.optional() && !n.optional()) {
                            tools.put(n.toolKind(), n);
                        }
                    }
                }
                case FOOD -> {
                    int have = inv.count(ctx::isFood);
                    if (have < n.min()) {
                        out.add(n.withCount(n.count() - have));
                    }
                }
                case BLOCKS -> {
                    if (n.min() <= 0) {
                        continue;
                    }
                    int have = inv.count(ctx::isThrowaway);
                    if (have < n.min()) {
                        out.add(n.withCount(n.count() - have));
                    }
                }
                case ITEM -> {
                    int have = inv.count(id -> Ids.matchesAny(n.globs(), id));
                    if (have < n.count()) {
                        out.add(n.withCount(n.count() - have));
                    }
                }
            }
        }
        List<Need> result = new ArrayList<>(tools.values());
        result.addAll(out);
        return result;
    }

    private static Need blocks(Context ctx) {
        int min = ctx.cfg().blocksMin();
        return new Need(Kind.BLOCKS, ctx.cfg().throwaway(), min * 2, min, null, null, true);
    }

    private static List<String> ids(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a == null ? new JsonArray() : a) {
            if (e.isJsonPrimitive()) {
                String s = e.getAsString().trim();
                if (!s.isEmpty() && !s.contains("*") && !s.startsWith("#")) {
                    out.add(Ids.normalize(Ids.stripState(s)));
                }
            }
        }
        return out;
    }

    /** One tool need per kind: the highest tier any block requires; required when any block requires it. */
    static List<Need> toolsFor(List<String> blocks, GameData data) {
        Map<String, Need> perKind = new LinkedHashMap<>();
        for (String b : blocks) {
            GameData.ToolReq t = data.isEmpty() ? guess(b) : data.toolFor(b);
            if ("none".equals(t.kind())) {
                continue;
            }
            Need prev = perKind.get(t.kind());
            String tier = t.minTier() == null ? "wood" : t.minTier();
            boolean required = t.required() || prev != null && !prev.optional();
            String best = prev == null || ProductionWork.tierRank(tier) > ProductionWork.tierRank(prev.minTier())
                    ? tier : prev.minTier();
            perKind.put(t.kind(), Need.tool(t.kind(), best, !required));
        }
        return new ArrayList<>(perKind.values());
    }

    /** Without game data: stone-like blocks and ores want a pickaxe (optional, any tier). */
    private static GameData.ToolReq guess(String block) {
        String p = Ids.path(block);
        if (p.endsWith("_ore") || p.contains("stone") || p.contains("deepslate") || p.equals("obsidian")
                || p.endsWith("_bricks") || p.equals("netherrack")) {
            return new GameData.ToolReq("pickaxe", "wood", false);
        }
        if (p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_planks")) {
            return new GameData.ToolReq("axe", "wood", false);
        }
        if (p.equals("dirt") || p.equals("sand") || p.equals("gravel") || p.equals("grass_block") || p.equals("clay")) {
            return new GameData.ToolReq("shovel", "wood", false);
        }
        return GameData.ToolReq.NONE;
    }

    /** Blocks a selection places: fill = volume, walls = the four x/z faces, shell = all six faces. */
    static long blocksOf(String op, Box box) {
        long w = box.width();
        long h = box.height();
        long l = box.length();
        long all = w * h * l;
        return switch (op) {
            case "walls" -> all - Math.max(0, w - 2) * h * Math.max(0, l - 2);
            case "shell" -> all - Math.max(0, w - 2) * Math.max(0, h - 2) * Math.max(0, l - 2);
            default -> all;
        };
    }

    /** Ingredients of {@code craft}: the given grid, else the first crafting recipe of the item. */
    static List<Need> craftInputs(JsonObject a, GameData data) {
        String item = Json.getString(a, "item", null);
        int count = Math.max(1, Json.getInt(a, "count", 1));
        if (item == null) {
            return List.of();
        }
        List<GameData.Recipe> recipes = data.craftingRecipesFor(item);
        int perCraft = recipes.isEmpty() ? 1 : recipes.getFirst().count();
        int crafts = (count + perCraft - 1) / perCraft;
        Map<List<String>, Integer> slots = new LinkedHashMap<>();
        JsonArray grid = Json.getArr(a, "grid");
        if (grid != null && !grid.isEmpty()) {
            for (JsonElement row : grid) {
                for (JsonElement cell : row.isJsonArray() ? row.getAsJsonArray() : new JsonArray()) {
                    List<String> opts = new ArrayList<>();
                    for (JsonElement o : cell.isJsonArray() ? cell.getAsJsonArray() : new JsonArray()) {
                        opts.add(Ids.normalize(o.getAsString()));
                    }
                    if (!opts.isEmpty()) {
                        slots.merge(opts, 1, Integer::sum);
                    }
                }
            }
        } else if (!recipes.isEmpty()) {
            for (List<String> ing : recipes.getFirst().ingredients()) {
                slots.merge(ing, 1, Integer::sum);
            }
        }
        List<Need> out = new ArrayList<>();
        slots.forEach((opts, n) -> out.add(Need.item(opts, n * crafts)));
        return out;
    }
}
