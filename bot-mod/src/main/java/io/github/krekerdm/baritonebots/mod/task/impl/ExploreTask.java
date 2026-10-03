package io.github.krekerdm.baritonebots.mod.task.impl;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import net.minecraft.core.BlockPos;

/** {@code explore [x] [z]}: continuous Baritone explore from (x, z) or the current position. */
public final class ExploreTask implements TaskExecutor {
    private int inactive;

    @Override
    public void start(TaskContext ctx) {
        BlockPos feet = ctx.feet();
        int x = Json.getInt(ctx.args(), "x", feet.getX());
        int z = Json.getInt(ctx.args(), "z", feet.getZ());
        ctx.baritone().getExploreProcess().explore(x, z);
        ctx.step("exploring from " + x + " " + z, -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (ctx.baritone().getExploreProcess().isActive()) {
            inactive = 0;
        } else if (++inactive > 40) {
            ctx.fail(Reasons.PATH_FAILED, "Baritone explore stopped", null);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getExploreProcess().onLostControl();
        }
    }
}
