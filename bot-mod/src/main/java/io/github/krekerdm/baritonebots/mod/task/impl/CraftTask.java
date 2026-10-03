package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.ContainerSession;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.task.plan.CraftGrid;
import io.github.krekerdm.baritonebots.mod.task.plan.SlotMoves;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Recipes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code craft item count [table] [grid]} (SPEC §3).
 * <ol>
 *   <li>Station: the 2×2 inventory grid when a known recipe (or the given grid) fits it and no {@code table} is
 *       given, else the given crafting table, else the nearest one within {@value #TABLE_SEARCH_RADIUS} blocks.</li>
 *   <li>Recipe book: every known recipe for the item that fits the grid and that the client thinks is craftable
 *       is placed with {@code handlePlaceRecipe}: one set per request (the server adds a set when the same recipe
 *       is already placed), or "max" when ≥ 64 crafts are still needed, so a shift-click never overshoots.</li>
 *   <li>Manual fallback from {@code grid}: exactly {@code n} items per cell with PICKUP clicks (the first round
 *       places one set to learn the yield).</li>
 *   <li>{@code QUICK_MOVE} on the result crafts every placed set; repeat until {@code count} is reached or nothing
 *       more can be placed. Leftovers in the grid are shift-clicked back before the next round and at the end.</li>
 * </ol>
 * ok with {@code {crafted, item, requested}} (also when fewer than requested could be made);
 * {@code missing_materials} when nothing could be crafted.
 */
public final class CraftTask implements TaskExecutor {
    private enum Phase { PLAN, BOOK_WAIT, FILL, RESULT_WAIT, TAKE_WAIT, CLEAR, CLEAR_WAIT, DONE }

    static final int TABLE_SEARCH_RADIUS = 16;
    private static final int BOOK_WAIT_TICKS = 12;
    private static final int RESULT_WAIT_TICKS = 20;
    private static final int SYNC_TICKS = 3;
    private static final int MAX_REQUEST_SETS = 64;

    private String item;
    private int count;
    private List<CraftGrid.Cell> layout;
    private ContainerSession session;
    private Map<String, Integer> before;
    private List<Recipes.Option> book = List.of();
    private int bookIndex;
    private int perCraft;
    private boolean manualFailed;
    private String failReason;
    private String failMessage;
    private boolean inventoryFull;
    private Phase phase = Phase.PLAN;
    private Phase afterClear = Phase.PLAN;
    private int waitTicks;
    private boolean usingBook;
    private int targetSets;
    private int requestedSets;
    private int lastSets;
    private int lastRequestTick = -100;
    private CraftGrid.Round round;
    private int fillIndex;
    private int craftedBeforeTake;
    private int clearAttempts;
    private int stalls;

    @Override
    public void start(TaskContext ctx) {
        JsonObject a = ctx.args();
        String raw = Json.getString(a, "item", null);
        if (raw == null || raw.isBlank()) {
            ctx.fail(Reasons.BAD_ARGS, "craft needs 'item'", null);
            return;
        }
        item = Ids.normalize(raw.trim());
        if (McIds.itemById(item).isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "unknown item '" + raw + "'", null);
            return;
        }
        count = Json.getInt(a, "count", 1);
        if (count < 1) {
            ctx.fail(Reasons.BAD_ARGS, "count must be at least 1", null);
            return;
        }
        CraftGrid grid;
        try {
            grid = CraftGrid.parse(a.get("grid"));
        } catch (IllegalArgumentException e) {
            ctx.fail(Reasons.BAD_ARGS, e.getMessage(), null);
            return;
        }
        if (grid != null && grid.isEmpty()) {
            grid = null;
        }
        BlockPos table = TaskArgs.pos(a, "table");
        if (table == null && a.has("table") && !a.get("table").isJsonNull()) {
            ctx.fail(Reasons.BAD_ARGS, "'table' must be a position", null);
            return;
        }
        LocalPlayer p = ctx.player();
        List<Recipes.Option> known = Recipes.forItem(p, ctx.bot().level(), item);
        boolean small;
        if (table != null) {
            small = false;
        } else if (known.stream().anyMatch(o -> !o.needsTable())) {
            small = true;
        } else if (grid != null && grid.fits(2, 2)) {
            small = true;
        } else if (known.isEmpty() && grid == null) {
            ctx.fail(Reasons.NOT_FOUND, "the recipe book knows no recipe for " + item + "; pass 'grid'", data(0));
            return;
        } else {
            small = false;
            table = findTable(ctx.bot().level(), ctx.feet());
            if (table == null) {
                ctx.fail(Reasons.NOT_FOUND, item + " needs a crafting table: pass 'table' or stand within "
                        + TABLE_SEARCH_RADIUS + " blocks of one", data(0));
                return;
            }
        }
        int gw = small ? 2 : 3;
        StackedItemContents contents = Recipes.inventoryContents(p);
        List<Recipes.Option> usable = new ArrayList<>();
        for (Recipes.Option o : known) {
            if (o.fits(gw, gw) && !Boolean.FALSE.equals(o.craftable(contents))) {
                usable.add(o);
            }
        }
        usable.sort(Comparator.comparing((Recipes.Option o) -> !Boolean.TRUE.equals(o.craftable(contents))));
        book = usable;
        layout = grid == null ? null : grid.layout(gw);
        if (book.isEmpty() && layout == null) {
            ctx.fail(Reasons.MISSING_MATERIALS, "not enough materials for any known recipe for " + item, data(0));
            return;
        }
        before = Inv.totals(p, false);
        session = small ? ContainerSession.playerInventory() : new ContainerSession(table);
        ctx.step("crafting " + item, 0);
    }

    @Override
    public void tick(TaskContext ctx) {
        session.tick(ctx);
        if (session.failed()) {
            session.close(ctx);
            ctx.fail(session.failReason(), session.failMessage(), data(crafted(ctx.player())));
            return;
        }
        if (!session.idle()) {
            return;
        }
        LocalPlayer p = ctx.player();
        AbstractContainerMenu m = session.menu();
        if (!(m instanceof AbstractCraftingMenu menu)) {
            session.close(ctx);
            ctx.fail(Reasons.CONTAINER_FAILED, "the block at " + session.pos().toShortString()
                    + " is not a crafting table", data(crafted(p)));
            return;
        }
        switch (phase) {
            case PLAN -> plan(ctx, p, menu);
            case BOOK_WAIT -> bookWait(ctx, menu);
            case FILL -> fill(ctx, p, menu);
            case RESULT_WAIT -> resultWait(ctx, p, menu);
            case TAKE_WAIT -> takeWait(p);
            case CLEAR -> clear(ctx, p, menu);
            case CLEAR_WAIT -> clearWait(ctx, p, menu);
            case DONE -> complete(ctx, p, menu);
        }
    }

    // ------------------------------------------------------------------ phases

    private void plan(TaskContext ctx, LocalPlayer p, AbstractCraftingMenu menu) {
        if (!menu.getCarried().isEmpty() || !gridEmpty(menu)) {
            startClear(Phase.PLAN);
            return;
        }
        int crafted = crafted(p);
        if (crafted >= count) {
            phase = Phase.DONE;
            return;
        }
        int remaining = count - crafted;
        ctx.step("crafting " + item + " (" + crafted + "/" + count + ")", crafted / (double) count);
        if (bookIndex < book.size()) {
            Recipes.Option o = book.get(bookIndex);
            int r = Math.min(SlotMoves.craftsFor(remaining, o.perCraft()), capacity(p, o.perCraft()));
            if (r <= 0) {
                inventoryFull = true;
                phase = Phase.DONE;
                return;
            }
            usingBook = true;
            targetSets = r >= MAX_REQUEST_SETS ? -1 : r;
            requestedSets = 0;
            lastSets = 0;
            waitTicks = 0;
            request(ctx, menu, o);
            phase = Phase.BOOK_WAIT;
            return;
        }
        if (layout != null && !manualFailed) {
            int maxSets = perCraft <= 0 ? 1
                    : Math.min(SlotMoves.craftsFor(remaining, perCraft), capacity(p, perCraft));
            if (maxSets <= 0) {
                inventoryFull = true;
                phase = Phase.DONE;
                return;
            }
            round = CraftGrid.planRound(layout, available(p), this::maxStack, maxSets);
            if (round.sets() <= 0) {
                phase = Phase.DONE; // out of materials
                return;
            }
            usingBook = false;
            fillIndex = 0;
            phase = Phase.FILL;
            fill(ctx, p, menu);
            return;
        }
        phase = Phase.DONE;
    }

    private void request(TaskContext ctx, AbstractCraftingMenu menu, Recipes.Option o) {
        boolean max = targetSets < 0;
        ctx.mc().gameMode.handlePlaceRecipe(menu.containerId, o.entry().id(), max);
        requestedSets = max ? MAX_REQUEST_SETS : requestedSets + 1;
        lastRequestTick = ctx.ticks();
    }

    private void bookWait(TaskContext ctx, AbstractCraftingMenu menu) {
        int sets = gridSets(menu);
        if (sets > lastSets) {
            lastSets = sets;
            waitTicks = 0;
        } else {
            waitTicks++;
        }
        if (targetSets < 0 ? sets > 0 && waitTicks >= SYNC_TICKS : sets >= targetSets) {
            toResult();
            return;
        }
        if (targetSets > 0 && sets >= requestedSets && ctx.ticks() - lastRequestTick >= 2) {
            request(ctx, menu, book.get(bookIndex)); // the last set landed: ask for one more
            return;
        }
        if (waitTicks > BOOK_WAIT_TICKS) {
            if (sets > 0) {
                toResult(); // out of materials for more sets
            } else {
                bookIndex++; // the server would not place this recipe
                phase = Phase.PLAN;
            }
        }
    }

    private void fill(TaskContext ctx, LocalPlayer p, AbstractCraftingMenu menu) {
        List<Slot> gridSlots = menu.getInputGridSlots();
        while (fillIndex < layout.size()) {
            CraftGrid.Cell cell = layout.get(fillIndex);
            if (cell.gridIndex() >= gridSlots.size()) {
                manualFailed("the grid does not fit this crafting station");
                return;
            }
            Slot dst = gridSlots.get(cell.gridIndex());
            String id = round.ids().get(cell.gridIndex());
            ItemStack have = dst.getItem();
            if (!have.isEmpty() && !id.equals(McIds.item(have))) {
                stall(ctx);
                return;
            }
            int need = round.sets() - have.getCount();
            if (need <= 0) {
                fillIndex++;
                continue;
            }
            Slot src = source(p, id, need);
            if (src == null) {
                stall(ctx); // the inventory changed under us: put everything back and plan again
                return;
            }
            ctx.step("placing " + id, -1);
            session.movePartial(src, dst, Math.min(need, src.getItem().getCount()));
            return; // one move per idle tick: plans always see the synced slots
        }
        toResult();
    }

    private void toResult() {
        waitTicks = 0;
        phase = Phase.RESULT_WAIT;
    }

    private void resultWait(TaskContext ctx, LocalPlayer p, AbstractCraftingMenu menu) {
        ItemStack r = menu.getResultSlot().getItem();
        if (r.isEmpty()) {
            if (++waitTicks > RESULT_WAIT_TICKS) {
                recipeFailed("the placed items craft nothing");
            }
            return;
        }
        String id = McIds.item(r);
        if (!item.equals(id)) {
            recipeFailed("the placed items craft " + id + ", not " + item);
            return;
        }
        perCraft = r.getCount();
        if (capacity(p, perCraft) < gridSets(menu)) {
            inventoryFull = true; // a shift-click would drop what does not fit
            startClear(Phase.DONE);
            return;
        }
        craftedBeforeTake = crafted(p);
        ctx.step("taking " + item, -1);
        session.click(menu.getResultSlot(), 0, ContainerInput.QUICK_MOVE);
        waitTicks = 0;
        phase = Phase.TAKE_WAIT;
    }

    private void takeWait(LocalPlayer p) {
        if (++waitTicks < SYNC_TICKS) {
            return;
        }
        if (crafted(p) > craftedBeforeTake) {
            stalls = 0;
        } else if (++stalls >= 2) {
            failReason = Reasons.ERROR;
            failMessage = "crafting made no progress";
            startClear(Phase.DONE);
            return;
        }
        phase = Phase.PLAN;
    }

    private void startClear(Phase next) {
        afterClear = next;
        clearAttempts = 0;
        phase = Phase.CLEAR;
    }

    private void clear(TaskContext ctx, LocalPlayer p, AbstractCraftingMenu menu) {
        boolean any = false;
        if (!menu.getCarried().isEmpty()) {
            any = session.returnCarried(p);
        }
        for (Slot s : menu.getInputGridSlots()) {
            if (!s.getItem().isEmpty()) {
                session.click(s, 0, ContainerInput.QUICK_MOVE);
                any = true;
            }
        }
        if (!any) {
            phase = afterClear;
            if (phase == Phase.DONE) {
                complete(ctx, p, menu);
            }
            return;
        }
        clearAttempts++;
        waitTicks = 0;
        phase = Phase.CLEAR_WAIT;
    }

    private void clearWait(TaskContext ctx, LocalPlayer p, AbstractCraftingMenu menu) {
        if (++waitTicks < SYNC_TICKS) {
            return;
        }
        if (gridEmpty(menu) && menu.getCarried().isEmpty()) {
            phase = afterClear;
            if (phase == Phase.DONE) {
                complete(ctx, p, menu);
            }
        } else if (clearAttempts >= 2) {
            inventoryFull = true;
            complete(ctx, p, menu); // closing the menu hands the rest back (or drops it)
        } else {
            phase = Phase.CLEAR;
        }
    }

    private void complete(TaskContext ctx, LocalPlayer p, AbstractCraftingMenu menu) {
        if (phase == Phase.DONE && (!gridEmpty(menu) || !menu.getCarried().isEmpty()) && clearAttempts == 0) {
            startClear(Phase.DONE);
            return;
        }
        if (session.ownInventory() && (!gridEmpty(menu) || !menu.getCarried().isEmpty())) {
            p.closeContainer(); // the server's InventoryMenu.removed returns grid + cursor items
        }
        session.close(ctx);
        int crafted = crafted(p);
        JsonObject d = data(crafted);
        if (inventoryFull) {
            d.addProperty("inventoryFull", true);
        }
        if (crafted >= count) {
            ctx.succeed("crafted " + crafted + " " + item, d);
        } else if (crafted > 0) {
            ctx.succeed("crafted " + crafted + " of " + count + " " + item, d);
        } else if (failReason != null) {
            ctx.fail(failReason, failMessage, d);
        } else if (inventoryFull) {
            ctx.fail(Reasons.INVENTORY_FULL, "no room for " + item, d);
        } else {
            ctx.fail(Reasons.MISSING_MATERIALS, "nothing could be crafted: missing materials for " + item, d);
        }
    }

    private void recipeFailed(String message) {
        if (usingBook) {
            bookIndex++;
            startClear(Phase.PLAN);
        } else {
            manualFailed(message);
        }
    }

    private void manualFailed(String message) {
        manualFailed = true;
        failReason = Reasons.BAD_ARGS;
        failMessage = message;
        startClear(Phase.PLAN);
    }

    private void stall(TaskContext ctx) {
        if (++stalls > 3) {
            manualFailed("could not place the grid items");
        } else {
            startClear(Phase.PLAN);
        }
    }

    // ------------------------------------------------------------------ helpers

    private int crafted(LocalPlayer p) {
        if (p == null || before == null) {
            return 0;
        }
        return Math.max(0, Inv.totals(p, false).getOrDefault(item, 0) - before.getOrDefault(item, 0));
    }

    private JsonObject data(int crafted) {
        return Json.obj("crafted", crafted, "item", item, "requested", count);
    }

    /** Sets currently in the grid: the smallest non-empty input stack (0 when empty). */
    private static int gridSets(AbstractCraftingMenu menu) {
        int sets = Integer.MAX_VALUE;
        for (Slot s : menu.getInputGridSlots()) {
            if (!s.getItem().isEmpty()) {
                sets = Math.min(sets, s.getItem().getCount());
            }
        }
        return sets == Integer.MAX_VALUE ? 0 : sets;
    }

    private static boolean gridEmpty(AbstractCraftingMenu menu) {
        return gridSets(menu) == 0;
    }

    /** Crafts whose output ({@code per} items each) still fits into the main inventory. */
    private int capacity(LocalPlayer p, int per) {
        int max = maxStack(item);
        int room = 0;
        for (Slot s : session.mainSlots(p)) {
            ItemStack st = s.getItem();
            if (st.isEmpty()) {
                room += max;
            } else if (item.equals(McIds.item(st))) {
                room += Math.max(0, st.getMaxStackSize() - st.getCount());
            }
        }
        return room / Math.max(1, per);
    }

    private Map<String, Integer> available(LocalPlayer p) {
        Map<String, Integer> out = new TreeMap<>();
        for (Slot s : session.mainSlots(p)) {
            ItemStack st = s.getItem();
            if (!st.isEmpty()) {
                out.merge(McIds.item(st), st.getCount(), Integer::sum);
            }
        }
        return out;
    }

    private int maxStack(String id) {
        return McIds.itemById(id).map(Item::getDefaultMaxStackSize).orElse(64);
    }

    /** Main slot holding {@code id}: an exact-size stack first, else the one needing the fewest clicks. */
    private Slot source(LocalPlayer p, String id, int need) {
        Slot best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Slot s : session.mainSlots(p)) {
            ItemStack st = s.getItem();
            if (st.isEmpty() || !id.equals(McIds.item(st))) {
                continue;
            }
            int c = st.getCount();
            if (c == need) {
                return s;
            }
            int cost = c < need ? 100 + 2 : SlotMoves.cost(c, need); // prefer stacks that cover the need
            if (cost < bestCost) {
                best = s;
                bestCost = cost;
            }
        }
        return best;
    }

    /** Nearest loaded crafting table within {@value #TABLE_SEARCH_RADIUS} blocks, or {@code null}. */
    static BlockPos findTable(ClientLevel level, BlockPos center) {
        if (level == null || center == null) {
            return null;
        }
        int r = TABLE_SEARCH_RADIUS;
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (!level.isLoaded(pos) || !"minecraft:crafting_table".equals(McIds.block(level.getBlockState(pos)))) {
                continue;
            }
            long d = (long) pos.distSqr(center);
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (session != null) {
            LocalPlayer p = ctx.player();
            if (session.ownInventory() && p != null && session.menu() instanceof AbstractCraftingMenu menu
                    && p.containerMenu == menu && (!gridEmpty(menu) || !menu.getCarried().isEmpty())) {
                p.closeContainer(); // cancelled mid-round: let the server hand the grid back
            }
            session.close(ctx);
        }
    }
}
