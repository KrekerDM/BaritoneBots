package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.ContainerSession;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.Map;

/**
 * {@code smelt_collect furnace [all]}: shift-clicks the result slot (and with {@code all} the input and fuel slots
 * too) of a furnace, blast furnace or smoker into the inventory. ok data {@code {collected:{id:count}}}, plus
 * {@code inventoryFull} when something had to stay.
 */
public final class SmeltCollectTask implements TaskExecutor {
    private static final int SYNC_TICKS = 3;
    private static final int MAX_ROUNDS = 3;

    private ContainerSession session;
    private boolean all;
    private Map<String, Integer> before;
    private int rounds;
    private int waitTicks = -1;

    @Override
    public void start(TaskContext ctx) {
        BlockPos pos = TaskArgs.pos(ctx.args(), "furnace");
        if (pos == null) {
            ctx.fail(Reasons.BAD_ARGS, "smelt_collect needs 'furnace'", null);
            return;
        }
        all = Json.getBool(ctx.args(), "all", false);
        before = Inv.totals(ctx.player(), false);
        session = new ContainerSession(pos);
    }

    @Override
    public void tick(TaskContext ctx) {
        session.tick(ctx);
        if (session.failed()) {
            session.close(ctx);
            ctx.fail(session.failReason(), session.failMessage(), data(ctx.player(), false));
            return;
        }
        if (!session.idle()) {
            return;
        }
        LocalPlayer p = ctx.player();
        if (!(session.menu() instanceof AbstractFurnaceMenu menu)) {
            session.close(ctx);
            ctx.fail(Reasons.CONTAINER_FAILED, "the block at " + session.pos().toShortString()
                    + " is not a furnace, blast furnace or smoker", data(p, false));
            return;
        }
        if (waitTicks >= 0 && ++waitTicks < SYNC_TICKS) {
            return;
        }
        boolean left = false;
        boolean queued = false;
        for (int idx : all ? new int[]{AbstractFurnaceMenu.RESULT_SLOT, AbstractFurnaceMenu.INGREDIENT_SLOT,
                AbstractFurnaceMenu.FUEL_SLOT} : new int[]{AbstractFurnaceMenu.RESULT_SLOT}) {
            Slot s = menu.getSlot(idx);
            if (!s.getItem().isEmpty()) {
                left = true;
                if (rounds < MAX_ROUNDS) {
                    session.click(s, 0, ContainerInput.QUICK_MOVE);
                    queued = true;
                }
            }
        }
        if (queued) {
            rounds++;
            waitTicks = 0;
            ctx.step("collecting from " + session.pos().toShortString(), -1);
            return;
        }
        session.close(ctx);
        ctx.succeed("collected", data(p, left));
    }

    private JsonObject data(LocalPlayer p, boolean inventoryFull) {
        JsonObject d = Json.obj("collected", TaskArgs.counts(p == null ? Map.of() : Inv.gained(before,
                Inv.totals(p, false))));
        if (inventoryFull) {
            d.addProperty("inventoryFull", true);
        }
        return d;
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
        }
    }
}
