package io.github.krekerdm.baritonebots.mod.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClustersTest {
    private static List<Clusters.Cluster> run(Clusters c, int max) {
        assertTrue(c.build(Long.MAX_VALUE));
        return c.result(max);
    }

    @Test
    void separateFieldsGetTheirOwnBoxes() {
        Clusters c = new Clusters(2, 10_000);
        // a 9x9 farmland field with a water line in the middle (gap 1 block) ...
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                if (z != 4) {
                    c.add(x, 63, z, "minecraft:farmland");
                }
            }
        }
        // ... and a 3x2 field 20 blocks away, some of it planted
        for (int x = 30; x < 33; x++) {
            for (int z = 0; z < 2; z++) {
                c.add(x, 64, z, x == 30 ? "minecraft:wheat" : "minecraft:farmland");
            }
        }
        List<Clusters.Cluster> out = run(c, 10);
        assertEquals(2, out.size());
        Clusters.Cluster big = out.get(0);
        assertEquals(72, big.count());
        assertEquals(List.of(0, 63, 0, 8, 63, 8), List.of(big.minX(), big.minY(), big.minZ(), big.maxX(), big.maxY(),
                big.maxZ()), "the water line does not split the field");
        Clusters.Cluster small = out.get(1);
        assertEquals(6, small.count());
        assertEquals(30, small.minX());
        assertEquals(32, small.maxX());
        assertEquals(2, small.ids().get("minecraft:wheat"));
        assertEquals(4, small.ids().get("minecraft:farmland"));
        assertEquals(78, c.total());
    }

    @Test
    void gapOneIsExactConnectivityAndNegativeCoordinatesWork() {
        Clusters c = new Clusters(1, 10_000);
        c.add(-5, -60, -5, "a");
        c.add(-4, -59, -4, "a"); // diagonal neighbour: joins
        c.add(-2, -59, -4, "a"); // one block apart: separate with gap 1
        List<Clusters.Cluster> out = run(c, 10);
        assertEquals(2, out.size());
        assertEquals(2, out.get(0).count());
        assertEquals(-5, out.get(0).minX());
        assertEquals(-59, out.get(0).maxY());
    }

    @Test
    void farBlocksNeverJoinAndLimitsHold() {
        Clusters c = new Clusters(3, 10_000);
        c.add(0, 0, 0, "x");
        c.add(6, 0, 0, "x"); // 2·gap apart: cells 0 and 2
        assertEquals(2, run(c, 10).size());

        Clusters limited = new Clusters(1, 2);
        assertTrue(limited.add(0, 0, 0, "x"));
        assertTrue(limited.add(10, 0, 0, "x"));
        assertTrue(limited.add(10, 0, 0, "x"), "an existing cell still counts");
        assertFalse(limited.add(20, 0, 0, "x"));
        assertTrue(limited.truncated());
        assertEquals(1, run(limited, 1).size(), "maxClusters caps the answer");
    }

    @Test
    void buildCanBeResumedAfterTheDeadline() {
        Clusters c = new Clusters(1, 100_000);
        for (int x = 0; x < 2000; x++) {
            c.add(x, 0, 0, "rail");
        }
        int rounds = 0;
        while (!c.build(System.nanoTime() - 1)) {
            rounds++;
        }
        assertTrue(rounds > 0, "an expired deadline stops after a slice");
        List<Clusters.Cluster> out = c.result(5);
        assertEquals(1, out.size());
        assertEquals(2000, out.get(0).count());
    }
}
