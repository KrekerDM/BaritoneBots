package io.github.krekerdm.baritonebots.mod.task.impl;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * {@code farm center [range=20] [durationSec=0]}: Baritone FarmProcess. Duration elapsed or nothing left to do →
 * ok (data.collected); {@code inventory_full} when free slots drop to the configured minimum.
 */
public final class FarmTask implements TaskExecutor {
    private int durationSec;
    private Map<String, Integer> before;
    private int inactive;

    @Override
    public void start(TaskContext ctx) {
        BlockPos center = TaskArgs.pos(ctx.args(), "center");
        if (center == null) {
            center = ctx.feet();
        }
        int range = Math.max(1, Json.getInt(ctx.args(), "range", 20));
        durationSec = Math.max(0, Json.getInt(ctx.args(), "durationSec", 0));
        before = Inv.totals(ctx.player(), false);
        ctx.baritone().getFarmProcess().farm(range, center);
        ctx.step("farming around " + center.toShortString(), durationSec > 0 ? 0 : -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        Map<String, Integer> collected = Inv.gained(before, Inv.totals(ctx.player(), false));
        if (durationSec > 0 && ctx.elapsedMs() >= durationSec * 1000L) {
            ctx.succeed("farm duration elapsed", Json.obj("collected", TaskArgs.counts(collected)));
            return;
        }
        if (ctx.ticks() % 20 == 0) {
            int free = Inv.freeSlots(ctx.player());
            if (free <= ctx.bot().config().behaviour().inventoryFullFreeSlots()) {
                ctx.bot().event(EventKinds.INVENTORY_FULL, Levels.WARN, "inventory full while farming",
                        Json.obj("freeSlots", free));
                ctx.fail(Reasons.INVENTORY_FULL, "inventory full", Json.obj("collected", TaskArgs.counts(collected)));
                return;
            }
            if (durationSec > 0) {
                ctx.step(ctx.stepName(), Math.min(1, ctx.elapsedMs() / (durationSec * 1000.0)));
            }
        }
        if (ctx.baritone().getFarmProcess().isActive()) {
            inactive = 0;
        } else if (++inactive >= 5) {
            ctx.succeed("nothing left to farm", Json.obj("collected", TaskArgs.counts(collected)));
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getFarmProcess().onLostControl();
        }
    }
}
