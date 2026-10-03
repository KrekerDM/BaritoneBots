package io.github.krekerdm.baritonebots.mod.task.impl;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code attack [radius=8] [types]}: kills hostile mobs ({@code Enemy}) — or, with {@code types}, living
 * non-player entities of those type ids — within {@code radius} of where the task started, nearest first, with
 * {@link Fighter}. Never targets players. Yields while the defense behaviour fights or the bot eats. ok once no
 * matching target has been seen for 1 s, data {@code {killed, unreachable}}.
 */
public final class AttackTask implements TaskExecutor {
    private static final int RESCAN_TICKS = 5;
    private static final int DONE_AFTER_IDLE_TICKS = 20;

    private final Fighter fighter = new Fighter();
    private final Set<Integer> unreachable = new HashSet<>();
    private final Map<Integer, LivingEntity> engaged = new HashMap<>();
    private BlockPos center;
    private int radius;
    private List<String> types;
    private LivingEntity target;
    private int idleTicks;
    private int killed;

    @Override
    public void start(TaskContext ctx) {
        radius = Math.max(1, Math.min(64, Json.getInt(ctx.args(), "radius", 8)));
        types = Json.getStringList(ctx.args(), "types").stream().filter(s -> !s.isBlank())
                .map(s -> Ids.normalize(s.trim())).toList();
        center = ctx.feet();
        ctx.step("looking for targets", -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        LocalPlayer p = ctx.player();
        countKills();
        if (ctx.bot().eater.busy() || ctx.bot().defense.engaged()) {
            fighter.releaseHold(ctx);
            ctx.step("waiting (eating / defending)", -1);
            return;
        }
        if (target == null || !target.isAlive() || target.isRemoved() || ctx.ticks() % RESCAN_TICKS == 0) {
            LivingEntity next = Fighter.pickTarget(ctx.bot().level(), p, center, radius, types, unreachable);
            if (next != target) {
                fighter.stop(ctx);
                target = next;
            }
        }
        if (target == null) {
            if (++idleTicks >= DONE_AFTER_IDLE_TICKS) {
                ctx.succeed(killed > 0 ? "killed " + killed : "no targets left", Json.obj("killed", killed,
                        "unreachable", unreachable.size(), "center", Positions.json(center)));
            }
            return;
        }
        idleTicks = 0;
        engaged.put(target.getId(), target);
        if (fighter.engage(ctx, target) == Fighter.Result.UNREACHABLE) {
            unreachable.add(target.getId());
            engaged.remove(target.getId());
            fighter.stop(ctx);
            target = null;
        }
    }

    /** Targets we fought that are now dead (removed entities count as killed only when they died). */
    private void countKills() {
        engaged.values().removeIf(e -> {
            if (e.isDeadOrDying()) {
                killed++;
                return true;
            }
            return e.isRemoved();
        });
    }

    @Override
    public void cleanup(TaskContext ctx) {
        fighter.stop(ctx);
    }
}
