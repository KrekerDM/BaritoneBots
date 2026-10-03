package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.ContainerSession;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code inspect containers}: opens each container, lets the container sensor send its snapshot (SPEC §4.5),
 * closes it. ok with {@code {seen:int, failed:[pos]}}.
 */
public final class InspectTask implements TaskExecutor {
    private static final int SNAPSHOT_WAIT_TICKS = 20;

    private List<BlockPos> containers;
    private final List<BlockPos> failed = new ArrayList<>();
    private int next;
    private int seen;
    private ContainerSession session;
    private long baseline;
    private int idleTicks;

    @Override
    public void start(TaskContext ctx) {
        containers = TaskArgs.posList(ctx.args(), "containers");
        if (containers.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "inspect needs 'containers'", null);
            return;
        }
        nextContainer(ctx);
    }

    private void nextContainer(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
            session = null;
        }
        if (next >= containers.size()) {
            JsonArray f = new JsonArray();
            failed.forEach(p -> f.add(Positions.json(p)));
            JsonObject data = Json.obj("seen", seen);
            data.add("failed", f);
            ctx.succeed("inspected " + seen + " container(s)", data);
            return;
        }
        BlockPos pos = containers.get(next++);
        ctx.step("inspecting " + pos.toShortString(), (next - 1) / (double) containers.size());
        session = new ContainerSession(pos);
        baseline = ctx.bot().containers.snapshotsSent();
        idleTicks = 0;
    }

    @Override
    public void tick(TaskContext ctx) {
        if (session == null) {
            return;
        }
        session.tick(ctx);
        if (session.failed()) {
            failed.add(session.pos());
            nextContainer(ctx);
            return;
        }
        if (!session.idle()) {
            return;
        }
        boolean sent = ctx.bot().containers.snapshotsSent() > baseline;
        if (sent || ++idleTicks > SNAPSHOT_WAIT_TICKS) {
            if (sent) {
                seen++;
            } else {
                failed.add(session.pos());
            }
            nextContainer(ctx);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
        }
    }
}
