package io.github.krekerdm.baritonebots.manager.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deficit resolution (SPEC §5.7 step 4) against the fixture game data. */
class ResolverTest {
    private static final GameData DATA = Fixtures.gameData();
    private static final Resolver.Env ALL = new Resolver.Env(true, true, Set.of(GameData.SMELTING));
    private static final Resolver.Env BARE = new Resolver.Env(true, false, Set.of());

    private static Resolver.Stock stock(Map<String, Integer> supply, Map<String, Integer> storage) {
        return new Resolver.Stock(supply, storage, Map.of(), Map.of());
    }

    private static Resolver.Action action(Resolver.Plan p, String kind, String item) {
        return p.actions().stream().filter(a -> a.kind().equals(kind) && a.item().equals(item)).findFirst().orElse(null);
    }

    private static Resolver.Row row(Resolver.Plan p, String item) {
        return p.rows().stream().filter(r -> r.item().equals(item)).findFirst().orElseThrow();
    }

    @Test
    void deficitSubtractsSupplyBotsAndTransit() {
        Resolver.Stock s = new Resolver.Stock(Map.of("minecraft:cobblestone", 10), Map.of(),
                Map.of("minecraft:cobblestone", 5), Map.of("minecraft:cobblestone", 20));
        Resolver.Plan p = Resolver.resolve(Map.of("minecraft:cobblestone", 100), Map.of("minecraft:cobblestone", 40),
                s, DATA, ALL);
        Resolver.Row r = row(p, "minecraft:cobblestone");
        assertEquals(100, r.needed());
        assertEquals(40, r.remaining());
        assertEquals(5, r.deficit(), "40 - 10 supply - 5 in bots - 20 in transit");
        assertEquals(Resolver.MINE, r.source());
        assertEquals(List.of("minecraft:stone"), action(p, Resolver.MINE, "minecraft:cobblestone").blocks(),
                "natural stone is mined, not cobblestone builds");
    }

    @Test
    void storageFirstThenMining() {
        Resolver.Plan p = Resolver.resolve(Map.of(), Map.of("minecraft:cobblestone", 100),
                stock(Map.of(), Map.of("minecraft:cobblestone", 64)), DATA, ALL);
        Resolver.Action haul = action(p, Resolver.HAUL, "minecraft:cobblestone");
        assertEquals(64, haul.count());
        assertEquals(36, action(p, Resolver.MINE, "minecraft:cobblestone").count());
        assertEquals(Resolver.MINE, row(p, "minecraft:cobblestone").source());

        Resolver.Plan enough = Resolver.resolve(Map.of(), Map.of("minecraft:cobblestone", 30),
                stock(Map.of(), Map.of("minecraft:cobblestone", 64)), DATA, ALL);
        assertEquals(Resolver.STORAGE, row(enough, "minecraft:cobblestone").source());
        assertEquals(1, enough.actions().size());

        Resolver.Plan noSupply = Resolver.resolve(Map.of(), Map.of("minecraft:cobblestone", 30),
                stock(Map.of(), Map.of("minecraft:cobblestone", 64)), DATA, new Resolver.Env(false, true, Set.of()));
        assertTrue(noSupply.actions().isEmpty(), "without supply containers builders take from storage directly");
    }

    @Test
    void craftingRecursesIntoIngredients() {
        Resolver.Plan p = Resolver.resolve(Map.of(), Map.of("minecraft:oak_planks", 10), stock(Map.of(), Map.of()), DATA, ALL);
        Resolver.Action craft = action(p, Resolver.CRAFT, "minecraft:oak_planks");
        assertEquals(3, craft.crafts());
        assertEquals(12, craft.count());
        assertFalse(craft.ready(), "logs have to be mined first");
        assertEquals(Map.of("minecraft:oak_log", 3), craft.inputs());
        assertEquals(3, action(p, Resolver.MINE, "minecraft:oak_log").count());
        assertNull(action(p, Resolver.MINE, "minecraft:oak_planks"), "planks blocks are never mined");

        Resolver.Plan ready = Resolver.resolve(Map.of(), Map.of("minecraft:oak_planks", 10),
                stock(Map.of(), Map.of("minecraft:oak_wood", 5)), DATA, ALL);
        Resolver.Action c2 = action(ready, Resolver.CRAFT, "minecraft:oak_planks");
        assertTrue(c2.ready());
        assertEquals(Map.of("minecraft:oak_wood", 3), c2.inputs(), "the ingredient option in stock is chosen");
    }

