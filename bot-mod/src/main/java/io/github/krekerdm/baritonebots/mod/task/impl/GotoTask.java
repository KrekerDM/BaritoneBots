package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.PathStep;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.core.BlockPos;

/** {@code goto x z [y] [range=2]}: in goal → ok; Baritone stopped elsewhere → {@code path_failed}. */
public final class GotoTask implements TaskExecutor {
    private final PathStep path = new PathStep();
    private double startDist;
    private int tx;
    private int tz;

    @Override
    public void start(TaskContext ctx) {
        JsonObject a = ctx.args();
        if (!a.has("x") || !a.has("z")) {
            ctx.fail(Reasons.BAD_ARGS, "goto needs x and z", null);
            return;
        }
        tx = Json.getInt(a, "x", 0);
        tz = Json.getInt(a, "z", 0);
        int range = Math.max(0, Json.getInt(a, "range", 2));
        Goal goal;
        if (a.has("y")) {
            BlockPos target = new BlockPos(tx, Json.getInt(a, "y", 64), tz);
            goal = range == 0 ? new GoalBlock(target) : new GoalNear(target, range);
        } else {
            goal = range == 0 ? new GoalXZ(tx, tz) : new PathStep.GoalNearXZ(tx, tz, range);
        }
        BlockPos feet = ctx.feet();
        startDist = Math.max(1, Math.hypot(feet.getX() - tx, feet.getZ() - tz));
        if (goal.isInGoal(feet)) {
            ctx.succeed("already there", Json.obj("pos", Positions.json(feet)));
            return;
        }
        ctx.step("walking to " + tx + " " + tz, 0);
        path.start(ctx, goal);
    }

    @Override
    public void tick(TaskContext ctx) {
        PathStep.Status st = path.tick(ctx);
        BlockPos feet = ctx.feet();
        if (st == PathStep.Status.ARRIVED) {
            ctx.succeed("arrived", Json.obj("pos", Positions.json(feet)));
        } else if (st == PathStep.Status.FAILED) {
            ctx.fail(Reasons.PATH_FAILED, "Baritone stopped before reaching the goal",
                    feet == null ? null : Json.obj("pos", Positions.json(feet)));
        } else if (feet != null && ctx.ticks() % 20 == 0) {
            double d = Math.hypot(feet.getX() - tx, feet.getZ() - tz);
            ctx.step("walking to " + tx + " " + tz, Math.max(0, Math.min(1, 1 - d / startDist)));
        }
    }
}
