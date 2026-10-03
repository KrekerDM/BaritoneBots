package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.process.IBuilderProcess;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Positions;

/**
 * {@code selection op box}: MVP supports {@code op=clear} only (Baritone {@code clearArea}); builder inactive →
 * ok, builder paused → {@code stuck}. Other ops finish with {@code unsupported}.
 */
public final class SelectionTask implements TaskExecutor {
    private int inactive;

    @Override
    public void start(TaskContext ctx) {
        String op = Json.getString(ctx.args(), "op", "");
        if (!TaskTypes.SELECTION_OPS.contains(op)) {
            ctx.fail(Reasons.BAD_ARGS, "unknown selection op '" + op + "'", null);
            return;
        }
        if (!"clear".equals(op)) {
            ctx.fail(Reasons.UNSUPPORTED, "selection op '" + op + "' is not supported yet (only clear)", null);
            return;
        }
        Box box = Box.fromJson(ctx.args().get("box"));
        if (box == null) {
            ctx.fail(Reasons.BAD_ARGS, "selection needs 'box'", null);
            return;
        }
        ctx.baritone().getBuilderProcess().clearArea(Positions.toBlockPos(box.min()), Positions.toBlockPos(box.max()));
        ctx.step("clearing " + box.min() + " .. " + box.max(), -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        IBuilderProcess b = ctx.baritone().getBuilderProcess();
        if (b.isActive() && b.isPaused()) {
            ctx.fail(Reasons.STUCK, "Baritone builder is paused", null);
            return;
        }
        if (b.isActive()) {
            inactive = 0;
        } else if (++inactive >= 5) {
            ctx.succeed("area cleared", null);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getBuilderProcess().onLostControl();
        }
    }
}
