package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Auto-supply needs per task type (SPEC §5.7a) against the fixture game data, and what of them a bot lacks. */
class TaskNeedsTest {
    private static final GameData DATA = Fixtures.gameData();
    private static final ManagerConfig.AutopilotCfg CFG = ManagerConfig.AutopilotCfg.defaults();
    private static final TaskNeeds.Context CTX = new TaskNeeds.Context(DATA, CFG, new Categories(CFG.categories(), DATA),
            List.of("minecraft:chicken"));

    private static TaskNeeds.Need find(List<TaskNeeds.Need> needs, TaskNeeds.Kind kind, String toolKind) {
        return needs.stream().filter(n -> n.kind() == kind && (toolKind == null || toolKind.equals(n.toolKind())))
                .findFirst().orElse(null);
    }

    private static Inventory inv(Map<String, Integer> items, Map<String, Double> durability) {
        return new Inventory(items, durability, 20, true);
    }

    @Test
    void mineNeedsTheRequiredToolTierFoodAndBlocks() {
        List<TaskNeeds.Need> stone = TaskNeeds.of("mine", Json.obj("blocks", Json.arr("minecraft:stone")), CTX, true);
        TaskNeeds.Need pick = find(stone, TaskNeeds.Kind.TOOL, "pickaxe");
        assertEquals("wood", pick.minTier());
        assertFalse(pick.optional(), "stone drops nothing without a pickaxe");
        assertEquals(CFG.blocksMin(), find(stone, TaskNeeds.Kind.BLOCKS, null).min());
        assertEquals(CFG.foodMin() * 2, find(stone, TaskNeeds.Kind.FOOD, null).count());

        List<TaskNeeds.Need> iron = TaskNeeds.of("mine", Json.obj("blocks", Json.arr("stone", "iron_ore")), CTX, true);
        assertEquals("stone", find(iron, TaskNeeds.Kind.TOOL, "pickaxe").minTier(), "the highest tier any block needs");

        TaskNeeds.Need axe = find(TaskNeeds.of("mine", Json.obj("blocks", Json.arr("oak_log")), CTX, true),
                TaskNeeds.Kind.TOOL, "axe");
        assertTrue(axe.optional(), "logs drop without an axe: fetch one only if there is one");
    }

    @Test
    void selectionCountsMaterialsByShape() {
        JsonObject box = Json.obj("a", Json.obj("x", 0, "y", 64, "z", 0), "b", Json.obj("x", 4, "y", 66, "z", 4));
        int fill = find(TaskNeeds.of("selection", Json.obj("op", "fill", "box", box, "block", "stone"), CTX, true),
                TaskNeeds.Kind.ITEM, null).count();
        int walls = find(TaskNeeds.of("selection", Json.obj("op", "walls", "box", box, "block", "stone"), CTX, true),
                TaskNeeds.Kind.ITEM, null).count();
        int shell = find(TaskNeeds.of("selection", Json.obj("op", "shell", "box", box, "block", "stone"), CTX, true),
                TaskNeeds.Kind.ITEM, null).count();
        assertEquals(75, fill);
        assertEquals(75 - 3 * 3 * 3, walls);
        assertEquals(75 - 3 * 1 * 3, shell);
        assertNull(find(TaskNeeds.of("selection", Json.obj("op", "fill", "box", box, "block", "stone"), CTX, false),
                TaskNeeds.Kind.ITEM, null), "project work restocks itself: no materials");
        List<TaskNeeds.Need> clear = TaskNeeds.of("selection", Json.obj("op", "clear", "box", box), CTX, true);
        assertTrue(find(clear, TaskNeeds.Kind.TOOL, "shovel").optional());
        assertTrue(find(clear, TaskNeeds.Kind.TOOL, "pickaxe").optional());
    }

    @Test
    void smeltCraftShearAndFarm() {
        List<TaskNeeds.Need> smelt = TaskNeeds.of("smelt_load", Json.obj("furnace", Json.obj("x", 0, "y", 0, "z", 0),
                "input", "raw_iron", "count", 16, "fuel", "coal", "fuelCount", 2), CTX, true);
        assertEquals(List.of("minecraft:raw_iron"), smelt.get(0).globs());
        assertEquals(16, smelt.get(0).count());
        assertEquals(List.of("minecraft:coal"), smelt.get(1).globs());
        assertEquals(2, smelt.get(1).count());

        List<TaskNeeds.Need> torch = TaskNeeds.of("craft", Json.obj("item", "torch", "count", 8), CTX, true);
        assertEquals(2, torch.size(), "two crafts of 4: 2 coal-or-charcoal + 2 sticks");
        assertEquals(List.of("minecraft:coal", "minecraft:charcoal"), torch.get(0).globs());
        assertEquals(2, torch.get(0).count());
        assertEquals(2, torch.get(1).count());

        assertEquals(List.of("minecraft:shears"), TaskNeeds.of("shear", new JsonObject(), CTX, false).getFirst().globs());
        assertTrue(find(TaskNeeds.of("farm", new JsonObject(), CTX, true), TaskNeeds.Kind.TOOL, "hoe").optional());
        assertTrue(TaskNeeds.of("goto", Json.obj("x", 1, "z", 1), CTX, true).isEmpty());
    }

    @Test
    void missingComparesWithInventoryAndDurability() {
        List<TaskNeeds.Need> needs = TaskNeeds.of("mine", Json.obj("blocks", Json.arr("iron_ore")), CTX, true);
        Inventory worn = inv(Map.of("minecraft:stone_pickaxe", 1, "minecraft:bread", 3, "minecraft:cobblestone", 40),
                Map.of("minecraft:stone_pickaxe", 0.05));
        List<TaskNeeds.Need> m1 = TaskNeeds.missing(needs, worn, CTX);
        assertEquals("stone", find(m1, TaskNeeds.Kind.TOOL, "pickaxe").minTier(), "a worn pickaxe does not count");
        assertEquals(CFG.foodMin() * 2 - 3, find(m1, TaskNeeds.Kind.FOOD, null).count(), "refill to twice the minimum");
        assertNull(find(m1, TaskNeeds.Kind.BLOCKS, null), "40 cobblestone ≥ 32");

        Inventory fresh = inv(Map.of("minecraft:iron_pickaxe", 1, "minecraft:cooked_beef", 20, "minecraft:dirt", 64),
                Map.of("minecraft:iron_pickaxe", 0.9));
        assertTrue(TaskNeeds.missing(needs, fresh, CTX).isEmpty(), "a better tier is fine");

        Inventory avoided = inv(Map.of("minecraft:chicken", 30, "minecraft:rotten_flesh", 30), Map.of());
        assertEquals(CFG.foodMin() * 2, find(TaskNeeds.missing(needs, avoided, CTX), TaskNeeds.Kind.FOOD, null).count(),
                "avoided and harmful food does not count");
    }

    @Test
    void buildMaterialsComeFromAProgressAnswer() {
        List<TaskNeeds.Need> n = TaskNeeds.fromBom(Json.obj("remaining", Json.obj("minecraft:stone", 40, "minecraft:glass", 0)));
        assertEquals(1, n.size());
        assertEquals(40, n.getFirst().count());
        assertEquals(List.of("minecraft:stone"), n.getFirst().globs());
        Inventory have = inv(Map.of("minecraft:stone", 50), Map.of());
        assertTrue(TaskNeeds.missing(n, have, CTX).isEmpty());
    }
}
