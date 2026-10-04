package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.baritone.SettingsOverride;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared parts of {@code breed}, {@code slaughter} and {@code shear}: the pen box, animals of one type inside it
 * (entity block position inside the box), putting an item into the main hand, and keeping Baritone from breaking
 * the pen (fences, fence gates and walls are added to {@code blocksToDisallowBreaking} for the task).
 */
final class AnimalPen {
    private static final int SWAP_INTERVAL_TICKS = 5;

    final Box box;
    final AABB aabb;
    final EntityType<?> type;
    final String typeId;
    private final SettingsOverride settings = new SettingsOverride();
    private int lastSwapTick = -SWAP_INTERVAL_TICKS;

    private AnimalPen(Box box, EntityType<?> type) {
        this.box = box;
        this.aabb = new AABB(box.min().x(), box.min().y(), box.min().z(),
                box.max().x() + 1, box.max().y() + 1, box.max().z() + 1);
        this.type = type;
        this.typeId = type == null ? null : McIds.entityType(type);
    }

    /** Parses {@code box} and (unless {@code defaultType} is given) {@code animal}; fails the task on bad input. */
    static AnimalPen parse(TaskContext ctx, EntityType<?> defaultType) {
        Box box = Box.fromJson(ctx.args().get("box"));
        if (box == null) {
            ctx.fail(Reasons.BAD_ARGS, "'box' {a,b} (the pen) is required", null);
            return null;
        }
        if (box.volume() > 1_000_000) {
            ctx.fail(Reasons.BAD_ARGS, "pen box is too large (" + box.volume() + " blocks)", null);
            return null;
        }
        EntityType<?> type = defaultType;
        if (type == null) {
            String id = Json.getString(ctx.args(), "animal", "");
            type = id.isBlank() ? null : McIds.entityTypeById(Ids.normalize(id.trim())).orElse(null);
            if (type == null) {
                ctx.fail(Reasons.BAD_ARGS, "'animal' must be a known entity type id, got '" + id + "'", null);
                return null;
            }
        }
        return new AnimalPen(box, type);
    }

    /** Living animals of the pen's type standing inside the box, nearest to the player first. */
    <T extends LivingEntity> List<T> animals(TaskContext ctx, Class<T> cls) {
        LocalPlayer p = ctx.player();
        List<T> out = new ArrayList<>(ctx.bot().level().getEntitiesOfClass(cls, aabb,
                e -> e.isAlive() && e.getType() == type && inside(e)));
        out.sort(Comparator.comparingDouble(p::distanceToSqr));
        return out;
    }

    boolean inside(Entity e) {
        BlockPos b = e.blockPosition();
        return box.contains(b.getX(), b.getY(), b.getZ());
    }

    /** Area searched for drops: the pen plus one block (items pop onto fences). */
    AABB itemArea() {
        return aabb.inflate(1);
    }

    /** Fences, fence gates and walls must not be broken while working in the pen. */
    void protectFences() {
        Settings s = BaritoneAPI.getSettings();
        List<Block> list = new ArrayList<>(s.blocksToDisallowBreaking.value);
        for (Block b : BuiltInRegistries.BLOCK) {
            if (!list.contains(b) && (b.defaultBlockState().is(BlockTags.FENCES)
                    || b.defaultBlockState().is(BlockTags.FENCE_GATES) || b.defaultBlockState().is(BlockTags.WALLS))) {
                list.add(b);
            }
        }
        settings.set(s.blocksToDisallowBreaking, list);
    }

    void restoreSettings() {
        settings.restore();
    }

    /**
     * Puts a matching item into the main hand: selects it on the hotbar, or swaps it there from the main inventory
     * / offhand (one SWAP click, at most every {@value #SWAP_INTERVAL_TICKS} ticks, only in the player's own menu
     * and never while another inventory action runs).
     *
     * @return 1 = in hand now, 0 = swap in progress (try again next tick), -1 = none in the inventory
     */
    int holdInMainHand(TaskContext ctx, Predicate<ItemStack> item) {
        LocalPlayer p = ctx.player();
        if (item.test(p.getMainHandItem())) {
            return 1;
        }
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inv.HOTBAR_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && item.test(s)) {
                Inv.select(p, i);
                return 1;
            }
        }
        int slot = Inv.findSlot(p, s -> item.test(s));
        if (slot < 0) {
            return -1;
        }
        if (!Inv.ownMenuOpen(p) || ctx.bot().pause.inventoryBusy() || ctx.bot().eater.busy()
                || ctx.ticks() - lastSwapTick < SWAP_INTERVAL_TICKS) {
            return 0;
        }
        int target = Inv.emptyHotbarSlot(p);
        if (target < 0) {
            target = inv.getSelectedSlot();
        }
        Inv.swapWithHotbar(ctx.mc(), p, slot, target);
        Inv.select(p, target);
        lastSwapTick = ctx.ticks();
        return 0;
    }
}
