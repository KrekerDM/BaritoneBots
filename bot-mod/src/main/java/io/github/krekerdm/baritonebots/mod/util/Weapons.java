package io.github.krekerdm.baritonebots.mod.util;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Id-based ranking of weapons and armor. Reading attribute modifiers would be exact but ties the code to
 * attribute APIs that change often; vanilla material tiers are stable and good enough for picking the best item.
 */
public final class Weapons {
    private static final String[] TIERS = {"wooden", "leather", "golden", "stone", "chainmail", "copper", "iron",
            "turtle", "diamond", "netherite"};

    private Weapons() {
    }

    /** Material tier of a vanilla item id (0 = unknown). */
    public static int tier(String id) {
        String path = Ids.path(id);
        for (int i = TIERS.length - 1; i >= 0; i--) {
            if (path.startsWith(TIERS[i] + "_")) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Melee score: swords over axes over maces/tridents, then by tier; 0 = not a weapon. */
    public static int score(ItemStack s) {
        String id = McIds.item(s);
        if (id == null) {
            return 0;
        }
        String path = Ids.path(id);
        int base;
        if (path.endsWith("_sword")) {
            base = 300;
        } else if (path.endsWith("_axe")) {
            base = 200;
        } else if (path.equals("mace") || path.equals("trident")) {
            base = 250;
        } else {
            return 0;
        }
        return base + tier(id) * 10 + (int) Math.round(Inv.durabilityLeft(s) * 5);
    }

    /** Inventory index in {@code [from, to)} with the highest {@link #score}, or -1. */
    public static int bestSlot(Player p, int from, int to) {
        int best = -1;
        int bestScore = 0;
        for (int i = from; i < to; i++) {
            int sc = score(p.getInventory().getItem(i));
            if (sc > bestScore) {
                best = i;
                bestScore = sc;
            }
        }
        return best;
    }

    /** Armor slot of a vanilla armor piece by id suffix, or {@code null}. */
    public static EquipmentSlot armorSlot(ItemStack s) {
        String id = McIds.item(s);
        if (id == null) {
            return null;
        }
        String path = Ids.path(id);
        if (path.endsWith("_helmet")) {
            return EquipmentSlot.HEAD;
        }
        if (path.endsWith("_chestplate")) {
            return EquipmentSlot.CHEST;
        }
        if (path.endsWith("_leggings")) {
            return EquipmentSlot.LEGS;
        }
        if (path.endsWith("_boots")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    /** Armor score: tier first, then remaining durability; 0 for empty/non-armor. */
    public static int armorScore(ItemStack s) {
        if (armorSlot(s) == null) {
            return 0;
        }
        return 100 + tier(McIds.item(s)) * 100 + (int) Math.round(Inv.durabilityLeft(s) * 99);
    }
}
