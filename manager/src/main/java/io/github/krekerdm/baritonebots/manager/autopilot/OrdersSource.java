package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.planner.Resolver;
import io.github.krekerdm.baritonebots.manager.planner.Restock;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.planner.WorkSource;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standing orders of one server (SPEC §5.7b): every planner tick each enabled order compares the stock of its item
 * in its {@code into} containers with {@code min}; below it the order becomes active and stays active until the
 * stock (plus what is in transit) reaches {@code max} (or {@code min}). Deficits resolve exactly like build deficits
 * ({@link Resolver}: haul from other storage → mine → craft → smelt → manual) and run as {@link ProductionWork} items
 * delivering into the {@code into} containers. Containers never inspected are inspected first. Loop-owned.
 */
final class OrdersSource implements WorkSource {
    static final double PRIORITY = 0.5;
    static final String INSPECT = "inspect";
    static final long BLOCKED_EVERY_MS = 10 * 60_000;

    private final Manager m;
    private final Autopilot ap;
    private final String serverId;
    private final Map<String, Boolean> active = new HashMap<>();
    private final Map<String, Host> hosts = new HashMap<>();
    private final Map<String, JsonObject> status = new LinkedHashMap<>();

    /** Where one order delivers and takes from. */
    final class Host implements ProductionWork.Host {
        final ManagerConfig.OrderDef order;
        final String dim;
        final List<WorldDoc.Container> into;
        final List<WorldDoc.Container> sources;

        Host(ManagerConfig.OrderDef order, String dim, List<WorldDoc.Container> into, List<WorldDoc.Container> sources) {
            this.order = order;
            this.dim = dim;
            this.into = into;
            this.sources = sources;
        }

        @Override
        public String serverId() {
            return serverId;
        }

        @Override
        public String dim() {
            return dim;
        }

        @Override
        public List<WorldDoc.Container> supply() {
            return into;
        }

        @Override
        public List<WorldDoc.Container> sources() {
            return sources;
        }

        @Override
        public String antiXray() {
            return m.config.get().server(serverId).map(ManagerConfig.ServerProfile::antiXray)
                    .orElse(ManagerConfig.ServerProfile.ANTI_XRAY_NONE);
        }
    }

    OrdersSource(Manager m, Autopilot ap, String serverId) {
        this.m = m;
        this.ap = ap;
        this.serverId = serverId;
    }

    @Override
    public String id() {
        return "orders:" + serverId;
    }

    @Override
    public String origin() {
        return Planner.ORIGIN_ORDER_PREFIX + serverId;
    }

    @Override
    public String serverId() {
        return serverId;
    }

    @Override
    public boolean allows(BotState b) {
        return true;
    }

    JsonObject status(String orderId) {
        return status.get(orderId);
    }

    // ------------------------------------------------------------------ evaluation

    @Override
    public List<WorkItem> workItems(long now) {
        hosts.clear();
        status.clear();
        List<WorkItem> out = new ArrayList<>();
        WorldDoc doc = m.worlds.get(serverId);
        for (ManagerConfig.OrderDef o : m.config.get().orders()) {
            if (o.serverId() == null || !o.serverId().equalsIgnoreCase(serverId)) {
                continue;
            }
            if (!o.enabled()) {
                status.put(o.id(), Json.obj("state", "disabled"));
                continue;
            }
            try {
                evaluate(o, doc, out);
            } catch (RuntimeException e) {
                status.put(o.id(), Json.obj("state", "error", "message", String.valueOf(e.getMessage())));
            }
        }
        active.keySet().removeIf(id -> !status.containsKey(id));
        return out;
    }

    /** Containers an order delivers into: a role ({@code storage}, {@code sorted:food}, ...) or one container id. */
    static List<WorldDoc.Container> into(ManagerConfig.OrderDef o, WorldDoc doc) {
        String into = o.into() == null ? "storage" : o.into();
        boolean role = SettingsSchema.ORDER_ROLES.contains(into) || into.startsWith(WorldDoc.SORTED_PREFIX);
        List<WorldDoc.Container> all = doc.containers.stream()
                .filter(c -> role ? c.hasRole(into) : c.id().equals(into)).toList();
        if (all.isEmpty()) {
            return all;
        }
        String dim = Dims.normalize(all.getFirst().dim());
        return all.stream().filter(c -> Dims.normalize(c.dim()).equals(dim)).toList();
    }

