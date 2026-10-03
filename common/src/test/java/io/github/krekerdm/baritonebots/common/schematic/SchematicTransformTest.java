package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform.Mirror;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicTransformTest {
    private static final Pos ORIGIN = new Pos(10, 64, 20);
    private static final int W = 3;
    private static final int H = 2;
    private static final int L = 5;

    private static SchematicTransform t(int rotation, Mirror mirror) {
        return new SchematicTransform(ORIGIN, rotation, mirror, W, H, L);
    }

    @Test
    void noRotationIsATranslation() {
        SchematicTransform t = t(0, Mirror.NONE);
        assertEquals(ORIGIN, t.toWorld(0, 0, 0));
        assertEquals(new Pos(12, 65, 24), t.toWorld(2, 1, 4));
        assertEquals(new Box(new Pos(10, 64, 20), new Pos(12, 65, 24)), t.footprint());
    }

    @Test
    void clockwise90() {
        SchematicTransform t = t(90, Mirror.NONE);
        assertEquals(ORIGIN, t.toWorld(0, 0, 0));
        // +x (east) turns into +z (south); +z (south) turns into -x (west): clockwise from above.
        assertEquals(new Pos(10, 64, 22), t.toWorld(2, 0, 0));
        assertEquals(new Pos(6, 64, 20), t.toWorld(0, 0, 4));
        assertEquals(new Box(new Pos(6, 64, 20), new Pos(10, 65, 22)), t.footprint());
        assertEquals(5, t.worldSizeX());
        assertEquals(3, t.worldSizeZ());
        assertEquals("CLOCKWISE_90", t.mcRotationName());
    }

    @Test
    void clockwise180And270() {
        SchematicTransform t180 = t(180, Mirror.NONE);
        assertEquals(new Pos(8, 65, 16), t180.toWorld(2, 1, 4));
        assertEquals(new Box(new Pos(8, 64, 16), new Pos(10, 65, 20)), t180.footprint());

        SchematicTransform t270 = t(270, Mirror.NONE);
        assertEquals(new Pos(14, 64, 20), t270.toWorld(0, 0, 4));
        assertEquals(new Pos(10, 64, 18), t270.toWorld(2, 0, 0));
        assertEquals(new Box(new Pos(10, 64, 18), new Pos(14, 65, 20)), t270.footprint());
        assertEquals("COUNTERCLOCKWISE_90", t270.mcRotationName());
    }

    @Test
    void mirrorsFlipTheExpectedAxis() {
        assertEquals(new Pos(8, 64, 20), t(0, Mirror.FRONT_BACK).toWorld(2, 0, 0));
        assertEquals(new Pos(10, 64, 24), t(0, Mirror.FRONT_BACK).toWorld(0, 0, 4));
        assertEquals(new Pos(10, 64, 16), t(0, Mirror.LEFT_RIGHT).toWorld(0, 0, 4));
        // mirror first, then rotate: FRONT_BACK makes x=2 → -2, rotating 90° clockwise sends -x to -z
        assertEquals(new Pos(10, 64, 18), t(90, Mirror.FRONT_BACK).toWorld(2, 0, 0));
    }

    @Test
    void everyCombinationIsABijectionOntoItsFootprint() {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            for (Mirror mirror : Mirror.values()) {
                SchematicTransform t = t(rotation, mirror);
                Box fp = t.footprint();
                assertEquals((long) W * H * L, fp.volume(), rotation + " " + mirror);
                assertEquals(H, fp.height());
                Set<Pos> seen = new HashSet<>();
                for (int y = 0; y < H; y++) {
                    for (int z = 0; z < L; z++) {
                        for (int x = 0; x < W; x++) {
                            Pos local = new Pos(x, y, z);
                            Pos world = t.toWorld(local);
                            assertTrue(fp.contains(world), "inside footprint " + rotation + " " + mirror);
                            assertEquals(local, t.toLocal(world), "inverse " + rotation + " " + mirror);
                            assertTrue(t.containsWorld(world));
                            seen.add(world);
                        }
                    }
                }
                assertEquals(W * H * L, seen.size());
                assertFalse(t.containsWorld(fp.max().offset(1, 0, 0)));
            }
        }
    }

    @Test
    void rotationAndMirrorParsing() {
        assertEquals(270, SchematicTransform.normalizeRotation(-90));
        assertEquals(90, SchematicTransform.normalizeRotation(450));
        assertEquals(0, SchematicTransform.normalizeRotation(360));
        assertThrows(IllegalArgumentException.class, () -> SchematicTransform.normalizeRotation(45));
        assertEquals(Mirror.NONE, Mirror.parse(null));
        assertEquals(Mirror.FRONT_BACK, Mirror.parse("Front_Back"));
        assertEquals(Mirror.LEFT_RIGHT, Mirror.parse("left_right"));
        assertThrows(IllegalArgumentException.class, () -> Mirror.parse("diagonal"));
    }

    @Test
    void ofUsesSchematicSize() {
        Schematic s = new Schematic(Schematic.Format.SPONGE_V2, 4, 1, 2, java.util.List.of("minecraft:air"),
                new int[8], null, null, 0);
        SchematicTransform t = SchematicTransform.of(s, Pos.ZERO, 90, "none");
        assertEquals(new Box(new Pos(-1, 0, 0), new Pos(0, 0, 3)), t.footprint());
    }
}
