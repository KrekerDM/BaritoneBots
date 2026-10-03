package io.github.krekerdm.baritonebots.mod.task;

import io.github.krekerdm.baritonebots.common.msg.BotStatus;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskResult;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import io.github.krekerdm.baritonebots.mod.util.Interact;

/**
 * Runs at most one task (SPEC §4.2): a new task replaces the current one (which reports {@code cancelled}),
 * {@code timeoutSec} is enforced here, and every started task produces exactly one {@code task_done}. After any
 * end the client is left neutral: task pause claims released, keys released, container closed, Baritone
 * processes cancelled.
 */
public final class TaskManager {
    public static final String OWNER_PREFIX = "task:";

    private final BotRuntime bot;
    private TaskContext ctx;
    private TaskExecutor executor;

    public TaskManager(BotRuntime bot) {
        this.bot = bot;
    }

    public boolean running() {
        return ctx != null;
    }

    /** Status view of the current task, or {@code null}. */
    public BotStatus.TaskInfo info() {
        TaskContext c = ctx;
        if (c == null) {
            return null;
        }
        boolean paused = bot.defense.engaged() || (bot.eater.busy() && !bot.eater.owner().startsWith(OWNER_PREFIX));
        return new BotStatus.TaskInfo(c.spec().id(), c.spec().type(), c.spec().label(),
                paused ? BotStatus.TaskInfo.PAUSED : BotStatus.TaskInfo.RUNNING, c.stepName(), c.progress());
    }

    public void start(TaskSpec spec) {
        if (spec == null || spec.id() == null || spec.id().isBlank()) {
            bot.warn("ignoring task without id");
            return;
        }
        if (ctx != null) {
            end("replaced by task " + spec.id(), Reasons.CANCELLED);
        }
        TaskContext c = new TaskContext(bot, spec);
        TaskExecutor ex = TaskRegistry.create(spec.type());
        if (ex == null) {
            boolean known = TaskTypes.isKnown(spec.type());
            c.fail(known ? Reasons.UNSUPPORTED : Reasons.BAD_ARGS,
                    known ? "task type '" + spec.type() + "' is not supported by this bot mod version"
                            : "unknown task type '" + spec.type() + "'", null);
            deliver(c);
            return;
        }
        if (!TaskTypes.IDLE.equals(spec.type())) {
            if (!bot.inGame()) {
                c.fail(Reasons.DISCONNECTED, "not on a server", null);
                deliver(c);
                return;
            }
            if (bot.baritone() == null) {
                c.fail(Reasons.ERROR, "Baritone is not ready", null);
                deliver(c);
                return;
            }
        }
        ctx = c;
        executor = ex;
        bot.status.markDirty();
        try {
            ex.start(c);
        } catch (RuntimeException e) {
            ModInfo.LOG.error("Task {} ({}) failed to start", spec.id(), spec.type(), e);
            c.fail(Reasons.ERROR, "start failed: " + e, null);
        }
        if (c.isFinished()) {
            finishCurrent(false);
        }
    }

    public void tick() {
        TaskContext c = ctx;
        if (c == null) {
            return;
        }
        int timeout = c.spec().timeoutSec();
        if (timeout > 0 && c.elapsedMs() >= timeout * 1000L) {
            end("timed out after " + timeout + " s", Reasons.TIMEOUT);
            return;
        }
        c.advance();
        try {
            executor.tick(c);
        } catch (RuntimeException e) {
            ModInfo.LOG.error("Task {} ({}) crashed", c.spec().id(), c.spec().type(), e);
            c.fail(Reasons.ERROR, "task error: " + e, null);
        }
        if (c.isFinished()) {
            finishCurrent(false);
        }
    }

    /** {@code cancel}: cancels the task if {@code taskId} is the current one. */
    public void cancel(String taskId) {
        if (ctx != null && ctx.spec().id().equals(taskId)) {
            end("cancelled by manager", Reasons.CANCELLED);
        }
    }

    /** Cancels whatever runs (stop, disconnect request, quit). */
    public void cancelCurrent(String why) {
        if (ctx != null) {
            end(why, Reasons.CANCELLED);
        }
    }

    public void onDisconnected() {
        if (ctx != null) {
            end("disconnected from the server", Reasons.DISCONNECTED);
        }
    }

    public void onDeath() {
        if (ctx != null) {
            end("the bot died", Reasons.DIED);
        }
    }

    private void end(String message, String reason) {
        TaskContext c = ctx;
        if (c == null) {
            return;
        }
        c.fail(reason, message, null);
        finishCurrent(true);
    }

    private void finishCurrent(boolean cancelled) {
        TaskContext c = ctx;
        TaskExecutor ex = executor;
        ctx = null;
        executor = null;
        if (cancelled) {
            try {
                ex.cancel(c);
            } catch (RuntimeException e) {
                ModInfo.LOG.error("Task {} cancel failed", c.spec().id(), e);
            }
        }
        try {
            ex.cleanup(c);
        } catch (RuntimeException e) {
            ModInfo.LOG.error("Task {} cleanup failed", c.spec().id(), e);
        }
        neutralize(c);
        deliver(c);
    }

    private void neutralize(TaskContext c) {
        bot.pause.releaseByPrefix(OWNER_PREFIX);
        if (bot.eater.busy() && bot.eater.owner().startsWith(OWNER_PREFIX)) {
            bot.eater.stop();
        }
        if (bot.mc.player != null) {
            if (!bot.eater.busy()) {
                Interact.releaseKeys(bot.mc); // never cut off auto-eat mid-bite
            }
            Interact.closeContainer(bot.mc.player);
        }
        if (bot.baritone() != null && !TaskTypes.IDLE.equals(c.spec().type())) {
            bot.baritone().getPathingBehavior().cancelEverything();
            bot.baritone().getInputOverrideHandler().clearAllKeys();
        }
    }

    private void deliver(TaskContext c) {
        TaskResult r = c.result();
        if (r == null) {
            return;
        }
        bot.sendReliable(MessageTypes.TASK_DONE, r);
        bot.status.markDirty();
        if (r.ok()) {
            ModInfo.LOG.info("Task {} ({}) done: {}", r.id(), r.type(), r.message());
        } else {
            ModInfo.LOG.info("Task {} ({}) failed: {} {}", r.id(), r.type(), r.reason(), r.message());
        }
    }
}
