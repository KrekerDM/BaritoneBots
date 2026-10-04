package io.github.krekerdm.baritonebots.manager.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Sector splitting and re-splitting (SPEC §5.7 step 2). */
class SectorsTest {
    private static final Box FOOTPRINT = new Box(new Pos(0, 60, 0), new Pos(39, 70, 9)); // 40 × 11 × 10

    /** Every block of {@code fp} in exactly one piece. */
    private static void assertPartition(Box fp, List<Box> pieces) {
        long sum = pieces.stream().mapToLong(Box::volume).sum();
        assertEquals(fp.volume(), sum, "volumes add up");
        for (int i = 0; i < pieces.size(); i++) {
            assertTrue(fp.contains(pieces.get(i)), "inside the footprint");
            for (int j = i + 1; j < pieces.size(); j++) {
                assertTrue(pieces.get(i).intersection(pieces.get(j)).isEmpty(), "no overlap");
            }
        }
    }

    @Test
    void splitsAlongTheLongerAxisWithMinimumWidth() {
        List<Box> four = Sectors.split(FOOTPRINT, 4);
        assertEquals(4, four.size());
        assertPartition(FOOTPRINT, four);
        assertTrue(four.stream().allMatch(b -> b.width() == 10 && b.length() == 10 && b.height() == 11));
        assertEquals(10, Sectors.split(FOOTPRINT, 20).size(), "40 blocks / min width 4 = at most 10 sectors");
        assertEquals(1, Sectors.split(FOOTPRINT, 0).size());
        Box narrow = new Box(new Pos(0, 0, 0), new Pos(2, 5, 6)); // 3 × 7: longer axis Z
        List<Box> n = Sectors.split(narrow, 3);
        assertEquals(1, n.size(), "7 blocks only fit one sector of width >= 4");
    }

    @Test
    void resplitKeepsFinishedSectorsAndCoversTheRest() {
        List<Box> four = Sectors.split(FOOTPRINT, 4);
        List<Sectors.Piece> current = new ArrayList<>();
        for (int i = 0; i < four.size(); i++) {
            current.add(new Sectors.Piece(four.get(i), i == 1)); // sector 2 is done
        }
        List<Sectors.Piece> next = Sectors.resplit(FOOTPRINT, current, 6);
        assertPartition(FOOTPRINT, next.stream().map(Sectors.Piece::box).toList());
        assertTrue(next.contains(new Sectors.Piece(four.get(1), true)), "done sector unchanged");
        long open = next.stream().filter(p -> !p.done()).count();
        assertEquals(6, open, "about one unfinished sector per builder");
        for (Sectors.Piece p : next) {
            assertTrue(p.box().width() >= Sectors.MIN_WIDTH);
        }
        // ordered along X
        for (int i = 1; i < next.size(); i++) {
            assertTrue(next.get(i).box().a().x() > next.get(i - 1).box().a().x());
        }
    }

    @Test
    void resplitWithFewerBuildersMergesRuns() {
        List<Box> four = Sectors.split(FOOTPRINT, 4);
        List<Sectors.Piece> current = four.stream().map(b -> new Sectors.Piece(b, false)).toList();
        List<Sectors.Piece> next = Sectors.resplit(FOOTPRINT, current, 1);
        assertEquals(1, next.size());
        assertEquals(FOOTPRINT, next.getFirst().box());

        // two separate unfinished runs keep at least one sector each, even for one builder
        List<Sectors.Piece> gaps = List.of(new Sectors.Piece(four.get(0), false), new Sectors.Piece(four.get(1), true),
                new Sectors.Piece(four.get(2), false), new Sectors.Piece(four.get(3), false));
        List<Sectors.Piece> merged = Sectors.resplit(FOOTPRINT, gaps, 1);
        assertEquals(3, merged.size());
        assertEquals(four.get(0), merged.get(0).box());
        assertEquals(new Box(four.get(2).a(), four.get(3).b()), merged.get(2).box());
        assertPartition(FOOTPRINT, merged.stream().map(Sectors.Piece::box).toList());
    }

    @Test
    void resplitWithNothingLeftChangesNothing() {
        List<Sectors.Piece> done = Sectors.split(FOOTPRINT, 3).stream().map(b -> new Sectors.Piece(b, true)).toList();
        assertEquals(done, Sectors.resplit(FOOTPRINT, done, 5));
        assertEquals(2, Sectors.resplit(FOOTPRINT, List.of(), 2).size());
    }

    @Test
    void allocationIsProportional() {
        List<Box> runs = List.of(new Box(new Pos(0, 0, 0), new Pos(29, 0, 0)), new Box(new Pos(40, 0, 0), new Pos(49, 0, 0)));
        int[] c = Sectors.allocate(runs, 4, Box.Axis.X);
        assertEquals(3, c[0]);
        assertEquals(1, c[1]);
    }

    @Test
    void sweepCutsTheLongSide() {
        Box sector = new Box(new Pos(0, 60, 0), new Pos(9, 70, 99)); // 10 wide, 100 long
        List<Box> parts = Sectors.sweep(sector, 32);
        assertEquals(4, parts.size());
        assertPartition(sector, parts);
        Set<Integer> widths = new HashSet<>();
        parts.forEach(p -> widths.add(p.width()));
        assertEquals(Set.of(10), widths, "cut across Z only");
        assertTrue(parts.stream().allMatch(p -> p.length() <= 32));
    }
}
