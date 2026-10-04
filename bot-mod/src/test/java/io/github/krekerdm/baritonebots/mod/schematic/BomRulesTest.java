package io.github.krekerdm.baritonebots.mod.schematic;

import io.github.krekerdm.baritonebots.mod.schematic.BomRules.Cost;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BOM counting rules on a fake block model ({@code block id → item id} like {@code Block#asItem}). */
class BomRulesTest {
    private static final Map<String, String> ITEM_OF = new HashMap<>();

    static {
        for (String same : new String[] {"stone", "oak_slab", "oak_door", "red_bed", "sunflower", "candle",
                "white_candle", "cake", "flower_pot", "poppy", "azalea", "sea_pickle", "turtle_egg", "pink_petals",
                "snow", "kelp", "bamboo", "big_dripleaf", "chest", "torch", "oak_stairs"}) {
            ITEM_OF.put("minecraft:" + same, "minecraft:" + same);
        }
        // Block#asItem: wall variants and crops map to their item
        ITEM_OF.put("minecraft:wall_torch", "minecraft:torch");
        ITEM_OF.put("minecraft:wheat", "minecraft:wheat_seeds");
        ITEM_OF.put("minecraft:carrots", "minecraft:carrot");
        ITEM_OF.put("minecraft:cave_vines", "minecraft:glow_berries");
        ITEM_OF.put("minecraft:melon_stem", "minecraft:melon_seeds");
    }

    private static Cost cost(String id, String... props) {
        Map<String, String> p = new HashMap<>();
        for (int i = 0; i + 1 < props.length; i += 2) {
            p.put(props[i], props[i + 1]);
        }
        return BomRules.cost("minecraft:" + id, p::get, ITEM_OF::get, false);
    }

    private static Map<String, Integer> items(String id, String... props) {
        return cost(id, props).items();
    }

    @Test
    void plainBlockIsOneItem() {
        assertEquals(Map.of("minecraft:stone", 1), items("stone"));
        assertEquals(Map.of("minecraft:oak_stairs", 1), items("oak_stairs", "half", "top", "facing", "north"),
                "stairs use half=top/bottom, which is not a double-block half");
        assertEquals(Map.of("minecraft:chest", 1), items("chest", "type", "left"));
    }

    @Test
    void skippedBlocks() {
        for (String id : new String[] {"air", "cave_air", "water", "lava", "bubble_column", "fire", "soul_fire",
                "nether_portal", "end_portal", "end_gateway", "piston_head", "moving_piston"}) {
            assertSame(BomRules.SKIP, cost(id), id);
        }
        assertSame(BomRules.SKIP, BomRules.cost("othermod:oil", n -> null, ITEM_OF::get, true),
                "the caller flags modded fluids");
        assertFalse(BomRules.SKIP.counted());
    }

    @Test
    void doubleSlabCountsTwo() {
        assertEquals(Map.of("minecraft:oak_slab", 2), items("oak_slab", "type", "double"));
        assertEquals(Map.of("minecraft:oak_slab", 1), items("oak_slab", "type", "bottom"));
    }

    @Test
    void onlyOneHalfOfDoorsTallPlantsAndBedsCounts() {
        assertEquals(Map.of("minecraft:oak_door", 1), items("oak_door", "half", "lower"));
        assertSame(BomRules.FREE, cost("oak_door", "half", "upper"));
        assertEquals(Map.of("minecraft:sunflower", 1), items("sunflower", "half", "lower"));
        assertSame(BomRules.FREE, cost("sunflower", "half", "upper"));
        assertEquals(Map.of("minecraft:red_bed", 1), items("red_bed", "part", "foot"));
        assertSame(BomRules.FREE, cost("red_bed", "part", "head"));
        assertTrue(BomRules.FREE.counted(), "the free half is still a schematic position");
    }

    @Test
    void wallVariantsAndCropsUseTheirItem() {
        assertEquals(Map.of("minecraft:torch", 1), items("wall_torch", "facing", "east"));
        assertEquals(Map.of("minecraft:wheat_seeds", 1), items("wheat", "age", "7"));
        assertEquals(Map.of("minecraft:carrot", 1), items("carrots", "age", "3"));
        assertEquals(Map.of("minecraft:melon_seeds", 1), items("attached_melon_stem", "facing", "north"));
    }

    @Test
    void amountsAndCompositeBlocks() {
        assertEquals(Map.of("minecraft:candle", 3), items("candle", "candles", "3", "lit", "false"));
        assertEquals(Map.of("minecraft:sea_pickle", 4), items("sea_pickle", "pickles", "4", "waterlogged", "true"));
        assertEquals(Map.of("minecraft:turtle_egg", 2), items("turtle_egg", "eggs", "2"));
        assertEquals(Map.of("minecraft:pink_petals", 3), items("pink_petals", "flower_amount", "3"));
        assertEquals(Map.of("minecraft:snow", 5), items("snow", "layers", "5"));
        assertEquals(Map.of("minecraft:cake", 1, "minecraft:white_candle", 1), items("white_candle_cake"));
        assertEquals(Map.of("minecraft:cake", 1, "minecraft:candle", 1), items("candle_cake", "lit", "true"));
        assertEquals(Map.of("minecraft:flower_pot", 1, "minecraft:poppy", 1), items("potted_poppy"));
        assertEquals(Map.of("minecraft:flower_pot", 1, "minecraft:azalea", 1), items("potted_azalea_bush"));
    }

    @Test
    void plantBodiesUseTheHeadItem() {
        assertEquals(Map.of("minecraft:kelp", 1), items("kelp_plant"));
        assertEquals(Map.of("minecraft:glow_berries", 1), items("cave_vines_plant", "berries", "false"));
        assertEquals(Map.of("minecraft:bamboo", 1), items("bamboo_sapling"));
        assertEquals(Map.of("minecraft:big_dripleaf", 1), items("big_dripleaf_stem"));
    }

    @Test
    void blocksWithoutItemAreUnobtainable() {
        Cost c = cost("frosted_ice", "age", "0");
        assertTrue(c.counted());
        assertTrue(c.items().isEmpty());
        assertEquals("minecraft:frosted_ice", c.unobtainable());
        Cost pot = cost("potted_unknown_plant");
        assertEquals(Map.of("minecraft:flower_pot", 1), pot.items());
        assertEquals("minecraft:potted_unknown_plant", pot.unobtainable());
        assertNull(cost("stone").unobtainable());
    }

    @Test
    void itemLookupIsOnlyAskedForBlockIds() {
        Function<String, String> strict = id -> {
            assertTrue(id.startsWith("minecraft:"), id);
            return ITEM_OF.get(id);
        };
        assertEquals(Map.of("minecraft:flower_pot", 1, "minecraft:poppy", 1),
                BomRules.cost("minecraft:potted_poppy", n -> null, strict, false).items());
    }
}
