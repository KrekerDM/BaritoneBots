package io.github.krekerdm.baritonebots.manager.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KitPlannerTest {
    private static final Pos BOT = new Pos(0, 64, 0);

    private static KitPlanner.Slot slot(String name, int count, String... any) {
        return new KitPlanner.Slot(name, List.of(any), count);
    }

    @Test
    void satisfiedSlotsProduceNoTakes() {
        List<KitPlanner.Slot> slots = List.of(slot("pick", 1, "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"),
                slot("head", 1, "minecraft:iron_helmet"));
        Map<String, Integer> have = Map.of("minecraft:iron_pickaxe", 1, "minecraft:iron_helmet", 1);
        KitPlanner.Plan p = KitPlanner.plan(slots, have, List.of(), BOT, false);
        assertTrue(p.takes().isEmpty());
        assertTrue(p.missing().isEmpty());
        assertFalse(p.needsInspect());
    }

    @Test
    void sameItemIsNotCountedForTwoSlots() {
        List<KitPlanner.Slot> slots = List.of(slot("food1", 16, "minecraft:bread"), slot("food2", 16, "minecraft:bread"));
        Map<String, Integer> have = Map.of("minecraft:bread", 20);
        KitPlanner.Source chest = new KitPlanner.Source(new Pos(5, 64, 0), "kit", Map.of("minecraft:bread", 64));
        KitPlanner.Plan p = KitPlanner.plan(slots, have, List.of(chest), BOT, false);
        assertEquals(1, p.takes().size());
        assertEquals(Map.of("minecraft:bread", 12), p.takes().getFirst().items());
    }

    @Test
    void unknownContainersAreInspectedFirst() {
        List<KitPlanner.Slot> slots = List.of(slot("pick", 1, "minecraft:iron_pickaxe"));
        KitPlanner.Source known = new KitPlanner.Source(new Pos(3, 64, 0), "kit", Map.of("minecraft:iron_pickaxe", 1));
        KitPlanner.Source unknown = new KitPlanner.Source(new Pos(9, 64, 0), "storage", null);
        KitPlanner.Plan p = KitPlanner.plan(slots, Map.of(), List.of(known, unknown), BOT, false);
        assertTrue(p.needsInspect());
        assertEquals(List.of(new Pos(9, 64, 0)), p.inspect());
        assertTrue(p.takes().isEmpty());
        // second round: unknown containers are skipped
        KitPlanner.Plan p2 = KitPlanner.plan(slots, Map.of(), List.of(known, unknown), BOT, true);
        assertFalse(p2.needsInspect());
        assertEquals(new Pos(3, 64, 0), p2.takes().getFirst().container());
    }

    @Test
    void kitContainersBeatStorageAndRankOrderIsRespected() {
        List<KitPlanner.Slot> slots = List.of(slot("chest", 1, "minecraft:diamond_chestplate", "minecraft:iron_chestplate"));
        KitPlanner.Source storage = new KitPlanner.Source(new Pos(1, 64, 0), "storage",
                Map.of("minecraft:diamond_chestplate", 1));
        KitPlanner.Source kit = new KitPlanner.Source(new Pos(50, 64, 0), "kit",
                Map.of("minecraft:iron_chestplate", 1, "minecraft:diamond_chestplate", 1));
        KitPlanner.Plan p = KitPlanner.plan(slots, Map.of(), List.of(storage, kit), BOT, false);
        assertEquals(1, p.takes().size());
        assertEquals(new Pos(50, 64, 0), p.takes().getFirst().container());
        assertEquals(Map.of("minecraft:diamond_chestplate", 1), p.takes().getFirst().items());
    }

    @Test
    void takesAreGroupedPerContainerInNearestNeighbourOrder() {
        List<KitPlanner.Slot> slots = List.of(slot("a", 1, "minecraft:iron_sword"), slot("b", 1, "minecraft:shield"),
                slot("c", 8, "minecraft:torch"));
        KitPlanner.Source far = new KitPlanner.Source(new Pos(100, 64, 0), "kit", Map.of("minecraft:iron_sword", 1));
        KitPlanner.Source near = new KitPlanner.Source(new Pos(10, 64, 0), "kit",
                Map.of("minecraft:shield", 1, "minecraft:torch", 64));
        KitPlanner.Plan p = KitPlanner.plan(slots, Map.of(), List.of(far, near), BOT, false);
        assertEquals(2, p.takes().size());
        assertEquals(new Pos(10, 64, 0), p.takes().get(0).container());
        assertEquals(Map.of("minecraft:shield", 1, "minecraft:torch", 8), p.takes().get(0).items());
        assertEquals(new Pos(100, 64, 0), p.takes().get(1).container());
    }

    @Test
    void globsAndMissingCounts() {
        List<KitPlanner.Slot> slots = List.of(slot("pick", 1, "minecraft:*_pickaxe"), slot("food", 32, "minecraft:cooked_*"));
        KitPlanner.Source s = new KitPlanner.Source(new Pos(2, 64, 2), "storage",
                Map.of("minecraft:stone_pickaxe", 2, "minecraft:cooked_beef", 10));
        KitPlanner.Plan p = KitPlanner.plan(slots, Map.of(), List.of(s), BOT, false);
        assertEquals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:cooked_beef", 10), p.takes().getFirst().items());
        assertEquals(Map.of("food", 22), p.missing());
    }

    @Test
    void offhandSlotAndSlotParsing() {
        JsonObject kit = Json.obj("slots", Json.obj(
                "offhand", Json.obj("any", Json.arr("shield"), "count", 1),
                "broken", Json.obj("any", Json.arr(), "count", 1)));
        List<KitPlanner.Slot> slots = KitPlanner.slotsOf(kit);
        assertEquals(1, slots.size());
        assertEquals("minecraft:shield", slots.getFirst().any().getFirst());
        KitPlanner.Plan p = KitPlanner.plan(slots, Map.of("minecraft:shield", 1), List.of(), BOT, false);
        assertEquals("minecraft:shield", p.offhand());
        assertNull(KitPlanner.plan(List.of(), Map.of(), List.of(), BOT, false).offhand());
    }

    @Test
    void takeArgsShape() {
        JsonObject a = KitPlanner.takeArgs(new KitPlanner.Take(new Pos(1, 2, 3), Map.of("minecraft:torch", 5)));
        assertEquals(1, a.getAsJsonObject("container").get("x").getAsInt());
        assertEquals("minecraft:torch", a.getAsJsonArray("items").get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(5, a.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }
}
