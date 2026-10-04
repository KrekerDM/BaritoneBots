package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.ContainerSession;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs.ItemRequest;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs.SlotPick;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.task.plan.SlotMoves;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code drop items:[{item:glob,count}]} ({@code count} -1 = all) and/or {@code slots:[{slot,item,count?}]} (exact
 * inventory stacks chosen by the manager's trash step; a stack whose item changed meanwhile is left alone): throws
 * matching items from the main inventory,
 * hotbar and offhand (never worn armor) with {@code THROW} clicks in the player's own menu — button 1 throws a whole
 * stack, button 0 one item; a partial count is either thrown item by item or first split into an empty slot,
 * whichever needs fewer clicks ({@link SlotMoves#throwMode}). The bot looks straight ahead first so the items land a
 * few blocks away. ok data {@code {dropped:{id:count}, missing:{glob:count}}}.
 */
public final class DropTask implements TaskExecutor {
    private List<ItemRequest> requests;
    private Map<Integer, SlotPick> picks;
    private final Map<Integer, Integer> pickStart = new HashMap<>();
    private final Set<Integer> picksDone = new HashSet<>();
    private final Set<Integer> exhausted = new HashSet<>();
    private final Set<Integer> stuck = new HashSet<>();
    private Map<String, Integer> before;
    private ContainerSession session;
    private int watchSlot = -1;
    private int watchCount;

    @Override
    public void start(TaskContext ctx) {
        requests = TaskArgs.itemRequests(ctx.args(), "items");
        picks = TaskArgs.slotPicks(ctx.args(), "slots");
        if (requests.isEmpty() && picks.isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "drop needs 'items' or 'slots'", null);
            return;
        }
        LocalPlayer p = ctx.player();
        for (SlotPick pk : picks.values()) {
            if (pk.slot() < Inv.MAIN_SIZE) {
                pickStart.put(pk.slot(), p.getInventory().getItem(pk.slot()).getCount());
            }
        }
        before = Inv.totals(p, false);
        p.setXRot(0f); // sent with the next movement packet, before the session settles
        session = ContainerSession.playerInventory();
    }

    @Override
    public void tick(TaskContext ctx) {
        session.tick(ctx);
        if (session.failed()) {
            session.close(ctx);
            ctx.fail(session.failReason(), session.failMessage(), data(ctx.player()));
            return;
        }
        if (!session.idle()) {
            return;
        }
        LocalPlayer p = ctx.player();
        if (watchSlot >= 0) {
            if (session.menu().getSlot(watchSlot).getItem().getCount() == watchCount) {
                stuck.add(watchSlot); // the server refused (e.g. curse of binding or no drop permission)
            }
            watchSlot = -1;
        }
        if (!plan(ctx, p)) {
            session.close(ctx);
            ctx.succeed("dropped", data(p));
        }
    }

    private boolean plan(TaskContext ctx, LocalPlayer p) {
        Map<String, Integer> dropped = Inv.gained(Inv.totals(p, false), before);
        List<Slot> slots = dropSlots(p);
        for (int i = 0; i < requests.size(); i++) {
            if (exhausted.contains(i)) {
                continue;
            }
            ItemRequest r = requests.get(i);
            int want = r.all() ? Integer.MAX_VALUE : r.count() - TakeTask.sumMatching(dropped, r.glob());
            Slot src = null;
            if (want > 0) {
                for (Slot s : slots) {
                    ItemStack st = s.getItem();
                    if (!st.isEmpty() && !stuck.contains(s.index) && Ids.matches(r.glob(), McIds.item(st))) {
                        src = s;
                        break;
                    }
                }
            }
            if (src == null) {
                exhausted.add(i);
                continue;
            }
            throwFrom(ctx, p, src, want);
            return true;
        }
        for (SlotPick pk : picks.values()) {
            if (picksDone.contains(pk.slot())) {
                continue;
            }
            Slot src = null;
            for (Slot s : slots) {
                if (s.getContainerSlot() == pk.slot()) {
                    src = s;
                    break;
                }
            }
            ItemStack st = src == null ? ItemStack.EMPTY : src.getItem();
            if (src == null || st.isEmpty() || stuck.contains(src.index) || !pk.item().equals(McIds.item(st))) {
                picksDone.add(pk.slot()); // emptied, refused or changed since the manager looked: leave it
                continue;
            }
            int thrown = Math.max(0, pickStart.getOrDefault(pk.slot(), st.getCount()) - st.getCount());
            int want = pk.whole() ? st.getCount() : Math.min(st.getCount(), pk.count() - thrown);
            if (want <= 0) {
                picksDone.add(pk.slot());
                continue;
            }
            throwFrom(ctx, p, src, want);
            return true;
        }
        return false;
    }

    /** Throws {@code want} items from {@code src}: whole stack, one by one, or split first (fewest clicks). */
    private void throwFrom(TaskContext ctx, LocalPlayer p, Slot src, int want) {
        int c = src.getItem().getCount();
        Slot empty = TakeTask.firstEmpty(session.mainSlots(p));
        ctx.step("dropping " + McIds.item(src.getItem()), -1);
        switch (SlotMoves.throwMode(c, want, empty != null)) {
            case WHOLE -> session.click(src, 1, ContainerInput.THROW);
            case SPLIT -> {
                session.movePartial(src, empty, want);
                session.click(empty, 1, ContainerInput.THROW);
            }
            case SINGLES -> {
                for (int k = 0; k < want; k++) {
                    session.click(src, 0, ContainerInput.THROW);
                }
            }
        }
        watchSlot = src.index;
        watchCount = c;
    }

    /** Main, hotbar and offhand slots of the player's own menu. */
    private List<Slot> dropSlots(LocalPlayer p) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : session.playerSlots(p)) {
            int cs = s.getContainerSlot();
            if (cs < Inv.MAIN_SIZE || cs == Inv.OFFHAND) {
                out.add(s);
            }
        }
        return out;
    }

    private JsonObject data(LocalPlayer p) {
        Map<String, Integer> dropped = p == null ? Map.of() : Inv.gained(Inv.totals(p, false), before);
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (ItemRequest r : requests) {
            if (!r.all()) {
                int m = r.count() - TakeTask.sumMatching(dropped, r.glob());
                if (m > 0) {
                    missing.merge(r.glob(), m, Integer::sum);
                }
            }
        }
        return Json.obj("dropped", TaskArgs.counts(dropped), "missing", TaskArgs.counts(missing));
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
        }
    }
}
