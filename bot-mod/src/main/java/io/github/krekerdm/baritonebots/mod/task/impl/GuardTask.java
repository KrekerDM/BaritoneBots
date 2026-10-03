package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.pathing.goals.GoalNear;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.behaviour.Eater;
import io.github.krekerdm.baritonebots.mod.task.PathStep;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code guard center [radius=12]} (continuous): attacks hostile mobs inside {@code radius} of {@code center} with
 * {@link Fighter}, walks back with {@link PathStep} when it ends up outside, and eats with the shared {@link Eater}
 * when hungry if auto-eat is off (auto-eat handles it otherwise). Runs until cancelled or timed out; fails with
 * {@code path_failed} after {@value #MAX_RETURN_FAILS} failed attempts to get back. Unreachable targets are ignored
 * for {@value #UNREACHABLE_RESET_TICKS} ticks.
 */
public final class GuardTask implements TaskExecutor {
    private static final int RESCAN_TICKS = 5;
    private static final int MAX_RETURN_FAILS = 5;
    private static final int RETURN_RETRY_TICKS = 100;
    private static final int UNREACHABLE_RESET_TICKS = 600;
    private static final int EAT_BELOW_FOOD = 14;

    private final Fighter fighter = new Fighter();
    private final PathStep path = new PathStep();
    private final Set<Integer> unreachable = new HashSet<>();
    private BlockPos center;
    private int radius;
    private LivingEntity target;
    private boolean returning;
    private int returnFails;
    private int retryAt;

    @Override
    public void start(TaskContext ctx) {
        center = TaskArgs.pos(ctx.args(), "center");
        if (center == null) {
            ctx.fail(Reasons.BAD_ARGS, "guard needs 'center'", null);
            return;
        }
        radius = Math.max(1, Math.min(64, Json.getInt(ctx.args(), "radius", 12)));
        ctx.step("guarding " + center.toShortString(), -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        LocalPlayer p = ctx.player();
        if (ctx.ticks() % UNREACHABLE_RESET_TICKS == 0) {
            unreachable.clear();
        }
        if (target == null || !target.isAlive() || target.isRemoved() || ctx.ticks() % RESCAN_TICKS == 0) {
            LivingEntity next = Fighter.pickTarget(ctx.bot().level(), p, center, radius, List.of(), unreachable);
            if (next != target) {
                fighter.stop(ctx);
                target = next;
            }
        }
        if (ctx.bot().eater.busy() || ctx.bot().defense.engaged()) {
            fighter.releaseHold(ctx);
            return;
        }
        if (target == null && maybeEat(ctx, p)) {
            return;
        }
        if (target != null) {
            stopReturning(ctx);
            if (fighter.engage(ctx, target) == Fighter.Result.UNREACHABLE) {
                unreachable.add(target.getId());
                fighter.stop(ctx);
                target = null;
            }
            return;
        }
        double dx = p.getX() - (center.getX() + 0.5);
        double dz = p.getZ() - (center.getZ() + 0.5);
        boolean outside = dx * dx + dz * dz > (double) radius * radius;
        if (returning) {
            PathStep.Status st = path.tick(ctx);
            if (st == PathStep.Status.ARRIVED) {
                returning = false;
                returnFails = 0;
                ctx.step("guarding " + center.toShortString(), -1);
            } else if (st == PathStep.Status.FAILED) {
                returning = false;
                retryAt = ctx.ticks() + RETURN_RETRY_TICKS;
                if (++returnFails >= MAX_RETURN_FAILS) {
                    ctx.fail(Reasons.PATH_FAILED, "cannot get back to the guard area",
                            Json.obj("center", Positions.json(center)));
                }
            }
        } else if (outside && ctx.ticks() >= retryAt) {
            returning = true;
            ctx.step("returning to " + center.toShortString(), -1);
            path.start(ctx, new GoalNear(center, Math.max(1, Math.min(4, radius / 2))));
        }
    }

    private void stopReturning(TaskContext ctx) {
        if (returning) {
            returning = false;
            ctx.baritone().getPathingBehavior().cancelEverything();
        }
    }

    /** Eats when auto-eat is off and the bot is hungry; true while eating was started. */
    private boolean maybeEat(TaskContext ctx, LocalPlayer p) {
        BotConfig.AutoEat cfg = ctx.bot().config().behaviour().autoEat();
        if (cfg.enabled() || ctx.ticks() % 20 != 0 || !p.canEat(false)) {
            return false;
        }
        int food = p.getFoodData().getFoodLevel();
        if (food >= EAT_BELOW_FOOD && !(p.getHealth() < p.getMaxHealth() / 2 && food < 20)) {
            return false;
        }
        Eater eater = ctx.bot().eater;
        if (!eater.hasFood(p)) {
            return false;
        }
        fighter.stop(ctx);
        stopReturning(ctx);
        ctx.step("eating", -1);
        return eater.start(ctx.owner());
    }

    @Override
    public void cleanup(TaskContext ctx) {
        fighter.stop(ctx);
    }
}
