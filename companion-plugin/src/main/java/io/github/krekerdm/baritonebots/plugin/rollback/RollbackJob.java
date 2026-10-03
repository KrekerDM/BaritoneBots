package io.github.krekerdm.baritonebots.plugin.rollback;

import io.github.krekerdm.baritonebots.plugin.journal.JournalRecord;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Applies a journal rollback on the server thread, {@code perTick} blocks per tick. Chunks that are not loaded are
 * loaded asynchronously first and held with a plugin chunk ticket until the job ends, so the server thread never
 * blocks on chunk IO and never writes into a chunk that unloads mid-job. Physics are off while restoring, like
 * other rollback tools, so restored sand does not fall and water does not flow into the gaps between batches.
 */
final class RollbackJob {
    /** 30 s at 20 TPS. */
    private static final int MAX_WAIT_TICKS = 600;

    private final Plugin plugin;
    private final List<JournalRecord> order;
    private final int perTick;
    private final Consumer<RollbackResult> done;

    private final Map<String, Optional<World>> worlds = new HashMap<>();
    private final Map<String, Optional<BlockData>> parsed = new HashMap<>();
    private final Map<ChunkKey, World> tickets = new HashMap<>();
    private final Set<ChunkKey> loading = new HashSet<>();
    private final Set<ChunkKey> failedChunks = new HashSet<>();
    private BukkitTask task;
    private int cursor;
    private int restored;
    private int skipped;
    private int failed;
    private int waitTicks;
    private boolean finished;

    /** @param undoOrder records newest first ({@link RollbackRules#undoOrder}) */
    RollbackJob(Plugin plugin, List<JournalRecord> undoOrder, int perTick, Consumer<RollbackResult> done) {
        this.plugin = plugin;
        this.order = undoOrder;
        this.perTick = Math.max(1, perTick);
        this.done = done;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Server thread: stops early and reports what was done so far as cancelled. */
    void cancel() {
        if (!finished) {
            finish(result().cancelled());
        }
    }

    int progress() {
        return cursor;
    }

    int total() {
        return order.size();
    }

    private void tick() {
        if (finished) {
            return;
        }
        if (!loading.isEmpty()) {
            if (++waitTicks < MAX_WAIT_TICKS) {
                return;
            }
            // A load that never completes must not keep the job (and the "busy" flag) alive forever.
            plugin.getLogger().warning("Rollback gave up waiting for " + loading.size() + " chunk(s) to load");
            failedChunks.addAll(loading);
            loading.clear();
        }
        waitTicks = 0;
        int budget = perTick;
        while (budget > 0 && cursor < order.size()) {
            JournalRecord r = order.get(cursor);
            World w = world(r.world());
            if (w == null || r.y() < w.getMinHeight() || r.y() >= w.getMaxHeight()) {
                failed++;
                cursor++;
                continue;
            }
            ChunkKey key = new ChunkKey(w.getUID(), r.x() >> 4, r.z() >> 4);
            if (failedChunks.contains(key)) {
                failed++;
                cursor++;
                continue;
            }
            if (!w.isChunkLoaded(key.x(), key.z())) {
                prefetch(budget);
                return;
            }
            apply(w, r);
            cursor++;
            budget--;
        }
        if (cursor >= order.size()) {
            finish(result());
        }
    }

    /** Requests every unloaded chunk among the next {@code count} records. */
    private void prefetch(int count) {
        int end = Math.min(order.size(), cursor + count);
        for (int i = cursor; i < end; i++) {
            JournalRecord r = order.get(i);
            World w = world(r.world());
            if (w == null) {
                continue;
            }
            ChunkKey key = new ChunkKey(w.getUID(), r.x() >> 4, r.z() >> 4);
            if (loading.contains(key) || failedChunks.contains(key) || w.isChunkLoaded(key.x(), key.z())) {
                continue;
            }
            loading.add(key);
            w.getChunkAtAsync(key.x(), key.z()).whenComplete((chunk, error) -> onMain(() -> loaded(w, key, chunk, error)));
        }
    }

    private void loaded(World w, ChunkKey key, Chunk chunk, Throwable error) {
        loading.remove(key);
        if (error != null || chunk == null) {
            failedChunks.add(key);
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Rollback could not load chunk " + key.x() + "," + key.z()
                        + " in " + w.getName(), error);
            }
            return;
        }
        if (finished) {
            return;
        }
        if (w.addPluginChunkTicket(key.x(), key.z(), plugin)) {
            tickets.put(key, w);
        }
    }

    private void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (RuntimeException disabled) {
            // Plugin disabled while a chunk was loading: the job is already cancelled.
        }
    }

    private void apply(World w, JournalRecord r) {
        Block b = w.getBlockAt(r.x(), r.y(), r.z());
        if (!RollbackRules.canRestore(r.after(), b.getBlockData().getAsString())) {
            skipped++;
            return;
        }
        Optional<BlockData> data = parsed.computeIfAbsent(r.before(), RollbackJob::parse);
        if (data.isEmpty()) {
            failed++;
            return;
        }
        b.setBlockData(data.get(), false);
        restored++;
    }

    private static Optional<BlockData> parse(String s) {
        try {
            return Optional.of(Bukkit.createBlockData(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private World world(String name) {
        return worlds.computeIfAbsent(name, n -> Optional.ofNullable(Bukkit.getWorld(n))).orElse(null);
    }

    private RollbackResult result() {
        return RollbackResult.journal(order.size(), restored, skipped, failed);
    }

    private void finish(RollbackResult result) {
        finished = true;
        if (task != null) {
            task.cancel();
        }
        for (Map.Entry<ChunkKey, World> e : tickets.entrySet()) {
            e.getValue().removePluginChunkTicket(e.getKey().x(), e.getKey().z(), plugin);
        }
        tickets.clear();
        done.accept(result);
    }

    private record ChunkKey(UUID world, int x, int z) {
    }
}
