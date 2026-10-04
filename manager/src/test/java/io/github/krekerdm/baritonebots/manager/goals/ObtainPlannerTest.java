package io.github.krekerdm.baritonebots.manager.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Goal planning (SPEC §5.7b2) on the fixture game data, executed against a tiny simulated world. */
class ObtainPlannerTest {
    private static final GameData DATA = Fixtures.gameData();

    /** Inventory, storage, placed table / furnace; applies steps the way the bot tasks would. */
    private static final class World {
        final Map<String, Integer> inv = new HashMap<>();
        final Map<WorldDoc.Container, Map<String, Integer>> storage = new LinkedHashMap<>();
        Pos table;
        final Map<String, Pos> furnaces = new HashMap<>();
        final List<String> log = new ArrayList<>();

        ObtainPlanner.World view() {
            List<ObtainPlanner.Source> s = new ArrayList<>();
            storage.forEach((c, items) -> s.add(new ObtainPlanner.Source(c, Map.copyOf(items))));
            return new ObtainPlanner.World(inv, s, table, furnaces, DATA);
        }

        void take(String id, int n) {
            int have = inv.getOrDefault(id, 0);
            assertTrue(have >= n, "inventory has " + n + " × " + id + " (has " + have + ") in " + inv);
            inv.merge(id, -n, Integer::sum);
        }

        void apply(ObtainPlanner.Step step) {
            for (ObtainPlanner.Action a : step.actions()) {
                assertTrue(a.ready(), "only ready actions run: " + a);
                switch (a.kind()) {
                    case ObtainPlanner.TAKE -> a.take().forEach((id, n) -> {
                        storage.get(a.container()).merge(id, -n, Integer::sum);
                        inv.merge(id, n, Integer::sum);
                        log.add("take:" + Ids.path(id));
                    });
                    case ObtainPlanner.CRAFT, ObtainPlanner.SMELT -> {
                        a.inputs().forEach(this::take);
                        inv.merge(a.item(), a.count(), Integer::sum);
                        log.add(a.kind() + ":" + Ids.path(a.item()));
                    }
                    case ObtainPlanner.MINE -> {
                        inv.merge(a.item(), a.count(), Integer::sum);
                        log.add("mine:" + Ids.path(a.item()) + "×" + a.count());
                    }
                    case ObtainPlanner.PLACE -> {
                        take(a.block(), 1);
                        if (ObtainPlanner.CRAFTING_TABLE.equals(a.block())) {
                            table = new Pos(2, 64, 0);
                        } else {
                            furnaces.put(GameData.SMELTING, new Pos(-2, 64, 0));
                        }
                        log.add("place:" + Ids.path(a.block()));
                    }
                    default -> throw new AssertionError(a.kind());
                }
            }
        }

        /** Runs the goal to the end; returns the number of planning steps. */
        int run(String item, int count, int maxSteps) {
            for (int i = 1; i <= maxSteps; i++) {
                ObtainPlanner.Step s = ObtainPlanner.next(item, count, view());
                if (s.done()) {
                    return i;
                }
                assertFalse(s.failed(), "step " + i + " failed: " + s.missing() + " log " + log);
                apply(s);
            }
            throw new AssertionError("no result after " + maxSteps + " steps: " + log);
        }

        int index(String entry) {
            for (int i = 0; i < log.size(); i++) {
                if (log.get(i).startsWith(entry)) {
                    return i;
                }
            }
            throw new AssertionError(entry + " not in " + log);
        }
    }

    @Test
    void woodenStoneIronChainFromNothing() {
        World w = new World();
        int steps = w.run("minecraft:iron_pickaxe", 1, 60);
        assertEquals(1, w.inv.getOrDefault("minecraft:iron_pickaxe", 0));
        assertTrue(w.log.getFirst().startsWith("mine:oak_log"), "first the logs: " + w.log);
        assertTrue(w.index("craft:wooden_pickaxe") < w.index("mine:cobblestone"), "stone needs a wooden pickaxe");
        assertTrue(w.index("mine:cobblestone") < w.index("craft:stone_pickaxe"));
        assertTrue(w.index("craft:stone_pickaxe") < w.index("mine:raw_iron"), "iron ore needs a stone pickaxe");
        assertTrue(w.index("craft:furnace") < w.index("place:furnace"));
        assertTrue(w.index("place:furnace") < w.index("smelt:iron_ingot"));
        assertTrue(w.index("smelt:iron_ingot") < w.index("craft:iron_pickaxe"));
        assertEquals(1, w.log.stream().filter(e -> e.equals("place:crafting_table")).count(), "one table, placed once");
        assertTrue(steps < 30, "re-planned " + steps + " times: " + w.log);
    }

