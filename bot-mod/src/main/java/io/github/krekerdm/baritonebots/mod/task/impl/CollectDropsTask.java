package io.github.krekerdm.baritonebots.mod.task.impl;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;

/** {@code collect_drops [radius=8] [items]}: picks up matching item entities; none left → ok (data.picked). */
public final class CollectDropsTask implements TaskExecutor {
    private DropCollector collector;

    @Override
    public void start(TaskContext ctx) {
        int radius = Math.max(1, Json.getInt(ctx.args(), "radius", 8));
        collector = new DropCollector(ctx.feet(), radius, TaskArgs.globs(ctx.args(), "items"));
        collector.start(ctx);
        ctx.step("collecting drops", -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (collector.tick(ctx) == DropCollector.Status.DONE) {
            ctx.succeed("no drops left", Json.obj("picked", TaskArgs.counts(collector.picked(ctx))));
        } else if (ctx.ticks() % 20 == 0) {
            ctx.step("collecting drops (" + collector.remaining(ctx) + " left)", -1);
        }
    }
}
