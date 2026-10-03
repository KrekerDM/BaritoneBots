package io.github.krekerdm.baritonebots.mod.task.impl;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.behaviour.Eater;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;

/**
 * {@code eat}: eats one allowed food item; {@code not_found} without food; ok "not hungry" when food is full.
 * The shared {@link Eater} is ticked by the eat behaviour; this task only starts it and waits for the result.
 */
public final class EatTask implements TaskExecutor {
    private boolean started;

    @Override
    public void start(TaskContext ctx) {
        if (!ctx.player().canEat(false)) {
            ctx.succeed("not hungry", Json.obj("food", ctx.player().getFoodData().getFoodLevel()));
            return;
        }
        if (!ctx.bot().eater.hasFood(ctx.player())) {
            ctx.fail(Reasons.NOT_FOUND, "no allowed food in inventory", null);
            return;
        }
        ctx.step("eating", -1);
        tryStart(ctx);
    }

    private void tryStart(TaskContext ctx) {
        Eater eater = ctx.bot().eater;
        if (eater.busy()) {
            return; // auto-eat is already eating; wait for it
        }
        if (eater.start(ctx.owner())) {
            started = true;
        } else if (eater.result(ctx.owner()) == Eater.Result.NO_FOOD) {
            ctx.fail(Reasons.NOT_FOUND, "no allowed food in inventory", null);
        }
    }

    @Override
    public void tick(TaskContext ctx) {
        Eater eater = ctx.bot().eater;
        if (!started) {
            if (ctx.ticks() > 200) {
                ctx.fail(Reasons.ERROR, "eater stayed busy", null);
            } else if (!eater.busy() && !ctx.player().canEat(false)) {
                ctx.succeed("ate (auto-eat)", Json.obj("food", ctx.player().getFoodData().getFoodLevel()));
            } else {
                tryStart(ctx);
            }
            return;
        }
        if (eater.busy()) {
            return;
        }
        Eater.Result r = eater.result(ctx.owner());
        if (r == Eater.Result.DONE) {
            ctx.succeed("ate", Json.obj("food", ctx.player().getFoodData().getFoodLevel()));
        } else {
            ctx.fail(Reasons.ERROR, "could not eat", null);
        }
    }
}
