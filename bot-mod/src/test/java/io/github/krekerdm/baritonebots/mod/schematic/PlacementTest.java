package io.github.krekerdm.baritonebots.mod.schematic;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform.Mirror;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementTest {
    private static final Pos ORIGIN = new Pos(100, 64, -40);

    @Test
    void matchesCommonTransformForEveryRotationAndMirror() {
        for (int rot : new int[] {0, 90, 180, 270}) {
            for (Mirror m : Mirror.values()) {
                SchematicTransform t = new SchematicTransform(ORIGIN, rot, m, 5, 3, 7);
                Placement p = new Placement(t);
                assertEquals(t.footprint(), p.footprint(), rot + "/" + m);
                Set<Pos> seen = new HashSet<>();
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 3; y++) {
                        for (int z = 0; z < 7; z++) {
                            Pos w = t.toWorld(x, y, z);
                            String where = rot + "/" + m + " local " + x + "," + y + "," + z;
                            assertEquals(w.x(), p.worldX(x, z), where);
                            assertEquals(w.y(), p.worldY(y), where);
                            assertEquals(w.z(), p.worldZ(x, z), where);
                            assertEquals(x, p.localX(w.x(), w.z()), where);
                            assertEquals(y, p.localY(w.y()), where);
                            assertEquals(z, p.localZ(w.x(), w.z()), where);
                            assertTrue(p.covers(w.x(), w.y(), w.z()), where);
                            assertTrue(seen.add(w), "transform must be a bijection: " + where);
                        }
                    }
                }
            }
        }
    }

    @Test
    void inverseMatchesCommonOutsideTheSchematicToo() {
        for (int rot : new int[] {0, 90, 180, 270}) {
            for (Mirror m : Mirror.values()) {
                SchematicTransform t = new SchematicTransform(ORIGIN, rot, m, 4, 2, 9);
                Placement p = new Placement(t);
                for (int wx = 85; wx <= 115; wx += 3) {
                    for (int wz = -55; wz <= -25; wz += 4) {
                        Pos l = t.toLocal(wx, 70, wz);
                        assertEquals(l.x(), p.localX(wx, wz));
                        assertEquals(l.z(), p.localZ(wx, wz));
                    }
                }
            }
        }
    }

    @Test
    void clockwiseRotationExtendsToTheNegativeSide() {
        // 90° clockwise seen from above: local +X (east) becomes world +Z (south), local +Z becomes world -X
        Placement p = new Placement(new SchematicTransform(ORIGIN, 90, Mirror.NONE, 5, 1, 3));
        assertEquals(ORIGIN.x(), p.worldX(4, 0));
        assertEquals(ORIGIN.z() + 4, p.worldZ(4, 0));
        assertEquals(ORIGIN.x() - 2, p.worldX(0, 2));
        assertEquals(new Box(new Pos(98, 64, -40), new Pos(100, 64, -36)), p.footprint());
    }

    @Test
    void regionIsFootprintIntersectedWithMask() {
        Placement p = new Placement(new SchematicTransform(ORIGIN, 0, Mirror.NONE, 10, 4, 10));
        assertEquals(p.footprint(), p.region(null));
        Box sector = new Box(new Pos(105, 0, -50), new Pos(120, 300, -35));
        assertEquals(new Box(new Pos(105, 64, -40), new Pos(109, 67, -35)), p.region(sector));
        assertNull(p.region(new Box(new Pos(0, 0, 0), new Pos(1, 1, 1))));
    }
}