    @Test
    void logsForTheWholeTreeAreMinedInOneGo() {
        ObtainPlanner.Step first = ObtainPlanner.next("minecraft:wooden_pickaxe", 1, new World().view());
        ObtainPlanner.Action mine = first.actions().getFirst();
        assertEquals(ObtainPlanner.MINE, mine.kind());
        assertEquals("minecraft:oak_log", mine.item());
        assertEquals(3, mine.count(), "4 planks for the table + 3 for the head + 2 for the sticks = 9 → 3 logs");
        assertTrue(mine.blocks().contains("minecraft:spruce_log"), "any tree will do");
    }

    @Test
    void storageFirstThenCraft() {
        World w = new World();
        w.table = new Pos(1, 64, 1);
        WorldDoc.Container chest = new WorldDoc.Container("c1", "minecraft:overworld", new Pos(5, 64, 0),
                "minecraft:chest", List.of("storage"), "", null, 0);
        w.storage.put(chest, new HashMap<>(Map.of("minecraft:iron_ingot", 5, "minecraft:stick", 8)));
        ObtainPlanner.Step s = ObtainPlanner.next("minecraft:iron_pickaxe", 1, w.view());
        assertEquals(ObtainPlanner.TAKE, s.actions().getFirst().kind());
        assertEquals(Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), s.actions().getFirst().take());
        assertEquals(2, w.run("minecraft:iron_pickaxe", 1, 5) - 1, "take, craft, done");
        assertEquals(List.of("take:iron_ingot", "take:stick", "craft:iron_pickaxe"), w.log);
    }

    @Test
    void toolTierDecidesWhatCanBeMined() {
        World w = new World();
        w.inv.put("minecraft:stone_pickaxe", 1);
        ObtainPlanner.Action a = ObtainPlanner.next("minecraft:raw_iron", 3, w.view()).actions().getFirst();
        assertEquals(ObtainPlanner.MINE, a.kind());
        assertTrue(a.blocks().contains("minecraft:iron_ore") && a.blocks().contains("minecraft:deepslate_iron_ore"));

        World wood = new World();
        wood.inv.put("minecraft:wooden_pickaxe", 1);
        wood.table = new Pos(0, 64, 0);
        wood.inv.put("minecraft:stick", 2);
        ObtainPlanner.Action b = ObtainPlanner.next("minecraft:raw_iron", 3, wood.view()).actions().getFirst();
        assertEquals("mine:minecraft:cobblestone", b.kind() + ":" + b.item(), "a stone pickaxe first, so cobblestone first");

        World stored = new World();
        WorldDoc.Container chest = new WorldDoc.Container("c1", "minecraft:overworld", new Pos(5, 64, 0),
                "minecraft:chest", List.of("storage"), "", null, 0);
        stored.storage.put(chest, new HashMap<>(Map.of("minecraft:iron_pickaxe", 1)));
        ObtainPlanner.Step s = ObtainPlanner.next("minecraft:raw_iron", 3, stored.view());
        assertEquals(Map.of("minecraft:iron_pickaxe", 1), s.actions().getFirst().take(),
                "a better pickaxe in storage is taken instead of crafting the tool chain");
        assertEquals(3, stored.run("minecraft:raw_iron", 3, 5), "take, mine, done");
    }

    @Test
    void doneAndUnobtainable() {
        World w = new World();
        w.inv.put("minecraft:torch", 40);
        assertTrue(ObtainPlanner.next("minecraft:torch", 32, w.view()).done());
        ObtainPlanner.Step diamond = ObtainPlanner.next("minecraft:diamond", 1, w.view());
        assertTrue(diamond.failed());
        assertEquals(Map.of("minecraft:diamond", 1), diamond.missing());
        ObtainPlanner.Step torch = ObtainPlanner.next("minecraft:torch", 64, new World().view());
        assertTrue(torch.failed(), "coal cannot be had in the fixture world");
    }

    @Test
    void progressPresets() {
        List<String> iron = GoalRunner.preset("iron").stream().map(o -> o.get("item").getAsString()).toList();
        assertTrue(iron.containsAll(List.of("minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel",
                "minecraft:iron_sword", "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings",
                "minecraft:iron_boots", "minecraft:torch", GoalRunner.FOOD)));
        assertTrue(GoalRunner.preset("wood").stream().filter(o -> o.get("item").getAsString().startsWith("minecraft:leather"))
                .allMatch(o -> o.has("soft")), "no wooden armor: leather is optional");
        assertTrue(GoalRunner.preset("diamond").stream().filter(o -> o.get("item").getAsString().equals("minecraft:diamond_pickaxe"))
                .noneMatch(o -> o.has("soft")), "tools are required");
    }

    @Test
    void placementSpotChecks() {
        assertTrue(GoalRunner.replaceable("minecraft:air"));
        assertTrue(GoalRunner.replaceable("minecraft:short_grass"));
        assertFalse(GoalRunner.replaceable("minecraft:stone"));
        assertTrue(GoalRunner.solid("minecraft:grass_block"));
        assertFalse(GoalRunner.solid("minecraft:water"));
        assertFalse(GoalRunner.solid(null));
    }
}
