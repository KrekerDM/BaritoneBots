package io.github.krekerdm.baritonebots.mod.task;

import baritone.api.IBaritone;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.msg.TaskResult;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Per-task state handed to a {@link TaskExecutor} (SPEC §4.2). The first {@link #succeed}/{@link #fail} wins; later
 * calls are ignored, which is what makes {@code task_done} exactly-once.
 */
public final class TaskContext {
    private final BotRuntime bot;
    private final TaskSpec spec;
    private final TaskContext parent;
    private final long startedAtMs = System.currentTimeMillis();
    private int ticks;
    private String step = "starting";
    private double progress = -1;
    private TaskResult result;

    TaskContext(BotRuntime bot, TaskSpec spec) {
        this(bot, spec, null);
    }

    /** A {@link SubTask} context: same task id (and pause owner), own result; steps show on the parent. */
    TaskContext(BotRuntime bot, TaskSpec spec, TaskContext parent) {
        this.bot = bot;
        this.spec = spec;
        this.parent = parent;
    }

    public TaskSpec spec() {
        return spec;
    }

    public JsonObject args() {
        return spec.args();
    }

    public Minecraft mc() {
        return bot.mc;
    }

    public IBaritone baritone() {
        return bot.baritone();
    }

    public BotRuntime bot() {
        return bot;
    }

    public LocalPlayer player() {
        return bot.mc.player;
    }

    /** Player feet position, or {@code null} when not in game. */
    public BlockPos feet() {
        LocalPlayer p = bot.mc.player;
        return p == null ? null : p.blockPosition();
    }

    /** Ticks since the task started (0 during {@code start}). */
    public int ticks() {
        return ticks;
    }

    void advance() {
        ticks++;
    }

    public long elapsedMs() {
        return System.currentTimeMillis() - startedAtMs;
    }

    /** Pause-process owner name for this task ({@code task:<id>}); released automatically at the end. */
    public String owner() {
        return TaskManager.OWNER_PREFIX + spec.id();
    }

    /** Updates the step shown in status ({@code progress} 0..1, or -1 for unknown). */
    public void step(String step, double progress) {
        if (parent != null) {
            parent.step(step, progress);
        }
        boolean changed = !step.equals(this.step);
        this.step = step;
        this.progress = progress;
        if (changed) {
            bot.status.markDirty();
        }
    }

    public String stepName() {
        return step;
    }

    public double progress() {
        return progress;
    }

    public void succeed(String message, JsonObject data) {
        if (result == null) {
            result = TaskResult.success(spec, message, data, elapsedMs());
        }
    }

    public void fail(String reason, String message, JsonObject data) {
        if (result == null) {
            result = TaskResult.failure(spec, reason, message, data, elapsedMs());
        }
    }

    public boolean isFinished() {
        return result != null;
    }

    TaskResult result() {
        return result;
    }
}
