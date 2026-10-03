package io.github.krekerdm.baritonebots.mod.task;

import baritone.api.pathing.goals.GoalGetToBlock;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.behaviour.ContainerSensor;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Opens one container for a task: path next to the block ({@link GoalGetToBlock}), look at it and
 * {@code useItemOn}, wait up to {@value #OPEN_WAIT_TICKS} ticks for a non-inventory menu (2 retries), let the
 * contents sync for 3 ticks, then accept clicks (at most 2 per tick through {@link ClickQueue}). Holds the task's
 * pause claim from opening until {@link #close}.
 */
public final class ContainerSession {
    public enum Phase { PATH, OPEN, WAIT, SETTLE, READY, CLOSED, FAILED }

    private static final int OPEN_WAIT_TICKS = 60;
    private static final int MAX_RETRIES = 2;
    private static final int SETTLE_TICKS = 3;

    private final BlockPos pos;
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
        this.pos = pos.immutable();
    }

    public BlockPos pos() {
        return pos;
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

    public void click(Slot slot, int button, ContainerInput input) {
        clicks.add(menu, slot.index, button, input);
    }

    /**
     * Moves {@code n} items from {@code from} to the empty slot {@code to} with the fewest clicks: pick up the
     * stack, then place single items with right clicks either into {@code to} or back into {@code from}.
     */
    public void movePartial(Slot from, Slot to, int n) {
        ItemStack s = from.getItem();
        int count = s.getCount();
        if (n <= 0) {
            return;
        }
        if (n >= count) {
            clicks.add(menu, from.index, 0, ContainerInput.PICKUP);
            clicks.add(menu, to.index, 0, ContainerInput.PICKUP);
            return;
        }
        int keep = count - n;
        clicks.add(menu, from.index, 0, ContainerInput.PICKUP);
        if (n <= keep) {
            for (int i = 0; i < n; i++) {
                clicks.add(menu, to.index, 1, ContainerInput.PICKUP);
            }
            clicks.add(menu, from.index, 0, ContainerInput.PICKUP);
        } else {
            for (int i = 0; i < keep; i++) {
                clicks.add(menu, from.index, 1, ContainerInput.PICKUP);
            }
            clicks.add(menu, to.index, 0, ContainerInput.PICKUP);
        }
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

    /** Closes the menu (if open) and releases the pause claim so the bot can walk again. */
    public void close(TaskContext ctx) {
        clicks.clear();
        LocalPlayer p = ctx.player();
        if (p != null && menu != null && p.containerMenu == menu) {
            p.closeContainer();
        }
        ctx.bot().pause.release(ctx.owner());
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
