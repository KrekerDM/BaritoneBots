package io.github.krekerdm.baritonebots.mod.schematic;

import baritone.api.schematic.IStaticSchematic;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;
import java.util.TreeMap;

/**
 * Walks the schematic positions inside a world region (footprint ∩ mask), one chunk column at a time, so a big
 * schematic can be spread over several client ticks ({@link #step(long)} stops at a deadline). Per position: one
 * {@code getDirect}, one identity-map lookup, and in {@link Mode#PROGRESS} one chunk read; no allocations.
 * <ul>
 *   <li>{@link Mode#BOM}: counts schematic positions per state → items ({@link BomRules}).</li>
 *   <li>{@link Mode#PROGRESS}: compares the world with the placed (mirrored + rotated) states. Correct = same block
 *       and equal properties except volatile / neighbour-derived ones; missing = world air, fluid or replaceable;
 *       wrong = any other block (or the same block in another state); unloaded = chunk not loaded.</li>
 * </ul>
 */
public final class SchematicScan {
    public enum Mode { BOM, PROGRESS }

    private final IStaticSchematic schematic;
    private final Placement placement;
    private final Box region;
    private final Mode mode;
    private final ClientLevel level;
    private final StateTable table;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final int cx0;
    private final int cz0;
    private final int czCount;
    private final int columns;
    private final Map<String, Integer> shortfall = new TreeMap<>();
    private int next;
    private int correct;
    private int missing;
    private int wrong;
    private int toClear;

    /** {@code level} may be null for {@link Mode#BOM}. */
    public SchematicScan(IStaticSchematic schematic, Placement placement, Box region, Mode mode, ClientLevel level) {
        this.schematic = schematic;
        this.placement = placement;
        this.region = region;
        this.mode = mode;
        this.level = level;
        this.table = new StateTable(placement.transform());
        this.cx0 = region.min().x() >> 4;
        this.cz0 = region.min().z() >> 4;
        int cxCount = (region.max().x() >> 4) - cx0 + 1;
        this.czCount = (region.max().z() >> 4) - cz0 + 1;
        this.columns = cxCount * czCount;
    }

    public ClientLevel level() {
        return level;
    }

    public Box region() {
        return region;
    }

    /** Scans chunk columns until done or {@code System.nanoTime() >= deadline}; true when finished. */
    public boolean step(long deadline) {
        while (next < columns) {
            int i = next++;
            scanColumn(cx0 + i / czCount, cz0 + i % czCount);
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return next >= columns;
    }

    public boolean done() {
        return next >= columns;
    }

    /** Fraction of chunk columns scanned. */
    public double fraction() {
        return columns == 0 ? 1 : next / (double) columns;
    }

    private void scanColumn(int cx, int cz) {
        int xa = Math.max(region.min().x(), cx << 4);
        int xb = Math.min(region.max().x(), (cx << 4) + 15);
        int za = Math.max(region.min().z(), cz << 4);
        int zb = Math.min(region.max().z(), (cz << 4) + 15);
        int ya = region.min().y();
        int yb = region.max().y();
        boolean progress = mode == Mode.PROGRESS;
        LevelChunk chunk = progress ? level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) : null;
        for (int wx = xa; wx <= xb; wx++) {
            for (int wz = za; wz <= zb; wz++) {
                int lx = placement.localX(wx, wz);
                int lz = placement.localZ(wx, wz);
                for (int wy = ya; wy <= yb; wy++) {
                    BlockState raw = schematic.getDirect(lx, placement.localY(wy), lz);
                    if (raw == null) {
                        continue;
                    }
                    StateTable.Info info = table.info(raw);
                    if (!info.cost.counted()) {
                        if (chunk != null && raw.isAir()) {
                            BlockState ws = chunk.getBlockState(pos.set(wx, wy, wz));
                            if (!ws.isAir() && !(ws.getBlock() instanceof LiquidBlock)) {
                                toClear++;
                            }
                        }
                        continue;
                    }
                    info.total++;
                    if (!progress) {
                        continue;
                    }
                    if (chunk == null) {
                        info.unloaded++;
                        continue;
                    }
                    BlockState ws = chunk.getBlockState(pos.set(wx, wy, wz));
                    BlockState want = info.placed;
                    if (ws == want) {
                        correct++;
                    } else if (ws.getBlock() == want.getBlock()) {
                        if (table.matches(ws, want)) {
                            correct++;
                        } else {
                            wrong++;
                            table.addShortfall(shortfall, info, ws);
                        }
                    } else if (ws.isAir() || ws.getBlock() instanceof LiquidBlock || ws.canBeReplaced()) {
                        missing++;
                        info.remaining++;
                    } else {
                        wrong++;
                        info.remaining++;
                    }
                }
            }
        }
    }

