package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.task.plan.AnimalFood;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code breed box animal [food] [max=20]}: feeds pairs of adult animals of one type inside the pen until no eligible
 * pair is left or the population reaches {@code max}. The client never learns an animal's love or breeding-cooldown
 * state (only babies are synced), so fed animals are remembered by UUID for 5 minutes (vanilla breeding cooldown),
 * also across tasks; an animal that did not take the food (no item used within 10 ticks) is remembered the same
 * way. Untamed horses/llamas/wolves/cats are not eligible. ok data {@code {fed, population}}.
 */
public final class BreedTask implements TaskExecutor {
    private static final long COOLDOWN_MS = 5 * 60_000;
    private static final int VERIFY_TICKS = 10;
    private static final int PENDING_PAIR_TICKS = 300;
    private static final int LINGER_TICKS = 60;
    /** UUID → time (ms) until which the animal is not fed again; shared by all breed tasks. */
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();

    private final Fighter mover = new Fighter();
    private final Set<Integer> unreachable = new HashSet<>();
    private final List<Integer> pairTicks = new ArrayList<>();
    private AnimalPen pen;
    private Item food;
    private int max;
    private int fed;
    private int population;
    private int untamed;
    private LivingEntity target;
    private LivingEntity firstOfPair;
    private int verifyStart = -1;
    private int foodBefore;
    private int lastFedTick = -LINGER_TICKS;
    private String finishMessage;
    private JsonObject finishExtra;

    @Override
    public void start(TaskContext ctx) {
        pen = AnimalPen.parse(ctx, null);
        if (pen == null) {
            return;
        }
        max = Math.max(2, Json.getInt(ctx.args(), "max", 20));
        COOLDOWN.values().removeIf(until -> until < System.currentTimeMillis());
        List<Animal> animals = pen.animals(ctx, Animal.class);
        Animal sample = animals.isEmpty() ? null : animals.get(0);
        String foodId = Json.getString(ctx.args(), "food", "");
        if (!foodId.isBlank()) {
            food = McIds.itemById(Ids.normalize(foodId.trim())).orElse(null);
            if (food == null) {
                ctx.fail(Reasons.BAD_ARGS, "unknown food item '" + foodId + "'", null);
                return;
            }
            if (sample != null && !sample.isFood(new ItemStack(food))) {
                ctx.fail(Reasons.BAD_ARGS, pen.typeId + " does not eat " + McIds.item(food), null);
                return;
            }
        } else {
            food = AnimalFood.defaultFood(pen.typeId).flatMap(McIds::itemById).orElse(null);
            if (food == null && sample != null) {
                food = foodFromInventory(ctx, sample);
            }
            if (food == null) {
                ctx.fail(sample == null ? Reasons.BAD_ARGS : Reasons.MISSING_MATERIALS, "no known food for "
                        + pen.typeId + (sample == null ? "; pass 'food'" : " in the inventory"), null);
                return;
            }
        }
        if (Inv.count(ctx.player(), s -> s.is(food)) == 0) {
            ctx.fail(Reasons.MISSING_MATERIALS, "no " + McIds.item(food) + " to feed " + pen.typeId,
                    Json.obj("missing", Json.obj(McIds.item(food), 2)));
            return;
        }
        pen.protectFences();
        ctx.step("breeding " + pen.typeId + " with " + McIds.item(food), -1);
    }

    /** First inventory item the animal accepts as food ({@code Animal#isFood}, item tags synced by the server). */
    private static Item foodFromInventory(TaskContext ctx, Animal sample) {
        int slot = Inv.findSlot(ctx.player(), sample::isFood);
        return slot < 0 ? null : ctx.player().getInventory().getItem(slot).getItem();
    }

