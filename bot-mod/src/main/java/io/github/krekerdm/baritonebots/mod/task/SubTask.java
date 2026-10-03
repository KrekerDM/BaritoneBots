package io.github.krekerdm.baritonebots.mod.task;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.msg.TaskResult;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;

/**
 * Runs another task executor inside a task (e.g. {@code transfer} = {@code take} then {@code deposit}). The child
 * gets its own {@link TaskContext} with the same task id, so pause claims and the status step belong to the parent
 * task; its result is read with {@link #result()} instead of being sent.
 */
public final class SubTask {
    private final TaskExecutor executor;
    private final TaskContext ctx;
    private boolean ended;

    public SubTask(TaskContext parent, String type, JsonObject args, TaskExecutor executor) {
        TaskSpec ps = parent.spec();
        this.ctx = new TaskContext(parent.bot(), new TaskSpec(ps.id(), type, args, 0, ps.label(), ps.origin()),
                parent);
        this.executor = executor;
    }

    public void start() {
        executor.start(ctx);
    }

    public void tick() {
        if (!ctx.isFinished()) {
            ctx.advance();
            executor.tick(ctx);
        }
    }

    public boolean finished() {
        return ctx.isFinished();
    }

    /** The child's result, {@code null} while it runs. */
    public TaskResult result() {
        return ctx.result();
    }

    /** Runs the child's {@code cancel} (when {@code cancelled}) and {@code cleanup} once. */
    public void end(boolean cancelled) {
        if (ended) {
            return;
        }
        ended = true;
        if (cancelled) {
            executor.cancel(ctx);
        }
        executor.cleanup(ctx);
    }
}
