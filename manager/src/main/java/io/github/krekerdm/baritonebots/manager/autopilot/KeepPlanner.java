package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Inventory hygiene (SPEC §5.7f): which stacks of a bot's main inventory are junk under a keep profile, and which
 * are junk for auto-trash. Pure, unit-tested.
 * <p>
 * Keep profile, in this order (a stack is decided once): {@code extra} globs keep whole stacks; armor in the
 * inventory is kept only when it beats the worn piece of its slot ({@code armor:"worn"}; the best one per slot);
 * {@code weapon:"best"} keeps the best sword; {@code tools} keeps the best tool of each listed kind — by tier, then
 * durability — and the other tools of those kinds are duplicates; food (minus harmful food) is kept up to
 * {@code food.max} items, best food first; {@code blocks} keeps up to {@code count} of the block globs (default: the
 * autopilot's throwaway blocks), largest stacks first; everything else is junk. Worn armor and the offhand are never
 * touched. With {@code protectValuables} (drop and trash chest) shulker boxes, named items and rare loot are never junk.
 */
public final class KeepPlanner {
    /** One main-inventory stack from the {@code inventory} query. */
    public record Stack(int slot, String item, int count, int damage, int maxDamage, boolean named) {
        public double durability() {
            return maxDamage > 0 ? Math.max(0, maxDamage - damage) / (double) maxDamage : 1.0;
        }
    }

    /** {@code count} items to remove from the stack in {@code slot} (expected to hold {@code item}). */
    public record Pick(int slot, String item, int count) {
        public JsonObject toJson() {
            return Json.obj("slot", slot, "item", item, "count", count);
        }
    }

    /** A keep profile (settings {@code keepProfiles.<name>}). */
    public record Profile(boolean wornArmor, boolean bestWeapon, List<String> tools, int foodMax,
                          List<String> blockGlobs, int blockCount, List<String> extra) {
        public Profile {
            tools = tools == null ? List.of() : List.copyOf(tools);
            blockGlobs = blockGlobs == null ? List.of() : List.copyOf(blockGlobs);
            extra = extra == null ? List.of() : List.copyOf(extra);
        }

        /** From settings JSON; {@code defaultBlocks} when the profile names no block globs. */
        public static Profile of(JsonObject o, List<String> defaultBlocks) {
            JsonObject food = Json.getObj(o, "food");
            JsonObject blocks = Json.getObj(o, "blocks");
            List<String> globs = blocks == null ? List.of() : Json.getStringList(blocks, "globs").stream()
                    .filter(s -> !s.isBlank()).map(s -> Ids.normalizeGlob(s.trim())).toList();
            return new Profile(!"none".equals(Json.getString(o, "armor", "worn")),
                    !"none".equals(Json.getString(o, "weapon", "best")),
                    Json.getStringList(o, "tools"),
                    food == null ? 0 : Math.max(0, Json.getInt(food, "max", 0)),
                    globs.isEmpty() ? defaultBlocks : globs,
                    blocks == null ? 0 : Math.max(0, Json.getInt(blocks, "count", 0)),
                    Json.getStringList(o, "extra").stream().filter(s -> !s.isBlank())
                            .map(s -> Ids.normalizeGlob(s.trim())).toList());
        }
    }

    /** Never thrown away by drop / trash chest. */
    static final List<String> VALUABLES = List.of("minecraft:*shulker_box", "minecraft:elytra",
            "minecraft:totem_of_undying", "minecraft:enchanted_golden_apple", "minecraft:nether_star",
            "minecraft:netherite_*", "minecraft:ancient_debris", "minecraft:diamond", "minecraft:diamond_block",
            "minecraft:emerald", "minecraft:emerald_block", "minecraft:beacon", "minecraft:dragon_egg",
            "minecraft:heart_of_the_sea", "minecraft:trident", "minecraft:mace", "minecraft:*_smithing_template");
    static final List<String> ARMOR_SLOTS = List.of("helmet", "chestplate", "leggings", "boots");
    static final List<String> ARMOR_TIERS = List.of("leather_", "golden_", "chainmail_", "iron_", "diamond_",
            "netherite_");
    static final List<String> FOOD_RANK = List.of("minecraft:golden_carrot", "minecraft:cooked_beef",
            "minecraft:cooked_porkchop", "minecraft:cooked_mutton", "minecraft:cooked_salmon",
            "minecraft:cooked_chicken", "minecraft:cooked_rabbit", "minecraft:cooked_cod", "minecraft:bread",
            "minecraft:baked_potato", "minecraft:pumpkin_pie", "minecraft:apple", "minecraft:carrot",
            "minecraft:beetroot", "minecraft:melon_slice", "minecraft:sweet_berries", "minecraft:glow_berries",
            "minecraft:dried_kelp", "minecraft:cookie", "minecraft:potato");

    private KeepPlanner() {
    }

    /** Stacks of an {@code inventory} query answer (main inventory 0..35). */
    public static List<Stack> stacks(JsonObject data) {
        List<Stack> out = new ArrayList<>();
        JsonArray slots = Json.getArr(data, "slots");
        for (JsonElement e : slots == null ? new JsonArray() : slots) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            String id = Json.getString(s, "item", null);
            int slot = Json.getInt(s, "slot", -1);
            int count = Json.getInt(s, "count", 0);
            if (id == null || slot < 0 || slot >= 36 || count <= 0 || Ids.isAir(id)) {
                continue;
            }
            out.add(new Stack(slot, Ids.normalize(id), count, Json.getInt(s, "damage", 0),
                    Json.getInt(s, "maxDamage", 0), s.has("name")));
        }
        return out;
    }

    /** Worn armor ids (head..feet, null = empty) of an {@code inventory} answer. */
    public static List<String> worn(JsonObject data) {
        List<String> out = new ArrayList<>();
        JsonArray armor = Json.getArr(data, "armor");
        for (JsonElement e : armor == null ? new JsonArray() : armor) {
            out.add(e.isJsonPrimitive() ? Ids.normalize(e.getAsString()) : null);
        }
        return out;
    }

    /**
     * Junk under a keep profile.
     *
     * @param isFood food a bot may eat (harmful food and {@code autoEat.avoid} excluded)
     */
    public static List<Pick> plan(List<Stack> stacks, List<String> worn, Profile p, Predicate<String> isFood,
                                  boolean protectValuables) {
        Map<Integer, Integer> keep = new HashMap<>();
        Set<Integer> decided = new HashSet<>();
        // extra + valuables: whole stacks
        for (Stack s : stacks) {
            if (Ids.matchesAny(p.extra(), s.item()) || protectValuables && (s.named() || Ids.matchesAny(VALUABLES, s.item()))) {
                keep.put(s.slot(), s.count());
                decided.add(s.slot());
            }
        }
        // armor: inventory pieces only when they beat what is worn (best per slot)
        for (String part : ARMOR_SLOTS) {
            int wornTier = -1;
            for (String w : worn) {
                if (w != null && w.endsWith("_" + part)) {
                    wornTier = Math.max(wornTier, armorTier(w));
                }
            }
            for (Stack s : stacks) { // a piece kept anyway (extra / valuable) raises the bar too
                if (decided.contains(s.slot()) && s.item().endsWith("_" + part)) {
                    wornTier = Math.max(wornTier, armorTier(s.item()));
                }
            }
            Stack best = null;
            for (Stack s : stacks) {
                if (decided.contains(s.slot()) || !s.item().endsWith("_" + part)) {
                    continue;
                }
                decided.add(s.slot());
                if (p.wornArmor() && armorTier(s.item()) > wornTier && (best == null || better(s, best, armorTier(s.item()),
                        armorTier(best.item())))) {
                    best = s;
                }
            }
            if (best != null) {
                keep.put(best.slot(), 1);
            }
        }
        // best weapon, best tool of each kind; other tools of these kinds are duplicates
        List<String> kinds = new ArrayList<>();
        if (p.bestWeapon()) {
            kinds.add("sword");
        }
        p.tools().forEach(k -> {
            if (!kinds.contains(k)) {
                kinds.add(k);
            }
        });
        for (String kind : kinds) {
            // a tool of this kind kept anyway (extra / valuable) is the one; the rest are duplicates
            boolean have = stacks.stream().anyMatch(s -> decided.contains(s.slot()) && isTool(s.item(), kind));
            Stack best = null;
            for (Stack s : stacks) {
                if (decided.contains(s.slot()) || !isTool(s.item(), kind)) {
                    continue;
                }
                decided.add(s.slot());
                if (!have && (best == null || better(s, best, toolTier(s.item(), kind), toolTier(best.item(), kind)))) {
                    best = s;
                }
            }
            if (best != null) {
                keep.put(best.slot(), 1);
            }
        }
        // food up to max, best first
        List<Stack> food = new ArrayList<>();
        for (Stack s : stacks) {
            if (!decided.contains(s.slot()) && isFood != null && isFood.test(s.item())) {
                food.add(s);
                decided.add(s.slot());
            }
        }
        food.sort(Comparator.comparingInt((Stack s) -> foodRank(s.item())).thenComparing(Comparator.comparingInt(Stack::count).reversed())
                .thenComparingInt(Stack::slot));
        fill(food, p.foodMax(), keep);
        // blocks up to count, largest stacks first
        List<Stack> blocks = new ArrayList<>();
        for (Stack s : stacks) {
            if (!decided.contains(s.slot()) && Ids.matchesAny(p.blockGlobs(), s.item())) {
                blocks.add(s);
                decided.add(s.slot());
            }
        }
        blocks.sort(Comparator.comparingInt(Stack::count).reversed().thenComparingInt(Stack::slot));
        fill(blocks, p.blockCount(), keep);
        return picks(stacks, keep);
    }

    /**
     * Auto-trash junk: stacks matching {@code junk}, minus {@code keepCounts} ({@code glob → count}, each budget used
     * by the first matching glob, stacks in slot order).
     */
    public static List<Pick> junk(List<Stack> stacks, List<String> junk, Map<String, Integer> keepCounts) {
        Map<String, Integer> budget = new LinkedHashMap<>();
        keepCounts.forEach((g, n) -> budget.put(Ids.normalizeGlob(g), Math.max(0, n)));
        Map<Integer, Integer> keep = new HashMap<>();
        List<Stack> ordered = new ArrayList<>(stacks);
        ordered.sort(Comparator.comparingInt(Stack::slot));
        for (Stack s : ordered) {
            if (!Ids.matchesAny(junk, s.item())) {
                keep.put(s.slot(), s.count());
                continue;
            }
            int k = 0;
            for (Map.Entry<String, Integer> b : budget.entrySet()) {
                if (b.getValue() > 0 && Ids.matches(b.getKey(), s.item())) {
                    k = Math.min(b.getValue(), s.count());
                    b.setValue(b.getValue() - k);
                    break;
                }
            }
            keep.put(s.slot(), k);
        }
        return picks(stacks, keep);
    }

    /** Estimated junk stacks from item totals (status), for the auto-trash decision. */
    public static int junkStacks(Map<String, Integer> totals, List<String> junk, Map<String, Integer> keepCounts) {
        Map<String, Integer> budget = new LinkedHashMap<>();
        keepCounts.forEach((g, n) -> budget.put(Ids.normalizeGlob(g), Math.max(0, n)));
        int stacks = 0;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            String id = Ids.normalize(e.getKey());
            if (!Ids.matchesAny(junk, id)) {
                continue;
            }
            int n = e.getValue();
            for (Map.Entry<String, Integer> b : budget.entrySet()) {
                if (b.getValue() > 0 && Ids.matches(b.getKey(), id)) {
                    int k = Math.min(b.getValue(), n);
                    b.setValue(b.getValue() - k);
                    n -= k;
                    break;
                }
            }
            stacks += (n + 63) / 64;
        }
        return stacks;
    }

    /** Items per id of a pick list. */
    public static Map<String, Integer> totals(List<Pick> picks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        picks.forEach(p -> out.merge(p.item(), p.count(), Integer::sum));
        return out;
    }

    /** Whole stacks among the picks (= slots freed). */
    public static int freedSlots(List<Stack> stacks, List<Pick> picks) {
        Map<Integer, Integer> counts = new HashMap<>();
        stacks.forEach(s -> counts.put(s.slot(), s.count()));
        int n = 0;
        for (Pick p : picks) {
            if (p.count() >= counts.getOrDefault(p.slot(), Integer.MAX_VALUE)) {
                n++;
            }
        }
        return n;
    }

    private static List<Pick> picks(List<Stack> stacks, Map<Integer, Integer> keep) {
        List<Pick> out = new ArrayList<>();
        for (Stack s : stacks) {
            int remove = s.count() - Math.min(s.count(), keep.getOrDefault(s.slot(), 0));
            if (remove > 0) {
                out.add(new Pick(s.slot(), s.item(), remove));
            }
        }
        out.sort(Comparator.comparingInt(Pick::slot));
        return out;
    }

    private static void fill(List<Stack> ordered, int max, Map<Integer, Integer> keep) {
        int left = max;
        for (Stack s : ordered) {
            int k = Math.min(left, s.count());
            keep.put(s.slot(), k);
            left -= k;
        }
    }

    private static boolean better(Stack a, Stack b, int tierA, int tierB) {
        if (tierA != tierB) {
            return tierA > tierB;
        }
        if (a.durability() != b.durability()) {
            return a.durability() > b.durability();
        }
        return a.slot() < b.slot();
    }

    static boolean isTool(String id, String kind) {
        return "shears".equals(kind) ? "minecraft:shears".equals(id) : Ids.path(id).endsWith("_" + kind);
    }

    static int toolTier(String id, String kind) {
        if ("shears".equals(kind)) {
            return 0;
        }
        return ProductionWork.toolTier(id, kind);
    }

    static int armorTier(String id) {
        String p = Ids.path(id);
        if (p.equals("turtle_helmet")) {
            return 3;
        }
        for (int i = ARMOR_TIERS.size() - 1; i >= 0; i--) {
            if (p.startsWith(ARMOR_TIERS.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private static int foodRank(String id) {
        int i = FOOD_RANK.indexOf(id);
        return i < 0 ? FOOD_RANK.size() : i;
    }
}
