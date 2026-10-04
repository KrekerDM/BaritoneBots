package io.github.krekerdm.baritonebots.manager.autopilot;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.ItemStacks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sorting categories (SPEC §5.7a): {@code autopilot.categories} in order, each a list of id globs and {@code #tag}
 * entries resolved through the game data's item tags. The first matching category wins; anything else is
 * {@link #MISC}. Also decides which category an unassigned storage chest adopts and what counts as food. Pure; one
 * instance per config + game data (lookups are memoised).
 */
public final class Categories {
    public static final String MISC = "misc";
    public static final String FOOD = "food";
    /** Edible but harmful: never fetched as food. */
    private static final Set<String> INEDIBLE = Set.of("minecraft:rotten_flesh", "minecraft:spider_eye",
            "minecraft:pufferfish", "minecraft:poisonous_potato", "minecraft:suspicious_stew", "minecraft:cake",
            "minecraft:chorus_fruit");
    /** Food globs when the configuration has no {@code food} category. */
    private static final List<String> FALLBACK_FOOD = List.of("minecraft:cooked_*", "minecraft:bread",
            "minecraft:baked_potato", "minecraft:golden_carrot", "minecraft:carrot", "minecraft:apple",
            "minecraft:melon_slice", "minecraft:sweet_berries", "minecraft:pumpkin_pie", "minecraft:cookie",
            "minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:chicken", "minecraft:dried_kelp");

    private final List<ManagerConfig.Category> defs;
    private final GameData data;
    private final List<String> foodGlobs;
    private final Map<String, String> memo = new HashMap<>();

    public Categories(List<ManagerConfig.Category> defs, GameData data) {
        this.defs = defs == null ? List.of() : List.copyOf(defs);
        this.data = data == null ? GameData.empty() : data;
        List<String> food = null;
        for (ManagerConfig.Category c : this.defs) {
            if (FOOD.equals(c.name())) {
                food = c.globs();
            }
        }
        this.foodGlobs = food == null || food.isEmpty() ? FALLBACK_FOOD : food;
    }

    /** Category names in configuration order, always ending with {@link #MISC}. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (ManagerConfig.Category c : defs) {
            if (!out.contains(c.name())) {
                out.add(c.name());
            }
        }
        if (!out.contains(MISC)) {
            out.add(MISC);
        }
        return out;
    }

    /** The category of an item. */
    public String of(String item) {
        String id = Ids.normalize(Ids.stripState(item));
        return memo.computeIfAbsent(id, k -> {
            for (ManagerConfig.Category c : defs) {
                if (!MISC.equals(c.name()) && matches(c.globs(), k)) {
                    return c.name();
                }
            }
            return MISC;
        });
    }

    /** Globs and {@code #tags}. */
    public boolean matches(Collection<String> globs, String id) {
        for (String g : globs) {
            if (g == null || g.isBlank()) {
                continue;
            }
            if (g.startsWith("#") ? data.itemTag(g).contains(id) : Ids.matches(g, id)) {
                return true;
            }
        }
        return false;
    }

    /** Something a bot may eat: the food category minus harmful items and the {@code autoEat.avoid} globs. */
    public boolean isFood(String item, Collection<String> avoid) {
        String id = Ids.normalize(item);
        return !INEDIBLE.contains(id) && matches(foodGlobs, id) && (avoid == null || !Ids.matchesAny(avoid, id));
    }

    /** Items per category (configuration order). */
    public Map<String, Map<String, Integer>> group(Map<String, Integer> items) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (String n : names()) {
            out.put(n, new LinkedHashMap<>());
        }
        items.forEach((id, n) -> {
            if (n > 0) {
                out.get(of(id)).merge(id, n, Integer::sum);
            }
        });
        out.values().removeIf(Map::isEmpty);
        return out;
    }

    /**
     * The category an unassigned storage chest adopts: the one holding more than half of its occupied slots
     * (stacks), or {@code null} when it is empty or mixed.
     */
    public String adopt(Map<String, Integer> totals) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        int all = 0;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            int s = ItemStacks.slots(e.getKey(), e.getValue());
            if (s > 0) {
                slots.merge(of(e.getKey()), s, Integer::sum);
                all += s;
            }
        }
        for (Map.Entry<String, Integer> e : slots.entrySet()) {
            if (e.getValue() * 2 > all) {
                return e.getKey();
            }
        }
        return null;
    }
}
