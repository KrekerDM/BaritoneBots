package io.github.krekerdm.baritonebots.mod.task.impl;

import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;

/** {@code idle}: ok immediately. */
public final class IdleTask implements TaskExecutor {
    @Override
    public void start(TaskContext ctx) {
        ctx.succeed("idle", null);
    }

    @Override
    public void tick(TaskContext ctx) {
    }
}
