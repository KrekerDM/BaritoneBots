package io.github.krekerdm.baritonebots.mod.baritone;

import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Keeps Baritone paused while any owner holds a claim (SPEC §4.3). Used by behaviours and tasks that need the
 * player to stand still: eating, melee in range, container clicks.
 * <p>
 * The process is temporary, so the processes below it (mine, goto, build ...) are only suspended, not cancelled,
 * and resume when the last claim is released. Client thread only.
 */
public final class PauseProcess implements IBaritoneProcess {
    /** Above every built-in Baritone process (they use {@link IBaritoneProcess#DEFAULT_PRIORITY} or small values). */
    public static final double PRIORITY = 1000;
    /** Claim-owner suffix of a task that is clicking in the player's own inventory menu. */
    public static final String INVENTORY_SUFFIX = ":inventory";

    private final Set<String> owners = new LinkedHashSet<>();

    /** Adds a claim; claiming twice with the same owner is a no-op. */
    public void claim(String owner) {
        owners.add(owner);
    }

    public void release(String owner) {
        owners.remove(owner);
    }

    /** Drops every claim whose owner starts with {@code prefix} (e.g. {@code "task:"}). */
    public void releaseByPrefix(String prefix) {
        owners.removeIf(o -> o.startsWith(prefix));
    }

    public void releaseAll() {
        owners.clear();
    }

    public boolean isClaimed() {
        return !owners.isEmpty();
    }

    public boolean isClaimedBy(String owner) {
        return owners.contains(owner);
    }

    /**
     * True while a task clicks in the player's own inventory menu (claim owner ending in {@link #INVENTORY_SUFFIX});
     * behaviours must not send inventory clicks (food or weapon swaps) in between.
     */
    public boolean inventoryBusy() {
        for (String o : owners) {
            if (o.endsWith(INVENTORY_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isActive() {
        return !owners.isEmpty();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        // Claims outlive Baritone's cancelEverything(): the owners release them when they are done.
    }

    @Override
    public double priority() {
        return PRIORITY;
    }

    @Override
    public String displayName0() {
        return "BaritoneBots pause " + owners;
    }
}
