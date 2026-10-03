package io.github.krekerdm.baritonebots.common.ids;

import io.github.krekerdm.baritonebots.common.geom.Dims;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdsTest {

    @Test
    void normalize() {
        assertEquals("minecraft:stone", Ids.normalize("stone"));
        assertEquals("minecraft:stone", Ids.normalize("  Stone "));
        assertEquals("othermod:steel_ingot", Ids.normalize("othermod:steel_ingot"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", Ids.normalize("OAK_STAIRS[facing=north,half=bottom]"));
        assertEquals("#minecraft:logs", Ids.normalize("#logs"));
        assertEquals("", Ids.normalize(""));
        assertNull(Ids.normalize(null));
        assertEquals("*", Ids.normalizeGlob("*"));
        assertEquals("minecraft:*_pickaxe", Ids.normalizeGlob("*_pickaxe"));
    }

    @Test
    void stateHelpers() {
        assertEquals("minecraft:oak_stairs", Ids.stripState("minecraft:oak_stairs[facing=north]"));
        assertEquals("minecraft:stone", Ids.stripState("minecraft:stone"));
        assertEquals(Map.of("facing", "north", "half", "bottom"), Ids.properties("minecraft:oak_stairs[facing=north, half=bottom]"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]",
                Ids.canonicalState("oak_stairs[half=bottom, facing=north]"));
        assertEquals("minecraft:stone", Ids.canonicalState("stone"));
        assertEquals("minecraft", Ids.namespace("stone"));
        assertEquals("othermod", Ids.namespace("othermod:thing[a=b]"));
        assertEquals("diamond_pickaxe", Ids.path("minecraft:diamond_pickaxe"));
        assertTrue(Ids.isAir("air"));
        assertTrue(Ids.isAir("minecraft:cave_air"));
        assertFalse(Ids.isAir("minecraft:glass"));
    }

    @Test
    void matches() {
        assertTrue(Ids.matches("*_pickaxe", "minecraft:diamond_pickaxe"));
        assertTrue(Ids.matches("minecraft:*_pickaxe", "diamond_pickaxe"));
        assertFalse(Ids.matches("*_pickaxe", "othermod:steel_pickaxe"), "a bare glob is in the minecraft namespace");
        assertTrue(Ids.matches("*", "othermod:anything"));
        assertTrue(Ids.matches("*:*_pickaxe", "othermod:steel_pickaxe"));
        assertFalse(Ids.matches("minecraft:*", "othermod:x"));
        assertTrue(Ids.matches("stone", "minecraft:stone"));
        assertFalse(Ids.matches("stone", "minecraft:stone_bricks"));
        assertTrue(Ids.matches("*log*", "minecraft:stripped_oak_log"));
        assertTrue(Ids.matches("*log", "minecraft:oak_log"));
        assertFalse(Ids.matches("*log", "minecraft:oak_logs"));
        assertTrue(Ids.matches("a*b*c", "minecraft:axxbyyc"));
        assertTrue(Ids.matches("a*b*c", "minecraft:abc"));
        assertFalse(Ids.matches("a*b*c", "minecraft:acb"));
        assertTrue(Ids.matches("**", "minecraft:x"));
        assertTrue(Ids.matches("oak_stairs", "minecraft:oak_stairs[facing=north]"), "state ignored when the glob has none");
        assertFalse(Ids.matches("oak_stairs[facing=south]", "minecraft:oak_stairs[facing=north]"));
        assertTrue(Ids.matches("oak_stairs[facing=*]", "minecraft:oak_stairs[facing=north]"));
        assertTrue(Ids.matches("Minecraft:Diamond_Sword", "minecraft:diamond_sword"));
        assertFalse(Ids.matches("", "minecraft:stone"));
        assertFalse(Ids.matches(null, "minecraft:stone"));
        assertFalse(Ids.matches("stone", null));
    }

    @Test
    void matchesAny() {
        assertTrue(Ids.matchesAny(List.of("dirt", "*_log"), "minecraft:birch_log"));
        assertFalse(Ids.matchesAny(List.of("dirt", "*_log"), "minecraft:birch_planks"));
        assertFalse(Ids.matchesAny(List.of(), "minecraft:dirt"));
        assertFalse(Ids.matchesAny(null, "minecraft:dirt"));
    }

    @Test
    void dims() {
        assertEquals(Dims.NETHER, Dims.normalize("nether"));
        assertEquals(Dims.END, Dims.normalize("the_end"));
        assertEquals(Dims.OVERWORLD, Dims.normalize("OVERWORLD"));
        assertEquals(Dims.OVERWORLD, Dims.normalize(null));
        assertEquals("othermod:mining_world", Dims.normalize("othermod:mining_world"));
    }
}
