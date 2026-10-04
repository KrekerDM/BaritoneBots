package io.github.krekerdm.baritonebots.manager.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recipes, item/block tags and simplified block loot of one game version (SPEC §5.7 "Game data"), parsed from the
 * client jar by {@link GameDataParser}. Immutable and thread-safe once built; every lookup takes full or short ids.
 */
public final class GameData {
    public static final String SHAPED = "minecraft:crafting_shaped";
    public static final String SHAPELESS = "minecraft:crafting_shapeless";
    public static final String SMELTING = "minecraft:smelting";
    public static final String BLASTING = "minecraft:blasting";
    public static final String SMOKING = "minecraft:smoking";
    public static final String CAMPFIRE = "minecraft:campfire_cooking";
    public static final String STONECUTTING = "minecraft:stonecutting";
    public static final List<String> TOOL_TIERS = List.of("wood", "stone", "iron", "diamond");

    /**
     * One recipe. {@code slots}: shaped = {@code width × height} cells row-major ({@code null} = empty cell);
     * shapeless = the ingredients; cooking / stonecutting = one ingredient. Each ingredient is the list of
     * acceptable item ids (tags already expanded).
     */
    public record Recipe(String id, String type, String result, int count, int width, int height,
                         List<List<String>> slots) {
        public Recipe {
            count = Math.max(1, count);
            slots = Collections.unmodifiableList(new ArrayList<>(slots));
        }

        public boolean shaped() {
            return SHAPED.equals(type);
        }

        public boolean crafting() {
            return SHAPED.equals(type) || SHAPELESS.equals(type);
        }

        public boolean cooking() {
            return SMELTING.equals(type) || BLASTING.equals(type) || SMOKING.equals(type) || CAMPFIRE.equals(type);
        }

        /** Non-empty ingredient slots (one entry per item consumed by one craft). */
        public List<List<String>> ingredients() {
            return slots.stream().filter(s -> s != null && !s.isEmpty()).toList();
        }

        public JsonObject toJson() {
            JsonArray s = new JsonArray();
            slots.forEach(cell -> s.add(cell == null ? com.google.gson.JsonNull.INSTANCE : Json.arrOf(cell)));
            return Json.obj("id", id, "type", type, "result", result, "count", count, "width", width,
                    "height", height, "slots", s);
        }
    }

    /** An item a block drops when mined without silk touch / shears; {@code guaranteed} = no chance roll. */
    public record Drop(String item, boolean guaranteed) {
    }

    /**
     * Tool needed to mine a block: {@code kind} pickaxe|axe|shovel|hoe|none, {@code minTier} wood|stone|iron|diamond
     * ({@code null} for none). {@code required}: without it the block drops nothing (pickaxe blocks and anything with a
     * {@code needs_*_tool} tag; an approximation of the game's per-block {@code requiresCorrectToolForDrops}).
     */
    public record ToolReq(String kind, String minTier, boolean required) {
        public static final ToolReq NONE = new ToolReq("none", null, false);

        public int tierRank() {
            return minTier == null ? -1 : TOOL_TIERS.indexOf(minTier);
        }

        public JsonObject toJson() {
            return Json.obj("kind", kind, "minTier", minTier, "required", required);
        }
    }

    private final String source;
    private final Map<String, Set<String>> itemTags;
    private final Map<String, Set<String>> blockTags;
    private final Map<String, List<Recipe>> byResult;
    private final Map<String, List<Drop>> dropsByBlock;
    private final Map<String, List<String>> blocksByDrop;
    private final Map<String, List<Recipe>> cookingByInput;
    private final int recipeCount;

