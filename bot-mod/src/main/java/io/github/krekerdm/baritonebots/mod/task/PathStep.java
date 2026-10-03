package io.github.krekerdm.baritonebots.mod.task;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import net.minecraft.core.BlockPos;

/**
 * Walks to a goal with Baritone's custom goal process. Arrival is checked with {@link Goal#isInGoal} on the
 * player's feet because the process goes inactive both on arrival and when path calculation fails
 * ({@code PathEvent.CALC_FAILED}); inactive without being in the goal = failed.
 */
public final class PathStep {
    public enum Status { RUNNING, ARRIVED, FAILED }

    private static final int INACTIVE_GRACE_TICKS = 5;

    private Goal goal;
    private int inactiveTicks;

    public Goal goal() {
        return goal;
    }

    public void start(TaskContext ctx, Goal g) {
        this.goal = g;
        this.inactiveTicks = 0;
        ctx.baritone().getCustomGoalProcess().setGoalAndPath(g);
    }

    public Status tick(TaskContext ctx) {
        BlockPos feet = ctx.feet();
        if (goal == null || feet == null) {
            return Status.FAILED;
        }
        if (goal.isInGoal(feet)) {
            return Status.ARRIVED;
        }
        ICustomGoalProcess proc = ctx.baritone().getCustomGoalProcess();
        if (proc.isActive()) {
            inactiveTicks = 0;
            return Status.RUNNING;
        }
        return ++inactiveTicks >= INACTIVE_GRACE_TICKS ? Status.FAILED : Status.RUNNING;
    }

    /** Within {@code range} blocks horizontally of (x, z), any y. */
    public static final class GoalNearXZ implements Goal {
        private final int x;
        private final int z;
        private final int rangeSq;

        public GoalNearXZ(int x, int z, int range) {
            this.x = x;
            this.z = z;
            this.rangeSq = range * range;
        }

        @Override
        public boolean isInGoal(int px, int py, int pz) {
            int dx = px - x;
            int dz = pz - z;
            return dx * dx + dz * dz <= rangeSq;
        }

        @Override
        public double heuristic(int px, int py, int pz) {
            return GoalXZ.calculate(px - x, pz - z);
        }

        @Override
        public String toString() {
            return "GoalNearXZ{x=" + x + ",z=" + z + ",r=" + (int) Math.sqrt(rangeSq) + "}";
        }
    }
}
