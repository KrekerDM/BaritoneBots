package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.autopilot.SupplyPlanner;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.ItemStacks;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.Restock;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Furnace logic shared by {@code smelt} projects, the autopilot's refuel items and the {@code smelt_all} manager step
 * (SPEC §5.5): which input / fuel a furnace gets ({@link #plan}), furnace slot checks, and {@code smelt_all} itself.
 */
public final class Smelter {
    public static final int MAX_LOAD = 64;
    /** {@code smelt_all} uses furnaces this close to the bot. */
    static final int STEP_RADIUS = 64;

    private Smelter() {
    }

    /** A furnace load; {@code fuel == null} = an input is there but no fuel. */
    public record Load(String input, int count, String fuel, int fuelCount, String type) {
        public JsonObject args(Pos furnace) {
            return Json.obj("furnace", furnace, "input", input, "count", count, "fuel", fuel, "fuelCount", fuelCount);
        }
    }

    /** Recipe type a furnace block runs. */
    public static String recipeType(String block) {
        String b = block == null ? "" : block;
        if (b.endsWith("blast_furnace")) {
            return GameData.BLASTING;
        }
        return b.endsWith("smoker") ? GameData.SMOKING : GameData.SMELTING;
    }

    /**
     * The load for an empty furnace of recipe {@code type}: the input matching {@code inputs} with the most stock that
     * the furnace can cook (per game data; without game data only plain furnaces, anything matching), up to
     * {@code max}; fuel = the first known fuel (best first) with enough stock, or with {@code fuels} globs any matching
     * burnable item. Null when no input is in stock. Pure.
     */
    public static Load plan(String type, Map<String, Integer> stock, List<String> inputs, List<String> fuels,
                            GameData data, int max) {
        GameData d = data == null ? GameData.empty() : data;
        String input = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            if (!Ids.matchesAny(inputs, e.getKey()) || e.getValue() <= best) {
                continue;
            }
            boolean ok = d.isEmpty() ? GameData.SMELTING.equals(type)
                    : d.cookingRecipesUsing(e.getKey()).stream().anyMatch(r -> r.type().equals(type));
            if (ok) {
                input = e.getKey();
                best = e.getValue();
            }
        }
        if (input == null) {
            return null;
        }
        int count = Math.min(Math.min(MAX_LOAD, max), best);
        List<String> candidates = new ArrayList<>();
        if (fuels == null || fuels.isEmpty()) {
            candidates.addAll(ItemStacks.knownFuels());
        } else {
            ItemStacks.knownFuels().stream().filter(f -> Ids.matchesAny(fuels, f)).forEach(candidates::add);
            stock.keySet().stream().filter(f -> Ids.matchesAny(fuels, f) && ItemStacks.burnItems(f) > 0
                            && !candidates.contains(f))
                    .sorted(Comparator.comparingDouble((String f) -> -ItemStacks.burnItems(f)).thenComparing(f -> f))
                    .forEach(candidates::add);
        }
        for (String f : candidates) {
            double burn = ItemStacks.burnItems(f);
            int n = Math.min(count, (int) Math.floor(MAX_LOAD * burn));
            int need = (int) Math.ceil(n / burn);
            int have = stock.getOrDefault(f, 0) - (f.equals(input) ? n : 0);
            if (n > 0 && have >= need) {
                return new Load(input, n, f, need, type);
            }
        }
        return new Load(input, count, null, 0, type);
    }

    static ContainerSnapshot.SlotItem slot(WorldDoc.Container c, int slot) {
        if (c.snapshot() == null) {
            return null;
        }
        for (ContainerSnapshot.SlotItem it : c.snapshot().items()) {
            if (it.slot() == slot && it.count() > 0) {
                return it;
            }
        }
        return null;
    }

    /** Snapshot shows something in the result slot. */
    public static boolean hasOutput(WorldDoc.Container f) {
        return slot(f, 2) != null;
    }

    /** Snapshot shows an empty input slot. */
    public static boolean inputEmpty(WorldDoc.Container f) {
        return f.snapshot() != null && slot(f, 0) == null;
    }

    // ------------------------------------------------------------------ smelt_all

    /**
     * {@code smelt_all {inputs?:[glob], fuel?:[glob]}}: for the furnaces (role {@code furnace}) within 64 blocks of the
     * bot: collect every result, load every empty furnace with the input of most stock ({@code inputs}, default
     * {@code autopilot.smeltInputs}) and fuel from the source containers (fuel role first), then deposit what was
     * collected. Furnaces never inspected are inspected first (once). Runs on the loop.
     */
    public static void smeltAll(Manager m, BotState b, QueueEntry e) {
        m.dispatcher.startStep(b, e);
        m.loop.post(() -> {
            if (!m.dispatcher.isRunning(b, e)) {
                return;
            }
            try {
                smeltAllNow(m, b, e);
            } catch (RuntimeException ex) {
                m.dispatcher.finishStep(b, e, false, Reasons.ERROR, String.valueOf(ex.getMessage()), null, null, false);
            }
        });
    }

    private static void smeltAllNow(Manager m, BotState b, QueueEntry e) {
        Dispatcher d = m.dispatcher;
        WorldDoc doc = b.def.serverId() == null ? null : m.worlds.get(b.def.serverId());
        String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        Pos at = Planner.posOf(b);
        List<WorldDoc.Container> furnaces = doc == null ? List.of() : doc.containers.stream()
                .filter(c -> c.hasRole("furnace") && Dims.normalize(c.dim()).equals(dim)
                        && (at == null || c.pos().distance(at) <= STEP_RADIUS)
                        && !m.planner.locks().heldByOther(Planner.containerKey(c), b.id))
                .sorted(Comparator.comparingDouble(c -> at == null ? 0 : c.pos().distance(at))).toList();
        if (furnaces.isEmpty()) {
            d.finishStep(b, e, false, Reasons.NOT_FOUND, "no free furnace (container role 'furnace') within "
                    + STEP_RADIUS + " blocks", null, null, false);
            return;
        }
        List<WorldDoc.Container> unknown = furnaces.stream().filter(c -> c.snapshot() == null).toList();
        if (!unknown.isEmpty() && !Json.getBool(e.args(), "_inspected", false)) {
            JsonObject again = e.args().deepCopy();
            again.addProperty("_inspected", true);
            d.finishStep(b, e, true, null, null, null, List.of(
                    d.childEntry(e, TaskTypes.INSPECT, Json.obj("containers",
                            Json.arrOf(unknown.stream().map(WorldDoc.Container::pos).limit(8).toList())), Restock.TAKE_TIMEOUT_SEC),
                    d.childEntry(e, Dispatcher.STEP_SMELT_ALL, again, 0)), true);
            return;
        }
        List<String> inputs = Json.getStringList(e.args(), "inputs");
        if (inputs.isEmpty()) {
            inputs = m.config.get().autopilot().smeltInputs();
        }
        List<String> fuels = Json.getStringList(e.args(), "fuel");
        GameData data = m.gameData.current();
        // stock: the bot's inventory plus the source containers (fuel role first, then nearest)
        Map<String, Integer> inv = new HashMap<>(b.status == null ? Map.of() : b.status.items());
        List<SupplyPlanner.Source> sources = new ArrayList<>(m.autopilot.supplySources(b));
        sources.sort(Comparator.comparingInt(s -> s.container().hasRole("fuel") ? 0 : 1));
        Map<WorldDoc.Container, Map<String, Integer>> left = new LinkedHashMap<>();
        for (SupplyPlanner.Source s : sources) {
            if (s.available() != null) {
                left.put(s.container(), new HashMap<>(s.available()));
            }
        }
        int slots = Math.max(1, (b.status == null ? 27 : b.status.freeSlots()) - 1);
        List<QueueEntry> collects = new ArrayList<>();
        List<QueueEntry> loads = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int loaded = 0;
        for (WorldDoc.Container f : furnaces) {
            if (hasOutput(f)) {
                collects.add(d.childEntry(e, TaskTypes.SMELT_COLLECT, Json.obj("furnace", f.pos(), "all", false), 300));
            }
            if (!inputEmpty(f)) {
                continue;
            }
            Map<String, Integer> stock = new HashMap<>(inv);
            left.values().forEach(av -> av.forEach((k, v) -> stock.merge(k, v, Integer::sum)));
            Load load = plan(recipeType(f.block()), stock, inputs, fuels, data, MAX_LOAD);
            if (load == null) {
                break; // nothing left to smelt
            }
            if (load.fuel() == null) {
                notes.add("no fuel for " + load.count() + " × " + Ids.path(load.input()));
                break;
            }
            List<Restock.Need> needs = new ArrayList<>();
            int haveIn = inv.getOrDefault(load.input(), 0);
            if (haveIn < load.count()) {
                needs.add(new Restock.Need(load.input(), load.count() - haveIn));
            }
            int haveFuel = inv.getOrDefault(load.fuel(), 0) - (load.fuel().equals(load.input()) ? load.count() : 0);
            if (haveFuel < load.fuelCount()) {
                needs.add(new Restock.Need(load.fuel(), load.fuelCount() - Math.max(0, haveFuel)));
            }
            Restock.Plan plan = Restock.plan(needs, new ArrayList<>(left.keySet()), left::get, c -> false, slots);
            if (!plan.missing().isEmpty()) {
                notes.add("not enough in reach for " + Ids.path(load.input()));
                break;
            }
            for (Restock.Take t : plan.takes()) {
                loads.add(d.childEntry(e, TaskTypes.TAKE, Restock.takeArgs(t), Restock.TAKE_TIMEOUT_SEC));
                t.items().forEach((k, v) -> {
                    left.get(t.container()).merge(k, -v, Integer::sum);
                    inv.merge(k, v, Integer::sum);
                });
                slots -= t.items().entrySet().stream().mapToInt(x -> ItemStacks.slots(x.getKey(), x.getValue())).sum();
            }
            loads.add(d.childEntry(e, TaskTypes.SMELT_LOAD, load.args(f.pos()), 300));
            inv.merge(load.input(), -load.count(), Integer::sum);
            inv.merge(load.fuel(), -load.fuelCount(), Integer::sum);
            loaded++;
            if (slots <= 0) {
                break;
            }
        }
        List<QueueEntry> children = new ArrayList<>(collects);
        children.addAll(loads);
        if (!collects.isEmpty()) {
            children.add(d.childEntry(e, Dispatcher.STEP_DEPOSIT_STORAGE, new JsonObject(), 0));
        }
        JsonObject result = Json.obj("collect", collects.size(), "loaded", loaded, "notes", Json.arrOf(notes));
        String msg = children.isEmpty() ? (notes.isEmpty() ? "nothing to smelt or collect" : String.join("; ", notes))
                : collects.size() + " to collect, " + loaded + " to load";
        d.finishStep(b, e, true, null, msg, result, children, false);
    }
}