    GameData(String source, Map<String, Set<String>> itemTags, Map<String, Set<String>> blockTags,
             List<Recipe> recipes, Map<String, List<Drop>> dropsByBlock) {
        this.source = source;
        this.itemTags = freeze(itemTags);
        this.blockTags = freeze(blockTags);
        Map<String, List<Recipe>> r = new LinkedHashMap<>();
        for (Recipe rec : recipes) {
            r.computeIfAbsent(rec.result(), k -> new ArrayList<>()).add(rec);
        }
        Comparator<Recipe> order = Comparator.comparingInt(GameData::typeRank).thenComparing(Recipe::id);
        r.replaceAll((k, v) -> v.stream().sorted(order).toList());
        this.byResult = Collections.unmodifiableMap(r);
        this.recipeCount = recipes.size();
        Map<String, List<Recipe>> cooking = new LinkedHashMap<>();
        for (Recipe rec : recipes) {
            if (rec.cooking() && !rec.slots().isEmpty() && rec.slots().getFirst() != null) {
                for (String in : rec.slots().getFirst()) {
                    cooking.computeIfAbsent(in, k -> new ArrayList<>()).add(rec);
                }
            }
        }
        this.cookingByInput = Collections.unmodifiableMap(cooking);
        Map<String, List<Drop>> d = new LinkedHashMap<>();
        Map<String, List<String>> by = new LinkedHashMap<>();
        Map<String, Set<String>> guaranteed = new LinkedHashMap<>();
        dropsByBlock.forEach((block, drops) -> {
            d.put(block, List.copyOf(drops));
            for (Drop drop : drops) {
                by.computeIfAbsent(drop.item(), k -> new ArrayList<>());
                if (!by.get(drop.item()).contains(block)) {
                    by.get(drop.item()).add(block);
                }
                if (drop.guaranteed()) {
                    guaranteed.computeIfAbsent(drop.item(), k -> new LinkedHashSet<>()).add(block);
                }
            }
        });
        by.replaceAll((item, blocks) -> {
            Set<String> g = guaranteed.getOrDefault(item, Set.of());
            return blocks.stream().sorted(Comparator.comparing((String b) -> !g.contains(b)).thenComparing(b -> b))
                    .toList();
        });
        this.dropsByBlock = Collections.unmodifiableMap(d);
        this.blocksByDrop = Collections.unmodifiableMap(by);
    }

