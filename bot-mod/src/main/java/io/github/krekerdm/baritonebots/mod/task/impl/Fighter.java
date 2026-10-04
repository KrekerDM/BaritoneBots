package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Melee helper for {@code attack} and {@code guard}: walks to the target with Baritone's custom goal process
 * ({@code GoalNear(target, 1)}, re-planned when the target moves), and once the target's hitbox is within
 * {@link Interact#ENTITY_REACH} of the eyes holds a pause claim ({@code <owner>:fight}), selects the best weapon
 * (same rule as the defense behaviour) and hits when the attack is charged (≥ 0.9). A target the path finder
 * fails to reach three times is reported {@link Result#UNREACHABLE}.
 */
final class Fighter {
    enum Result { APPROACHING, IN_REACH, ATTACKING, UNREACHABLE }

    private static final int PATH_GRACE_TICKS = 6;
    private static final int MAX_PATH_FAILS = 3;
    private static final int MAX_ARRIVED_NO_REACH = 10;

    private LivingEntity target;
    private BlockPos goalPos;
    private int pathTick;
    private int pathFails;
    private int arrivedNoReach;
    private boolean holding;
    private boolean pathing;

    static String claimOwner(TaskContext ctx) {
        return ctx.owner() + ":fight";
    }

    Result engage(TaskContext ctx, LivingEntity t) {
        Result r = approach(ctx, t);
        if (r != Result.IN_REACH) {
            return r;
        }
        LocalPlayer p = ctx.player();
        ctx.bot().defense.equipWeapon(p);
        Interact.lookAt(p, t.getBoundingBox().getCenter());
        if (p.getAttackStrengthScale(0.5f) >= 0.9f) {
            Interact.attack(ctx.mc(), p, t);
        }
        ctx.step("attacking " + McIds.entity(t), -1);
        return Result.ATTACKING;
    }

    /**
     * Walks towards {@code t}; once its hitbox is in reach, holds the pause claim and returns {@link Result#IN_REACH}
     * (used directly by tasks that interact with animals instead of hitting them).
     */
    Result approach(TaskContext ctx, LivingEntity t) {
        LocalPlayer p = ctx.player();
        if (t != target) {
            target = t;
            goalPos = null;
            pathFails = 0;
            arrivedNoReach = 0;
        }
        if (inReach(p, t)) {
            if (!holding) {
                ctx.bot().pause.claim(claimOwner(ctx));
                holding = true;
            }
            return Result.IN_REACH;
        }
        releaseHold(ctx);
        ICustomGoalProcess cg = ctx.baritone().getCustomGoalProcess();
        BlockPos tp = t.blockPosition();
        boolean stale = goalPos == null || goalPos.distManhattan(tp) > 2;
        boolean inactive = goalPos != null && !cg.isActive() && ctx.ticks() - pathTick > PATH_GRACE_TICKS;
        if (inactive) {
            // inactive without having arrived = the path calculation failed; arrived but still out of reach
            // (target on a ledge, behind glass) counts as a failure only after several tries
            boolean arrived = new GoalNear(goalPos, 1).isInGoal(p.blockPosition());
            if (arrived ? ++arrivedNoReach >= MAX_ARRIVED_NO_REACH : ++pathFails >= MAX_PATH_FAILS) {
                return Result.UNREACHABLE;
            }
        }
        if (stale || inactive) {
            goalPos = tp;
            pathTick = ctx.ticks();
            pathing = true;
            cg.setGoalAndPath(new GoalNear(tp, 1));
            ctx.step("approaching " + McIds.entity(t), -1);
        }
        return Result.APPROACHING;
    }

    /** Lets Baritone move again (eating, defense, walking back) without forgetting the target. */
    void releaseHold(TaskContext ctx) {
        if (holding) {
            ctx.bot().pause.release(claimOwner(ctx));
            holding = false;
        }
    }

    /** Drops the target: releases the hold and stops the approach path. */
    void stop(TaskContext ctx) {
        releaseHold(ctx);
        if (pathing && ctx.baritone() != null) {
            ctx.baritone().getPathingBehavior().cancelEverything();
        }
        pathing = false;
        target = null;
        goalPos = null;
    }

    static boolean inReach(LocalPlayer p, Entity e) {
        return e.getBoundingBox().distanceToSqr(p.getEyePosition())
                <= Interact.ENTITY_REACH * Interact.ENTITY_REACH;
    }

    /**
     * Nearest living non-player entity within {@code radius} of {@code center} that is hostile ({@link Enemy}) or,
     * when {@code types} is not empty, whose type id is listed; {@code skip} = entity ids to ignore.
     */
    static LivingEntity pickTarget(ClientLevel level, LocalPlayer p, BlockPos center, int radius, List<String> types,
                                   Set<Integer> skip) {
        Vec3 c = Vec3.atCenterOf(center);
        double r2 = (double) radius * radius;
        AABB box = new AABB(center).inflate(radius);
        return level.getEntities(p, box, e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof Player)
                        && !skip.contains(e.getId()) && e.position().distanceToSqr(c) <= r2
                        && (types.isEmpty() ? e instanceof Enemy : types.contains(McIds.entity(e))))
                .stream()
                .map(e -> (LivingEntity) e)
                .min(Comparator.comparingDouble(p::distanceToSqr))
                .orElse(null);
    }
}
