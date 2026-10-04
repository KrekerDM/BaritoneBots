package io.github.krekerdm.baritonebots.manager.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlatSiteTest {
    /**
     * A 21×21 grid around (0, 0): hilly (height = 60 + (x+z) mod 4) except a flat 6×5 meadow at x 4..9, z -8..-4
     * (heights 64/65), a lake at x -8..-4, z 2..6 (flat but water) and an unloaded corner.
     */
    private static FlatSite.Grid grid() {
        int size = 21;
        int x0 = -10;
        int z0 = -10;
        JsonArray heights = new JsonArray();
        StringBuilder surface = new StringBuilder();
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int x = x0 + dx;
                int z = z0 + dz;
                if (x >= 8 && z >= 8) {
                    heights.add(JsonNull.INSTANCE);
                    surface.append('?');
                } else if (x >= 4 && x <= 9 && z >= -8 && z <= -4) {
                    heights.add(64 + ((x + z) & 1));
                    surface.append('.');
                } else if (x >= -8 && x <= -4 && z >= 2 && z <= 6) {
                    heights.add(62);
                    surface.append('w');
                } else {
                    heights.add(60 + Math.floorMod(x + z, 4) * 2);
                    surface.append('.');
                }
            }
        }
        return FlatSite.parse(Json.obj("x0", x0, "z0", z0, "size", size, "heights", heights,
                "surface", surface.toString()));
    }

    @Test
    void findsTheFlatMeadowNotTheLake() {
        FlatSite.Site s = FlatSite.find(grid(), 5, 4, 0, 0, 30, List.of(), 1);
        assertEquals(new FlatSite.Site(4, -7, 66), s,
                "nearest 5×4 inside the meadow (the closer lake is water); floor = highest + 1");
        assertNull(FlatSite.find(grid(), 7, 4, 0, 0, 30, List.of(), 1), "the meadow is only 6 wide");
        assertNull(FlatSite.find(grid(), 5, 4, 0, 0, 30, List.of(), 0), "variance 0 needs one height");
    }

    @Test
    void exclusionsRadiusAndTheGridEdge() {
        // a project on the western part of the meadow (with the 1-block margin) leaves no 5×4 spot
        assertNull(FlatSite.find(grid(), 5, 4, 0, 0, 30, List.of(new Box(new Pos(5, 60, -6), new Pos(5, 70, -6))), 1));
        // a 2×2 then still fits east of it (x 7..8 is 2 blocks from x 5)
        FlatSite.Site small = FlatSite.find(grid(), 2, 2, 0, 0, 30,
                List.of(new Box(new Pos(5, 60, -6), new Pos(5, 70, -6))), 1);
        assertEquals(7, small.x());
        assertNull(FlatSite.find(grid(), 5, 4, 0, 0, 5, List.of(), 1), "too far from the centre");
        assertNull(FlatSite.find(grid(), 30, 30, 0, 0, 100, List.of(), 1), "bigger than the scanned grid");
    }
}
