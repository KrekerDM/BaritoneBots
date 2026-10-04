package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code slaughter box animal [keep=4]}: kills adult animals of one type inside the pen while more than {@code keep}
 * adults are left (babies are never attacked and do not count towards {@code keep}), nearest first, with
 * {@link Fighter} (best weapon, attack cooldown), then collects every item inside the pen. Yields while the defense
 * behaviour fights or the bot eats. ok data {@code {killed, picked, unreachable?}}.
 */
public final class SlaughterTask implements TaskExecutor {
    private static final int RESCAN_TICKS = 5;
    private static final int DROP_WAIT_TICKS = 20;

    private final Fighter fighter = new Fighter();
    private final Set<Integer> unreachable = new HashSet<>();
    private final Map<Integer, LivingEntity> engaged = new HashMap<>();
    private AnimalPen pen;
    private int keep;
    private int killed;
    private LivingEntity target;
    private DropCollector collector;
    private int collectStart;

    @Override
    public void start(TaskContext ctx) {
        pen = AnimalPen.parse(ctx, null);
        if (pen == null) {
            return;
        }
        keep = Math.max(0, Json.getInt(ctx.args(), "keep", 4));
        pen.protectFences();
        ctx.step("slaughtering " + pen.typeId + " (keep " + keep + ")", -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        countKills();
        if (ctx.bot().eater.busy() || ctx.bot().defense.engaged()) {
            fighter.releaseHold(ctx);
            ctx.step("waiting (eating / defending)", -1);
            return;
        }
        if (collector != null) {
            collect(ctx);
            return;
        }
        if (target == null || !target.isAlive() || ctx.ticks() % RESCAN_TICKS == 0) {
            LivingEntity next = pickTarget(ctx);
            if (next != target) {
                fighter.stop(ctx);
                target = next;
            }
            if (target == null) {
                fighter.stop(ctx);
                collector = new DropCollector(pen.itemArea(), List.of());
                collector.start(ctx);
                collectStart = ctx.ticks();
                ctx.step("collecting drops", -1);
                return;
            }
        }
        engaged.put(target.getId(), target);
        if (fighter.engage(ctx, target) == Fighter.Result.UNREACHABLE) {
            unreachable.add(target.getId());
            engaged.remove(target.getId());
            fighter.stop(ctx);
            target = null;
        }
    }

    /** Nearest reachable adult while more than {@code keep} adults are alive, else null. */
    private LivingEntity pickTarget(TaskContext ctx) {
        List<LivingEntity> adults = pen.animals(ctx, LivingEntity.class).stream().filter(e -> !e.isBaby()).toList();
        if (adults.size() <= keep) {
            return null;
        }
        if (target != null && target.isAlive() && adults.contains(target)) {
            return target; // finish the current fight
        }
        return adults.stream().filter(e -> !unreachable.contains(e.getId())).findFirst().orElse(null);
    }

    private void countKills() {
        engaged.values().removeIf(e -> {
            if (e.isDeadOrDying()) {
                killed++;
                return true;
            }
            return e.isRemoved();
        });
    }

    private void collect(TaskContext ctx) {
        // loot of the last kill needs a moment to reach the client
        if (collector.tick(ctx) == DropCollector.Status.DONE && ctx.ticks() - collectStart >= DROP_WAIT_TICKS) {
            JsonObject data = Json.obj("killed", killed, "picked", TaskArgs.counts(collector.picked(ctx)));
            if (!unreachable.isEmpty()) {
                data.addProperty("unreachable", unreachable.size());
            }
            ctx.succeed(killed > 0 ? "killed " + killed + " " + pen.typeId : "nothing to slaughter", data);
        } else if (ctx.ticks() % 20 == 0) {
            ctx.step("collecting drops (" + collector.remaining(ctx) + " left)", -1);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        fighter.stop(ctx);
        if (pen != null) {
            pen.restoreSettings();
        }
    }
}
