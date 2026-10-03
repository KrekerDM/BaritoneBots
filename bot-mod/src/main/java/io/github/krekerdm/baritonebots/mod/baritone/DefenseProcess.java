package io.github.krekerdm.baritonebots.mod.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;

/**
 * Baritone process driven by the defense behaviour: either holds still (melee in range) or paths to a goal
 * (run away / close in on a hostile). Temporary, so the interrupted task process resumes afterwards.
 * Sits below {@link PauseProcess} so an explicit pause (eating, container work) wins. Client thread only.
 */
public final class DefenseProcess implements IBaritoneProcess {
    public static final double PRIORITY = 900;

    private Goal goal;
    private boolean hold;
    private String reason = "";

    /** Stand still (Baritone paused). */
    public void hold(String why) {
        this.hold = true;
        this.goal = null;
        this.reason = why;
    }

    /** Path towards {@code g} (e.g. {@code GoalRunAway} or {@code GoalNear}). */
    public void pathTo(Goal g, String why) {
        this.hold = false;
        this.goal = g;
        this.reason = why;
    }

    public void clear() {
        this.hold = false;
        this.goal = null;
        this.reason = "";
    }

    public Goal goal() {
        return goal;
    }

    @Override
    public boolean isActive() {
        return hold || goal != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (hold || goal == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        // Driven every tick by the defense behaviour, which decides when to stop.
    }

    @Override
    public double priority() {
        return PRIORITY;
    }

    @Override
    public String displayName0() {
        return "BaritoneBots defense " + reason;
    }
}
