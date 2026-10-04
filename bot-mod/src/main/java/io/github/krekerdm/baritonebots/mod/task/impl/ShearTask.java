package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@code shear box}: shears every adult, unsheared sheep inside the pen ({@code Sheep#readyForShearing}, the sheared
 * flag is synced to the client), checking that the flag flipped after each click, then collects the wool inside the
 * pen. No shears = {@code missing_materials}; shears breaking midway end the shearing early ({@code outOfShears}).
 * ok data {@code {sheared, picked}}.
 */
public final class ShearTask implements TaskExecutor {
    private static final Predicate<ItemStack> SHEARS = s -> s.is(Items.SHEARS);
    private static final int VERIFY_TICKS = 10;
    private static final int MAX_TRIES = 3;
    private static final int DROP_WAIT_TICKS = 10;

    private final Fighter mover = new Fighter();
    private final Set<Integer> skipped = new HashSet<>();
    private final Map<Integer, Integer> tries = new HashMap<>();
    private AnimalPen pen;
    private Sheep target;
    private int verifyStart = -1;
    private int sheared;
    private boolean outOfShears;
    private DropCollector collector;
    private int collectStart;

    @Override
    public void start(TaskContext ctx) {
        pen = AnimalPen.parse(ctx, EntityTypes.SHEEP);
        if (pen == null) {
            return;
        }
        if (Inv.count(ctx.player(), SHEARS) == 0) {
            ctx.fail(Reasons.MISSING_MATERIALS, "no shears", Json.obj("missing", Json.obj("minecraft:shears", 1)));
            return;
        }
        pen.protectFences();
        ctx.step("shearing sheep", -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (ctx.bot().eater.busy() || ctx.bot().defense.engaged()) {
            mover.releaseHold(ctx);
            ctx.step("waiting (eating / defending)", -1);
            return;
        }
        if (collector != null) {
            collect(ctx);
            return;
        }
        if (verifyStart >= 0) {
            verify(ctx);
            return;
        }
        if (target == null || !target.isAlive() || !target.readyForShearing() || !pen.inside(target)) {
            mover.stop(ctx);
            target = pen.animals(ctx, Sheep.class).stream()
                    .filter(s -> s.readyForShearing() && !skipped.contains(s.getId())).findFirst().orElse(null);
        }
        if (target != null && Inv.count(ctx.player(), SHEARS) == 0) {
            if (sheared == 0) {
                ctx.fail(Reasons.MISSING_MATERIALS, "no shears left",
                        Json.obj("missing", Json.obj("minecraft:shears", 1)));
                return;
            }
            outOfShears = true;
            target = null;
        }
        if (target == null) {
            mover.stop(ctx);
            collector = new DropCollector(pen.itemArea(), List.of("minecraft:*_wool"));
            collector.start(ctx);
            collectStart = ctx.ticks();
            ctx.step("collecting wool", -1);
            return;
        }
        Fighter.Result r = mover.approach(ctx, target);
        if (r == Fighter.Result.UNREACHABLE) {
            skipped.add(target.getId());
            mover.stop(ctx);
            target = null;
            return;
        }
        if (r != Fighter.Result.IN_REACH) {
            ctx.step("walking to a sheep (" + sheared + " sheared)", -1);
            return;
        }
        if (pen.holdInMainHand(ctx, SHEARS) == 1) {
            Interact.useOnEntity(ctx.mc(), ctx.player(), target, InteractionHand.MAIN_HAND);
            verifyStart = ctx.ticks();
            ctx.step("shearing (" + sheared + " sheared)", -1);
        }
    }

    /** The sheared flag comes back from the server as entity data; a sheep that ignores us is retried, then skipped. */
    private void verify(TaskContext ctx) {
        boolean done = target.isSheared() || !target.isAlive();
        if (!done && ctx.ticks() - verifyStart < VERIFY_TICKS) {
            return;
        }
        verifyStart = -1;
        if (target.isSheared()) {
            sheared++;
        } else if (tries.merge(target.getId(), 1, Integer::sum) >= MAX_TRIES) {
            skipped.add(target.getId());
        }
        mover.releaseHold(ctx);
        target = null;
    }

    private void collect(TaskContext ctx) {
        if (collector.tick(ctx) == DropCollector.Status.DONE && ctx.ticks() - collectStart >= DROP_WAIT_TICKS) {
            JsonObject data = Json.obj("sheared", sheared, "picked", TaskArgs.counts(collector.picked(ctx)));
            if (outOfShears) {
                data.addProperty("outOfShears", true);
            }
            if (!skipped.isEmpty()) {
                data.addProperty("skipped", skipped.size());
            }
            ctx.succeed(sheared > 0 ? "sheared " + sheared + " sheep" : "no sheep to shear", data);
        } else if (ctx.ticks() % 20 == 0) {
            ctx.step("collecting wool (" + collector.remaining(ctx) + " left)", -1);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        mover.stop(ctx);
        if (pen != null) {
            pen.restoreSettings();
        }
    }
}
