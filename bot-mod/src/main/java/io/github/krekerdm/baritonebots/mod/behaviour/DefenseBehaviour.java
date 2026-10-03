package io.github.krekerdm.baritonebots.mod.behaviour;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;
import io.github.krekerdm.baritonebots.mod.util.Weapons;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Defense (SPEC §2.5 {@code behaviour.defense}). Hostile = {@link Enemy} within {@code radius} (plus a player that
 * just hurt the bot when {@code retaliatePlayers}). {@code flee}: run away from hostiles; {@code fight}: melee the
 * nearest hostile with the best hotbar weapon once the attack is charged (≥ 0.9), running away instead when health
 * is below {@code fleeBelowHealth}; {@code ignore}: only report. Entities in {@code avoidEntities} or whose name
 * matches {@code avoidNamePatterns} are always fled from. {@code threat} events: once per entity type per 30 s.
 */
public final class DefenseBehaviour {
    private static final int SCAN_INTERVAL = 5;

    private final BotRuntime bot;
    private final RateLimiter limiter = new RateLimiter(1, 1000);
    private List<String> patternSource = List.of();
    private List<Pattern> namePatterns = List.of();
    private List<LivingEntity> hostiles = new ArrayList<>();
    private List<Entity> avoid = new ArrayList<>();
    private long lastWeaponSwapTick = -100;

    public DefenseBehaviour(BotRuntime bot) {
        this.bot = bot;
    }

    /** True while defense moves or holds the bot (status {@code task.state = paused}). */
    public boolean engaged() {
        return bot.defenseProcess.isActive();
    }

    public void reset() {
        bot.defenseProcess.clear();
        hostiles = new ArrayList<>();
        avoid = new ArrayList<>();
    }

    public void tick() {
        LocalPlayer p = bot.player();
        if (!bot.inGame() || p.isDeadOrDying() || p.isSpectator() || bot.baritone() == null) {
            if (engaged()) {
                reset();
            }
            return;
        }
        BotConfig.Defense cfg = bot.config().behaviour().defense();
        if (bot.ticks() % SCAN_INTERVAL == 0) {
            scan(p, cfg);
        }
        if (BotConfig.Defense.MODE_IGNORE.equals(cfg.mode()) || bot.eater.busy()) {
            bot.defenseProcess.clear();
            return;
        }
        hostiles.removeIf(e -> !e.isAlive() || e.isRemoved());
        avoid.removeIf(e -> !e.isAlive() || e.isRemoved());
        boolean flee = !avoid.isEmpty() || (!hostiles.isEmpty()
                && (BotConfig.Defense.MODE_FLEE.equals(cfg.mode()) || p.getHealth() < cfg.fleeBelowHealth()));
        if (flee) {
            List<BlockPos> from = new ArrayList<>();
            avoid.forEach(e -> from.add(e.blockPosition()));
            hostiles.forEach(e -> from.add(e.blockPosition()));
            bot.defenseProcess.pathTo(new GoalRunAway(cfg.radius() + 8.0, from.toArray(BlockPos[]::new)), "flee");
            return;
        }
        if (!BotConfig.Defense.MODE_FIGHT.equals(cfg.mode()) || hostiles.isEmpty() || Interact.containerOpen(p)) {
            bot.defenseProcess.clear();
            return;
        }
        LivingEntity target = hostiles.stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElseThrow();
        double dist = p.distanceTo(target);
        if (dist <= Interact.ENTITY_REACH) {
            bot.defenseProcess.hold("fight " + McIds.entity(target));
            equipWeapon(p);
            Interact.lookAt(p, target.getBoundingBox().getCenter());
            if (p.getAttackStrengthScale(0.5f) >= 0.9f) {
                Interact.attack(bot.mc, p, target);
            }
        } else {
            bot.defenseProcess.pathTo(new GoalNear(target.blockPosition(), 1), "approach " + McIds.entity(target));
        }
    }

    private void scan(LocalPlayer p, BotConfig.Defense cfg) {
        if (cfg.avoidNamePatterns() != patternSource) {
            patternSource = cfg.avoidNamePatterns();
            List<Pattern> compiled = new ArrayList<>();
            for (String s : patternSource) {
                try {
                    compiled.add(Pattern.compile(s));
                } catch (PatternSyntaxException e) {
                    bot.warn("invalid avoidNamePatterns entry '" + s + "': " + e.getDescription());
                }
            }
            namePatterns = compiled;
        }
        double r = Math.max(1, cfg.radius());
        AABB box = p.getBoundingBox().inflate(r);
        Entity attacker = recentAttacker(p, cfg);
        List<LivingEntity> foundHostiles = new ArrayList<>();
        List<Entity> foundAvoid = new ArrayList<>();
        for (Entity e : bot.level().getEntities(p, box, e -> e.isAlive() && e != p)) {
            if (p.distanceTo(e) > r) {
                continue;
            }
            if (shouldAvoid(e, cfg)) {
                foundAvoid.add(e);
                threat(e, "avoid");
            } else if (e instanceof LivingEntity le && (e instanceof Enemy || e == attacker)) {
                foundHostiles.add(le);
                threat(e, "hostile");
            }
        }
        hostiles = foundHostiles;
        avoid = foundAvoid;
    }

    private Entity recentAttacker(LocalPlayer p, BotConfig.Defense cfg) {
        if (!cfg.retaliatePlayers() || p.hurtTime <= 0) {
            return null;
        }
        DamageSource src = p.getLastDamageSource();
        Entity e = src == null ? null : src.getEntity();
        return e instanceof Player && e != p ? e : null;
    }

    private boolean shouldAvoid(Entity e, BotConfig.Defense cfg) {
        if (cfg.avoidEntities().contains(McIds.entity(e))) {
            return true;
        }
        if (namePatterns.isEmpty()) {
            return false;
        }
        String name = e.getName().getString();
        for (Pattern pt : namePatterns) {
            if (pt.matcher(name).find()) {
                return true;
            }
        }
        return false;
    }

    private void threat(Entity e, String kind) {
        String type = McIds.entity(e);
        if (limiter.once("threat:" + type, 30_000)) {
            bot.event(EventKinds.THREAT, Levels.WARN, kind + " " + type + " nearby", Json.obj(
                    "type", type, "pos", Positions.json(e.blockPosition()), "kind", kind,
                    "name", e.hasCustomName() ? e.getName().getString() : null));
        }
    }

    /** Selects the best hotbar weapon; pulls one from the main inventory at most once per second. */
    private void equipWeapon(LocalPlayer p) {
        int bestHotbar = Weapons.bestSlot(p, 0, Inv.HOTBAR_SIZE);
        int bestMain = Weapons.bestSlot(p, Inv.HOTBAR_SIZE, Inv.MAIN_SIZE);
        int hotScore = bestHotbar < 0 ? 0 : Weapons.score(p.getInventory().getItem(bestHotbar));
        int mainScore = bestMain < 0 ? 0 : Weapons.score(p.getInventory().getItem(bestMain));
        if (mainScore > hotScore && Inv.ownMenuOpen(p) && bot.ticks() - lastWeaponSwapTick >= 20) {
            int target = bestHotbar >= 0 ? bestHotbar : p.getInventory().getSelectedSlot();
            Inv.swapWithHotbar(bot.mc, p, bestMain, target);
            lastWeaponSwapTick = bot.ticks();
            Inv.select(p, target);
            return;
        }
        if (bestHotbar >= 0) {
            Inv.select(p, bestHotbar);
        }
    }
}
