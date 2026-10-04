package io.github.krekerdm.baritonebots.manager.planner;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Build sectors (SPEC §5.7 step 2): vertical slabs of the footprint along its longer horizontal axis, one per builder,
 * at least {@link #MIN_WIDTH} blocks wide. Pure.
 */
public final class Sectors {
    public static final int MIN_WIDTH = 4;

    /** A sector box and whether it is finished (finished sectors never move on a re-split). */
    public record Piece(Box box, boolean done) {
    }

    private Sectors() {
    }

    /** First split: {@code builders} slabs (fewer when the footprint is narrow). */
    public static List<Box> split(Box footprint, int builders) {
        return footprint.split(Math.max(1, builders), MIN_WIDTH);
    }

    /**
     * Re-split for a new builder count: finished sectors stay; every run of adjacent unfinished sectors is merged
     * and cut again so that there are about {@code builders} unfinished sectors in total (at least one per run,
     * widths proportional to the runs). The result covers exactly the same blocks, ordered along the axis.
     */
    public static List<Piece> resplit(Box footprint, List<Piece> current, int builders) {
        Box.Axis axis = footprint.longerHorizontalAxis();
        if (current.isEmpty()) {
            return split(footprint, builders).stream().map(b -> new Piece(b, false)).toList();
        }
        List<Piece> sorted = new ArrayList<>(current);
        sorted.sort(Comparator.comparingInt(p -> start(p.box(), axis)));
        List<Piece> out = new ArrayList<>();
        List<Box> runs = new ArrayList<>();
        List<Integer> runSlot = new ArrayList<>(); // index in out where the run's pieces go
        Box run = null;
        for (Piece p : sorted) {
            if (p.done()) {
                if (run != null) {
                    runs.add(run);
                    runSlot.add(out.size());
                    out.add(null);
                    run = null;
                }
                out.add(p);
            } else {
                run = run == null ? p.box() : run.union(p.box());
            }
        }
        if (run != null) {
            runs.add(run);
            runSlot.add(out.size());
            out.add(null);
        }
        if (runs.isEmpty()) {
            return List.copyOf(out);
        }
        int[] counts = allocate(runs, Math.max(builders, runs.size()), axis);
        List<Piece> result = new ArrayList<>();
        int r = 0;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) != null) {
                result.add(out.get(i));
            } else {
                for (Box b : runs.get(r).split(counts[r], axis, MIN_WIDTH)) {
                    result.add(new Piece(b, false));
                }
                r++;
            }
        }
        return List.copyOf(result);
    }

    /** Largest-remainder share of {@code total} slabs, at least one per run. */
    static int[] allocate(List<Box> runs, int total, Box.Axis axis) {
        int n = runs.size();
        int[] counts = new int[n];
        long width = runs.stream().mapToLong(b -> b.size(axis)).sum();
        double[] rem = new double[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            double exact = (double) total * runs.get(i).size(axis) / Math.max(1, width);
            counts[i] = Math.max(1, (int) Math.floor(exact));
            rem[i] = exact - Math.floor(exact);
            used += counts[i];
        }
        while (used < total) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (rem[i] > rem[best]) {
                    best = i;
                }
            }
            counts[best]++;
            rem[best] = -1;
            used++;
        }
        return counts;
    }

    private static int start(Box b, Box.Axis axis) {
        Pos a = b.a();
        return switch (axis) {
            case X -> a.x();
            case Y -> a.y();
            case Z -> a.z();
        };
    }

    /**
     * Sub-boxes of a sector for a verification walk: the sector cut across its other horizontal axis into pieces of
     * at most {@code maxWidth} blocks, so a bot standing at each piece's centre has its chunks loaded.
     */
    public static List<Box> sweep(Box sector, int maxWidth) {
        Box.Axis other = sector.width() >= sector.length() ? Box.Axis.X : Box.Axis.Z;
        int size = sector.size(other);
        int n = Math.max(1, (size + maxWidth - 1) / maxWidth);
        return sector.split(n, other, 1);
    }
}
