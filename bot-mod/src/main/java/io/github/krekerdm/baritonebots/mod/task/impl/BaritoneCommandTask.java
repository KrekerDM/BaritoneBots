package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.process.IBaritoneProcess;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;

import java.util.Optional;

/**
 * {@code baritone command}: runs a Baritone command (without {@code #}); ok once no Baritone process has been in
 * control for 40 ticks; {@code bad_args} if the command is rejected.
 */
public final class BaritoneCommandTask implements TaskExecutor {
    private static final int IDLE_TICKS = 40;
    private int idle;

    @Override
    public void start(TaskContext ctx) {
        String cmd = Json.getString(ctx.args(), "command", "").trim();
        while (cmd.startsWith("#")) {
            cmd = cmd.substring(1).trim();
        }
        if (cmd.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "baritone needs 'command'", null);
            return;
        }
        boolean ok;
        try {
            ok = ctx.baritone().getCommandManager().execute(cmd);
        } catch (RuntimeException e) {
            ctx.fail(Reasons.BAD_ARGS, "command failed: " + e.getMessage(), null);
            return;
        }
        if (!ok) {
            ctx.fail(Reasons.BAD_ARGS, "Baritone did not accept '" + cmd + "'", null);
            return;
        }
        ctx.step("running #" + cmd, -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        Optional<IBaritoneProcess> proc = ctx.baritone().getPathingControlManager().mostRecentInControl();
        boolean busy = proc.filter(p -> p.isActive() && p != ctx.bot().pause && p != ctx.bot().defenseProcess)
                .isPresent() || ctx.baritone().getPathingBehavior().isPathing();
        idle = busy ? 0 : idle + 1;
        if (idle >= IDLE_TICKS) {
            ctx.succeed("Baritone finished", null);
        }
    }
}
