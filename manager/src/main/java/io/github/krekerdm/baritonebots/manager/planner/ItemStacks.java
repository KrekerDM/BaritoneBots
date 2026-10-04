package io.github.krekerdm.baritonebots.manager.planner;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item facts that live in game code rather than in the jar's data files: stack sizes and furnace fuel values.
 * Heuristics by id; good enough to size restocks and fuel loads.
 */
public final class ItemStacks {
    /** Items smelted per fuel item (vanilla burn time / 200 ticks), best fuels first. */
    private static final Map<String, Double> FUELS = new LinkedHashMap<>();

    static {
        FUELS.put("minecraft:coal_block", 80.0);
        FUELS.put("minecraft:dried_kelp_block", 20.0);
        FUELS.put("minecraft:blaze_rod", 12.0);
        FUELS.put("minecraft:coal", 8.0);
        FUELS.put("minecraft:charcoal", 8.0);
    }

    private static final List<String> SINGLE = List.of("*_sword", "*_pickaxe", "*_axe", "*_shovel", "*_hoe",
            "*_helmet", "*_chestplate", "*_leggings", "*_boots", "*_bed", "*_boat", "*_chest_boat", "*_raft",
            "*minecart", "*_shulker_box", "minecraft:shulker_box", "minecraft:potion", "minecraft:splash_potion",
            "minecraft:lingering_potion", "minecraft:cake", "*_bucket", "minecraft:saddle", "minecraft:shears",
            "minecraft:bow", "minecraft:crossbow", "minecraft:trident", "minecraft:shield", "minecraft:elytra",
            "minecraft:totem_of_undying", "minecraft:flint_and_steel", "minecraft:fishing_rod", "*_horse_armor",
            "minecraft:music_disc_*", "minecraft:written_book", "minecraft:writable_book", "*_bundle",
            "minecraft:bundle", "minecraft:mace", "*_harness", "minecraft:spyglass", "minecraft:brush");
    private static final List<String> SIXTEEN = List.of("*_sign", "*_hanging_sign", "*_banner", "minecraft:snowball",
            "minecraft:egg", "minecraft:blue_egg", "minecraft:brown_egg", "minecraft:ender_pearl",
            "minecraft:bucket", "minecraft:honey_bottle", "minecraft:armor_stand", "minecraft:written_book",
            "minecraft:wind_charge");

    private ItemStacks() {
    }

    public static int maxStack(String item) {
        String id = Ids.normalize(item);
        if ("minecraft:bucket".equals(id)) {
            return 16;
        }
        if (Ids.matchesAny(SINGLE, id)) {
            return 1;
        }
        return Ids.matchesAny(SIXTEEN, id) ? 16 : 64;
    }

    /** Slots needed for {@code count} items. */
    public static int slots(String item, int count) {
        int max = maxStack(item);
        return count <= 0 ? 0 : (count + max - 1) / max;
    }

    /** Items one fuel item smelts; 0 = not a fuel. Logs/planks 1.5, wooden slabs 0.75, sticks 0.5, bamboo 0.25. */
    public static double burnItems(String item) {
        String id = Ids.normalize(item);
        Double known = FUELS.get(id);
        if (known != null) {
            return known;
        }
        boolean nether = id.contains("crimson") || id.contains("warped"); // nether wood does not burn
        if (!nether && (Ids.matches("minecraft:*_log", id) || Ids.matches("minecraft:*_wood", id)
                || Ids.matches("minecraft:*_planks", id))) {
            return 1.5;
        }
        if (Ids.matches("minecraft:*_slab", id) && (id.contains("oak") || id.contains("spruce") || id.contains("birch")
                || id.contains("jungle") || id.contains("acacia") || id.contains("mangrove") || id.contains("cherry")
                || id.contains("bamboo"))) {
            return 0.75;
        }
        if ("minecraft:stick".equals(id)) {
            return 0.5;
        }
        if ("minecraft:bamboo".equals(id)) {
            return 0.25;
        }
        return 0;
    }

    /** Known fuels, best first (for "fuel" globs and the panel). */
    public static List<String> knownFuels() {
        return List.copyOf(FUELS.keySet());
    }
}
