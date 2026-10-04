package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ConfigValidator;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.planner.Resolver;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gather} projects (SPEC §5.7): item quotas delivered into storage. Config {@code {quotas:{item:count},
 * into:"storage|supply|kit|fuel|inbox|sorted:<cat>|<containerId>", storage?:[pos], repeat:false, dim}}. When the
 * project starts, the indexed stock of every quota item in the target containers is the baseline; the target is
 * baseline + count. Deficits resolve like build deficits ({@link Resolver}: haul from other containers → mine →
 * craft → smelt → manual) and run as {@link ProductionWork} items delivering into the targets. Done when every
 * item's stock reached its target (with {@code repeat}: a new baseline, the next round starts).
 */
public final class GatherKind implements ProjectKind {
    public static final String KIND = "gather";
    private final Manager m;

    public GatherKind(Manager m) {
        this.m = m;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject normalizeConfig(JsonObject config, String serverId) {
        KindConfig c = new KindConfig(config);
        JsonObject quotas = c.itemCounts("quotas", true);
        String into = Json.getString(config, "into", "storage").trim();
        if (!ConfigValidator.validInto(into)) {
            c.errors.put("into", "pattern");
        }
        JsonArray storage = c.positions("storage");
        boolean repeat = c.bool("repeat", false);
        return c.done(Json.obj("quotas", quotas, "into", into, "storage", storage, "repeat", repeat, "dim", c.dim()));
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService svc) {
        return new Runtime(p, svc);
    }

    /** Target stock per item = baseline + quota. Pure. */
    static Map<String, Integer> targets(Map<String, Integer> quotas, Map<String, Integer> baseline) {
        Map<String, Integer> out = new LinkedHashMap<>();
        quotas.forEach((item, n) -> out.put(item, baseline.getOrDefault(item, 0) + n));
        return out;
    }

    /**
     * The deficit plan: the target containers' stock counts like a project's supply, other containers feed hauling
     * and ingredients. Pure.
     */
    static Resolver.Plan plan(Map<String, Integer> target, Map<String, Integer> stock, Map<String, Integer> transit,
                              Map<String, Integer> storage, GameData data, Resolver.Env env) {
        Map<String, Integer> want = new LinkedHashMap<>();
        target.forEach((item, t) -> {
            if (stock.getOrDefault(item, 0) < t) {
                want.put(item, t);
            }
        });
        Resolver.Stock s = new Resolver.Stock(stock, storage, Map.of(), transit);
        return Resolver.resolve(want, want, s, data, env);
    }

    static final class Runtime extends KindRuntime {
        private final ProductionWork production;
        private final Map<String, Integer> quotas = new LinkedHashMap<>();
        private final JsonArray storagePos;
        private final String into;
        private final boolean repeat;
        private Map<String, Integer> baseline;
        private int rounds;
        private Map<String, Integer> lastStock = Map.of();
        private Resolver.Plan plan;
        private List<WorldDoc.Container> targetCache = List.of();

        Runtime(Project p, ProjectService svc) {
            super(p, svc);
            production = new ProductionWork(m, planner);
            JsonObject q = Json.getObj(p.config, "quotas");
            if (q != null) {
                q.entrySet().forEach(e -> quotas.put(e.getKey(), e.getValue().getAsInt()));
            }
            storagePos = Json.getArr(p.config, "storage");
            into = Json.getString(p.config, "into", "storage");
            repeat = Json.getBool(p.config, "repeat", false);
            JsonObject b = Json.getObj(p.state, "baseline");
            if (b != null) {
                baseline = new LinkedHashMap<>();
                b.entrySet().forEach(e -> baseline.put(e.getKey(), e.getValue().getAsInt()));
            }
            rounds = Json.getInt(p.state, "rounds", 0);
        }

        @Override
        public void start(boolean restart) {
            super.start(restart);
            if (restart) {
                baseline = null; // a new start gathers the quotas again
                rounds = 0;
            }
        }

        /** The target containers: configured positions, else the {@code into} role / container id in the dimension. */
        List<WorldDoc.Container> targetContainers() {
            if (storagePos != null && !storagePos.isEmpty()) {
                return resolve(storagePos, "storage");
            }
            WorldDoc doc = doc();
            if (doc == null) {
                return List.of();
            }
            boolean role = SettingsSchema.ORDER_ROLES.contains(into) || into.startsWith(WorldDoc.SORTED_PREFIX);
            return doc.containers.stream().filter(c -> Dims.normalize(c.dim()).equals(dim)
                    && (role ? c.hasRole(into) : c.id().equals(into))).toList();
        }

        @Override
        public List<WorldDoc.Container> supply() {
            return targetCache;
        }

        /** Everything but the targets (hauling from a target into a target would go round in circles). */
        @Override
        public List<WorldDoc.Container> sources() {
            List<WorldDoc.Container> out = new ArrayList<>();
            for (WorldDoc.Container c : super.sources()) {
                if (targetCache.stream().noneMatch(t -> t.pos().equals(c.pos())) && !c.hasRole("supply")) {
                    out.add(c);
                }
            }
            return out;
        }

        private Map<String, Integer> stock(List<WorldDoc.Container> cs) {
            Map<String, Integer> out = new HashMap<>();
            for (WorldDoc.Container c : cs) {
                Map<String, Integer> av = planner.available(c, null);
                if (av != null) {
                    quotas.keySet().forEach(item -> out.merge(item, av.getOrDefault(item, 0), Integer::sum));
                }
            }
            return out;
        }

        @Override
        public List<WorkItem> workItems(long now) {
            List<WorkItem> out = new ArrayList<>();
            targetCache = targetContainers();
            if (targetCache.isEmpty()) {
                notes.put("targets", "no container matches '" + into + "' in " + dim);
                svc.blocked(p, "targets", notes.get("targets"), null);
                return out;
            }
            notes.remove("targets");
            List<WorldDoc.Container> unknown = unknown(targetCache);
            if (!unknown.isEmpty()) {
                out.add(inspectItem("targets", unknown, p.priority));
                return out;
            }
            Map<String, Integer> stock = stock(targetCache);
            lastStock = stock;
            if (baseline == null) {
                baseline = new LinkedHashMap<>(stock);
                svc.changed(p);
            }
            Map<String, Integer> target = GatherKind.targets(quotas, baseline);
            boolean met = target.entrySet().stream().allMatch(e -> stock.getOrDefault(e.getKey(), 0) >= e.getValue());
            if (met) {
                if (planner.assignmentsOf(id()).isEmpty()) {
                    if (repeat) {
                        rounds++;
                        baseline = new LinkedHashMap<>(stock);
                        svc.changed(p);
                    } else {
                        rounds++;
                        svc.done(p);
                    }
                }
                return out;
            }
            Map<String, Integer> transit = new HashMap<>();
            for (Assignment a : planner.assignmentsOf(id())) {
                a.promised.forEach((k, v) -> transit.merge(k, v, Integer::sum));
            }
            Map<String, Integer> storage = new HashMap<>();
            for (WorldDoc.Container c : sources()) {
                Map<String, Integer> av = planner.available(c, null);
                if (av != null) {
                    av.forEach((k, v) -> storage.merge(k, v, Integer::sum));
                }
            }
            GameData data = m.gameData.current();
            Resolver.Env env = new Resolver.Env(true, production.hasCraftingTable(this), production.furnaceTypes(this));
            plan = plan(target, stock, transit, storage, data, env);
            Map<String, String> why = new LinkedHashMap<>();
            out.addAll(production.items(id(), this, plan, p.priority, data, why));
            notes.keySet().removeIf(k -> k.startsWith("note:"));
            why.forEach((k, v) -> notes.put("note:" + k, v));
            if (!plan.manual().isEmpty()) {
                List<String> parts = new ArrayList<>();
                plan.manual().forEach((k, v) -> parts.add(v + " × " + Ids.path(k)));
                svc.blocked(p, "manual", String.join(", ", parts) + " must be added by hand",
                        Json.obj("manual", Json.toTree(plan.manual())));
            } else {
                svc.unblocked(p, "manual");
            }
            return out;
        }

        @Override
        public boolean eligible(BotState b, WorkItem w) {
            return INSPECT.equals(w.kind()) || production.eligible(b, w, this);
        }

        @Override
        public void begin(Assignment a, BotState b) {
            if (INSPECT.equals(a.item.kind())) {
                beginInspect(a);
            } else {
                production.begin(a, b, this);
            }
        }

        @Override
        public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
            if (INSPECT.equals(a.item.kind())) {
                planner.finish(a);
            } else {
                production.onBatchDone(a, b, results, this);
            }
            svc.changed(p);
        }

        @Override
        public JsonObject view(boolean full) {
            JsonArray items = new JsonArray();
            int done = 0;
            int total = 0;
            Map<String, Integer> base = baseline == null ? Map.of() : baseline;
            for (Map.Entry<String, Integer> e : quotas.entrySet()) {
                int stock = lastStock.getOrDefault(e.getKey(), 0);
                int delivered = baseline == null ? 0 : Math.max(0, Math.min(e.getValue(), stock - base.getOrDefault(e.getKey(), 0)));
                done += delivered;
                total += e.getValue();
                items.add(Json.obj("item", e.getKey(), "count", e.getValue(), "delivered", delivered, "stock", stock,
                        "target", base.getOrDefault(e.getKey(), 0) + e.getValue()));
            }
            JsonObject progress = Json.obj("done", done, "total", total, "items", items, "rounds", rounds);
            JsonObject o = view(progress);
            JsonArray manual = new JsonArray();
            if (plan != null) {
                plan.manual().forEach((item, count) -> manual.add(Json.obj("item", item, "count", count)));
                if (full) {
                    JsonArray actions = new JsonArray();
                    plan.actions().forEach(x -> actions.add(x.view()));
                    o.add("actions", actions);
                }
            }
            o.add("manual", manual);
            return o;
        }

        @Override
        public JsonObject persist() {
            return Json.obj("baseline", baseline == null ? null : Json.toTree(baseline), "rounds", rounds);
        }
    }
}
