package io.github.krekerdm.baritonebots.mod.schematic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Items needed to place one block state (SPEC §2.4 {@code bom}). Pure code: the caller supplies the block id, a
 * property lookup ({@code name → value name}, null when absent) and {@code itemOf} ({@code block id → item id},
 * null when the block has no item; in the mod this is {@code Block#asItem}, which already maps wall variants to
 * their item and crops to their seed).
 * <ul>
 *   <li>skipped (not counted anywhere): air, fluids, bubble columns, fire, portals, piston heads, moving pistons;</li>
 *   <li>free (a position, but no item): the upper half of doors / tall plants ({@code half=upper}) and the head of
 *       beds — the lower half / foot carries the item;</li>
 *   <li>double slab = 2 slabs; candles / sea pickles / turtle eggs / petals ({@code flower_amount}) / leaf litter
 *       ({@code segment_amount}) / snow layers count their amount;</li>
 *   <li>candle cake = cake + candle, potted plant = flower pot + plant;</li>
 *   <li>plant bodies use their head's item (kelp_plant → kelp, cave_vines_plant → glow berries, ...), attached
 *       stems their seeds;</li>
 *   <li>anything else without an item is {@link Cost#unobtainable() unobtainable}.</li>
 * </ul>
 */
public final class BomRules {
    /** Not a placement (air, fluid, fire, portal, piston head, moving piston). */
    public static final Cost SKIP = new Cost(true, Map.of(), null);
    /** A position that is placed together with another one (upper door half, bed head). */
    public static final Cost FREE = new Cost(false, Map.of(), null);

    private static final Set<String> SKIP_IDS = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:water", "minecraft:lava", "minecraft:bubble_column",
            "minecraft:fire", "minecraft:soul_fire",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:piston_head", "minecraft:moving_piston");

    private static final Map<String, String> ITEM_ALIASES = Map.of(
            "minecraft:kelp_plant", "minecraft:kelp",
            "minecraft:cave_vines_plant", "minecraft:cave_vines",
            "minecraft:weeping_vines_plant", "minecraft:weeping_vines",
            "minecraft:twisting_vines_plant", "minecraft:twisting_vines",
            "minecraft:bamboo_sapling", "minecraft:bamboo",
            "minecraft:big_dripleaf_stem", "minecraft:big_dripleaf",
            "minecraft:attached_melon_stem", "minecraft:melon_stem",
            "minecraft:attached_pumpkin_stem", "minecraft:pumpkin_stem");

    /** Integer properties whose value is the number of items in the block. */
    private static final List<String> AMOUNT_PROPS = List.of("candles", "pickles", "eggs", "flower_amount",
            "segment_amount");

    private BomRules() {
    }

    /**
     * Cost of one position.
     *
     * @param items        item id → count (empty for skipped/free positions)
     * @param unobtainable block id when (part of) the block has no item, else null
     */
    public record Cost(boolean skip, Map<String, Integer> items, String unobtainable) {
        /** True when the position counts as a block of the schematic (not skipped). */
        public boolean counted() {
            return !skip;
        }
    }

    /**
     * @param blockId    namespaced block id without properties
     * @param prop       property name → value name, null when the state has no such property
     * @param itemOf     block id → item id, null when the block has no item
     * @param airOrFluid the state is air or a fluid block (lets modded fluids be skipped too)
     */
    public static Cost cost(String blockId, Function<String, String> prop, Function<String, String> itemOf,
                            boolean airOrFluid) {
        if (airOrFluid || SKIP_IDS.contains(blockId)) {
            return SKIP;
        }
        if ("upper".equals(prop.apply("half"))) {
            return FREE; // doors, tall flowers/grass, pitcher crop, small dripleaf (stairs use top/bottom)
        }
        if (blockId.endsWith("_bed") && "head".equals(prop.apply("part"))) {
            return FREE;
        }
        int colon = blockId.indexOf(':');
        String ns = colon < 0 ? "minecraft" : blockId.substring(0, colon);
        String path = colon < 0 ? blockId : blockId.substring(colon + 1);
        if (path.endsWith("candle_cake")) {
            String candle = ns + ":" + path.substring(0, path.length() - "_cake".length());
            return pair(blockId, itemOf, ns + ":cake", candle);
        }
        if (path.startsWith("potted_")) {
            String plant = path.substring("potted_".length());
            if (plant.endsWith("azalea_bush")) {
                plant = plant.substring(0, plant.length() - "_bush".length());
            }
            return pair(blockId, itemOf, ns + ":flower_pot", ns + ":" + plant);
        }
        int n = 1;
        if ("double".equals(prop.apply("type"))) {
            n = 2; // double slab (chests use single/left/right, piston heads normal/sticky)
        }
        for (String p : AMOUNT_PROPS) {
            n = amount(prop.apply(p), n);
        }
        if ("minecraft:snow".equals(blockId)) {
            n = amount(prop.apply("layers"), n);
        }
        String item = itemOf.apply(ITEM_ALIASES.getOrDefault(blockId, blockId));
        if (item == null) {
            return new Cost(false, Map.of(), blockId);
        }
        return new Cost(false, Map.of(item, n), null);
    }

    /** Block made of two items (one each); a part without an item makes the block unobtainable. */
    private static Cost pair(String blockId, Function<String, String> itemOf, String blockA, String blockB) {
        Map<String, Integer> items = new LinkedHashMap<>();
        boolean missing = false;
        for (String b : new String[] {blockA, blockB}) {
            String item = itemOf.apply(b);
            if (item == null) {
                missing = true;
            } else {
                items.merge(item, 1, Integer::sum);
            }
        }
        return new Cost(false, Map.copyOf(items), missing ? blockId : null);
    }

    private static int amount(String value, int def) {
        if (value == null) {
            return def;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
