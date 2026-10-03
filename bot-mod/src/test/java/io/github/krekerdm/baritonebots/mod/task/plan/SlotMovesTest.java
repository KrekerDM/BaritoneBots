package io.github.krekerdm.baritonebots.mod.task.plan;

import io.github.krekerdm.baritonebots.mod.task.plan.SlotMoves.Click;
import io.github.krekerdm.baritonebots.mod.task.plan.SlotMoves.Target;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotMovesTest {
    /** Simulates vanilla PICKUP clicks on two slots of one item type; returns {source, dest, cursor}. */
    private static int[] simulate(int count, int destStart, List<Click> clicks) {
        int src = count;
        int dst = destStart;
        int cursor = 0;
        for (Click c : clicks) {
            boolean onSrc = c.target() == Target.SOURCE;
            int slot = onSrc ? src : dst;
            if (c.button() == 0) {
                if (cursor == 0) {
                    cursor = slot;
                    slot = 0;
                } else {
                    slot += cursor;
                    cursor = 0;
                }
            } else {
                if (cursor == 0) {
                    int half = (slot + 1) / 2;
                    cursor = half;
                    slot -= half;
                } else {
                    slot++;
                    cursor--;
                }
            }
            if (onSrc) {
                src = slot;
            } else {
                dst = slot;
            }
        }
        return new int[]{src, dst, cursor};
    }

    @Test
    void partialMovesExactlyNAndEmptiesTheCursor() {
        for (int count = 1; count <= 64; count++) {
            for (int n = 1; n <= count; n++) {
                for (int destStart : new int[]{0, 5}) {
                    List<Click> clicks = SlotMoves.partial(count, n);
                    int[] r = simulate(count, destStart, clicks);
                    assertEquals(count - n, r[0], "source after moving " + n + " of " + count);
                    assertEquals(destStart + n, r[1], "dest after moving " + n + " of " + count);
                    assertEquals(0, r[2], "cursor after moving " + n + " of " + count);
                    assertEquals(SlotMoves.cost(count, n), clicks.size());
                }
            }
        }
    }

    @Test
    void partialUsesTheCheaperDirection() {
        assertEquals(3, SlotMoves.partial(64, 1).size()); // pick up, 1 right click, put back
        assertEquals(3, SlotMoves.partial(64, 63).size()); // pick up, 1 right click back, put rest
        assertEquals(2, SlotMoves.partial(10, 10).size());
        assertEquals(34, SlotMoves.partial(64, 32).size());
        assertTrue(SlotMoves.partial(0, 3).isEmpty());
        assertTrue(SlotMoves.partial(5, 0).isEmpty());
    }

    @Test
    void throwModePicksTheFewestClicks() {
        assertEquals(SlotMoves.ThrowMode.WHOLE, SlotMoves.throwMode(16, 16, false));
        assertEquals(SlotMoves.ThrowMode.WHOLE, SlotMoves.throwMode(16, 40, true));
        assertEquals(SlotMoves.ThrowMode.SINGLES, SlotMoves.throwMode(64, 3, true));
        assertEquals(SlotMoves.ThrowMode.SPLIT, SlotMoves.throwMode(64, 60, true));
        assertEquals(SlotMoves.ThrowMode.SINGLES, SlotMoves.throwMode(64, 60, false));
    }

    @Test
    void craftsForRoundsUp() {
        assertEquals(0, SlotMoves.craftsFor(0, 4));
        assertEquals(1, SlotMoves.craftsFor(1, 4));
        assertEquals(1, SlotMoves.craftsFor(4, 4));
        assertEquals(2, SlotMoves.craftsFor(5, 4));
        assertEquals(7, SlotMoves.craftsFor(7, 0));
    }
}
