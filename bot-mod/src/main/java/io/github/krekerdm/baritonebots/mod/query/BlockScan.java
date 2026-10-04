package io.github.krekerdm.baritonebots.mod.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code scan_blocks {center, radius ≤ 96, dy?, ids?:[glob], tags?:[block tag], gap?, maxClusters?}} (SPEC §2.4):
 * walks the loaded chunk sections of the box {@code center ± radius} (vertically {@code ± dy}, default radius),
 * skipping sections whose palette cannot contain a target, and feeds matching blocks into {@link Clusters}. Runs
 * section by section across ticks within the query budget, then builds the clusters the same way. Client thread.
 */
final class BlockScan {
    static final int MAX_RADIUS = 96;
    static final int MAX_CELLS = 50_000;

    private final ClientLevel level;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int minY;
    private final int maxY;
    private final Map<Block, String> targets;
    private final Predicate<BlockState> match;
    private final Clusters clusters;
    private final int maxClusters;
    private final List<long[]> columns = new ArrayList<>();
    private int col;
    private int secY = Integer.MIN_VALUE;
    private int unloaded;
    private boolean scanned;

    private BlockScan(ClientLevel level, BlockPos center, int radius, int dy, Map<Block, String> targets, int gap,
                      int maxClusters) {
        this.level = level;
        this.minX = center.getX() - radius;
        this.maxX = center.getX() + radius;
        this.minZ = center.getZ() - radius;
        this.maxZ = center.getZ() + radius;
        this.minY = Math.max(level.getMinY(), center.getY() - dy);
        this.maxY = Math.min(level.getMaxY(), center.getY() + dy);
        this.targets = targets;
        this.match = s -> targets.containsKey(s.getBlock());
        this.clusters = new Clusters(gap, MAX_CELLS);
        this.maxClusters = maxClusters;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                columns.add(new long[] {cx, cz});
            }
        }
    }

    /** Parses the arguments; {@code fallback} is the centre when none is given. */
    static BlockScan create(ClientLevel level, JsonObject args, BlockPos fallback) {
        Pos c = Pos.fromJson(args.get("center"));
        BlockPos center = c == null ? fallback : new BlockPos(c.x(), c.y(), c.z());
        int radius = Math.max(1, Math.min(MAX_RADIUS, Json.getInt(args, "radius", 48)));
        int dy = Math.max(0, Math.min(2 * MAX_RADIUS, Json.getInt(args, "dy", radius)));
        int gap = Math.max(1, Math.min(8, Json.getInt(args, "gap", 2)));
        int maxClusters = Math.max(1, Math.min(256, Json.getInt(args, "maxClusters", 32)));
        List<String> ids = Json.getStringList(args, "ids").stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(Ids::normalizeGlob).toList();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String t : Json.getStringList(args, "tags")) {
            String raw = t.trim();
            if (raw.startsWith("#")) {
                raw = raw.substring(1);
            }
            Identifier id = raw.isEmpty() ? null : Identifier.tryParse(Ids.normalize(raw));
            if (id == null) {
                throw new IllegalArgumentException("bad block tag '" + t + "'");
            }
            tags.add(TagKey.create(Registries.BLOCK, id));
        }
        if (ids.isEmpty() && tags.isEmpty()) {
            throw new IllegalArgumentException("ids or tags required");
        }
        Map<Block, String> targets = new HashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String id = McIds.block(b);
            boolean hit = Ids.matchesAny(ids, id);
            for (int i = 0; !hit && i < tags.size(); i++) {
                hit = b.defaultBlockState().is(tags.get(i));
            }
            if (hit) {
                targets.put(b, id);
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no block matches the given ids / tags");
        }
        return new BlockScan(level, center, radius, dy, targets, gap, maxClusters);
    }

    ClientLevel level() {
        return level;
    }

    /** Scans, then clusters, until done (true) or the deadline (false). */
    boolean step(long deadline) {
        while (!scanned) {
            if (col >= columns.size()) {
                scanned = true;
                break;
            }
            int cx = (int) columns.get(col)[0];
            int cz = (int) columns.get(col)[1];
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                unloaded++;
                nextColumn();
                continue;
            }
            int top = maxY >> 4;
            if (secY == Integer.MIN_VALUE) {
                secY = minY >> 4;
            }
            if (secY > top) {
                nextColumn();
                continue;
            }
            scanSection(chunk, cx, cz, secY++);
            if (System.nanoTime() >= deadline) {
                return false;
            }
        }
        return clusters.build(deadline);
    }

    private void nextColumn() {
        col++;
        secY = Integer.MIN_VALUE;
    }

    private void scanSection(LevelChunk chunk, int cx, int cz, int sy) {
        int idx = chunk.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) {
            return;
        }
        LevelChunkSection s = sections[idx];
        if (s == null || s.hasOnlyAir() || !s.maybeHas(match)) {
            return;
        }
        int x0 = Math.max(minX, cx << 4);
        int x1 = Math.min(maxX, (cx << 4) + 15);
        int z0 = Math.max(minZ, cz << 4);
        int z1 = Math.min(maxZ, (cz << 4) + 15);
        int y0 = Math.max(minY, sy << 4);
        int y1 = Math.min(maxY, (sy << 4) + 15);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockState st = s.getBlockState(x & 15, y & 15, z & 15);
                    String id = targets.get(st.getBlock());
                    if (id != null) {
                        clusters.add(x, y, z, id);
                    }
                }
            }
        }
    }

    JsonObject result() {
        JsonArray out = new JsonArray();
        for (Clusters.Cluster c : clusters.result(maxClusters)) {
            JsonObject ids = new JsonObject();
            c.ids().forEach(ids::addProperty);
            out.add(Json.obj("box", Json.obj("a", Json.obj("x", c.minX(), "y", c.minY(), "z", c.minZ()),
                    "b", Json.obj("x", c.maxX(), "y", c.maxY(), "z", c.maxZ())), "count", c.count(), "ids", ids));
        }
        return Json.obj("clusters", out, "total", clusters.total(), "truncated", clusters.truncated(),
                "unloadedChunks", unloaded);
    }
}
