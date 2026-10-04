package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.Restock;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code smelt} projects (SPEC §5.7): a furnace array. Config {@code {inputs:[glob], fuel?:[glob], furnaces?:[pos],
 * output?:[pos], continuous:false, dim}}; furnaces default to the containers with role {@code furnace}. Per furnace
 * (lock = the furnace): {@code collect} when the snapshot shows a result or our load should be done
 * ({@code smelt_collect} + deposit into {@code output} / deposit_storage), {@code load} when it is empty
 * (input with the most stock and fuel via {@link Smelter#plan}, fuel role first: {@code take}s + {@code smelt_load}).
 * One-shot projects are done when no input is left in the source containers and nothing is in our furnaces.
 */
public final class SmeltKind implements ProjectKind {
    public static final String KIND = "smelt";
    static final String COLLECT = "collect";
    static final String LOAD = "load";
    private final Manager m;

    public SmeltKind(Manager m) {
        this.m = m;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject normalizeConfig(JsonObject config, String serverId) {
        JsonObject cfg = config.deepCopy();
        if (!cfg.has("inputs") && cfg.has("input")) {
            cfg.add("inputs", cfg.remove("input")); // the panel's old field name
        }
        KindConfig c = new KindConfig(cfg);
        JsonArray inputs = c.globs("inputs", true);
        JsonArray fuel = c.globs("fuel", false);
        JsonArray furnaces = c.positions("furnaces");
        JsonArray output = c.positions("output");
        boolean continuous = c.bool("continuous", false);
        return c.done(Json.obj("inputs", inputs, "fuel", fuel, "furnaces", furnaces, "output", output,
                "continuous", continuous, "dim", c.dim()));
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService svc) {
        return new Runtime(p, svc);
    }

    /** Milliseconds until {@code count} items are cooked (10 s each, 5 s in blast furnaces / smokers) plus 5 s. Pure. */
    static long cookMs(String type, int count) {
        return count * (GameData.SMELTING.equals(type) ? 10_000L : 5_000L) + 5_000L;
    }

    static final class Runtime extends KindRuntime {
        private final List<String> inputs;
        private final List<String> fuels;
        private final JsonArray furnacePos;
        private final JsonArray outputPos;
        private final boolean continuous;
        /** furnace key → when our load is done. */
        final Map<String, Long> readyAt = new HashMap<>();
        /** furnace key → items we put in and did not collect yet. */
        final Map<String, Integer> inFurnace = new HashMap<>();
        final JsonObject smelted;
        private int remainingInputs;
        private long runningSince;
        private long runningMs;

        Runtime(Project p, ProjectService svc) {
            super(p, svc);
            inputs = Json.getStringList(p.config, "inputs");
            fuels = Json.getStringList(p.config, "fuel");
            furnacePos = Json.getArr(p.config, "furnaces");
            outputPos = Json.getArr(p.config, "output");
            continuous = Json.getBool(p.config, "continuous", false);
            JsonObject s = Json.getObj(p.state, "smelted");
            smelted = s == null ? new JsonObject() : s.deepCopy();
            JsonObject r = Json.getObj(p.state, "readyAt");
            if (r != null) {
                r.entrySet().forEach(e -> readyAt.put(e.getKey(), e.getValue().getAsLong()));
            }
            JsonObject f = Json.getObj(p.state, "inFurnace");
            if (f != null) {
                f.entrySet().forEach(e -> inFurnace.put(e.getKey(), e.getValue().getAsInt()));
            }
            runningMs = Json.getLong(p.state, "runningMs", 0);
        }

        @Override
        public void start(boolean restart) {
            super.start(restart);
            runningSince = System.currentTimeMillis();
            if (restart) {
                smelted.entrySet().clear();
                runningMs = 0;
            }
        }

        @Override
        public void stop() {
            super.stop();
            if (runningSince > 0) {
                runningMs += System.currentTimeMillis() - runningSince;
                runningSince = 0;
            }
        }

        List<WorldDoc.Container> furnaces() {
            return resolve(furnacePos, "furnace");
        }

        private List<WorldDoc.Container> outputs() {
            return resolve(outputPos, null);
        }

        /** Sources for inputs and fuel: fuel containers first. */
        @Override
        public List<WorldDoc.Container> sources() {
            List<WorldDoc.Container> out = new ArrayList<>(super.sources());
            out.sort(Comparator.comparingInt(c -> c.hasRole("fuel") ? 0 : 1));
            return out;
        }

        private Map<String, Integer> stock(Assignment except) {
            Map<String, Integer> out = new HashMap<>();
            for (WorldDoc.Container c : sources()) {
                Map<String, Integer> av = planner.available(c, except);
                if (av != null) {
                    av.forEach((k, v) -> out.merge(k, v, Integer::sum));
                }
            }
            return out;
        }

        @Override
        public List<WorkItem> workItems(long now) {
            List<WorkItem> out = new ArrayList<>();
            List<WorldDoc.Container> furnaces = furnaces();
            if (furnaces.isEmpty()) {
                notes.put("furnaces", "no furnace (role 'furnace') in " + dim);
                svc.blocked(p, "furnaces", notes.get("furnaces"), null);
                return out;
            }
            notes.remove("furnaces");
            List<WorldDoc.Container> unknown = unknown(furnaces);
            if (!unknown.isEmpty()) {
                out.add(inspectItem("furnaces", unknown, p.priority));
                return out;
            }
            Map<String, Integer> stock = stock(null);
            remainingInputs = stock.entrySet().stream().filter(e -> Ids.matchesAny(inputs, e.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();
            GameData data = m.gameData.current();
            boolean pending = false;
            boolean inputLeft = false;
            notes.remove("fuel");
            for (WorldDoc.Container f : furnaces) {
                String key = Planner.containerKey(f);
                Long ready = readyAt.get(key);
                if (Smelter.hasOutput(f) || ready != null && now >= ready) {
                    pending = true;
                    out.add(new WorkItem(COLLECT + ":" + key, id(), COLLECT, "smelter", p.priority + 0.5, null, dim,
                            f.pos(), key, 1, "collect " + f.pos(), Json.obj("furnace", f.pos())));
                    continue;
                }
                if (ready != null) {
                    pending = true;
                    continue;
                }
                if (!Smelter.inputEmpty(f)) {
                    continue;
                }
                Smelter.Load load = Smelter.plan(Smelter.recipeType(f.block()), stock, inputs, fuels, data, Smelter.MAX_LOAD);
                if (load == null) {
                    continue;
                }
                inputLeft = true;
                if (load.fuel() == null) {
                    notes.put("fuel", "no fuel in storage for " + load.count() + " × " + Ids.path(load.input()));
                    svc.blocked(p, "fuel", notes.get("fuel"), null);
                    continue;
                }
                stock.merge(load.input(), -load.count(), Integer::sum);
                stock.merge(load.fuel(), -load.fuelCount(), Integer::sum);
                out.add(new WorkItem(LOAD + ":" + key, id(), LOAD, "smelter", p.priority, null, dim, f.pos(), key, 1,
                        "load " + f.pos() + " with " + load.count() + " × " + Ids.path(load.input()),
                        Json.obj("furnace", f.pos(), "furnaceKey", key)));
            }
            if (!notes.containsKey("fuel")) {
                svc.unblocked(p, "fuel");
            }
            // furnaces filled by someone else: inputs may still be waiting for a free furnace
            inputLeft |= remainingInputs > 0 && furnaces.stream().anyMatch(f -> !Smelter.inputEmpty(f));
            boolean busy = !planner.assignmentsOf(id()).isEmpty();
            if (!continuous && !pending && !inputLeft && !busy && out.isEmpty()) {
                svc.done(p);
            }
            return out;
        }

        @Override
        public boolean eligible(BotState b, WorkItem w) {
            return INSPECT.equals(w.kind()) || roleOk(b, "smelter") || roleOk(b, "hauler");
        }

        private WorldDoc.Container furnace(Assignment a) {
            WorldDoc doc = doc();
            return doc == null ? null : doc.containerAt(dim, io.github.krekerdm.baritonebots.common.geom.Pos.fromJson(
                    a.item.data().get("furnace")));
        }

        @Override
        public void begin(Assignment a, BotState b) {
            switch (a.item.kind()) {
                case INSPECT -> beginInspect(a);
                case COLLECT -> {
                    readyAt.remove(a.item.lock());
                    a.phase("collect");
                    planner.push(a, List.of(
                            Planner.entry(a, TaskTypes.SMELT_COLLECT, Json.obj("furnace", a.item.data().get("furnace").deepCopy(),
                                    "all", false), 300, a.item.label()),
                            deposit(a, outputs())));
                }
                default -> load(a, b);
            }
        }

        /** Plans the load again with fresh availability (other assignments' reservations excluded), then takes. */
        private void load(Assignment a, BotState b) {
            WorldDoc.Container f = furnace(a);
            if (f == null) {
                planner.release(a, "stopped", false);
                return;
            }
            Map<String, Integer> inv = b.status == null ? Map.of() : b.status.items();
            Map<String, Integer> stock = stock(a);
            inv.forEach((k, v) -> stock.merge(k, v, Integer::sum));
            Smelter.Load load = Smelter.plan(Smelter.recipeType(f.block()), stock, inputs, fuels, m.gameData.current(),
                    Smelter.MAX_LOAD);
            if (load == null || load.fuel() == null) {
                planner.finish(a);
                return;
            }
            a.ctx = load;
            List<Restock.Need> needs = new ArrayList<>();
            int haveIn = inv.getOrDefault(load.input(), 0);
            if (haveIn < load.count()) {
                needs.add(new Restock.Need(load.input(), load.count() - haveIn));
            }
            int haveFuel = inv.getOrDefault(load.fuel(), 0) - (load.fuel().equals(load.input()) ? load.count() : 0);
            if (haveFuel < load.fuelCount()) {
                needs.add(new Restock.Need(load.fuel(), load.fuelCount() - Math.max(0, haveFuel)));
            }
            if (needs.isEmpty()) {
                smeltLoad(a, f, load);
                return;
            }
            Restock.Plan plan = Restock.plan(needs, sources(), c -> planner.available(c, a),
                    c -> planner.locks().heldByOther(Planner.containerKey(c), a.botId),
                    Math.max(1, (b.status == null ? 27 : b.status.freeSlots()) - 1));
            if (!plan.missing().isEmpty()) {
                planner.fail(a, Reasons.MISSING_MATERIALS, "not in storage any more: " + plan.missing());
                return;
            }
            List<QueueEntry> takes = Restock.entries(planner, a, plan, Planner.posOf(b));
            if (takes.size() < plan.takes().size()) {
                planner.fail(a, Reasons.CONTAINER_FAILED, "a container is in use");
                return;
            }
            a.phase("take inputs");
            planner.push(a, takes);
        }

        private void smeltLoad(Assignment a, WorldDoc.Container f, Smelter.Load load) {
            a.phase("load");
            a.ctx = new Object[] {load, "load"};
            planner.push(a, List.of(Planner.entry(a, TaskTypes.SMELT_LOAD, load.args(f.pos()), 300, a.item.label())));
        }

        @Override
        public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
            planner.releaseContainers(a);
            switch (a.item.kind()) {
                case INSPECT -> planner.finish(a);
                case COLLECT -> {
                    Map<String, Integer> got = sum(results, TaskTypes.SMELT_COLLECT, "collected");
                    addAll(smelted, got);
                    String key = a.item.lock();
                    inFurnace.remove(key);
                    WorldDoc.Container f = furnace(a);
                    ContainerSnapshot.SlotItem left = f == null ? null : Smelter.slot(f, 0);
                    if (left != null) { // not finished yet: come back when the rest is cooked
                        readyAt.put(key, System.currentTimeMillis() + cookMs(Smelter.recipeType(f.block()), left.count()));
                        inFurnace.put(key, left.count());
                    }
                    Assignment.Result bad = firstBad(results);
                    if (got.isEmpty() && bad != null) {
                        planner.fail(a, bad.reason(), bad.message());
                    } else {
                        planner.finish(a);
                    }
                }
                default -> {
                    WorldDoc.Container f = furnace(a);
                    if (a.ctx instanceof Smelter.Load load) { // takes done
                        if (Restock.taken(results).isEmpty() || f == null) {
                            Assignment.Result bad = firstBad(results);
                            planner.fail(a, bad == null ? Reasons.MISSING_MATERIALS : bad.reason(), "nothing taken");
                        } else {
                            smeltLoad(a, f, load);
                        }
                        return;
                    }
                    Smelter.Load load = (Smelter.Load) ((Object[]) a.ctx)[0];
                    Assignment.Result r = last(results, TaskTypes.SMELT_LOAD);
                    if (r == null || !r.ok()) {
                        planner.fail(a, r == null ? Reasons.CANCELLED : r.reason(), r == null ? null : r.message());
                        return;
                    }
                    addAll(smelted, sum(results, TaskTypes.SMELT_LOAD, "collected"));
                    int loaded = Math.max(0, Json.getInt(r.data(), "loaded", load.count()));
                    if (loaded > 0) {
                        readyAt.put(a.item.lock(), System.currentTimeMillis() + cookMs(load.type(), loaded));
                        inFurnace.merge(a.item.lock(), loaded, Integer::sum);
                        planner.finish(a);
                    } else {
                        planner.fail(a, Reasons.MISSING_MATERIALS, "the furnace took nothing");
                    }
                }
            }
            svc.changed(p);
        }

        @Override
        public JsonObject view(boolean full) {
            int done = smelted.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
            int inside = inFurnace.values().stream().mapToInt(Integer::intValue).sum();
            JsonObject progress = Json.obj("done", done, "total", done + inside + remainingInputs, "smelted", smelted.deepCopy(),
                    "inFurnaces", inside, "remainingInputs", remainingInputs, "furnacesBusy", readyAt.size());
            long ms = runningMs + (runningSince > 0 ? System.currentTimeMillis() - runningSince : 0);
            if (ms > 60_000) {
                progress.addProperty("itemsPerHour", Math.round(done * 3_600_000.0 / ms));
            }
            return view(progress);
        }

        @Override
        public JsonObject persist() {
            long ms = runningMs + (runningSince > 0 ? System.currentTimeMillis() - runningSince : 0);
            return Json.obj("smelted", smelted.deepCopy(), "readyAt", Json.toTree(readyAt), "inFurnace", Json.toTree(inFurnace),
                    "runningMs", ms);
        }
    }
}
