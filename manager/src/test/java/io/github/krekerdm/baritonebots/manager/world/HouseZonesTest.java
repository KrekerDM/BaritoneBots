package io.github.krekerdm.baritonebots.manager.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HouseZonesTest {
    private static final String OW = "minecraft:overworld";

    private static JsonObject cluster(int x0, int y0, int z0, int x1, int y1, int z1, Map<String, Integer> ids) {
        JsonObject io = new JsonObject();
        ids.forEach(io::addProperty);
        int count = ids.values().stream().mapToInt(Integer::intValue).sum();
        return Json.obj("box", Json.obj("a", Json.obj("x", x0, "y", y0, "z", z0), "b", Json.obj("x", x1, "y", y1, "z", z1)),
                "count", count, "ids", io);
    }

    private static JsonObject scan(JsonObject... clusters) {
        JsonArray a = new JsonArray();
        for (JsonObject c : clusters) {
            a.add(c);
        }
        return Json.obj("clusters", a);
    }

    @Test
    void housesNeedPlayerOnlyBlocks() {
        JsonObject house = cluster(0, 64, 0, 6, 69, 6, Map.of("minecraft:oak_planks", 60, "minecraft:glass", 4,
                "minecraft:oak_door", 2));
        JsonObject mineshaft = cluster(100, 20, 0, 140, 22, 3, Map.of("minecraft:oak_planks", 80, "minecraft:oak_fence", 40));
        JsonObject mesa = cluster(200, 64, 200, 260, 90, 260, Map.of("minecraft:orange_terracotta", 900));
        JsonObject lonelyTable = cluster(30, 64, 30, 30, 64, 30, Map.of("minecraft:crafting_table", 1));
        List<Box> zones = HouseBlocks.houseZones(scan(house, mineshaft, mesa, lonelyTable));
        assertEquals(1, zones.size(), "only the house: no door / glass in a mineshaft or a mesa, a table alone is no house");
        assertEquals(new Box(new Pos(-2, 63, -2), new Pos(8, 71, 8)), zones.getFirst(),
                "2 blocks around the walls, one into the ground, roof + 2");
    }

    @Test
    void touchingHousesBecomeOneZone() {
        JsonObject a = cluster(0, 64, 0, 6, 69, 6, Map.of("minecraft:oak_planks", 50, "minecraft:white_bed", 2));
        JsonObject b = cluster(9, 64, 0, 14, 68, 5, Map.of("minecraft:stone_bricks", 50, "minecraft:wall_torch", 3));
        List<Box> zones = HouseBlocks.houseZones(scan(a, b));
        assertEquals(1, zones.size());
        assertEquals(new Box(new Pos(-2, 63, -2), new Pos(16, 71, 8)), zones.getFirst());
    }

    @Test
    void naturalBlocksStayBreakable() {
        for (String id : List.of("minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:oak_log",
                "minecraft:oak_leaves", "minecraft:coal_ore", "minecraft:deepslate_iron_ore", "minecraft:sand",
                "minecraft:gravel", "minecraft:cobblestone", "minecraft:terracotta", "minecraft:orange_terracotta",
                "minecraft:netherrack", "minecraft:torchflower", "minecraft:smooth_basalt")) {
            assertFalse(Ids.matchesAny(HouseBlocks.NO_BREAK, id), id);
        }
        for (String id : List.of("minecraft:oak_planks", "minecraft:glass", "minecraft:white_stained_glass_pane",
                "minecraft:oak_door", "minecraft:spruce_trapdoor", "minecraft:red_bed", "minecraft:white_wool",
                "minecraft:white_carpet", "minecraft:red_concrete", "minecraft:bricks", "minecraft:stone_bricks",
                "minecraft:polished_andesite", "minecraft:smooth_stone", "minecraft:oak_stairs", "minecraft:stone_slab",
                "minecraft:oak_fence", "minecraft:cobblestone_wall", "minecraft:lantern", "minecraft:wall_torch",
                "minecraft:oak_wall_sign", "minecraft:crafting_table", "minecraft:white_glazed_terracotta")) {
            assertTrue(Ids.matchesAny(HouseBlocks.NO_BREAK, id), id);
            assertTrue(Ids.matchesAny(HouseBlocks.DETECT, id), id);
        }
    }

    @Test
    void mergeKeepsUserDecisions() {
        WorldDoc doc = new WorldDoc();
        Box house = new Box(new Pos(0, 63, 0), new Pos(10, 70, 10));
        assertEquals(1, doc.mergeHouseZones(OW, List.of(house)));
        assertTrue(doc.zones.getFirst().auto());
        assertEquals(0, doc.mergeHouseZones(OW, List.of(house)), "already covered");

        Box grown = new Box(new Pos(0, 63, 0), new Pos(14, 70, 10));
        assertEquals(1, doc.mergeHouseZones(OW, List.of(grown)));
        assertEquals(1, doc.zones.size());
        assertEquals(grown, doc.zones.getFirst().box(), "the house grew: the zone grows with it");

        Box smaller = new Box(new Pos(2, 63, 2), new Pos(6, 66, 6));
        doc.mergeHouseZones(OW, List.of(smaller));
        assertEquals(grown, doc.zones.getFirst().box(), "a zone never shrinks on its own");

        WorldDoc.Zone z = doc.zones.getFirst();
        doc.zones.set(0, new WorldDoc.Zone(z.name(), z.dim(), z.box(), z.source(), true));
        assertEquals(0, doc.mergeHouseZones(OW, List.of(house)), "switched off by the user: not brought back");

        doc.zones.clear();
        doc.zones.add(new WorldDoc.Zone("base", OW, new Box(new Pos(-20, 50, -20), new Pos(20, 90, 20))));
        assertEquals(0, doc.mergeHouseZones(OW, List.of(house)), "inside a manual zone: nothing to add");
        assertEquals(1, doc.mergeHouseZones("minecraft:the_nether", List.of(house)), "other dimension");
    }

    @Test
    void zonesRoundTripWithSourceAndOff() {
        WorldDoc doc = new WorldDoc();
        doc.zones.add(new WorldDoc.Zone("", OW, new Box(new Pos(0, 0, 0), new Pos(1, 1, 1)), WorldDoc.SOURCE_AUTO, true));
        doc.zones.add(new WorldDoc.Zone("shop", OW, new Box(new Pos(5, 0, 0), new Pos(6, 1, 1))));
        WorldDoc back = WorldDoc.fromJson(doc.toJson());
        assertTrue(back.zones.get(0).auto());
        assertTrue(back.zones.get(0).off());
        assertFalse(back.zones.get(1).auto());
        assertTrue(back.zones.get(1).active());
        WorldDoc old = WorldDoc.fromJson(Json.obj("zones", Json.arr(Json.obj("name", "x", "dim", OW,
                "box", Json.obj("a", Json.obj("x", 0, "y", 0, "z", 0), "b", Json.obj("x", 1, "y", 1, "z", 1))))));
        assertFalse(old.zones.getFirst().auto(), "zones saved before sources existed are manual");
    }
}
