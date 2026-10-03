package io.github.krekerdm.baritonebots.mod.task;

/**
 * One task type (SPEC §4.2). All methods run on the client thread and must not block. The framework handles the
 * timeout, replacement, {@code task_done} delivery and the neutral state afterwards (pause claims of the task
 * released, keys released, container closed, Baritone cancelled), so {@link #cancel} only has to undo what the
 * framework cannot know about (e.g. restoring a Baritone setting).
 */
public interface TaskExecutor {
    /** Called once. May finish the task immediately via {@link TaskContext#succeed}/{@link TaskContext#fail}. */
    void start(TaskContext ctx);

    /** Called every client tick until {@link TaskContext#isFinished()}. */
    void tick(TaskContext ctx);

    /** Called when the task is cancelled, replaced, timed out, or the bot died/disconnected. */
    default void cancel(TaskContext ctx) {
    }

    /** Called after the task finished for any reason (success included), before the neutral-state cleanup. */
    default void cleanup(TaskContext ctx) {
    }
}
