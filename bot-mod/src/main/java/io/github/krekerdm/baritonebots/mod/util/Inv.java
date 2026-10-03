package io.github.krekerdm.baritonebots.mod.util;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Player inventory helpers. Inventory indices follow {@link Inventory}: 0-8 hotbar, 9-35 main, 36-39 armor
 * (feet..head), 40 offhand. Menu slot ids (for {@code handleContainerInput}) are different; use
 * {@link #toInventoryMenuSlot(int)} for the player's own {@link InventoryMenu}.
 */
public final class Inv {
    public static final int HOTBAR_SIZE = Inventory.SELECTION_SIZE;
    public static final int MAIN_SIZE = Inventory.INVENTORY_SIZE;
    public static final int OFFHAND = Inventory.SLOT_OFFHAND;
    /** Armor in status/query order: head, chest, legs, feet. */
    public static final EquipmentSlot[] ARMOR_HEAD_TO_FEET = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Inv() {
    }

    /** InventoryMenu slot id for an inventory index (hotbar 36-44, main 9-35, armor 5-8, offhand 45). */
    public static int toInventoryMenuSlot(int invSlot) {
        if (invSlot >= 0 && invSlot < HOTBAR_SIZE) {
            return InventoryMenu.USE_ROW_SLOT_START + invSlot;
        }
        if (invSlot >= HOTBAR_SIZE && invSlot < MAIN_SIZE) {
            return invSlot;
        }
        if (invSlot >= 36 && invSlot <= 39) {
            // 36 feet .. 39 head  ->  8 feet .. 5 head
            return InventoryMenu.ARMOR_SLOT_START + (39 - invSlot);
        }
        if (invSlot == OFFHAND) {
            return InventoryMenu.SHIELD_SLOT;
        }
        throw new IllegalArgumentException("no InventoryMenu slot for inventory index " + invSlot);
    }

    /** Empty slots among the 36 main + hotbar slots. */
    public static int freeSlots(Player p) {
        int free = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Total count of matching items in main, hotbar and offhand. */
    public static int count(Player p, Predicate<ItemStack> filter) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && filter.test(s)) {
                n += s.getCount();
            }
        }
        ItemStack off = inv.getItem(OFFHAND);
        if (!off.isEmpty() && filter.test(off)) {
            n += off.getCount();
        }
        return n;
    }

    /** Count of items whose id matches any glob. */
    public static int count(Player p, Collection<String> globs) {
        return count(p, s -> Ids.matchesAny(globs, McIds.item(s)));
    }

    /** Item totals by id over main, hotbar, offhand and (optionally) armor. Sorted by id. */
    public static Map<String, Integer> totals(Player p, boolean includeArmor) {
        Map<String, Integer> out = new TreeMap<>();
        Inventory inv = p.getInventory();
        int end = includeArmor ? 39 : MAIN_SIZE - 1;
        for (int i = 0; i <= end; i++) {
            add(out, inv.getItem(i));
        }
        add(out, inv.getItem(OFFHAND));
        return out;
    }

    private static void add(Map<String, Integer> out, ItemStack s) {
        if (!s.isEmpty()) {
            out.merge(McIds.item(s), s.getCount(), Integer::sum);
        }
    }

    /** Positive per-id differences {@code after - before}. */
    public static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new LinkedHashMap<>();
        after.forEach((id, n) -> {
            int d = n - before.getOrDefault(id, 0);
            if (d > 0) {
                out.put(id, d);
            }
        });
        return out;
    }

    /** First inventory index (hotbar first, then main, then offhand) holding a matching stack, or -1. */
    public static int findSlot(Player p, Predicate<ItemStack> filter) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < MAIN_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && filter.test(s)) {
                return i;
            }
        }
        ItemStack off = inv.getItem(OFFHAND);
        return !off.isEmpty() && filter.test(off) ? OFFHAND : -1;
    }

    /** Empty hotbar index or -1. */
    public static int emptyHotbarSlot(Player p) {
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (p.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** True when no container screen/menu is open, i.e. inventory clicks go to the player's own menu. */
    public static boolean ownMenuOpen(Player p) {
        return p.containerMenu == p.inventoryMenu;
    }

    /**
     * Swaps inventory index {@code invSlot} (main 9-35, hotbar 0-8 or offhand 40) with hotbar slot
     * {@code hotbar} using one {@code SWAP} click in the player's own menu. Needs {@link #ownMenuOpen}.
     */
    public static void swapWithHotbar(Minecraft mc, LocalPlayer p, int invSlot, int hotbar) {
        if (invSlot == hotbar) {
            return;
        }
        InventoryMenu menu = p.inventoryMenu;
        click(mc, p, menu, toInventoryMenuSlot(invSlot), hotbar, ContainerInput.SWAP);
    }

    /** One container click; {@code button} is the mouse button, or the hotbar index for {@code SWAP}. */
    public static void click(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, int slotId, int button,
                             ContainerInput input) {
        mc.gameMode.handleContainerInput(menu.containerId, slotId, button, input, p);
    }

    /** Selects a hotbar slot; the server is told on the next game-mode tick or item use. */
    public static void select(LocalPlayer p, int hotbar) {
        if (hotbar >= 0 && hotbar < HOTBAR_SIZE) {
            p.getInventory().setSelectedSlot(hotbar);
        }
    }

    /** Remaining durability as a fraction 0..1, or 1 for undamageable stacks. */
    public static double durabilityLeft(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) {
            return 1.0;
        }
        return (s.getMaxDamage() - s.getDamageValue()) / (double) s.getMaxDamage();
    }
}
