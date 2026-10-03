package io.github.krekerdm.baritonebots.mod.task.impl;

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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * {@code smelt_load furnace input count fuel fuelCount} for furnaces, blast furnaces and smokers
 * ({@link AbstractFurnaceMenu}: slot 0 input, 1 fuel, 2 result). Collects the result slot first, then moves exactly
 * {@code count} input and {@code fuelCount} fuel items with PICKUP clicks (partial stacks by right-click
 * splitting). ok data {@code {loaded, fuel, collected}}; {@code missing_materials} when no input could be loaded
 * because the inventory has none; {@code container_failed} when the input slot holds a different item.
 */
public final class SmeltLoadTask implements TaskExecutor {
    private enum Phase { COLLECT, COLLECT_WAIT, INPUT, FUEL, DONE }

    private static final int SYNC_TICKS = 3;

    private String inputGlob;
    private String fuelGlob;
    private int count;
    private int fuelCount;
    private ContainerSession session;
    private Map<String, Integer> before;
    private Map<String, Integer> afterCollect;
    private Map<String, Integer> afterInput;
    private String inputId;
    private String fuelId;
    private String fuelBlocked;
    private boolean inputFull;
    private int watchSlot = -1;
    private int watchCount;
    private Phase phase = Phase.COLLECT;
    private int waitTicks;