    private static Map<String, Set<String>> freeze(Map<String, Set<String>> m) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(out);
    }

    private static int typeRank(Recipe r) {
        return switch (r.type()) {
            case SHAPED, SHAPELESS -> 0;
            case SMELTING -> 1;
            case BLASTING, SMOKING -> 2;
            case CAMPFIRE -> 3;
            default -> 4;
        };
    }

    /** No data at all (no client jar): callers fall back to "item id = mineable block id". */
    public static GameData empty() {
        return new GameData("none", Map.of(), Map.of(), List.of(), Map.of());
    }

    public String source() {
        return source;
    }

    public boolean isEmpty() {
        return recipeCount == 0 && dropsByBlock.isEmpty();
    }

    public JsonObject stats() {
        return Json.obj("source", source, "recipes", recipeCount, "itemTags", itemTags.size(),
                "blockTags", blockTags.size(), "lootTables", dropsByBlock.size());
    }

    // ------------------------------------------------------------------ lookups

    /** Every recipe producing {@code item}: crafting first, then smelting, blasting/smoking, campfire, stonecutting. */
    public List<Recipe> recipesFor(String item) {
        return byResult.getOrDefault(Ids.normalize(item), List.of());
    }

    /** Shaped / shapeless crafting recipes producing {@code item}. */
    public List<Recipe> craftingRecipesFor(String item) {
        return recipesFor(item).stream().filter(Recipe::crafting).toList();
    }

    /** Furnace-family recipes producing {@code item} (smelting, blasting, smoking, campfire cooking). */
    public List<Recipe> smeltingSources(String item) {
        return recipesFor(item).stream().filter(Recipe::cooking).toList();
    }

    /** Furnace-family recipes that accept {@code input} (what a furnace makes of it). */
    public List<Recipe> cookingRecipesUsing(String input) {
        return cookingByInput.getOrDefault(Ids.normalize(input), List.of());
    }

    /** Blocks whose loot (no silk touch, no shears) can contain {@code item}; guaranteed drops first. */
    public List<String> blocksDropping(String item) {
        return blocksByDrop.getOrDefault(Ids.normalize(item), List.of());
    }

    /** What a block drops when mined normally (empty when unknown or silk-touch only). */
    public List<Drop> drops(String block) {
        return dropsByBlock.getOrDefault(Ids.normalize(Ids.stripState(block)), List.of());
    }

    public boolean hasLootTable(String block) {
        return dropsByBlock.containsKey(Ids.normalize(Ids.stripState(block)));
    }

    /** Resolved item tag (nested tags expanded); {@code tag} with or without {@code #}. */
    public Set<String> itemTag(String tag) {
        return itemTags.getOrDefault(tagId(tag), Set.of());
    }

    public Set<String> blockTag(String tag) {
        return blockTags.getOrDefault(tagId(tag), Set.of());
    }

    private static String tagId(String tag) {
        String t = tag == null ? "" : tag.trim();
        return Ids.normalize(t.startsWith("#") ? t.substring(1) : t);
    }

    /** True when a recipe does not fit the 2×2 inventory grid. */
    public static boolean needsTable(Recipe r) {
        if (r.shaped()) {
            return r.width() > 2 || r.height() > 2;
        }
        return r.ingredients().size() > 4;
    }

    /**
     * The 3×3 grid for the bot's {@code craft.grid}: rows of cells, each cell the acceptable ids or {@code null}.
     * Shaped patterns sit in the top-left corner; shapeless ingredients fill row by row.
     */
    public static List<List<List<String>>> craftingGrid(Recipe r) {
        List<List<List<String>>> rows = new ArrayList<>();
        for (int y = 0; y < 3; y++) {
            List<List<String>> row = new ArrayList<>();
            for (int x = 0; x < 3; x++) {
                row.add(null);
            }
            rows.add(row);
        }
        if (r.shaped()) {
            for (int y = 0; y < r.height() && y < 3; y++) {
                for (int x = 0; x < r.width() && x < 3; x++) {
                    rows.get(y).set(x, r.slots().get(y * r.width() + x));
                }
            }
        } else if (r.crafting()) {
            List<List<String>> ing = r.ingredients();
            for (int i = 0; i < ing.size() && i < 9; i++) {
                rows.get(i / 3).set(i % 3, ing.get(i));
            }
        }
        return rows;
    }

    /** {@link #craftingGrid(Recipe)} as JSON ({@code null} cells stay JSON null). */
    public static JsonArray craftingGridJson(Recipe r) {
        JsonArray out = new JsonArray();
        for (List<List<String>> row : craftingGrid(r)) {
            JsonArray a = new JsonArray();
            row.forEach(cell -> a.add(cell == null ? com.google.gson.JsonNull.INSTANCE : Json.arrOf(cell)));
            out.add(a);
        }
        return out;
    }

    /** Tool kind and minimum tier from {@code mineable/*} and {@code needs_*_tool} block tags. */
    public ToolReq toolFor(String block) {
        String b = Ids.normalize(Ids.stripState(block));
        String kind = "none";
        for (String k : List.of("pickaxe", "axe", "shovel", "hoe")) {
            if (blockTag("minecraft:mineable/" + k).contains(b)) {
                kind = k;
                break;
            }
        }
        String tier = null;
        if (blockTag("minecraft:needs_diamond_tool").contains(b)) {
            tier = "diamond";
        } else if (blockTag("minecraft:needs_iron_tool").contains(b)) {
            tier = "iron";
        } else if (blockTag("minecraft:needs_stone_tool").contains(b)) {
            tier = "stone";
        }
        if ("none".equals(kind)) {
            return tier == null ? ToolReq.NONE : new ToolReq("pickaxe", tier, true);
        }
        return new ToolReq(kind, tier == null ? "wood" : tier, "pickaxe".equals(kind) || tier != null);
    }

    /** Everything known about one item, for the panel / debugging. */
    public JsonObject describe(String item) {
        String id = Ids.normalize(item);
        JsonArray recipes = new JsonArray();
        recipesFor(id).forEach(r -> recipes.add(r.toJson()));
        JsonArray drops = new JsonArray();
        drops(id).forEach(d -> drops.add(Json.obj("item", d.item(), "guaranteed", d.guaranteed())));
        return Json.obj("item", id, "recipes", recipes, "blocksDropping", Json.arrOf(blocksDropping(id)),
                "drops", drops, "tool", toolFor(id).toJson());
    }
}
