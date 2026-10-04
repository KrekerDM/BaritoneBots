package io.github.krekerdm.baritonebots.mod.task.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnimalFoodAndMiningTest {
    @Test
    void defaultFoodTable() {
        assertEquals(Optional.of("minecraft:wheat"), AnimalFood.defaultFood("minecraft:cow"));
        assertEquals(Optional.of("minecraft:wheat"), AnimalFood.defaultFood("mooshroom"));
        assertEquals(Optional.of("minecraft:wheat"), AnimalFood.defaultFood("sheep"));
        assertEquals(Optional.of("minecraft:wheat"), AnimalFood.defaultFood("goat"));
        assertEquals(Optional.of("minecraft:carrot"), AnimalFood.defaultFood("pig"));
        assertEquals(Optional.of("minecraft:wheat_seeds"), AnimalFood.defaultFood("chicken"));
        assertEquals(Optional.of("minecraft:carrot"), AnimalFood.defaultFood("rabbit"));
        assertEquals(Optional.of("minecraft:golden_carrot"), AnimalFood.defaultFood("horse"));
        assertEquals(Optional.of("minecraft:golden_carrot"), AnimalFood.defaultFood("donkey"));
        assertEquals(Optional.of("minecraft:hay_block"), AnimalFood.defaultFood("llama"));
        assertEquals(Optional.of("minecraft:seagrass"), AnimalFood.defaultFood(" minecraft:turtle "));
        assertEquals(Optional.empty(), AnimalFood.defaultFood("minecraft:fox"), "others use Animal#isFood");
        assertEquals(Optional.empty(), AnimalFood.defaultFood(null));
    }

    @Test
    void legitMiningLevels() {
        assertEquals(-58, LegitMining.bestY(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")));
        assertEquals(-58, LegitMining.bestY(List.of("deepslate_redstone_ore")));
        assertEquals(-16, LegitMining.bestY(List.of("minecraft:gold_ore")));
        assertEquals(0, LegitMining.bestY(List.of("minecraft:lapis_ore")));
        assertEquals(16, LegitMining.bestY(List.of("minecraft:iron_ore")));
        assertEquals(48, LegitMining.bestY(List.of("minecraft:copper_ore")));
        assertEquals(96, LegitMining.bestY(List.of("minecraft:coal_ore")));
        assertEquals(16, LegitMining.bestY(List.of("minecraft:stone", "minecraft:emerald_ore", "minecraft:iron_ore")),
                "first ore with a known level wins");
        assertNull(LegitMining.bestY(List.of("minecraft:emerald_ore")));
        assertNull(LegitMining.bestY(List.of("minecraft:nether_gold_ore", "minecraft:nether_quartz_ore")));
        assertNull(LegitMining.bestY(List.of()));
    }
}
