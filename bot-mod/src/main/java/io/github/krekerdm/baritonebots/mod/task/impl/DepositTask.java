package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.ContainerSession;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code deposit containers [keep] [keepCounts] [only]}: visits the containers in order and moves every inventory
 * stack that matches {@code only} (when given) and not {@code keep} into them; {@code keepCounts} keeps that many
 * of the matching items (taken from the hotbar first). A full container moves on to the next one. ok with
 * {@code {moved, left}}; {@code container_failed} only when no container could be opened at all.
 */
public final class DepositTask implements TaskExecutor {
    private record Move(Slot slot, int count) {
    }

    private List<BlockPos> containers;
    private List<String> keep;
    private List<String> only;
    private Map<String, Integer> keepCounts;
    private Map<String, Integer> before;
    private final List<BlockPos> failed = new ArrayList<>();
    private final Set<Integer> stuck = new HashSet<>();
    private int next;
    private ContainerSession session;
    private int watchSlot = -1;
    private int watchCount;

    @Override
    public void start(TaskContext ctx) {
        containers = TaskArgs.posList(ctx.args(), "containers");
        if (containers.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "deposit needs 'containers'", null);
            return;
        }
        keep = TaskArgs.globs(ctx.args(), "keep");
        only = TaskArgs.globs(ctx.args(), "only");
        keepCounts = TaskArgs.globCounts(ctx.args(), "keepCounts");
        before = Inv.totals(ctx.player(), false);
        if (moves(inventorySlots(ctx.player())).isEmpty()) {
            ctx.succeed("nothing to deposit", data(ctx));
            return;
        }
        nextContainer(ctx);
    }

    private void nextContainer(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
        }
        stuck.clear();
        watchSlot = -1;
        if (next >= containers.size()) {
            session = null;
            finish(ctx);
            return;
        }
        session = new ContainerSession(containers.get(next++));
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
        if (watchSlot >= 0) {
            if (session.menu().getSlot(watchSlot).getItem().getCount() == watchCount) {
                stuck.add(watchSlot);
            }
            watchSlot = -1;
        }
        LocalPlayer p = ctx.player();
        for (Move m : moves(session.playerSlots(p))) {
            if (stuck.contains(m.slot().index)) {
                continue;
            }
            ItemStack stack = m.slot().getItem();
            if (m.count() >= stack.getCount()) {
                session.click(m.slot(), 0, ContainerInput.QUICK_MOVE);
            } else {
                Slot empty = TakeTask.firstEmpty(session.containerSlots(p));
                if (empty == null) {
                    stuck.add(m.slot().index);
                    continue;
                }
                session.movePartial(m.slot(), empty, m.count());
            }
            ctx.step("depositing " + McIds.item(stack) + " into " + session.pos().toShortString(), -1);
            watchSlot = m.slot().index;
            watchCount = stack.getCount();
            return;
        }
        if (stuck.isEmpty()) {
            session.close(ctx);
            session = null;
            finish(ctx); // everything that should go is gone
        } else {
            nextContainer(ctx); // container full for the rest
        }
    }

    /** Stacks to deposit (with how many from each), honouring only/keep/keepCounts; hotbar slots first. */
    private List<Move> moves(List<Slot> playerSlots) {
        List<Slot> ordered = new ArrayList<>();
        for (Slot s : playerSlots) {
            if (s.getContainerSlot() < Inv.HOTBAR_SIZE) {
                ordered.add(s);
            }
        }
        for (Slot s : playerSlots) {
            if (s.getContainerSlot() >= Inv.HOTBAR_SIZE) {
                ordered.add(s);
            }
        }
        Map<String, Integer> budget = new LinkedHashMap<>(keepCounts);
        List<Move> out = new ArrayList<>();
        for (Slot s : ordered) {
            ItemStack st = s.getItem();
            if (st.isEmpty()) {
                continue;
            }
            String id = McIds.item(st);
            if ((!only.isEmpty() && !Ids.matchesAny(only, id)) || Ids.matchesAny(keep, id)) {
                continue;
            }
            int keepHere = 0;
            for (Map.Entry<String, Integer> b : budget.entrySet()) {
                if (b.getValue() > 0 && Ids.matches(b.getKey(), id)) {
                    keepHere = Math.min(b.getValue(), st.getCount());
                    b.setValue(b.getValue() - keepHere);
                    break;
                }
            }
            int dep = st.getCount() - keepHere;
            if (dep > 0) {
                out.add(new Move(s, dep));
            }
        }
        return out;
    }

    /** Main + hotbar slots of the player's own inventory menu. */
    private static List<Slot> inventorySlots(LocalPlayer p) {
        Inventory inv = p.getInventory();
        List<Slot> out = new ArrayList<>();
        for (Slot s : p.inventoryMenu.slots) {
            if (s.container == inv && s.getContainerSlot() < Inv.MAIN_SIZE) {
                out.add(s);
            }
        }
        return out;
    }

    private void finish(TaskContext ctx) {
        if (failed.size() == containers.size()) {
            ctx.fail(Reasons.CONTAINER_FAILED, "no container could be opened", data(ctx));
        } else {
            ctx.succeed("deposited", data(ctx));
        }
    }

    private JsonObject data(TaskContext ctx) {
        LocalPlayer p = ctx.player();
        Map<String, Integer> moved = Inv.gained(Inv.totals(p, false), before);
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Move m : moves(inventorySlots(p))) {
            left.merge(McIds.item(m.slot().getItem()), m.count(), Integer::sum);
        }
        JsonArray failedArr = new JsonArray();
        failed.forEach(f -> failedArr.add(Positions.json(f)));
        JsonObject d = Json.obj("moved", TaskArgs.counts(moved), "left", TaskArgs.counts(left));
        d.add("failed", failedArr);
        return d;
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
        }
    }
}