    private void evaluate(ManagerConfig.OrderDef o, WorldDoc doc, List<WorkItem> out) {
        String item = Ids.normalize(o.item());
        List<WorldDoc.Container> into = into(o, doc);
        if (into.isEmpty()) {
            status.put(o.id(), Json.obj("state", "no_containers", "message", "no container matches '" + o.into() + "'"));
            return;
        }
        String dim = Dims.normalize(into.getFirst().dim());
        List<WorldDoc.Container> unknown = into.stream().filter(c -> c.snapshot() == null).toList();
        if (!unknown.isEmpty()) {
            String key = INSPECT + ":" + o.id();
            out.add(new WorkItem(key, id(), INSPECT, null, PRIORITY, null, dim, unknown.getFirst().pos(), key, 1,
                    "inspect order containers", Json.obj("order", o.id(),
                    "containers", Json.arrOf(unknown.stream().map(WorldDoc.Container::pos).limit(8).toList()))));
            status.put(o.id(), Json.obj("state", "inspecting"));
            return;
        }
        Planner planner = m.planner;
        int stock = 0;
        for (WorldDoc.Container c : into) {
            Map<String, Integer> av = planner.available(c, null);
            stock += av == null ? 0 : av.getOrDefault(item, 0);
        }
        int transit = 0;
        for (Assignment a : planner.assignmentsOf(id())) {
            if (o.id().equals(Json.getString(a.item.data(), "order", ""))) {
                transit += a.promised.getOrDefault(item, 0);
            }
        }
        int target = o.target();
        boolean found = m.config.get().autopilot().useFound();
        List<WorldDoc.Container> sources = doc.containers.stream()
                .filter(c -> Dims.normalize(c.dim()).equals(dim) && !into.contains(c) && !c.hasRole("supply")
                        && ap.isSource(c, found))
                .toList();
        Host host = new Host(o, dim, into, sources);
        hosts.put(o.id(), host); // also for inactive orders: running work still delivers through it
        boolean on = active(active.getOrDefault(o.id(), false), stock, transit, o.min(), target);
        active.put(o.id(), on);
        JsonObject st = Json.obj("state", on ? "active" : "ok", "stock", stock, "target", target, "inTransit", transit);
        status.put(o.id(), st);
        if (!on) {
            ap.forgetThrottle("order_blocked/" + o.id());
            return;
        }
        Map<String, Integer> storage = new HashMap<>();
        for (WorldDoc.Container c : sources) {
            Map<String, Integer> av = planner.available(c, null);
            if (av != null) {
                av.forEach((k, v) -> storage.merge(k, v, Integer::sum));
            }
        }
        ProductionWork production = ap.production();
        GameData data = m.gameData.current();
        Resolver.Env env = new Resolver.Env(true, production.hasCraftingTable(host), production.furnaceTypes(host));
        Resolver.Plan plan = plan(item, target, stock, transit, storage, data, env);
        Map<String, String> notes = new LinkedHashMap<>();
        for (WorkItem w : production.items(id(), host, plan, PRIORITY, data, notes)) {
            JsonObject d = w.data().deepCopy();
            d.addProperty("order", o.id());
            out.add(new WorkItem(o.id() + "/" + w.id(), w.source(), w.kind(), w.role(), w.priority(), w.needs(), w.dim(),
                    w.location(), w.lock(), w.capacity(), Ids.path(item) + ": " + w.label(), d));
        }
        JsonArray rows = new JsonArray();
        plan.rows().forEach(r -> rows.add(r.view()));
        JsonArray actions = new JsonArray();
        plan.actions().forEach(a -> actions.add(a.view()));
        st.add("rows", rows);
        st.add("actions", actions);
        if (!notes.isEmpty()) {
            st.add("notes", Json.toTree(notes));
        }
        if (!plan.manual().isEmpty()) {
            st.add("manual", Json.toTree(plan.manual()));
            if (ap.once("order_blocked/" + o.id(), BLOCKED_EVERY_MS)) {
                List<String> parts = new ArrayList<>();
                plan.manual().forEach((k, v) -> parts.add(v + " × " + Ids.path(k)));
                m.event("order_blocked", Levels.WARN, null, "event.order.blocked", Map.of("item", Ids.path(item),
                        "items", String.join(", ", parts)), Json.obj("order", o.id(), "manual", Json.toTree(plan.manual())));
            }
        }
    }

    /** Hysteresis: an order turns active below {@code min} and stays active until stock + transit reach the target. */
    static boolean active(boolean wasActive, int stock, int transit, int min, int target) {
        boolean on = wasActive || stock < min;
        return on && stock + transit < target;
    }

    /**
     * The deficit plan of one order: remaining = target, the {@code into} stock counts like a project's supply,
     * other storage feeds hauling and ingredients. Pure.
     */
    static Resolver.Plan plan(String item, int target, int stock, int transit, Map<String, Integer> storage, GameData data,
                              Resolver.Env env) {
        Resolver.Stock s = new Resolver.Stock(Map.of(item, stock), storage, Map.of(), Map.of(item, transit));
        return Resolver.resolve(Map.of(item, target), Map.of(item, target), s, data, env);
    }

    // ------------------------------------------------------------------ running

    private Host hostOf(WorkItem w) {
        return hosts.get(Json.getString(w.data(), "order", ""));
    }

    @Override
    public boolean eligible(BotState b, WorkItem w) {
        if (INSPECT.equals(w.kind())) {
            return true;
        }
        Host h = hostOf(w);
        return h != null && ap.production().eligible(b, w, h);
    }

    @Override
    public void begin(Assignment a, BotState b) {
        if (INSPECT.equals(a.item.kind())) {
            a.phase("inspect");
            m.planner.push(a, List.of(Planner.entry(a, TaskTypes.INSPECT,
                    Json.obj("containers", a.item.data().get("containers").deepCopy()), Restock.TAKE_TIMEOUT_SEC, null)));
            return;
        }
        Host h = hostOf(a.item);
        if (h == null) {
            m.planner.release(a, "stopped", true);
            return;
        }
        ap.production().begin(a, b, h);
    }

    @Override
    public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
        if (INSPECT.equals(a.item.kind())) {
            m.planner.finish(a);
            return;
        }
        Host h = hostOf(a.item);
        if (h == null) {
            m.planner.release(a, "stopped", true);
            return;
        }
        ap.production().onBatchDone(a, b, results, h);
    }

    @Override
    public void onReleased(Assignment a, String why) {
    }
}
