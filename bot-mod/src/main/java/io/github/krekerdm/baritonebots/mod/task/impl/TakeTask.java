package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.ContainerSession;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs.ItemRequest;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code take container items:[{item:glob,count}]}: opens the container and moves matching stacks into the
 * inventory (whole stacks with {@code QUICK_MOVE}, partial counts by right-click splitting). ok with
 * {@code {taken:{id:count}, missing:{glob:count}}}; missing items are not a failure.
 */
public final class TakeTask implements TaskExecutor {
    private ContainerSession session;
    private List<ItemRequest> requests;
    private final Set<Integer> exhausted = new HashSet<>();
    private Map<String, Integer> before;
    private boolean inventoryFull;
    private int watchSlot = -1;
    private int watchCount;

    @Override
    public void start(TaskContext ctx) {
        BlockPos pos = TaskArgs.pos(ctx.args(), "container");
        requests = TaskArgs.itemRequests(ctx.args(), "items");
        if (pos == null || requests.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "take needs 'container' and 'items'", null);
            return;
        }
        before = Inv.totals(ctx.player(), false);
        session = new ContainerSession(pos);
    }

    @Override
    public void tick(TaskContext ctx) {
        session.tick(ctx);
        if (session.failed()) {
            session.close(ctx);
            ctx.fail(session.failReason(), session.failMessage(), data(ctx));
            return;
        }
        if (!session.idle()) {
            return;
        }
        LocalPlayer p = ctx.player();
        if (watchSlot >= 0) {
            if (session.menu().getSlot(watchSlot).getItem().getCount() == watchCount) {
                inventoryFull = true;
            }
            watchSlot = -1;
        }
        if (inventoryFull || !plan(ctx, p)) {
            session.close(ctx);
            ctx.succeed(inventoryFull ? "inventory full" : "taken", data(ctx));
        }
    }

    private boolean plan(TaskContext ctx, LocalPlayer p) {
        Map<String, Integer> gained = Inv.gained(before, Inv.totals(p, false));
        for (int i = 0; i < requests.size(); i++) {
            if (exhausted.contains(i)) {
                continue;
            }
            ItemRequest r = requests.get(i);
            int want = r.all() ? Integer.MAX_VALUE : r.count() - sumMatching(gained, r.glob());
            Slot src = want <= 0 ? null : firstMatching(session.containerSlots(p), r.glob());
            if (src == null) {
                exhausted.add(i);
                continue;
            }
            ItemStack stack = src.getItem();
            ctx.step("taking " + McIds.item(stack), -1);
            if (want >= stack.getCount()) {
                session.click(src, 0, ContainerInput.QUICK_MOVE);
            } else {
                Slot empty = firstEmpty(session.playerSlots(p));
                if (empty == null) {
                    inventoryFull = true;
                    return false;
                }
                session.movePartial(src, empty, want);
            }
            watchSlot = src.index;
            watchCount = stack.getCount();
            return true;
        }
        return false;
    }

    private JsonObject data(TaskContext ctx) {
        Map<String, Integer> taken = Inv.gained(before, Inv.totals(ctx.player(), false));
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (ItemRequest r : requests) {
            if (!r.all()) {
                int m = r.count() - sumMatching(taken, r.glob());
                if (m > 0) {
                    missing.merge(r.glob(), m, Integer::sum);
                }
            }
        }
        JsonObject d = Json.obj("taken", TaskArgs.counts(taken), "missing", TaskArgs.counts(missing));
        if (inventoryFull) {
            d.addProperty("inventoryFull", true);
        }
        return d;
    }

    static int sumMatching(Map<String, Integer> counts, String glob) {
        int n = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (Ids.matches(glob, e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }

    static Slot firstMatching(List<Slot> slots, String glob) {
        for (Slot s : slots) {
            ItemStack st = s.getItem();
            if (!st.isEmpty() && Ids.matches(glob, McIds.item(st))) {
                return s;
            }
        }
        return null;
    }

    static Slot firstEmpty(List<Slot> slots) {
        for (Slot s : slots) {
            if (s.getItem().isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
