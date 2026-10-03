package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonArray;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.task.ClickQueue;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Weapons;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/**
 * {@code equip [armor=true] [offhand:glob]}: puts the best armor from the inventory on (tier, then durability;
 * unknown wearables such as elytra or heads are left alone) and moves a matching item to the offhand. Clicks go
 * through the player's own inventory menu, at most 2 per tick.
 */
public final class EquipTask implements TaskExecutor {
    private static final int MAX_OPS = 12;

    private final ClickQueue clicks = new ClickQueue();
    private final JsonArray equipped = new JsonArray();
    private boolean armor;
    private String offhandGlob;
    private int ops;

    @Override
    public void start(TaskContext ctx) {
        armor = Json.getBool(ctx.args(), "armor", true);
        String off = Json.getString(ctx.args(), "offhand", null);
        offhandGlob = off == null || off.isBlank() ? null : Ids.normalizeGlob(off.trim());
        Interact.closeContainer(ctx.player());
        ctx.step("equipping", -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        LocalPlayer p = ctx.player();
        if (!clicks.isEmpty()) {
            clicks.tick(ctx.mc(), p);
            return;
        }
        if (!Inv.ownMenuOpen(p)) {
            Interact.closeContainer(p);
            return;
        }
        if (ops++ >= MAX_OPS || !plan(p)) {
            ctx.succeed(equipped.isEmpty() ? "nothing better to equip" : "equipped", Json.obj("equipped", equipped));
        }
    }

    private boolean plan(LocalPlayer p) {
        InventoryMenu menu = p.inventoryMenu;
        if (armor) {
            for (EquipmentSlot slot : Inv.ARMOR_HEAD_TO_FEET) {
                ItemStack cur = p.getItemBySlot(slot);
                if (!cur.isEmpty() && Weapons.armorSlot(cur) == null) {
                    continue;
                }
                int curScore = Weapons.armorScore(cur);
                int best = -1;
                int bestScore = curScore;
                for (int i = 0; i < Inv.MAIN_SIZE; i++) {
                    ItemStack s = p.getInventory().getItem(i);
                    if (Weapons.armorSlot(s) == slot && Weapons.armorScore(s) > bestScore) {
                        best = i;
                        bestScore = Weapons.armorScore(s);
                    }
                }
                if (best >= 0) {
                    int from = Inv.toInventoryMenuSlot(best);
                    int to = armorMenuSlot(slot);
                    equipped.add(McIds.item(p.getInventory().getItem(best)));
                    clicks.add(menu, from, 0, ContainerInput.PICKUP);
                    clicks.add(menu, to, 0, ContainerInput.PICKUP);
                    if (!cur.isEmpty()) {
                        clicks.add(menu, from, 0, ContainerInput.PICKUP);
                    }
                    return true;
                }
            }
        }
        if (offhandGlob != null) {
            String glob = offhandGlob;
            offhandGlob = null;
            ItemStack off = p.getOffhandItem();
            if (off.isEmpty() || !Ids.matches(glob, McIds.item(off))) {
                for (int i = 0; i < Inv.MAIN_SIZE; i++) {
                    ItemStack s = p.getInventory().getItem(i);
                    if (!s.isEmpty() && Ids.matches(glob, McIds.item(s))) {
                        equipped.add(McIds.item(s));
                        clicks.add(menu, Inv.toInventoryMenuSlot(i), Inv.OFFHAND, ContainerInput.SWAP);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int armorMenuSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> InventoryMenu.ARMOR_SLOT_START;
            case CHEST -> InventoryMenu.ARMOR_SLOT_START + 1;
            case LEGS -> InventoryMenu.ARMOR_SLOT_START + 2;
            default -> InventoryMenu.ARMOR_SLOT_START + 3;
        };
    }
}
