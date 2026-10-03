package io.github.krekerdm.baritonebots.mod.task.plan;

import com.google.gson.JsonPrimitive;
import io.github.krekerdm.baritonebots.common.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftGridTest {
    private static final String PLANKS = "[\"oak_planks\",\"spruce_planks\"]";

    @Test
    void sticksFitTheInventoryGridAndShiftToTheTopLeft() {
        CraftGrid g = CraftGrid.parse(Json.parse("[[null,null,null],[null," + PLANKS + ",null],[null," + PLANKS
                + ",null]]"));
        assertEquals(1, g.width());
        assertEquals(2, g.height());
        assertTrue(g.fits(2, 2));
        List<CraftGrid.Cell> two = g.layout(2);
        assertEquals(List.of(0, 2), two.stream().map(CraftGrid.Cell::gridIndex).toList());
        assertEquals(List.of("minecraft:oak_planks", "minecraft:spruce_planks"), two.getFirst().accepts());
        assertEquals(List.of(0, 3), g.layout(3).stream().map(CraftGrid.Cell::gridIndex).toList());
    }

    @Test
    void chestNeedsTheTable() {
        String p = "\"oak_planks\"";
        CraftGrid g = CraftGrid.parse(Json.parse("[[" + p + "," + p + "," + p + "],[" + p + ",null," + p + "],[" + p
                + "," + p + "," + p + "]]"));
        assertFalse(g.fits(2, 2));
        assertTrue(g.fits(3, 3));
        assertEquals(8, g.layout(3).size());
    }

    @Test
    void parseAcceptsStringsAndRejectsBadShapes() {
        CraftGrid g = CraftGrid.parse(new JsonPrimitive("[[\"stick\"]]"));
        assertEquals(1, g.layout(2).size());
        assertNull(CraftGrid.parse(null));
        assertNull(CraftGrid.parse(new JsonPrimitive(" ")));
        assertTrue(CraftGrid.parse(Json.parse("[[null,[]],[]]")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CraftGrid.parse(Json.parse("[[1,2,3,4]]")));
        assertThrows(IllegalArgumentException.class, () -> CraftGrid.parse(Json.parse("[[],[],[],[]]")));
        assertThrows(IllegalArgumentException.class, () -> CraftGrid.parse(Json.parse("{\"a\":1}")));
        assertThrows(IllegalArgumentException.class, () -> CraftGrid.parse(Json.parse("[[{\"x\":1}]]")));
        assertThrows(IllegalArgumentException.class, () -> CraftGrid.parse(new JsonPrimitive("[[")));
    }

    @Test
    void planRoundBalancesSetsAgainstStock() {
        CraftGrid g = CraftGrid.parse(Json.parse("[[" + PLANKS + "],[" + PLANKS + "]]"));
        List<CraftGrid.Cell> cells = g.layout(2);
        // 9 oak planks for two cells: at most 4 sets of oak
        CraftGrid.Round r = CraftGrid.planRound(cells, Map.of("minecraft:oak_planks", 9), id -> 64, 10);
        assertEquals(4, r.sets());
        assertEquals("minecraft:oak_planks", r.ids().get(0));
        // capped by what is wanted
        assertEquals(2, CraftGrid.planRound(cells, Map.of("minecraft:oak_planks", 64), id -> 64, 2).sets());
        // capped by stack size
        assertEquals(16, CraftGrid.planRound(cells, Map.of("minecraft:oak_planks", 64), id -> 16, 64).sets());
        // two kinds: one per cell gives more sets than sharing one kind
        CraftGrid.Round mixed = CraftGrid.planRound(cells, Map.of("minecraft:oak_planks", 5,
                "minecraft:spruce_planks", 5), id -> 64, 64);
        assertEquals(5, mixed.sets());
        // a cell without any accepted item → nothing
        assertEquals(0, CraftGrid.planRound(cells, Map.of("minecraft:stone", 64), id -> 64, 64).sets());
        assertEquals(0, CraftGrid.planRound(cells, Map.of("minecraft:oak_planks", 1), id -> 64, 64).sets());
    }

    @Test
    void globsAreAccepted() {
        CraftGrid g = CraftGrid.parse(Json.parse("[[\"*_log\"]]"));
        CraftGrid.Round r = CraftGrid.planRound(g.layout(2), Map.of("minecraft:birch_log", 3), id -> 64, 64);
        assertEquals(3, r.sets());
        assertEquals("minecraft:birch_log", r.ids().get(0));
    }
}
