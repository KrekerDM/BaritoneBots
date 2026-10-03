package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.pathing.goals.GoalBlock;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.mod.task.PathStep;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks onto matching item entities within {@code radius} of a center until none are left. An item that cannot
 * be reached (path failure, 20 s, or still there 2 s after arriving) is skipped.
 */
final class DropCollector {
    enum Status { RUNNING, DONE }

    private static final int TARGET_TIMEOUT_TICKS = 400;
    private static final int LINGER_TICKS = 40;

    private final BlockPos center;
    private final int radius;
    private final List<String> globs;
    private final Set<Integer> skipped = new HashSet<>();
    private final PathStep path = new PathStep();
    private Map<String, Integer> before;
    private ItemEntity target;
    private int targetTicks;
    private int arrivedTicks;

    DropCollector(BlockPos center, int radius, List<String> globs) {
        this.center = center;
        this.radius = radius;
        this.globs = globs;
    }

    void start(TaskContext ctx) {
        before = Inv.totals(ctx.player(), false);
    }

    Map<String, Integer> picked(TaskContext ctx) {
        return Inv.gained(before, Inv.totals(ctx.player(), false));
    }

    int remaining(TaskContext ctx) {
        return candidates(ctx).size();
    }

    Status tick(TaskContext ctx) {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            target = candidates(ctx).stream()
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player()))).orElse(null);
            if (target == null) {
                return Status.DONE;
            }
            targetTicks = 0;
            arrivedTicks = 0;
            path.start(ctx, new GoalBlock(target.blockPosition()));
        }
        targetTicks++;
        PathStep.Status st = path.tick(ctx);
        if (st == PathStep.Status.ARRIVED) {
            arrivedTicks++;
        }
        if (st == PathStep.Status.FAILED || targetTicks > TARGET_TIMEOUT_TICKS || arrivedTicks > LINGER_TICKS) {
            skipped.add(target.getId());
            target = null;
        } else if (st == PathStep.Status.ARRIVED && !target.blockPosition().equals(ctx.feet())
                && arrivedTicks % 10 == 0) {
            path.start(ctx, new GoalBlock(target.blockPosition())); // item slid away
        }
        return Status.RUNNING;
    }

    private List<ItemEntity> candidates(TaskContext ctx) {
        AABB box = new AABB(center).inflate(radius);
        return ctx.bot().level().getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive()
                && !skipped.contains(e.getId())
                && (globs.isEmpty() || Ids.matchesAny(globs, McIds.item(e.getItem()))));
    }
}
