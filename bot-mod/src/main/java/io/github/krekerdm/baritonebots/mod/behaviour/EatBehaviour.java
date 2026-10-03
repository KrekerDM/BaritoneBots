package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;
import net.minecraft.client.player.LocalPlayer;

/**
 * Auto-eat (SPEC §2.5 {@code behaviour.autoEat}): eats when food is below {@code belowFood}, or health is below
 * {@code belowHealth} and the bot can still eat. Never interrupts open container work. Raises {@code food_low}
 * (at most every 2 min) when hungry without allowed food.
 */
public final class EatBehaviour {
    public static final String OWNER = "eat";

    private final BotRuntime bot;
    private final RateLimiter limiter = new RateLimiter(1, 1000);
    private int cooldownTicks;

    public EatBehaviour(BotRuntime bot) {
        this.bot = bot;
    }

    public void tick() {
        Eater eater = bot.eater;
        if (eater.busy()) {
            eater.tick();
            if (!eater.busy() && eater.result(OWNER) == Eater.Result.FAILED) {
                cooldownTicks = 60;
            }
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        BotConfig.AutoEat cfg = bot.config().behaviour().autoEat();
        LocalPlayer p = bot.player();
        if (!cfg.enabled() || !bot.inGame() || p.isDeadOrDying() || p.isCreative() || p.isSpectator()
                || Interact.containerOpen(p) || bot.pause.inventoryBusy() || bot.ticks() % 10 != 0) {
            return;
        }
        int food = p.getFoodData().getFoodLevel();
        boolean hungry = food < cfg.belowFood() || (p.getHealth() < cfg.belowHealth() && food < 20);
        if (!hungry || !p.canEat(false)) {
            return;
        }
        if (!eater.start(OWNER)) {
            if (eater.result(OWNER) == Eater.Result.NO_FOOD && limiter.once("food_low", 120_000)) {
                bot.event(EventKinds.FOOD_LOW, Levels.WARN, "hungry (" + food + ") and no allowed food in inventory",
                        Json.obj("food", food));
            }
            cooldownTicks = 100;
        }
    }
}
