package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.pathing.goals.GoalNear;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.PathStep;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.core.BlockPos;

import java.util.List;

/** {@code recover pos dim [radius=6]}: goto then collect_drops around {@code pos}; ok data {@code picked}. */
public final class RecoverTask implements TaskExecutor {
    private final PathStep path = new PathStep();
    private BlockPos pos;
    private int radius;
    private DropCollector collector;

    @Override
    public void start(TaskContext ctx) {
        pos = TaskArgs.pos(ctx.args(), "pos");
        if (pos == null) {
            ctx.fail(Reasons.BAD_ARGS, "recover needs 'pos'", null);
            return;
        }
        String dim = Json.getString(ctx.args(), "dim", null);
        String here = McIds.dim(ctx.bot().level());
        if (dim != null && !Ids.normalize(dim).equals(here)) {
            ctx.fail(Reasons.NOT_FOUND, "drops are in " + dim + " but the bot is in " + here, null);
            return;
        }
        radius = Math.max(1, Json.getInt(ctx.args(), "radius", 6));
        if (ctx.feet().closerThan(pos, radius)) {
            beginCollect(ctx);
        } else {
            ctx.step("walking to " + pos.toShortString(), -1);
            path.start(ctx, new GoalNear(pos, 2));
        }
    }

    private void beginCollect(TaskContext ctx) {
        collector = new DropCollector(pos, radius, List.of());
        collector.start(ctx);
        ctx.step("collecting drops at " + pos.toShortString(), -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (collector == null) {
            PathStep.Status st = path.tick(ctx);
            if (st == PathStep.Status.ARRIVED) {
                beginCollect(ctx);
            } else if (st == PathStep.Status.FAILED) {
                ctx.fail(Reasons.PATH_FAILED, "cannot reach " + pos.toShortString(), null);
            }
            return;
        }
        if (collector.tick(ctx) == DropCollector.Status.DONE) {
            ctx.succeed("recovered", Json.obj("picked", TaskArgs.counts(collector.picked(ctx))));
        }
    }
}
