package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Container sensing (SPEC §4.5): when a non-inventory menu opens within 5 s of the bot using a block
 * ({@link #noteUse}), sends a {@code container} snapshot {@value Protocol#CONTAINER_SNAPSHOT_DELAY_TICKS} ticks
 * later and again (open=false) when it closes. Container slots are the menu slots whose container is not the
 * player's inventory; {@code slot} in the snapshot is the index among those.
 */
public final class ContainerSensor {
    private static final int USE_TO_OPEN_TICKS = 100;

    private final BotRuntime bot;
    private BlockPos lastUsePos;
    private String lastUseDim;
    private long lastUseTick = -10_000;
    private AbstractContainerMenu tracked;
    private BlockPos trackedPos;
    private String trackedDim;
    private long snapshotDueTick = -1;
    private long snapshotsSent;

    public ContainerSensor(BotRuntime bot) {
        this.bot = bot;
    }

    /** Remember the block the bot just right-clicked. */
    public void noteUse(BlockPos pos) {
        lastUsePos = pos.immutable();
        lastUseDim = bot.level() == null ? null : McIds.dim(bot.level());
        lastUseTick = bot.ticks();
    }

    /** Position of the block whose menu is open (and was opened by the bot), or {@code null}. */
    public BlockPos openPos() {
        return tracked != null ? trackedPos : null;
    }

    /** Number of open snapshots sent so far; tasks wait for it to change. */
    public long snapshotsSent() {
        return snapshotsSent;
    }

    public void tick() {
        LocalPlayer p = bot.player();
        if (p == null || bot.level() == null) {
            tracked = null;
            return;
        }
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != null && menu != p.inventoryMenu) {
            if (menu != tracked) {
                closeTracked();
                tracked = menu;
                boolean ours = lastUsePos != null && bot.ticks() - lastUseTick <= USE_TO_OPEN_TICKS;
                trackedPos = ours ? lastUsePos : null;
                trackedDim = ours ? lastUseDim : null;
                snapshotDueTick = ours ? bot.ticks() + Protocol.CONTAINER_SNAPSHOT_DELAY_TICKS : -1;
                lastUsePos = null;
            }
            if (snapshotDueTick >= 0 && bot.ticks() >= snapshotDueTick) {
                snapshotDueTick = -1;
                bot.sendContainer(snapshot(p, menu, trackedPos, trackedDim, true));
                snapshotsSent++;
            }
        } else {
            closeTracked();
        }
    }

    private void closeTracked() {
        LocalPlayer p = bot.player();
        if (tracked != null && trackedPos != null && p != null) {
            bot.sendContainer(snapshot(p, tracked, trackedPos, trackedDim, false));
        }
        tracked = null;
        trackedPos = null;
        snapshotDueTick = -1;
    }

    /** Container slots of a menu (not the player's own inventory). */
    public static List<Slot> containerSlots(LocalPlayer p, AbstractContainerMenu menu) {
        Inventory inv = p.getInventory();
        List<Slot> out = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container != inv) {
                out.add(s);
            }
        }
        return out;
    }

    /** Player-inventory slots of a menu. */
    public static List<Slot> playerSlots(LocalPlayer p, AbstractContainerMenu menu) {
        Inventory inv = p.getInventory();
        List<Slot> out = new ArrayList<>();
        for (Slot s : menu.slots) {
            if (s.container == inv) {
                out.add(s);
            }
        }
        return out;
    }

    public ContainerSnapshot snapshot(LocalPlayer p, AbstractContainerMenu menu, BlockPos pos, String dim,
                                      boolean open) {
        List<Slot> slots = containerSlots(p, menu);
        List<ContainerSnapshot.SlotItem> items = new ArrayList<>();
        int free = 0;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack s = slots.get(i).getItem();
            if (s.isEmpty()) {
                free++;
            } else {
                items.add(new ContainerSnapshot.SlotItem(i, McIds.item(s), s.getCount()));
            }
        }
        String block = pos != null && bot.level() != null ? McIds.block(bot.level().getBlockState(pos)) : null;
        return new ContainerSnapshot(dim, pos == null ? null : Positions.toPos(pos), block, slots.size(), free, items,
                System.currentTimeMillis(), open);
    }
}