    @Override
    public void tick(TaskContext ctx) {
        if (finishMessage != null) {
            linger(ctx);
            return;
        }
        if (ctx.bot().eater.busy() || ctx.bot().defense.engaged()) {
            mover.releaseHold(ctx);
            ctx.step("waiting (eating / defending)", -1);
            return;
        }
        if (verifyStart >= 0) {
            verifyFeeding(ctx);
            return;
        }
        List<Animal> animals = pen.animals(ctx, Animal.class);
        population = animals.size();
        pairTicks.removeIf(t -> ctx.ticks() - t > PENDING_PAIR_TICKS);
        List<Animal> eligible = eligible(animals);
        if (target == null || !target.isAlive() || !eligible.contains(target)) {
            mover.stop(ctx);
            target = pickTarget(ctx, eligible);
            if (target == null) {
                return; // finished
            }
        }
        if (Inv.count(ctx.player(), s -> s.is(food)) == 0) {
            if (fed == 0) {
                ctx.fail(Reasons.MISSING_MATERIALS, "no " + McIds.item(food) + " left",
                        Json.obj("missing", Json.obj(McIds.item(food), 2)));
            } else {
                finish("out of " + McIds.item(food), Json.obj("outOfFood", true));
            }
            return;
        }
        Fighter.Result r = mover.approach(ctx, target);
        if (r == Fighter.Result.UNREACHABLE) {
            unreachable.add(target.getId());
            mover.stop(ctx);
            target = null;
            return;
        }
        if (r != Fighter.Result.IN_REACH) {
            ctx.step("walking to " + pen.typeId + " (" + fed + " fed)", -1);
            return;
        }
        // the food goes into the hand only now: while walking Baritone may select tools to break blocks
        if (pen.holdInMainHand(ctx, s -> s.is(food)) == 1) {
            foodBefore = Inv.count(ctx.player(), s -> s.is(food));
            Interact.useOnEntity(ctx.mc(), ctx.player(), target, InteractionHand.MAIN_HAND);
            verifyStart = ctx.ticks();
            ctx.step("feeding " + pen.typeId + " (" + fed + " fed)", -1);
        }
    }

    /** Waits for the server to take the food; either way the animal is not fed again for 5 minutes. */
    private void verifyFeeding(TaskContext ctx) {
        boolean used = Inv.count(ctx.player(), s -> s.is(food)) < foodBefore;
        if (!used && ctx.ticks() - verifyStart < VERIFY_TICKS) {
            return;
        }
        verifyStart = -1;
        COOLDOWN.put(target.getUUID(), System.currentTimeMillis() + COOLDOWN_MS);
        if (used) {
            fed++;
            lastFedTick = ctx.ticks();
            if (firstOfPair == null) {
                firstOfPair = target;
            } else {
                firstOfPair = null;
                pairTicks.add(ctx.ticks());
            }
        }
        mover.releaseHold(ctx);
        target = null;
    }

    private List<Animal> eligible(List<Animal> animals) {
        long now = System.currentTimeMillis();
        List<Animal> out = new ArrayList<>();
        untamed = 0;
        for (Animal a : animals) {
            if (a.isBaby() || unreachable.contains(a.getId()) || COOLDOWN.getOrDefault(a.getUUID(), 0L) > now) {
                continue;
            }
            if ((a instanceof AbstractHorse h && !h.isTamed()) || (a instanceof TamableAnimal t && !t.isTame())) {
                untamed++;
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /** Next animal to feed (the partner nearest to the half-fed pair first), or null after finishing. */
    private LivingEntity pickTarget(TaskContext ctx, List<Animal> eligible) {
        boolean midPair = firstOfPair != null && firstOfPair.isAlive();
        if (!midPair) {
            firstOfPair = null;
            if (population + pairTicks.size() >= max) {
                finish("population " + population + " reached max " + max, null);
                return null;
            }
            if (eligible.size() < 2) {
                finish(fed > 0 ? "no eligible pair left" : "no eligible pair", null);
                return null;
            }
            return eligible.get(0);
        }
        if (eligible.isEmpty()) {
            finish("no partner left for the last fed " + pen.typeId, null);
            return null;
        }
        Iterator<Animal> it = eligible.iterator();
        Animal best = it.next();
        while (it.hasNext()) {
            Animal a = it.next();
            if (a.distanceToSqr(firstOfPair) < best.distanceToSqr(firstOfPair)) {
                best = a;
            }
        }
        return best;
    }

    private void finish(String message, JsonObject extra) {
        if (finishMessage == null) {
            finishMessage = message == null ? "done" : message;
            finishExtra = extra;
        }
    }

    /** Gives babies a moment to spawn so {@code population} is current. */
    private void linger(TaskContext ctx) {
        mover.stop(ctx);
        if (ctx.ticks() - lastFedTick < LINGER_TICKS) {
            ctx.step("waiting for babies", -1);
            return;
        }
        population = pen.animals(ctx, Animal.class).size();
        JsonObject data = Json.obj("fed", fed, "population", population);
        if (untamed > 0) {
            data.addProperty("untamed", untamed);
        }
        if (!unreachable.isEmpty()) {
            data.addProperty("unreachable", unreachable.size());
        }
        if (finishExtra != null) {
            finishExtra.entrySet().forEach(e -> data.add(e.getKey(), e.getValue()));
        }
        ctx.succeed(finishMessage, data);
    }

    @Override
    public void cleanup(TaskContext ctx) {
        mover.stop(ctx);
        if (pen != null) {
            pen.restoreSettings();
        }
    }
}