    /** {@code bom} data: {@code {items, blocks, unobtainable?}}. */
    public JsonObject bomJson() {
        Map<String, Integer> items = new TreeMap<>();
        Map<String, Integer> unobtainable = new TreeMap<>();
        int blocks = 0;
        for (StateTable.Info i : table.all()) {
            if (i.total > 0) {
                blocks += i.total;
                add(items, unobtainable, i.cost, i.total);
            }
        }
        JsonObject o = Json.obj("items", TaskArgs.counts(items), "blocks", blocks);
        if (!unobtainable.isEmpty()) {
            o.add("unobtainable", TaskArgs.counts(unobtainable));
        }
        return o;
    }

    /**
     * {@code progress} data. {@code remaining} = items for missing and wrong positions plus unloaded positions
     * (their state is unknown, so they count as still to build); same-block shortfalls (single slab where a double
     * one belongs) add only the difference.
     */
    public JsonObject progressJson(boolean withSectorBox) {
        Map<String, Integer> remaining = new TreeMap<>(shortfall);
        Map<String, Integer> unobtainable = new TreeMap<>();
        int total = 0;
        int unloaded = 0;
        for (StateTable.Info i : table.all()) {
            total += i.total;
            unloaded += i.unloaded;
            add(remaining, unobtainable, i.cost, i.remaining + i.unloaded);
        }
        JsonObject o = Json.obj("total", total, "correct", correct, "missing", missing, "wrong", wrong,
                "unloaded", unloaded, "toClear", toClear, "remaining", TaskArgs.counts(remaining));
        if (!unobtainable.isEmpty()) {
            o.add("unobtainable", TaskArgs.counts(unobtainable));
        }
        if (withSectorBox) {
            o.add("sectorBox", Json.toTree(region));
        }
        return o;
    }

    /**
     * Items still needed for wrong/missing positions in loaded chunks minus {@code inventory} (item id → count);
     * blocks without an item are listed under their block id.
     */
    public Map<String, Integer> missingVs(Map<String, Integer> inventory) {
        Map<String, Integer> need = new TreeMap<>(shortfall);
        Map<String, Integer> unobtainable = new TreeMap<>();
        for (StateTable.Info i : table.all()) {
            add(need, unobtainable, i.cost, i.remaining);
        }
        Map<String, Integer> out = new TreeMap<>();
        need.forEach((id, n) -> {
            int d = n - inventory.getOrDefault(id, 0);
            if (d > 0) {
                out.put(id, d);
            }
        });
        out.putAll(unobtainable);
        return out;
    }

    /** Positions in loaded chunks that still need a block (missing + wrong). */
    public int loadedTodo() {
        return missing + wrong;
    }

    private static void add(Map<String, Integer> items, Map<String, Integer> unobtainable, BomRules.Cost c, int n) {
        if (n <= 0 || !c.counted()) {
            return;
        }
        c.items().forEach((item, k) -> items.merge(item, k * n, Integer::sum));
        if (c.unobtainable() != null) {
            unobtainable.merge(c.unobtainable(), n, Integer::sum);
        }
    }
}
