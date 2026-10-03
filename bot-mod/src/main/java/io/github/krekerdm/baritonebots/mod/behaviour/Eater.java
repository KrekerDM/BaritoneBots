package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Eats one food item while holding a {@link io.github.krekerdm.baritonebots.mod.baritone.PauseProcess} claim.
 * Shared by the auto-eat behaviour and the {@code eat} task; only one owner eats at a time. Client thread only.
 * <p>
 * Steps: pick the most nourishing allowed food (not in {@code autoEat.avoid}), move it to the hotbar with one
 * {@code SWAP} click if needed, select it, then hold the use key until the item is consumed (max 100 ticks).
 */
public final class Eater {
    public enum Result { NONE, RUNNING, DONE, NO_FOOD, FAILED }

    private static final int MAX_EAT_TICKS = 100;

    private final BotRuntime bot;
    private String owner;
    private InteractionHand hand;
    private int ticks;
    private int prevSelected = -1;
    private int startFood;
    private int startCount;
    private boolean sawUsing;
    private Result result = Result.NONE;

    public Eater(BotRuntime bot) {
        this.bot = bot;
    }

    public boolean busy() {
        return owner != null;
    }

    public String owner() {
        return owner;
    }

    /** Result of the last eat attempt of {@code who} ({@code NONE} if someone else owns the eater). */
    public Result result(String who) {
        return who.equals(owner) || owner == null ? result : Result.NONE;
    }

    /** Whether the inventory holds any allowed food. */
    public boolean hasFood(LocalPlayer p) {
        return findFood(p) >= 0;
    }

    /** Starts eating; returns {@code false} (and result {@code NO_FOOD}) when there is nothing allowed to eat. */
    public boolean start(String who) {
        LocalPlayer p = bot.player();
        if (busy() || p == null || bot.mc.gameMode == null) {
            return false;
        }
        int slot = findFood(p);
        if (slot < 0) {
            result = Result.NO_FOOD;
            return false;
        }
        Interact.closeContainer(p);
        owner = who;
        ticks = 0;
        sawUsing = false;
        result = Result.RUNNING;
        startFood = p.getFoodData().getFoodLevel();
        prevSelected = p.getInventory().getSelectedSlot();
        if (slot == Inv.OFFHAND) {
            hand = InteractionHand.OFF_HAND;
        } else {
            hand = InteractionHand.MAIN_HAND;
            int hotbar = slot;
            if (slot >= Inv.HOTBAR_SIZE) {
                hotbar = pickHotbarSlot(p);
                Inv.swapWithHotbar(bot.mc, p, slot, hotbar);
            }
            Inv.select(p, hotbar);
        }
        startCount = p.getItemInHand(hand).getCount();
        bot.pause.claim(who);
        return true;
    }

    /** Advances eating; call every tick while {@link #busy()}. */
    public void tick() {
        if (owner == null) {
            return;
        }
        Minecraft mc = bot.mc;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || p.isDeadOrDying()) {
            finish(Result.FAILED);
            return;
        }
        ticks++;
        if (p.isUsingItem()) {
            sawUsing = true;
            mc.options.keyUse.setDown(true);
        } else if (sawUsing) {
            boolean ate = p.getFoodData().getFoodLevel() > startFood || p.getItemInHand(hand).getCount() < startCount
                    || !isFood(p.getItemInHand(hand));
            finish(ate ? Result.DONE : Result.FAILED);
            return;
        } else if (ticks > 2 && !isFood(p.getItemInHand(hand))) {
            finish(Result.FAILED); // swap did not land (server rejected the click)
            return;
        } else {
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(p, hand);
        }
        if (ticks > MAX_EAT_TICKS) {
            finish(Result.FAILED);
        }
    }

    /** Aborts eating and releases the pause claim. */
    public void stop() {
        if (owner != null) {
            finish(Result.FAILED);
        }
    }

    private void finish(Result r) {
        Minecraft mc = bot.mc;
        mc.options.keyUse.setDown(false);
        LocalPlayer p = mc.player;
        if (p != null && p.isUsingItem() && mc.gameMode != null) {
            mc.gameMode.releaseUsingItem(p);
        }
        if (p != null && prevSelected >= 0 && hand == InteractionHand.MAIN_HAND) {
            Inv.select(p, prevSelected);
        }
        if (owner != null) {
            bot.pause.release(owner);
        }
        owner = null;
        prevSelected = -1;
        result = r;
    }

    /** Hotbar slot to receive food: an empty one, else one not holding a tool/weapon, else the last. */
    private static int pickHotbarSlot(LocalPlayer p) {
        int empty = Inv.emptyHotbarSlot(p);
        if (empty >= 0) {
            return empty;
        }
        for (int i = Inv.HOTBAR_SIZE - 1; i >= 0; i--) {
            if (!p.getInventory().getItem(i).isDamageableItem()) {
                return i;
            }
        }
        return Inv.HOTBAR_SIZE - 1;
    }

    private boolean isFood(ItemStack s) {
        return !s.isEmpty() && s.has(DataComponents.FOOD);
    }

    private boolean allowed(ItemStack s) {
        if (!isFood(s)) {
            return false;
        }
        List<String> avoid = bot.config().behaviour().autoEat().avoid();
        return !Ids.matchesAny(avoid, McIds.item(s));
    }

    /** Inventory index of the best allowed food, preferring the offhand and hotbar on ties; -1 if none. */
    private int findFood(LocalPlayer p) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        ItemStack off = p.getInventory().getItem(Inv.OFFHAND);
        if (allowed(off)) {
            best = Inv.OFFHAND;
            bestScore = score(off) + 1;
        }
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (allowed(s)) {
                int sc = score(s) + (i < Inv.HOTBAR_SIZE ? 1 : 0);
                if (sc > bestScore) {
                    best = i;
                    bestScore = sc;
                }
            }
        }
        return best;
    }

    private static int score(ItemStack s) {
        FoodProperties f = s.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition() * 10;
    }
}
