package io.github.krekerdm.baritonebots.mod.task.impl;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fake-ore handling for {@code mine fakeOres=true} (Paper anti-xray engine mode 2, SPEC §5.7b3). Every
 * {@value #SCAN_INTERVAL} ticks the target ores within {@value #RADIUS} blocks of the bot are remembered; a remembered
 * ore that turns into another solid block (stone, deepslate, ...) without being mined first was a fake one: the
 * server only sent the real block once it was exposed. Such positions are blacklisted for the task. Baritone 1.19.0
 * has no position blacklist in its API, but its mine process reads the client world every tick and drops targets
 * that no longer match, so a blacklisted position whose fake ore is ever sent again (chunk re-sent) is put back to
 * the real block in the client world, which Baritone then ignores.
 */
final class FakeOreWatcher {
    private static final int SCAN_INTERVAL = 10;
    private static final int RADIUS = 5;
    private static final int MAX_WATCHED = 4096;
    private static final int MAX_BLACKLIST = 4096;
    /** Client-only change: update clients + skip neighbour shape updates. */
    private static final int CLIENT_SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final Set<Block> ores;
    private final Map<BlockPos, BlockState> watched = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> blacklist = new LinkedHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    FakeOreWatcher(Set<Block> ores) {
        this.ores = ores;
    }

    int fakeCount() {
        return blacklist.size();
    }

    void tick(ClientLevel level, BlockPos feet, int tick) {
        if (level == null || feet == null || tick % SCAN_INTERVAL != 0) {
            return;
        }
        checkWatched(level, feet);
        remask(level);
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS + 1; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (!level.isLoaded(cursor) || blacklist.containsKey(cursor)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(cursor);
                    if (ores.contains(st.getBlock()) && !watched.containsKey(cursor)) {
                        if (watched.size() >= MAX_WATCHED) {
                            Iterator<BlockPos> it = watched.keySet().iterator();
                            it.next();
                            it.remove();
                        }
                        watched.put(cursor.immutable(), st);
                    }
                }
            }
        }
    }

    /** Watched ores that became air were mined (or fell); ones that became another solid block were fake. */
    private void checkWatched(ClientLevel level, BlockPos feet) {
        Iterator<Map.Entry<BlockPos, BlockState>> it = watched.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockState> e = it.next();
            BlockPos pos = e.getKey();
            if (!level.isLoaded(pos)) {
                it.remove();
                continue;
            }
            BlockState now = level.getBlockState(pos);
            if (ores.contains(now.getBlock())) {
                if (pos.distManhattan(feet) > 64) {
                    it.remove();
                }
                continue;
            }
            it.remove();
            if (!now.isAir() && !(now.getBlock() instanceof LiquidBlock) && blacklist.size() < MAX_BLACKLIST) {
                blacklist.put(pos, now);
            }
        }
    }

    /** A blacklisted position that shows an ore again (re-obfuscated chunk) gets its known real block back. */
    private void remask(ClientLevel level) {
        for (Map.Entry<BlockPos, BlockState> e : blacklist.entrySet()) {
            BlockPos pos = e.getKey();
            if (level.isLoaded(pos) && ores.contains(level.getBlockState(pos).getBlock())) {
                level.setBlock(pos, e.getValue(), CLIENT_SET_FLAGS);
            }
        }
    }
}
