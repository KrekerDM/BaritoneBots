package io.github.krekerdm.baritonebots.common.geom;

import io.github.krekerdm.baritonebots.common.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxTest {

    @Test
    void normalisesCornersAndMeasures() {
        Box b = new Box(new Pos(5, 1, 9), new Pos(0, 3, 2));
        assertEquals(new Pos(0, 1, 2), b.a());
        assertEquals(new Pos(5, 3, 9), b.b());
        assertEquals(6, b.width());
        assertEquals(3, b.height());
        assertEquals(8, b.length());
        assertEquals(144, b.volume());
        assertTrue(b.contains(new Pos(5, 3, 9)));
        assertFalse(b.contains(new Pos(6, 3, 9)));
        assertEquals(Box.Axis.Z, b.longerHorizontalAxis());
    }

    @Test
    void jsonShapeAndParsing() {
        Box b = new Box(new Pos(3, 0, 3), new Pos(1, 2, 1));
        assertEquals("{\"a\":{\"x\":1,\"y\":0,\"z\":1},\"b\":{\"x\":3,\"y\":2,\"z\":3}}", Json.toJson(b));
        Box reversed = Json.fromJson("{\"a\":{\"x\":3,\"y\":2,\"z\":3},\"b\":{\"x\":1,\"y\":0,\"z\":1}}", Box.class);
        assertEquals(b, reversed);
        assertEquals(b, Box.fromJson(Json.parse("{\"a\":[3,2,3],\"b\":{\"x\":1,\"y\":0,\"z\":1}}")));
    }

    @Test
    void splitAlongLongerHorizontalAxis() {
        Box b = new Box(new Pos(0, 60, 0), new Pos(9, 70, 3));
        List<Box> three = b.split(3, 1);
        assertEquals(List.of(
                new Box(new Pos(0, 60, 0), new Pos(3, 70, 3)),
                new Box(new Pos(4, 60, 0), new Pos(6, 70, 3)),
                new Box(new Pos(7, 60, 0), new Pos(9, 70, 3))), three);

        // min width 4 → at most 10/4 = 2 slabs
        List<Box> limited = b.split(3, 4);
        assertEquals(2, limited.size());
        assertEquals(5, limited.get(0).width());
        assertEquals(5, limited.get(1).width());

        assertEquals(List.of(b), b.split(5, 20));
        assertEquals(List.of(b), b.split(1, 4));

        Box tall = new Box(new Pos(0, 0, 0), new Pos(3, 0, 11));
        List<Box> z = tall.split(4, 3);
        assertEquals(4, z.size());
        long volume = 0;
        for (int i = 0; i < z.size(); i++) {
            assertEquals(3, z.get(i).length());
            assertEquals(4, z.get(i).width());
            volume += z.get(i).volume();
            for (int j = i + 1; j < z.size(); j++) {
                assertFalse(z.get(i).intersects(z.get(j)));
            }
        }
        assertEquals(tall.volume(), volume);
        assertThrows(IllegalArgumentException.class, () -> b.split(0, 1));
    }

    @Test
    void setOperations() {
        Box a = new Box(new Pos(0, 0, 0), new Pos(4, 4, 4));
        Box b = new Box(new Pos(3, 3, 3), new Pos(8, 8, 8));
        Box far = new Box(new Pos(10, 0, 0), new Pos(12, 1, 1));
        assertTrue(a.intersects(b));
        assertFalse(a.intersects(far));
        assertEquals(Optional.of(new Box(new Pos(3, 3, 3), new Pos(4, 4, 4))), a.intersection(b));
        assertEquals(Optional.empty(), a.intersection(far));
        assertEquals(new Box(new Pos(0, 0, 0), new Pos(8, 8, 8)), a.union(b));
        assertTrue(a.union(b).contains(a));
        assertEquals(new Box(new Pos(-1, -1, -1), new Pos(5, 5, 5)), a.expand(1));
        assertEquals(Box.ofSize(Pos.ZERO, 5, 5, 5), a);
    }

    @Test
    void posHelpers() {
        assertEquals(new Pos(1, -2, 3), Pos.parse("1 -2 3"));
        assertEquals(new Pos(1, -2, 3), Pos.parse("1,-2, 3"));
        assertEquals(new Pos(1, 2, 3), Pos.fromJson(Json.parse("{\"x\":1.7,\"y\":2,\"z\":3}")));
        assertEquals(null, Pos.fromJson(Json.parse("{\"x\":1}")));
        assertEquals(new Pos(-1, 64, 0), new Vec3d(-0.5, 64.9, 0.2).toPos());
        assertEquals(25, new Pos(0, 0, 0).distSq(new Pos(3, 0, 4)));
    }
}
