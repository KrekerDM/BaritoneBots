package io.github.krekerdm.baritonebots.mod.task;

import baritone.api.pathing.goals.GoalGetToBlock;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.baritone.PauseProcess;
import io.github.krekerdm.baritonebots.mod.behaviour.ContainerSensor;
import io.github.krekerdm.baritonebots.mod.task.plan.SlotMoves;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens one container for a task: path next to the block ({@link GoalGetToBlock}), look at it and
 * {@code useItemOn}, wait up to {@value #OPEN_WAIT_TICKS} ticks for a non-inventory menu (2 retries), let the
 * contents sync for 3 ticks, then accept clicks (at most 2 per tick through {@link ClickQueue}). Holds the task's
 * pause claim from opening until {@link #close}.
 * <p>
 * {@link #playerInventory()} drives the player's own {@code InventoryMenu} (2×2 crafting, dropping) the same way:
 * any open container is closed, the pause is claimed, and {@link #close} leaves the inventory menu alone.
 */
public final class ContainerSession {
    public enum Phase { PATH, OPEN, WAIT, SETTLE, READY, CLOSED, FAILED }

    private static final int OPEN_WAIT_TICKS = 60;
    private static final int MAX_RETRIES = 2;
    private static final int SETTLE_TICKS = 3;

    private final BlockPos pos;
    private final boolean ownInventory;
    private final ClickQueue clicks = new ClickQueue();
    private final PathStep path = new PathStep();
    private boolean pathStarted;
    private Phase phase = Phase.PATH;
    private int waitTicks;
    private int retries;
    private AbstractContainerMenu menu;
    private String failReason;
    private String failMessage;

    public ContainerSession(BlockPos pos) {
        this(pos.immutable(), false);
    }

    private ContainerSession(BlockPos pos, boolean ownInventory) {
        this.pos = pos;
        this.ownInventory = ownInventory;
        this.phase = ownInventory ? Phase.OPEN : Phase.PATH;
    }

    /** A session on the player's own inventory menu (no block, no walking). */
    public static ContainerSession playerInventory() {
        return new ContainerSession(null, true);
    }

    /** Container position, or {@code null} for {@link #playerInventory()}. */
    public BlockPos pos() {
        return pos;
    }

    public boolean ownInventory() {
        return ownInventory;
    }

    public Phase phase() {
        return phase;
    }

    public boolean failed() {
        return phase == Phase.FAILED;
    }

    public String failReason() {
        return failReason;
    }

    public String failMessage() {
        return failMessage;
    }

    /** Open, synced and no clicks pending: the task may inspect the menu and plan the next operation. */
    public boolean idle() {
        return phase == Phase.READY && clicks.isEmpty();
    }

    public AbstractContainerMenu menu() {
        return menu;
    }

    public List<Slot> containerSlots(LocalPlayer p) {
        return ContainerSensor.containerSlots(p, menu);
    }

    public List<Slot> playerSlots(LocalPlayer p) {
        return ContainerSensor.playerSlots(p, menu);
    }

    /** Player main + hotbar slots of the menu (no armor, offhand or crafting slots). */
    public List<Slot> mainSlots(LocalPlayer p) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : playerSlots(p)) {
            if (s.getContainerSlot() < Inv.MAIN_SIZE) {
                out.add(s);
            }
        }
        return out;
    }

    public void click(Slot slot, int button, ContainerInput input) {
        clicks.add(menu, slot.index, button, input);
    }

    /** Click by menu slot id ({@code -999} = outside the window). */
    public void click(int slotId, int button, ContainerInput input) {
        clicks.add(menu, slotId, button, input);
    }

    /**
     * Moves {@code n} items from {@code from} to {@code to} (empty, or the same item with room for {@code n}) with
     * the fewest clicks: pick up the stack, then place single items with right clicks either into {@code to} or
     * back into {@code from} ({@link SlotMoves#partial}).
     */
    public void movePartial(Slot from, Slot to, int n) {
        for (SlotMoves.Click c : SlotMoves.partial(from.getItem().getCount(), n)) {
            Slot target = c.target() == SlotMoves.Target.SOURCE ? from : to;
            clicks.add(menu, target.index, c.button(), ContainerInput.PICKUP);
        }
    }

    /**
     * Queues a click that puts the cursor stack back into the player's main inventory (a stack of the same item
     * with room first, else an empty slot). Returns {@code false} when there is nowhere to put it.
     */
    public boolean returnCarried(LocalPlayer p) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return true;
        }
        Slot target = null;
        for (Slot s : mainSlots(p)) {
            ItemStack st = s.getItem();
            if (!st.isEmpty() && ItemStack.isSameItemSameComponents(st, carried)
                    && st.getCount() + carried.getCount() <= st.getMaxStackSize()) {
                target = s;
                break;
            }
        }
        if (target == null) {
            for (Slot s : mainSlots(p)) {
                if (s.getItem().isEmpty()) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        clicks.add(menu, target.index, 0, ContainerInput.PICKUP);
        return true;
    }

    public void tick(TaskContext ctx) {
        LocalPlayer p = ctx.player();
        if (p == null) {
            fail(Reasons.DISCONNECTED, "not in game");
            return;
        }
        switch (phase) {
            case PATH -> tickPath(ctx, p);
            case OPEN -> open(ctx, p);
            case WAIT -> {
                if (Interact.containerOpen(p)) {
                    menu = p.containerMenu;
                    waitTicks = 0;
                    phase = Phase.SETTLE;
                } else if (++waitTicks > OPEN_WAIT_TICKS) {
                    if (retries++ < MAX_RETRIES) {
                        phase = Phase.OPEN;
                    } else {
                        fail(Reasons.CONTAINER_FAILED, "container at " + pos.toShortString() + " did not open");
                    }
                }
            }
            case SETTLE -> {
                if (p.containerMenu != menu) {
                    fail(Reasons.CONTAINER_FAILED, "container closed while opening");
                } else if (++waitTicks >= SETTLE_TICKS) {
                    phase = Phase.READY;
                }
            }
            case READY -> {
                if (p.containerMenu != menu || !clicks.tick(ctx.mc(), p)) {
                    fail(Reasons.CONTAINER_FAILED, "container closed unexpectedly");
                }
            }
            default -> {
            }
        }
    }

    private void tickPath(TaskContext ctx, LocalPlayer p) {
        if (Interact.eyeDistanceTo(p, pos) <= Interact.BLOCK_REACH) {
            phase = Phase.OPEN;
            open(ctx, p);
            return;
        }
        if (!pathStarted) {
            pathStarted = true;
            ctx.step("walking to container " + pos.toShortString(), -1);
            path.start(ctx, new GoalGetToBlock(pos));
            return;
        }
        PathStep.Status st = path.tick(ctx);
        if (st == PathStep.Status.ARRIVED) {
            phase = Phase.OPEN;
        } else if (st == PathStep.Status.FAILED) {
            fail(Reasons.PATH_FAILED, "cannot reach container at " + pos.toShortString());
        }
    }

    private void open(TaskContext ctx, LocalPlayer p) {
        if (ownInventory) {
            if (ctx.bot().eater.busy()) {
                ctx.step("waiting for eating to finish", -1);
                return; // the eater swaps food into the hotbar; start clicking after it
            }
            if (Interact.containerOpen(p)) {
                p.closeContainer();
            }
            ctx.bot().pause.claim(ctx.owner());
            ctx.bot().pause.claim(ctx.owner() + PauseProcess.INVENTORY_SUFFIX); // keeps auto-eat/defense swaps out
            ctx.mc().options.keyUse.setDown(false);
            menu = p.inventoryMenu;
            waitTicks = 0;
            phase = Phase.SETTLE;
            return;
        }
        if (ctx.bot().level() == null || !ctx.bot().level().isLoaded(pos)) {
            fail(Reasons.NOT_FOUND, "container chunk at " + pos.toShortString() + " is not loaded");
            return;
        }
        if (ctx.bot().level().getBlockState(pos).isAir()) {
            fail(Reasons.NOT_FOUND, "no container at " + pos.toShortString());
            return;
        }
        if (Interact.containerOpen(p)) {
            p.closeContainer();
        }
        ctx.bot().pause.claim(ctx.owner());
        ctx.mc().options.keyUse.setDown(false);
        p.setShiftKeyDown(false);
        ctx.step("opening container " + pos.toShortString(), -1);
        Interact.useOnBlock(ctx.mc(), p, pos);
        ctx.bot().containers.noteUse(pos);
        waitTicks = 0;
        phase = Phase.WAIT;
    }

    /**
     * Closes the menu (if open; the player's own inventory menu stays) and releases the pause claim so the bot can
     * walk again.
     */
    public void close(TaskContext ctx) {
        clicks.clear();
        LocalPlayer p = ctx.player();
        if (!ownInventory && p != null && menu != null && p.containerMenu == menu) {
            p.closeContainer();
        }
        ctx.bot().pause.release(ctx.owner());
        ctx.bot().pause.release(ctx.owner() + PauseProcess.INVENTORY_SUFFIX);
        if (phase != Phase.FAILED) {
            phase = Phase.CLOSED;
        }
    }

    private void fail(String reason, String message) {
        phase = Phase.FAILED;
        failReason = reason;
        failMessage = message;
        clicks.clear();
    }
}
