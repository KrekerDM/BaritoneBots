package io.github.krekerdm.baritonebots.manager.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cluster answers of {@code scan_blocks} → boxes, and the nearest one. */
class AutoDetectTest {
    private static com.google.gson.JsonObject cluster(int ax, int az, int bx, int bz, int count) {
        return Json.obj("box", Json.obj("a", Json.obj("x", ax, "y", 63, "z", az), "b", Json.obj("x", bx, "y", 64, "z", bz)),
                "count", count, "ids", Json.obj("minecraft:oak_fence", count));
    }

    @Test
    void smallClustersAreIgnoredAndTheNearestWins() {
        var data = Json.obj("clusters", Json.arr(cluster(100, 100, 110, 110, 40), cluster(0, 0, 1, 0, 2),
                cluster(20, -5, 30, 5, 30)));
        List<Box> boxes = AutoDetect.clusters(data, 8);
        assertEquals(2, boxes.size(), "a 2-block cluster is not a pen");
        assertEquals(new Box(new Pos(20, 63, -5), new Pos(30, 64, 5)), AutoDetect.nearest(boxes, new Pos(0, 64, 0)));
        assertEquals(new Box(new Pos(100, 63, 100), new Pos(110, 64, 110)),
                AutoDetect.nearest(boxes, new Pos(90, 64, 90)));
        assertNull(AutoDetect.nearest(List.of(), new Pos(0, 0, 0)));
        assertEquals(List.of(), AutoDetect.clusters(Json.obj(), 1));
    }
}
