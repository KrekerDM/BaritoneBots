package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code mine blocks [amount=0] [minY] [maxY]}: Baritone MineProcess. Inactive → ok with {@code collected}
 * (items gained), or {@code not_found} when nothing was gained; {@code inventory_full} when free slots drop to
 * {@code behaviour.inventoryFullFreeSlots}.
 */
public final class MineTask implements TaskExecutor {
    private Map<String, Integer> before;
    private int amount;
    private Integer oldMinY;
    private Integer oldMaxY;
    private int inactive;

    @Override
    public void start(TaskContext ctx) {
        List<String> ids = new ArrayList<>();
        for (String raw : Json.getStringList(ctx.args(), "blocks")) {
            String id = Ids.normalize(raw.trim());
            if (McIds.blockById(id).isPresent()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "mine needs 'blocks' with at least one known block id", null);
            return;
        }
        amount = Math.max(0, Json.getInt(ctx.args(), "amount", 0));
        Settings s = BaritoneAPI.getSettings();
        if (ctx.args().has("minY")) {
            oldMinY = s.minYLevelWhileMining.value;
            s.minYLevelWhileMining.value = Json.getInt(ctx.args(), "minY", oldMinY);
        }
        if (ctx.args().has("maxY")) {
            oldMaxY = s.maxYLevelWhileMining.value;
            s.maxYLevelWhileMining.value = Json.getInt(ctx.args(), "maxY", oldMaxY);
        }
        before = Inv.totals(ctx.player(), false);
        ctx.baritone().getMineProcess().mineByName(amount, ids.toArray(String[]::new));
        ctx.step("mining " + String.join(",", ids), amount > 0 ? 0 : -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        Map<String, Integer> collected = Inv.gained(before, Inv.totals(ctx.player(), false));
        int total = collected.values().stream().mapToInt(Integer::intValue).sum();
        if (ctx.ticks() % 20 == 0) {
            int free = Inv.freeSlots(ctx.player());
            if (free <= ctx.bot().config().behaviour().inventoryFullFreeSlots()) {
                ctx.bot().event(EventKinds.INVENTORY_FULL, Levels.WARN, "inventory full while mining",
                        Json.obj("freeSlots", free));
                ctx.fail(Reasons.INVENTORY_FULL, "inventory full", Json.obj("collected", TaskArgs.counts(collected)));
                return;
            }
            if (amount > 0) {
                ctx.step(ctx.stepName(), Math.min(1, total / (double) amount));
            }
        }
        if (ctx.baritone().getMineProcess().isActive()) {
            inactive = 0;
            return;
        }
        if (++inactive < 5) {
            return;
        }
        if (total == 0) {
            ctx.fail(Reasons.NOT_FOUND, "no matching blocks found", Json.obj("collected", TaskArgs.counts(collected)));
        } else {
            ctx.succeed("mining finished", Json.obj("collected", TaskArgs.counts(collected)));
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getMineProcess().cancel();
        }
        Settings s = BaritoneAPI.getSettings();
        if (oldMinY != null) {
            s.minYLevelWhileMining.value = oldMinY;
        }
        if (oldMaxY != null) {
            s.maxYLevelWhileMining.value = oldMaxY;
        }
    }
}
