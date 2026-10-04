package io.github.krekerdm.baritonebots.manager.refs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Flat building site search on a {@code heightmap} answer (SPEC §5.7e, build site for {@code {"ref":"auto"}}).
 * Pure, unit-tested.
 * <p>
 * A site is a {@code w × l} rectangle of known, solid-ground columns (no liquid, no trees) whose heights differ by at
 * most {@code maxVariance}, lying fully inside the scanned grid with its centre within {@code radius} of the centre,
 * and not overlapping (with a one-block margin) any excluded box in x/z. Among the sites the one whose centre is
 * nearest to the centre wins (ties: lower z, then lower x). Range minima / maxima use sliding windows, so a search is
 * linear in the grid size.
 */
public final class FlatSite {
    static final int UNKNOWN = Integer.MIN_VALUE;

    private FlatSite() {
    }

    /** Heights per column (row-major, z outer), {@link #UNKNOWN} = not loaded / not ground. */
    public record Grid(int x0, int z0, int size, int[] heights) {
        public int at(int dx, int dz) {
            return heights[dz * size + dx];
        }
    }

    /** Footprint minimum corner and the floor: {@code y} = the highest ground block + 1. */
    public record Site(int x, int z, int y) {
    }

    /** From a {@code heightmap} answer: liquid / tree / unknown columns become {@link #UNKNOWN}. */
    public static Grid parse(JsonObject data) {
        int size = Json.getInt(data, "size", 0);
        int[] h = new int[Math.max(0, size * size)];
        JsonArray heights = Json.getArr(data, "heights");
        String surface = Json.getString(data, "surface", "");
        for (int i = 0; i < h.length; i++) {
            JsonElement e = heights != null && i < heights.size() ? heights.get(i) : null;
            boolean ground = i < surface.length() && surface.charAt(i) == '.';
            h[i] = e == null || e.isJsonNull() || !ground ? UNKNOWN : e.getAsInt();
        }
        return new Grid(Json.getInt(data, "x0", 0), Json.getInt(data, "z0", 0), size, h);
    }

    /**
     * The nearest flat site, or null.
     *
     * @param w footprint size along x
     * @param l footprint size along z
     */
    public static Site find(Grid g, int w, int l, int centerX, int centerZ, int radius, List<Box> exclusions,
                            int maxVariance) {
        int n = g.size();
        if (w < 1 || l < 1 || w > n || l > n) {
            return null;
        }
        int cols = n - w + 1;
        int rows = n - l + 1;
        // per row: min / max / unknown count over x windows of width w
        int[][] rowMin = new int[n][cols];
        int[][] rowMax = new int[n][cols];
        int[][] rowBad = new int[n][cols];
        int[] line = new int[n];
        for (int z = 0; z < n; z++) {
            int bad = 0;
            for (int x = 0; x < n; x++) {
                line[x] = g.at(x, z);
            }
            slide(line, w, rowMin[z], true);
            slide(line, w, rowMax[z], false);
            for (int x = 0; x < n; x++) {
                bad += line[x] == UNKNOWN ? 1 : 0;
                if (x >= w) {
                    bad -= line[x - w] == UNKNOWN ? 1 : 0;
                }
                if (x >= w - 1) {
                    rowBad[z][x - w + 1] = bad;
                }
            }
        }
        Site best = null;
        double bestD = Double.MAX_VALUE;
        int[] colMin = new int[n];
        int[] colMax = new int[n];
        int[] winMin = new int[rows];
        int[] winMax = new int[rows];
        for (int x = 0; x < cols; x++) {
            int bad = 0;
            for (int z = 0; z < n; z++) {
                colMin[z] = rowMin[z][x];
                colMax[z] = rowMax[z][x];
            }
            slide(colMin, l, winMin, true);
            slide(colMax, l, winMax, false);
            for (int z = 0; z < n; z++) {
                bad += rowBad[z][x];
                if (z >= l) {
                    bad -= rowBad[z - l][x];
                }
                if (z < l - 1) {
                    continue;
                }
                int z0 = z - l + 1;
                if (bad > 0 || winMax[z0] - winMin[z0] > maxVariance) {
                    continue;
                }
                int wx = g.x0() + x;
                int wz = g.z0() + z0;
                double cx = wx + (w - 1) / 2.0;
                double cz = wz + (l - 1) / 2.0;
                double d = Math.hypot(cx - centerX, cz - centerZ);
                if (d > radius || d > bestD || blocked(wx, wz, w, l, exclusions)) {
                    continue;
                }
                if (d < bestD || best == null || wz < best.z() || wz == best.z() && wx < best.x()) {
                    best = new Site(wx, wz, winMax[z0] + 1);
                    bestD = d;
                }
            }
        }
        return best;
    }

    /** Sliding window minimum (or maximum) of {@code in} with width {@code k}; UNKNOWN values are ignored. */
    private static void slide(int[] in, int k, int[] out, boolean min) {
        ArrayDeque<Integer> dq = new ArrayDeque<>();
        for (int i = 0; i < in.length; i++) {
            if (in[i] != UNKNOWN) {
                while (!dq.isEmpty() && (min ? in[dq.peekLast()] >= in[i] : in[dq.peekLast()] <= in[i])) {
                    dq.pollLast();
                }
                dq.addLast(i);
            }
            while (!dq.isEmpty() && dq.peekFirst() <= i - k) {
                dq.pollFirst();
            }
            if (i >= k - 1 && i - k + 1 < out.length) {
                out[i - k + 1] = dq.isEmpty() ? (min ? Integer.MAX_VALUE : Integer.MIN_VALUE + 1) : in[dq.peekFirst()];
            }
        }
    }

    private static boolean blocked(int x, int z, int w, int l, List<Box> exclusions) {
        for (Box b : exclusions) {
            if (x <= b.max().x() + 1 && x + w - 1 >= b.min().x() - 1 && z <= b.max().z() + 1
                    && z + l - 1 >= b.min().z() - 1) {
                return true;
            }
        }
        return false;
    }
}
