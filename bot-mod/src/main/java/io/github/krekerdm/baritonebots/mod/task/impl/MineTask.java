package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.baritone.SettingsOverride;
import io.github.krekerdm.baritonebots.mod.behaviour.ProtectionGuard;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.task.plan.LegitMining;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code mine blocks [amount=0] [minY] [maxY] [strategy=normal|legit] [y] [fakeOres]}: Baritone MineProcess.
 * Inactive → ok with {@code collected} (items gained), or {@code not_found} when nothing was gained;
 * {@code inventory_full} when free slots drop to {@code behaviour.inventoryFullFreeSlots}.
 * <p>
 * Anti-xray servers (SPEC §5.7b3): {@code strategy=legit} (implied by {@code fakeOres=true}) sets Baritone's
 * {@code legitMine} for the task — only ores the bot can see are targeted, otherwise it branch-mines at
 * {@code legitMineYLevel} = {@code y}, or the best level for the first requested ore. {@code fakeOres} also turns
 * {@code legitMineIncludeDiagonals} off and blacklists fake ores ({@link FakeOreWatcher}). Every result carries
 * {@code data.strategy} (and {@code data.fakeOres} with {@code fakeOres}). All settings are restored afterwards.
 */
public final class MineTask implements TaskExecutor {
    private final SettingsOverride settings = new SettingsOverride();
    private Map<String, Integer> before;
    private int amount;
    private int inactive;
    private String strategy = "normal";
    private FakeOreWatcher fakeOres;

    @Override
    public void start(TaskContext ctx) {
        JsonObject a = ctx.args();
        List<String> ids = new ArrayList<>();
        Set<Block> blocks = new HashSet<>();
        for (String raw : Json.getStringList(a, "blocks")) {
            String id = Ids.normalize(raw.trim());
            McIds.blockById(id).ifPresent(b -> {
                ids.add(id);
                blocks.add(b);
            });
        }
        if (ids.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "mine needs 'blocks' with at least one known block id", null);
            return;
        }
        String s0 = Json.getString(a, "strategy", "normal").trim().toLowerCase(Locale.ROOT);
        if (!s0.isEmpty() && !"normal".equals(s0) && !"legit".equals(s0)) {
            ctx.fail(Reasons.BAD_ARGS, "unknown mine strategy '" + s0 + "' (normal or legit)", null);
            return;
        }
        boolean fake = Json.getBool(a, "fakeOres", false);
        strategy = "legit".equals(s0) || fake ? "legit" : "normal";
        amount = Math.max(0, Json.getInt(a, "amount", 0));
        Settings s = BaritoneAPI.getSettings();
        if (a.has("minY")) {
            settings.set(s.minYLevelWhileMining, Json.getInt(a, "minY", s.minYLevelWhileMining.value));
        }
        if (a.has("maxY")) {
            settings.set(s.maxYLevelWhileMining, Json.getInt(a, "maxY", s.maxYLevelWhileMining.value));
        }
        if ("legit".equals(strategy)) {
            settings.set(s.legitMine, true);
            Integer y = a.has("y") ? Integer.valueOf(Json.getInt(a, "y", s.legitMineYLevel.value))
                    : LegitMining.bestY(ids);
            if (y != null) {
                settings.set(s.legitMineYLevel, y);
            }
            if (fake) {
                settings.set(s.legitMineIncludeDiagonals, false);
                fakeOres = new FakeOreWatcher(blocks);
            }
        }
        before = Inv.totals(ctx.player(), false);
        ProtectionGuard.setTargets(blocks); // a target inside a zone is refused, but never learned as "do not break"
        ctx.baritone().getMineProcess().mineByName(amount, ids.toArray(String[]::new));
        ctx.step(("legit".equals(strategy) ? "legit mining " : "mining ") + String.join(",", ids), amount > 0 ? 0 : -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (fakeOres != null) {
            fakeOres.tick(ctx.bot().level(), ctx.feet(), ctx.ticks());
        }
        Map<String, Integer> collected = Inv.gained(before, Inv.totals(ctx.player(), false));
        int total = collected.values().stream().mapToInt(Integer::intValue).sum();
        if (ctx.ticks() % 20 == 0) {
            int free = Inv.freeSlots(ctx.player());
            if (free <= ctx.bot().config().behaviour().inventoryFullFreeSlots()) {
                ctx.bot().event(EventKinds.INVENTORY_FULL, Levels.WARN, "inventory full while mining",
                        Json.obj("freeSlots", free));
                ctx.fail(Reasons.INVENTORY_FULL, "inventory full", data(collected));
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
            ctx.fail(Reasons.NOT_FOUND, "no matching blocks found", data(collected));
        } else {
            ctx.succeed("mining finished", data(collected));
        }
    }

    private JsonObject data(Map<String, Integer> collected) {
        JsonObject d = Json.obj("collected", TaskArgs.counts(collected), "strategy", strategy);
        if (fakeOres != null) {
            d.addProperty("fakeOres", fakeOres.fakeCount());
        }
        return d;
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getMineProcess().cancel();
        }
        settings.restore();
    }
}
