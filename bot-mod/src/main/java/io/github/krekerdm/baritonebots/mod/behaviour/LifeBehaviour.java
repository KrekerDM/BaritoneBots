package io.github.krekerdm.baritonebots.mod.behaviour;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;
import io.github.krekerdm.baritonebots.mod.util.Reflect;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Death tracking, auto-respawn and health/tool warnings (SPEC §2.6 {@code death}, {@code respawned},
 * {@code damaged}, {@code tool_low}).
 */
public final class LifeBehaviour {
    private static final int RESPAWN_DELAY_TICKS = 20;

    private final BotRuntime bot;
    private final RateLimiter limiter = new RateLimiter(1, 1000);
    private boolean dead;
    private long deadSinceTick;
    private BlockPos lastDeathPos;
    private String lastDeathDim;
    private final float[] healthRing = new float[20];
    private int ringPos;
    private boolean ringFilled;

    public LifeBehaviour(BotRuntime bot) {
        this.bot = bot;
    }

    public boolean dead() {
        return dead;
    }

    public BlockPos lastDeathPos() {
        return lastDeathPos;
    }

    public String lastDeathDim() {
        return lastDeathDim;
    }

    public void tick() {
        LocalPlayer p = bot.player();
        if (!bot.inGame()) {
            ringFilled = false;
            ringPos = 0;
            return;
        }
        boolean deathScreen = bot.mc.gui.screen() instanceof DeathScreen;
        if (!dead && (p.isDeadOrDying() || deathScreen)) {
            onDeath(p);
        } else if (dead && !p.isDeadOrDying() && !deathScreen) {
            dead = false;
            bot.event(EventKinds.RESPAWNED, Levels.INFO, "respawned", Json.obj(
                    "pos", Positions.json(p.blockPosition()), "dim", McIds.dim(bot.level())));
            bot.status.markDirty();
        }
        if (dead && deathScreen && bot.config().behaviour().autoRespawn()
                && bot.ticks() - deadSinceTick >= RESPAWN_DELAY_TICKS && bot.ticks() % 20 == 0) {
            p.respawn();
            bot.mc.gui.setScreen(null);
        }
        if (!dead) {
            watchHealth(p);
            if (bot.ticks() % 40 == 0) {
                watchTool(p);
            }
        }
    }

    private void onDeath(LocalPlayer p) {
        dead = true;
        deadSinceTick = bot.ticks();
        lastDeathPos = p.blockPosition();
        lastDeathDim = McIds.dim(bot.level());
        String cause = null;
        Object c = Reflect.get(bot.mc.gui.screen(), "causeOfDeath");
        if (c instanceof Component comp) {
            cause = comp.getString();
        }
        DamageSource src = p.getLastDamageSource();
        if ((cause == null || cause.isBlank()) && src != null) {
            cause = src.getMsgId();
        }
        JsonObject data = Json.obj("pos", Positions.json(lastDeathPos), "dim", lastDeathDim,
                "cause", cause == null ? "unknown" : cause);
        bot.event(EventKinds.DEATH, Levels.WARN, "died at " + lastDeathPos.toShortString() + " in " + lastDeathDim
                + (cause == null ? "" : ": " + cause), data);
        bot.tasks.onDeath();
        bot.eater.stop();
        bot.defense.reset();
        bot.status.markDirty();
    }

    /** {@code damaged} when health dropped by 4 or more within the last second (20 ticks). */
    private void watchHealth(LocalPlayer p) {
        float h = p.getHealth();
        float oldest = healthRing[ringPos];
        healthRing[ringPos] = h;
        ringPos = (ringPos + 1) % healthRing.length;
        if (ringPos == 0) {
            ringFilled = true;
        }
        float max = h;
        int n = ringFilled ? healthRing.length : ringPos;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, healthRing[i]);
        }
        if (ringFilled && oldest >= 0 && max - h >= 4.0f && limiter.once("damaged", 3_000)) {
            DamageSource src = p.getLastDamageSource();
            Entity attacker = src == null ? null : src.getEntity();
            String source = attacker != null ? McIds.entity(attacker) : src != null ? src.getMsgId() : "unknown";
            bot.event(EventKinds.DAMAGED, Levels.WARN, "took " + Math.round(max - h) + " damage from " + source,
                    Json.obj("source", source, "health", h, "lost", max - h));
        }
    }

    private void watchTool(LocalPlayer p) {
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty() || !hand.isDamageableItem()) {
            return;
        }
        double left = Inv.durabilityLeft(hand);
        if (left <= bot.config().behaviour().lowToolDurability()) {
            String id = McIds.item(hand);
            if (limiter.once("tool_low:" + id, 120_000)) {
                bot.event(EventKinds.TOOL_LOW, Levels.WARN, id + " is almost broken",
                        Json.obj("item", id, "durabilityLeft", hand.getMaxDamage() - hand.getDamageValue()));
            }
        }
    }
}
