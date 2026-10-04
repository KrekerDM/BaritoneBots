package io.github.krekerdm.baritonebots.mod.query;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups matching block positions into compact clusters for {@code scan_blocks} (SPEC §5.7e). Pure, unit-tested.
 * <p>
 * Positions are binned into cubic cells of {@code gap} blocks; clusters are the connected groups of non-empty cells
 * (26-neighbourhood). With {@code gap = 1} that is exact 26-connectivity of the blocks; in general blocks closer than
 * {@code gap} on every axis always join, blocks {@code 2·gap} or more apart on some axis never link directly. Each
 * cell keeps its count, bounding box and per-id counts, so memory grows with the cells, not the blocks; at most
 * {@code maxCells} cells are kept ({@link #truncated()} afterwards). {@link #build(long)} runs incrementally against
 * a {@link System#nanoTime()} deadline.
 */
public final class Clusters {
    /** One cluster: inclusive bounds, block count and counts per id. */
    public record Cluster(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int count,
                          Map<String, Integer> ids) {
    }

    private static final class Cell {
        int count;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int[] idCounts = new int[4];
    }

    private final int gap;
    private final int maxCells;
    private final Map<Long, Cell> cells = new HashMap<>();
    private final List<String> ids = new ArrayList<>();
    private final Map<String, Integer> idIndex = new HashMap<>();
    private long total;
    private boolean truncated;

    // incremental build state
    private Iterator<Long> seeds;
    private final Set<Long> visited = new HashSet<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private Acc current;
    private final List<Cluster> built = new ArrayList<>();
    private boolean done;

    public Clusters(int gap, int maxCells) {
        this.gap = Math.max(1, gap);
        this.maxCells = Math.max(1, maxCells);
    }

    /** Adds a block; false when it was dropped because the cell limit is reached. */
    public boolean add(int x, int y, int z, String id) {
        long key = key(Math.floorDiv(x, gap), Math.floorDiv(y, gap), Math.floorDiv(z, gap));
        Cell c = cells.get(key);
        if (c == null) {
            if (cells.size() >= maxCells) {
                truncated = true;
                return false;
            }
            c = new Cell();
            cells.put(key, c);
        }
        c.count++;
        c.minX = Math.min(c.minX, x);
        c.minY = Math.min(c.minY, y);
        c.minZ = Math.min(c.minZ, z);
        c.maxX = Math.max(c.maxX, x);
        c.maxY = Math.max(c.maxY, y);
        c.maxZ = Math.max(c.maxZ, z);
        int idx = idIndex.computeIfAbsent(id, k -> {
            ids.add(k);
            return ids.size() - 1;
        });
        if (idx >= c.idCounts.length) {
            c.idCounts = java.util.Arrays.copyOf(c.idCounts, Math.max(idx + 1, c.idCounts.length * 2));
        }
        c.idCounts[idx]++;
        total++;
        return true;
    }

    public long total() {
        return total;
    }

    public boolean truncated() {
        return truncated;
    }

    public int cellCount() {
        return cells.size();
    }

    /** Connects cells into clusters until done (true) or the deadline passes (false; call again). */
    public boolean build(long deadlineNanos) {
        if (done) {
            return true;
        }
        if (seeds == null) {
            seeds = new ArrayList<>(cells.keySet()).iterator();
        }
        int ops = 0;
        while (true) {
            if (current == null) {
                Long seed = null;
                while (seeds.hasNext()) {
                    Long k = seeds.next();
                    if (visited.add(k)) {
                        seed = k;
                        break;
                    }
                }
                if (seed == null) {
                    done = true;
                    return true;
                }
                current = new Acc(ids.size());
                queue.add(seed);
            }
            while (!queue.isEmpty()) {
                long k = queue.poll();
                current.add(cells.get(k));
                int cx = cx(k);
                int cy = cy(k);
                int cz = cz(k);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx | dy | dz) == 0) {
                                continue;
                            }
                            long n = key(cx + dx, cy + dy, cz + dz);
                            if (cells.containsKey(n) && visited.add(n)) {
                                queue.add(n);
                            }
                        }
                    }
                }
                if ((++ops & 255) == 0 && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
            built.add(current.toCluster(ids));
            current = null;
        }
    }

    /** The clusters once {@link #build} returned true: largest first (then lowest x, z), at most {@code max}. */
    public List<Cluster> result(int max) {
        List<Cluster> out = new ArrayList<>(built);
        out.sort(Comparator.comparingInt(Cluster::count).reversed().thenComparingInt(Cluster::minX)
                .thenComparingInt(Cluster::minZ).thenComparingInt(Cluster::minY));
        return out.size() > max ? List.copyOf(out.subList(0, Math.max(0, max))) : out;
    }

    private static final class Acc {
        int count;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int[] idCounts;

        Acc(int ids) {
            idCounts = new int[Math.max(1, ids)];
        }

        void add(Cell c) {
            count += c.count;
            minX = Math.min(minX, c.minX);
            minY = Math.min(minY, c.minY);
            minZ = Math.min(minZ, c.minZ);
            maxX = Math.max(maxX, c.maxX);
            maxY = Math.max(maxY, c.maxY);
            maxZ = Math.max(maxZ, c.maxZ);
            if (c.idCounts.length > idCounts.length) {
                idCounts = java.util.Arrays.copyOf(idCounts, c.idCounts.length);
            }
            for (int i = 0; i < c.idCounts.length; i++) {
                idCounts[i] += c.idCounts[i];
            }
        }

        Cluster toCluster(List<String> names) {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < idCounts.length && i < names.size(); i++) {
                if (idCounts[i] > 0) {
                    m.put(names.get(i), idCounts[i]);
                }
            }
            return new Cluster(minX, minY, minZ, maxX, maxY, maxZ, count, java.util.Collections.unmodifiableMap(m));
        }
    }

    // 26 bits x, 26 bits z, 12 bits y (cell coordinates, two's complement)
    static long key(int cx, int cy, int cz) {
        return ((long) (cx & 0x3FFFFFF) << 38) | ((long) (cz & 0x3FFFFFF) << 12) | (cy & 0xFFF);
    }

    static int cx(long k) {
        return (int) (k >> 38); // arithmetic shift restores the sign of the top field
    }

    static int cz(long k) {
        return (int) ((k << 26) >> 38);
    }

    static int cy(long k) {
        return (int) ((k << 52) >> 52);
    }
}
