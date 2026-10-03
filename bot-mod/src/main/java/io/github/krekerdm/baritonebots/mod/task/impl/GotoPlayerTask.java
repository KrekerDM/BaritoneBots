package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.pathing.goals.GoalNear;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.PathStep;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/** {@code goto_player player [range=3]}: ok on arrival; {@code not_found} if the player is not tracked. */
public final class GotoPlayerTask implements TaskExecutor {
    private final PathStep path = new PathStep();
    private String name;
    private int range;
    private BlockPos lastTarget;

    /** A tracked player (not the bot) with this name, ignoring case; {@code null} if none. */
    static Player findPlayer(TaskContext ctx, String name) {
        LocalPlayer self = ctx.player();
        if (ctx.bot().level() == null || name == null) {
            return null;
        }
        for (Player pl : ctx.bot().level().players()) {
            if (pl != self && pl.getGameProfile().name().equalsIgnoreCase(name)) {
                return pl;
            }
        }
        return null;
    }

    @Override
    public void start(TaskContext ctx) {
        name = Json.getString(ctx.args(), "player", null);
        range = Math.max(1, Json.getInt(ctx.args(), "range", 3));
        if (name == null || name.isBlank()) {
            ctx.fail(Reasons.BAD_ARGS, "goto_player needs 'player'", null);
            return;
        }
        Player target = findPlayer(ctx, name);
        if (target == null) {
            ctx.fail(Reasons.NOT_FOUND, "player " + name + " is not in tracking range", null);
            return;
        }
        if (ctx.player().distanceTo(target) <= range + 0.5) {
            ctx.succeed("already next to " + name, Json.obj("pos", Positions.json(ctx.feet())));
            return;
        }
        retarget(ctx, target);
    }

    private void retarget(TaskContext ctx, Player target) {
        lastTarget = target.blockPosition();
        ctx.step("walking to " + name, -1);
        path.start(ctx, new GoalNear(lastTarget, range));
    }

    @Override
    public void tick(TaskContext ctx) {
        Player target = findPlayer(ctx, name);
        if (target == null) {
            ctx.fail(Reasons.NOT_FOUND, "lost track of player " + name, null);
            return;
        }
        if (ctx.player().distanceTo(target) <= range + 0.5) {
            ctx.succeed("arrived at " + name, Json.obj("pos", Positions.json(ctx.feet())));
            return;
        }
        if (ctx.ticks() % 20 == 0 && target.blockPosition().distManhattan(lastTarget) > 2) {
            retarget(ctx, target);
            return;
        }
        if (path.tick(ctx) == PathStep.Status.FAILED) {
            ctx.fail(Reasons.PATH_FAILED, "cannot reach " + name, null);
        }
    }
}