    @Override
    public void start(TaskContext ctx) {
        JsonObject a = ctx.args();
        BlockPos pos = TaskArgs.pos(a, "furnace");
        String input = Json.getString(a, "input", null);
        String fuel = Json.getString(a, "fuel", null);
        count = Json.getInt(a, "count", 0);
        fuelCount = Json.getInt(a, "fuelCount", 0);
        if (pos == null || input == null || input.isBlank() || count < 1 || fuelCount < 0
                || (fuelCount > 0 && (fuel == null || fuel.isBlank()))) {
            ctx.fail(Reasons.BAD_ARGS, "smelt_load needs 'furnace', 'input', 'count' ≥ 1 and 'fuel' when "
                    + "'fuelCount' > 0", null);
            return;
        }
        inputGlob = Ids.normalizeGlob(input.trim());
        fuelGlob = fuel == null || fuel.isBlank() ? null : Ids.normalizeGlob(fuel.trim());
        before = Inv.totals(ctx.player(), false);
        session = new ContainerSession(pos);
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
        if (!(session.menu() instanceof AbstractFurnaceMenu menu)) {
            session.close(ctx);
            ctx.fail(Reasons.CONTAINER_FAILED, "the block at " + session.pos().toShortString()
                    + " is not a furnace, blast furnace or smoker", data(p));
            return;
        }
        switch (phase) {
            case COLLECT -> {
                Slot result = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT);
                if (result.getItem().isEmpty()) {
                    afterCollect = Inv.totals(p, false);
                    phase = Phase.INPUT;
                } else {
                    ctx.step("collecting " + McIds.item(result.getItem()), -1);
                    session.click(result, 0, ContainerInput.QUICK_MOVE);
                    waitTicks = 0;
                    phase = Phase.COLLECT_WAIT;
                }
            }
            case COLLECT_WAIT -> {
                if (++waitTicks >= SYNC_TICKS) {
                    afterCollect = Inv.totals(p, false);
                    phase = Phase.INPUT;
                }
            }
            case INPUT -> {
                ItemStack cur = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
                if (!cur.isEmpty() && !Ids.matches(inputGlob, McIds.item(cur))) {
                    session.close(ctx);
                    ctx.fail(Reasons.CONTAINER_FAILED, "the furnace input holds " + McIds.item(cur)
                            + "; collect it first (smelt_collect all=true)", data(p));
                    return;
                }
                if (!load(ctx, p, menu, AbstractFurnaceMenu.INGREDIENT_SLOT, inputGlob, count, true)) {
                    afterInput = Inv.totals(p, false);
                    watchSlot = -1;
                    phase = Phase.FUEL;
                }
            }
            case FUEL -> {
                ItemStack cur = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
                if (fuelCount <= 0) {
                    phase = Phase.DONE;
                } else if (!cur.isEmpty() && !Ids.matches(fuelGlob, McIds.item(cur))) {
                    fuelBlocked = McIds.item(cur);
                    phase = Phase.DONE;
                } else if (!load(ctx, p, menu, AbstractFurnaceMenu.FUEL_SLOT, fuelGlob, fuelCount, false)) {
                    phase = Phase.DONE;
                }
            }
            case DONE -> finish(ctx, p, menu);
        }
    }

    /**
     * Queues one move of up to the remaining amount into {@code slotIdx}; {@code false} when this slot is done
     * (amount reached, slot full, nothing left in the inventory, or the slot refused the item).
     */
    private boolean load(TaskContext ctx, LocalPlayer p, AbstractFurnaceMenu menu, int slotIdx, String glob, int want,
                         boolean input) {
        if (!menu.getCarried().isEmpty()) {
            return session.returnCarried(p); // a refused placement left items on the cursor
        }
        Slot dst = menu.getSlot(slotIdx);
        ItemStack cur = dst.getItem();
        if (watchSlot == slotIdx) {
            watchSlot = -1;
            if (cur.getCount() == watchCount) {
                return false; // the slot did not take the item (e.g. not a fuel)
            }
        }
        String id = input ? inputId : fuelId;
        if (!cur.isEmpty()) {
            id = McIds.item(cur);
        } else if (id == null) {
            Slot first = TakeTask.firstMatching(session.mainSlots(p), glob);
            id = first == null ? null : McIds.item(first.getItem());
        }
        if (id == null) {
            return false;
        }
        if (input) {
            inputId = id;
        } else {
            fuelId = id;
        }
        Map<String, Integer> baseline = input ? afterCollect : afterInput;
        int done = baseline.getOrDefault(id, 0) - Inv.totals(p, false).getOrDefault(id, 0);
        int left = want - Math.max(0, done);
        if (left <= 0) {
            return false;
        }
        int room = cur.isEmpty() ? McIds.itemById(id).map(Item::getDefaultMaxStackSize).orElse(64)
                : cur.getMaxStackSize() - cur.getCount();
        if (room <= 0) {
            if (input) {
                inputFull = true;
            }
            return false;
        }
        Slot src = null;
        for (Slot s : session.mainSlots(p)) {
            if (!s.getItem().isEmpty() && id.equals(McIds.item(s.getItem()))) {
                src = s;
                if (s.getItem().getCount() == Math.min(left, room)) {
                    break;
                }
            }
        }
        if (src == null) {
            return false;
        }
        int n = Math.min(Math.min(left, room), src.getItem().getCount());
        ctx.step((input ? "loading " : "fuelling with ") + id, -1);
        watchSlot = slotIdx;
        watchCount = cur.getCount();
        session.movePartial(src, dst, n);
        return true;
    }

    private void finish(TaskContext ctx, LocalPlayer p, AbstractFurnaceMenu menu) {
        session.close(ctx);
        JsonObject d = data(p);
        int loaded = d.get("loaded").getAsInt();
        if (loaded > 0 || inputFull) {
            ctx.succeed("loaded " + loaded + " " + (inputId == null ? inputGlob : inputId), d);
        } else {
            ctx.fail(Reasons.MISSING_MATERIALS, "no " + inputGlob + " in the inventory", d);
        }
    }

    private JsonObject data(LocalPlayer p) {
        Map<String, Integer> now = p == null ? Map.of() : Inv.totals(p, false);
        Map<String, Integer> collected = afterCollect == null ? Map.of() : Inv.gained(before, afterCollect);
        Map<String, Integer> inputEnd = afterInput == null ? now : afterInput; // input and fuel may be one item
        int loaded = inputId == null || afterCollect == null ? 0
                : Math.max(0, afterCollect.getOrDefault(inputId, 0) - inputEnd.getOrDefault(inputId, 0));
        int fuel = fuelId == null || afterInput == null ? 0
                : Math.max(0, afterInput.getOrDefault(fuelId, 0) - now.getOrDefault(fuelId, 0));
        JsonObject d = Json.obj("loaded", loaded, "fuel", fuel, "collected", TaskArgs.counts(collected));
        if (inputId != null) {
            d.addProperty("input", inputId);
        }
        if (fuelId != null) {
            d.addProperty("fuelItem", fuelId);
        }
        if (inputFull) {
            d.addProperty("inputFull", true);
        }
        if (fuelBlocked != null) {
            d.addProperty("fuelBlocked", fuelBlocked);
        }
        return d;
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (session != null) {
            session.close(ctx);
        }
    }
}
