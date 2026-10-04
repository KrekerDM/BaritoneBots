package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class KeepPlannerTest {
    private static final Predicate<String> FOOD = id -> new Categories(
            ManagerConfig.AutopilotCfg.defaults().categories(), null).isFood(id, List.of("minecraft:rotten_flesh"));
    private static final List<String> THROWAWAY = ManagerConfig.AutopilotCfg.defaults().throwaway();

    private static KeepPlanner.Stack s(int slot, String item, int count) {
        return new KeepPlanner.Stack(slot, "minecraft:" + item, count, 0, 0, false);
    }

    private static KeepPlanner.Stack tool(int slot, String item, int damage, int max) {
        return new KeepPlanner.Stack(slot, "minecraft:" + item, 1, damage, max, false);
    }

    private static KeepPlanner.Profile gear() {
        ManagerConfig cfg = ManagerConfig.fromJson(io.github.krekerdm.baritonebots.manager.config.SettingsSchema.defaults());
        return KeepPlanner.Profile.of(cfg.keepProfile(null), THROWAWAY);
    }

    private static Map<Integer, Integer> removed(List<KeepPlanner.Pick> picks) {
        Map<Integer, Integer> m = new java.util.TreeMap<>();
        picks.forEach(p -> m.put(p.slot(), p.count()));
        return m;
    }

    @Test
    void defaultProfileKeepsTheBestOfEachKind() {
        List<KeepPlanner.Stack> inv = List.of(
                tool(0, "iron_pickaxe", 240, 250),      // iron but nearly broken
                tool(1, "iron_pickaxe", 50, 250),       // best pickaxe: iron, most durability
                tool(2, "diamond_axe", 0, 1561),        // best axe
                tool(3, "stone_axe", 0, 131),           // duplicate kind
                tool(4, "wooden_sword", 0, 59),
                tool(5, "stone_sword", 10, 131),        // best sword
                tool(6, "iron_shovel", 0, 250),         // kind not in the profile
                s(7, "bread", 40), s(8, "cooked_beef", 40), // food up to 64, best first
                s(9, "rotten_flesh", 12),               // harmful food is junk
                s(10, "cobblestone", 64), s(11, "cobblestone", 30), s(12, "dirt", 20), // 64 blocks kept
                s(13, "torch", 64),                      // extra
                s(14, "andesite", 64));
        Map<Integer, Integer> junk = removed(KeepPlanner.plan(inv, Arrays.asList(null, null, null, null), gear(), FOOD,
                true));
        assertEquals(Map.of(0, 1, 3, 1, 4, 1, 6, 1, 7, 16, 9, 12, 11, 30, 12, 20, 14, 64), junk);
    }

    @Test
    void armorOnlyWhenItBeatsWhatIsWorn() {
        List<KeepPlanner.Stack> inv = List.of(tool(0, "diamond_chestplate", 0, 528), tool(1, "iron_chestplate", 0, 240),
                tool(2, "leather_helmet", 0, 55), tool(3, "golden_boots", 0, 91), tool(4, "netherite_boots", 0, 481));
        List<String> worn = Arrays.asList("minecraft:iron_helmet", "minecraft:iron_chestplate", null, null);
        Map<Integer, Integer> junk = removed(KeepPlanner.plan(inv, worn, gear(), FOOD, true));
        assertEquals(Map.of(1, 1, 2, 1, 3, 1), junk, "diamond chestplate and netherite boots are upgrades");
    }

    @Test
    void valuablesAreNeverThrownButCanBeStored() {
        List<KeepPlanner.Stack> inv = new ArrayList<>(List.of(s(0, "diamond", 5), s(1, "white_shulker_box", 1),
                s(2, "raw_iron", 30), new KeepPlanner.Stack(3, "minecraft:stick", 1, 0, 0, true)));
        assertEquals(Map.of(2, 30), removed(KeepPlanner.plan(inv, List.of(), gear(), FOOD, true)),
                "drop / trash chest keep valuables and named items");
        assertEquals(Map.of(0, 5, 1, 1, 2, 30, 3, 1), removed(KeepPlanner.plan(inv, List.of(), gear(), FOOD, false)),
                "storing hands everything over");
    }

    @Test
    void customProfileWithShearsAndNoWeapon() {
        KeepPlanner.Profile p = KeepPlanner.Profile.of(Json.obj("armor", "none", "weapon", "none",
                "tools", Json.arr("shears", "hoe"), "food", Json.obj("max", 0),
                "blocks", Json.obj("globs", Json.arr("oak_planks"), "count", 10)), THROWAWAY);
        List<KeepPlanner.Stack> inv = List.of(tool(0, "shears", 200, 238), tool(1, "shears", 0, 238),
                tool(2, "iron_sword", 0, 250), tool(3, "golden_hoe", 10, 32), tool(4, "wooden_hoe", 0, 59),
                s(5, "oak_planks", 64), s(6, "bread", 3), tool(7, "iron_helmet", 0, 165));
        Map<Integer, Integer> junk = removed(KeepPlanner.plan(inv, List.of(), p, FOOD, true));
        // shears: the fresher pair; hoes: golden and wooden have the same tier → durability decides (wooden is unused)
        assertEquals(Map.of(0, 1, 2, 1, 3, 1, 5, 54, 6, 3, 7, 1), junk);
    }

    @Test
    void autoTrashJunkAndEstimate() {
        ManagerConfig.AutoTrash at = ManagerConfig.AutopilotCfg.defaults().autoTrash();
        assertTrue(at.enabled());
        List<KeepPlanner.Stack> inv = List.of(s(0, "cobbled_deepslate", 64), s(1, "cobbled_deepslate", 40),
                s(2, "gravel", 64), s(3, "diamond", 2), s(4, "wheat_seeds", 70), s(5, "granite", 12));
        Map<Integer, Integer> junk = removed(KeepPlanner.junk(inv, at.junk(), at.keepCounts()));
        assertEquals(Map.of(1, 40, 2, 64, 4, 6, 5, 12), junk, "64 cobbled deepslate and 64 seeds stay");
        assertEquals(3, KeepPlanner.freedSlots(inv, KeepPlanner.junk(inv, at.junk(), at.keepCounts())),
                "whole stacks only: deepslate 40, gravel, granite");
        assertEquals(3, KeepPlanner.junkStacks(Map.of("minecraft:gravel", 128, "minecraft:cobbled_deepslate", 100,
                "minecraft:diamond", 9), at.junk(), at.keepCounts()), "2 gravel stacks + 36 surplus deepslate");
        assertEquals(0, KeepPlanner.junkStacks(Map.of("minecraft:sand", 64), at.junk(), at.keepCounts()));
    }

    @Test
    void parsesTheInventoryQuery() {
        var data = Json.obj("slots", Json.arr(
                Json.obj("slot", 0, "item", "minecraft:iron_pickaxe", "count", 1, "damage", 25, "maxDamage", 250),
                Json.obj("slot", 40, "item", "minecraft:shield", "count", 1),
                Json.obj("slot", 5, "item", "minecraft:stick", "count", 2, "name", "Wand")),
                "armor", Json.arr("minecraft:iron_helmet", null, null, null));
        List<KeepPlanner.Stack> st = KeepPlanner.stacks(data);
        assertEquals(2, st.size(), "only main inventory slots");
        assertEquals(0.9, st.getFirst().durability(), 1e-9);
        assertTrue(st.get(1).named());
        assertEquals(Arrays.asList("minecraft:iron_helmet", null, null, null), KeepPlanner.worn(data));
        assertTrue(SettingsSchema.DEFAULT_KEEP_PROFILE.length() > 0);
    }
}