    @Test
    void sticksUseSurplusSupplyButNotWhatTheBuildNeeds() {
        // the build still needs 8 planks; supply holds 10 → 2 spare planks feed the sticks
        Map<String, Integer> remaining = Map.of("minecraft:oak_planks", 8, "minecraft:stick", 8);
        Resolver.Plan p = Resolver.resolve(Map.of(), remaining, stock(Map.of("minecraft:oak_planks", 10), Map.of()), DATA, ALL);
        Resolver.Action sticks = action(p, Resolver.CRAFT, "minecraft:stick");
        assertEquals(2, sticks.crafts());
        assertEquals(Map.of("minecraft:oak_planks", 4), sticks.inputs());
        assertFalse(sticks.ready(), "only 2 spare planks, 2 more must be crafted");
        assertTrue(action(p, Resolver.CRAFT, "minecraft:oak_planks") != null);
    }

    @Test
    void tableAndFurnaceRequirements() {
        Resolver.Plan noTable = Resolver.resolve(Map.of(), Map.of("minecraft:furnace", 1), stock(Map.of(), Map.of()), DATA, BARE);
        assertEquals(Map.of("minecraft:furnace", 1), noTable.manual(), "3×3 recipe without a crafting table");
        Resolver.Plan table = Resolver.resolve(Map.of(), Map.of("minecraft:furnace", 1), stock(Map.of(), Map.of()), DATA, ALL);
        Resolver.Action craft = action(table, Resolver.CRAFT, "minecraft:furnace");
        assertEquals(Map.of("minecraft:cobblestone", 8), craft.inputs());
        assertEquals(8, action(table, Resolver.MINE, "minecraft:cobblestone").count());

        Resolver.Plan glassNoFurnace = Resolver.resolve(Map.of(), Map.of("minecraft:glass", 4), stock(Map.of(), Map.of()), DATA, BARE);
        assertEquals(Map.of("minecraft:glass", 4), glassNoFurnace.manual());
    }

    @Test
    void smeltingWithFuel() {
        Resolver.Plan noFuel = Resolver.resolve(Map.of(), Map.of("minecraft:glass", 16),
                stock(Map.of(), Map.of("minecraft:sand", 64)), DATA, ALL);
        Resolver.Action smelt = action(noFuel, Resolver.SMELT, "minecraft:glass");
        assertEquals("minecraft:coal", smelt.fuel());
        assertFalse(smelt.ready());
        assertEquals(Map.of("minecraft:coal", 2), noFuel.manual(), "coal cannot be mined in the fixture world");

        Resolver.Plan fuel = Resolver.resolve(Map.of(), Map.of("minecraft:glass", 16),
                stock(Map.of(), Map.of("minecraft:sand", 64, "minecraft:oak_planks", 20)), DATA, ALL);
        Resolver.Action s2 = action(fuel, Resolver.SMELT, "minecraft:glass");
        assertTrue(s2.ready());
        assertEquals("minecraft:oak_planks", s2.fuel());
        assertEquals(Map.of("minecraft:sand", 16, "minecraft:oak_planks", 11), s2.inputs(), "16 / 1.5 per plank");

        Resolver.Plan stone = Resolver.resolve(Map.of(), Map.of("minecraft:stone_bricks", 4),
                stock(Map.of(), Map.of("minecraft:coal", 64)), DATA, ALL);
        Resolver.Action bricks = action(stone, Resolver.CRAFT, "minecraft:stone_bricks");
        assertEquals(Map.of("minecraft:stone", 4), bricks.inputs());
        assertEquals(Map.of("minecraft:cobblestone", 4, "minecraft:coal", 1), action(stone, Resolver.SMELT, "minecraft:stone").inputs());
        assertEquals(4, action(stone, Resolver.MINE, "minecraft:cobblestone").count(), "stone ← smelt cobblestone ← mine stone");
    }

    @Test
    void withoutGameDataTheItemIsTheBlock() {
        Resolver.Plan p = Resolver.resolve(Map.of(), Map.of("minecraft:glass", 4), stock(Map.of(), Map.of()), null, ALL);
        assertEquals(List.of("minecraft:glass"), action(p, Resolver.MINE, "minecraft:glass").blocks());
        assertEquals(List.of("minecraft:dirt", "minecraft:grass_block"), Resolver.mineBlocks(DATA, "dirt"));
        assertEquals(List.of(), Resolver.mineBlocks(DATA, "minecraft:white_wool"));
    }

    @Test
    void storageIsSharedBetweenItems() {
        // 6 oak logs in storage: planks take 3 (12 planks), the log deficit itself gets the other 3 hauled
        Map<String, Integer> remaining = Map.of("minecraft:oak_planks", 12, "minecraft:oak_log", 5);
        Resolver.Plan p = Resolver.resolve(Map.of(), remaining, stock(Map.of(), Map.of("minecraft:oak_log", 6)), DATA, ALL);
        int hauled = action(p, Resolver.HAUL, "minecraft:oak_log").count();
        Resolver.Action craft = action(p, Resolver.CRAFT, "minecraft:oak_planks");
        assertEquals(5, hauled, "items sorted: oak_log first takes 5 of 6");
        assertEquals(Map.of("minecraft:oak_log", 3), craft.inputs());
        assertFalse(craft.ready(), "only one log left for the planks");
    }
}
