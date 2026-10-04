package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Sorting categories (SPEC §5.7a): first match wins, #tags from game data, majority adoption, food. */
class CategoriesTest {
    private final Categories cats = new Categories(ManagerConfig.AutopilotCfg.defaults().categories(), Fixtures.gameData());

    @Test
    void mapsItemsByGlobsAndTags() {
        assertEquals("ores_ingots", cats.of("minecraft:raw_iron"));
        assertEquals("ores_ingots", cats.of("iron_ingot"));
        assertEquals("ores_ingots", cats.of("minecraft:deepslate_iron_ore"), "ores before the *deepslate* stone glob");
        assertEquals("wood", cats.of("minecraft:oak_log"));
        assertEquals("wood", cats.of("minecraft:oak_wood"), "via the #minecraft:logs tag of the game data");
        assertEquals("stone_building", cats.of("minecraft:cobblestone_slab"));
        assertEquals("stone_building", cats.of("minecraft:cobblestone"));
        assertEquals("food", cats.of("minecraft:bread"));
        assertEquals("tools_armor", cats.of("minecraft:iron_pickaxe"));
        assertEquals("redstone", cats.of("minecraft:repeater"));
        assertEquals("farming", cats.of("minecraft:wheat_seeds"));
        assertEquals("mob_drops", cats.of("minecraft:string"));
        assertEquals(Categories.MISC, cats.of("minecraft:nether_star"));
        assertEquals(List.of("ores_ingots", "wood", "food", "tools_armor", "redstone", "farming", "mob_drops",
                "stone_building", "misc"), cats.names());
    }

    @Test
    void customCategoriesAndMiscFallback() {
        Categories c = new Categories(List.of(new ManagerConfig.Category("gems", List.of("minecraft:diamond", "minecraft:emerald"))),
                null);
        assertEquals("gems", c.of("diamond"));
        assertEquals("misc", c.of("minecraft:stone"));
        assertEquals(List.of("gems", "misc"), c.names());
        assertTrue(c.isFood("minecraft:bread", List.of()), "fallback food list without a food category");
    }

    @Test
    void foodExcludesHarmfulAndAvoided() {
        assertTrue(cats.isFood("minecraft:cooked_beef", List.of()));
        assertFalse(cats.isFood("minecraft:rotten_flesh", List.of()));
        assertFalse(cats.isFood("minecraft:spider_eye", List.of()));
        assertFalse(cats.isFood("minecraft:chicken", List.of("minecraft:chicken")));
        assertFalse(cats.isFood("minecraft:cobblestone", List.of()));
    }

    @Test
    void storageChestsAdoptTheMajorityCategory() {
        assertEquals("stone_building", cats.adopt(Map.of("minecraft:cobblestone", 192, "minecraft:bread", 3)),
                "3 slots of stone vs 1 slot of food");
        assertNull(cats.adopt(Map.of("minecraft:cobblestone", 64, "minecraft:bread", 3)), "1:1 is no majority");
        assertNull(cats.adopt(Map.of()), "empty chests stay unassigned");
        assertEquals("tools_armor", cats.adopt(Map.of("minecraft:iron_pickaxe", 2, "minecraft:dirt", 1)),
                "unstackable tools count one slot each");
        assertEquals(Map.of("food", Map.of("minecraft:bread", 5), "ores_ingots", Map.of("minecraft:coal", 3)),
                cats.group(Map.of("minecraft:bread", 5, "minecraft:coal", 3)));
    }
}
