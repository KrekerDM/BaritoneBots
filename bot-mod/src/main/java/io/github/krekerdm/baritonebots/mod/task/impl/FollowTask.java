package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import net.minecraft.world.entity.player.Player;

/** {@code follow player [radius=3]}: continuous Baritone follow until cancelled or timed out. */
public final class FollowTask implements TaskExecutor {
    private Integer oldRadius;
    private String name;

    @Override
    public void start(TaskContext ctx) {
        name = Json.getString(ctx.args(), "player", null);
        if (name == null || name.isBlank()) {
            ctx.fail(Reasons.BAD_ARGS, "follow needs 'player'", null);
            return;
        }
        if (GotoPlayerTask.findPlayer(ctx, name) == null) {
            ctx.fail(Reasons.NOT_FOUND, "player " + name + " is not in tracking range", null);
            return;
        }
        Settings s = BaritoneAPI.getSettings();
        oldRadius = s.followRadius.value;
        s.followRadius.value = Math.max(1, Json.getInt(ctx.args(), "radius", 3));
        String target = name;
        ctx.baritone().getFollowProcess().follow(e -> e instanceof Player pl
                && pl.getGameProfile().name().equalsIgnoreCase(target));
        ctx.step("following " + name, -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (ctx.ticks() % 20 == 0) {
            boolean visible = GotoPlayerTask.findPlayer(ctx, name) != null;
            ctx.step(visible ? "following " + name : "waiting for " + name + " to come into range", -1);
            if (!ctx.baritone().getFollowProcess().isActive()) {
                String target = name;
                ctx.baritone().getFollowProcess().follow(e -> e instanceof Player pl
                        && pl.getGameProfile().name().equalsIgnoreCase(target));
            }
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getFollowProcess().cancel();
        }
        if (oldRadius != null) {
            BaritoneAPI.getSettings().followRadius.value = oldRadius;
        }
    }
}
