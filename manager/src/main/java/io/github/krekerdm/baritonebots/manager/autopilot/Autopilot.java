package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotEvent;
import io.github.krekerdm.baritonebots.common.msg.BotStatus;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.tasks.KitPlanner;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import io.github.krekerdm.baritonebots.manager.world.WorldStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The autopilot (SPEC §5.7a, §5.7b): owns auto-supply ({@link Supply}), stuck detection ({@link StuckDetector}),
 * container discovery (periodic {@code containers_nearby} scans from online, idle-ish bots merged into the world
 * index with default roles), one {@link AutopilotSource} per server profile (sorting, idle work, inspections,
 * refills) and one {@link OrdersSource} per server with standing orders. Everything autopilot work does goes
 * through the planner with {@code auto:} / {@code order:} origins, so manual tasks still win. Loop-owned.
 */
public final class Autopilot {
    /** A bot that scanned from (about) the same place is not scanned there again before this. */
    static final long RESCAN_SAME_PLACE_MS = 10 * 60_000;
    static final long QUERY_TIMEOUT_MS = 3_000;
    /** Containers farther than this from the bot are not used by auto-supply. */
    static final int SUPPLY_RADIUS = 160;
    /** Roles of containers auto-supply, goals and orders may take from. */
    static final List<String> SOURCE_ROLES = List.of("storage", "kit", "supply", "inbox", "fuel");

    private final Manager m;
    private final Planner planner;
    private final Supply supply;
    private final ProductionWork production;
    private final StuckDetector stuck = new StuckDetector();
    private final Map<String, AutopilotSource> sources = new LinkedHashMap<>();
    private final Map<String, OrdersSource> orders = new LinkedHashMap<>();
    private final Map<String, Scan> scans = new HashMap<>();
    private final Map<String, Long> throttle = new HashMap<>();
    private Categories categories;
    private List<ManagerConfig.Category> catDefs;
    private GameData catData;

    private record Scan(long at, Pos pos, String dim) {
    }

    public Autopilot(Manager m, Planner planner) {
        this.m = m;
        this.planner = planner;
        this.supply = new Supply(m, this);
        this.production = new ProductionWork(m, planner);
    }

    public Supply supply() {
        return supply;
    }

    ProductionWork production() {
        return production;
    }

    Manager manager() {
        return m;
    }

    /** The effective autopilot settings of a bot (global + {@code bots[].autopilot}). */
    public ManagerConfig.AutopilotCfg cfg(BotState b) {
        return m.config.get().autopilot().forBot(b == null ? null : b.def);
    }

    /** Categories for the current config and game data. */
    public Categories categories() {
        List<ManagerConfig.Category> defs = m.config.get().autopilot().categories();
        GameData data = m.gameData.current();
        if (categories == null || defs != catDefs || data != catData) {
            categories = new Categories(defs, data);
            catDefs = defs;
            catData = data;
        }
        return categories;
    }

    public TaskNeeds.Context needsContext(BotState b) {
        List<String> avoid;
        try {
            avoid = m.effectiveConfig(b).behaviour().autoEat().avoid();
        } catch (RuntimeException e) {
            avoid = List.of();
        }
        return new TaskNeeds.Context(m.gameData.current(), cfg(b), categories(), avoid);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Registers one work source per server profile and one orders source per server with orders. */
    public void sync(ManagerConfig cfg) {
        java.util.Set<String> servers = new java.util.HashSet<>();
        for (ManagerConfig.ServerProfile s : cfg.servers()) {
            String key = s.id().toLowerCase(Locale.ROOT);
            servers.add(key);
            if (!sources.containsKey(key)) {
                AutopilotSource src = new AutopilotSource(m, this, s.id());
                sources.put(key, src);
                planner.addSource(src);
            }
            boolean hasOrders = cfg.orders().stream().anyMatch(o -> o.serverId() != null && o.serverId().equalsIgnoreCase(s.id()));
            if (hasOrders && !orders.containsKey(key)) {
                OrdersSource os = new OrdersSource(m, this, s.id());
                orders.put(key, os);
                planner.addSource(os);
            } else if (!hasOrders && orders.containsKey(key)) {
                planner.removeSource(orders.remove(key).id(), "stopped");
            }
        }
        for (String key : List.copyOf(sources.keySet())) {
            if (!servers.contains(key)) {
                planner.removeSource(sources.remove(key).id(), "stopped");
            }
        }
        for (String key : List.copyOf(orders.keySet())) {
            if (!servers.contains(key)) {
                planner.removeSource(orders.remove(key).id(), "stopped");
            }
        }
    }

    /** After every planner tick: container discovery, goal bookkeeping. */
    public void afterTick() {
        long now = System.currentTimeMillis();
        try {
            discovery(now);
        } catch (RuntimeException e) {
            io.github.krekerdm.baritonebots.manager.util.Log.error("autopilot discovery failed", e);
        }
        m.goals.sweep();
    }

    public void forgetBot(String botId) {
        supply.forget(botId);
        stuck.forget(botId);
        scans.remove(botId);
    }

    // ------------------------------------------------------------------ status, events

    /** Every status: stuck detection for the running task. */
    public void onStatus(BotState b) {
        QueueEntry cur = b.queue.current();
        BotStatus st = b.status;
        long now = System.currentTimeMillis();
        if (cur == null || st == null || st.pos() == null || m.catalog.isStep(cur.type()) || !b.online()) {
            stuck.update(b.id, null, now, 0);
            return;
        }
        BotStatus.TaskInfo ti = st.task();
        StuckDetector.Sample s = new StuckDetector.Sample(cur.id(), cur.type(),
                ti != null && BotStatus.TaskInfo.PAUSED.equals(ti.state()), st.pos().x(), st.pos().y(), st.pos().z(),
                ti == null ? null : ti.step(), ti == null ? -1 : ti.progress(),
                st.baritone() == null ? null : st.baritone().goal());
        long ms = cfg(b).stuckSec() * 1000L;
        if (stuck.update(b.id, s, now, ms)) {
            m.dispatcher.onStuck(b, ms);
        }
    }

    /** {@code tool_low} / {@code food_low}: a refill at the next safe point. */
    public void onBotEvent(BotState b, BotEvent ev) {
        if (EventKinds.FOOD_LOW.equals(ev.kind())) {
            supply.onLow(b, "food");
        } else if (EventKinds.TOOL_LOW.equals(ev.kind())) {
            String item = ev.data() == null ? "" : Json.getString(ev.data(), "item", "");
            String kind = toolKindOf(item);
            if (kind != null) {
                supply.onLow(b, "tool:" + kind);
            }
        }
    }

    static String toolKindOf(String item) {
        String p = Ids.path(item == null ? "" : item);
        for (String k : List.of("pickaxe", "axe", "shovel", "hoe", "sword")) {
            if (p.endsWith("_" + k)) {
                return k;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ discovery

    private void discovery(long now) {
        for (BotState b : m.bots.all()) {
            if (!b.online() || b.dead || b.def.serverId() == null || b.status == null) {
                continue;
            }
            ManagerConfig.AutopilotCfg cfg = cfg(b);
            QueueEntry cur = b.queue.current();
            if (!cfg.discovery() || cur != null && m.catalog.isHeavy(cur.type())) {
                continue; // idle-ish only: never in the middle of mining / building / exploring
            }
            Pos pos = Planner.posOf(b);
            if (pos == null) {
                continue;
            }
            String dim = b.status.dim() == null ? Dims.OVERWORLD : Dims.normalize(b.status.dim());
            Scan last = scans.get(b.id);
            if (last != null && now - last.at() < cfg.discoveryIntervalSec() * 1000L) {
                continue;
            }
            if (last != null && last.dim().equals(dim) && last.pos().distance(pos) < cfg.discoveryRadius() / 2.0
                    && now - last.at() < RESCAN_SAME_PLACE_MS) {
                continue;
            }
            scanNow(b);
        }
    }

    /** One {@code containers_nearby} scan from the bot's position, merged with default roles. */
    public void scanNow(BotState b) {
        Pos pos = Planner.posOf(b);
        if (pos == null || b.def.serverId() == null) {
            return;
        }
        String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        scans.put(b.id, new Scan(System.currentTimeMillis(), pos, dim));
        String sid = b.def.serverId();
        int radius = cfg(b).discoveryRadius();
        planner.queryVia(b, QueryKinds.CONTAINERS_NEARBY, Json.obj("radius", radius), 10_000, (r, t) -> {
            if (r == null || !r.ok() || r.data() == null) {
                return;
            }
            JsonArray found = Json.getArr(r.data(), "containers");
            if (found == null || found.isEmpty()) {
                return;
            }
            int added = m.worlds.mergeDiscovered(sid, found, roleChooser(sid));
            if (added > 0) {
                m.event("containers_found", Levels.INFO, b.id, "event.autopilot.discovered",
                        Map.of("bot", b.id, "count", added, "radius", radius));
                m.broadcastWorld(sid);
            }
        });
    }

    /**
     * Default roles of a newly found container: furnaces / crafting tables by block; chests, barrels and shulker
     * boxes become {@code storage} within {@code autopilot.homeRadius} of a home waypoint, else {@code found}.
     */
    public WorldStore.RoleChooser roleChooser(String serverId) {
        return (dim, pos, block) -> {
            List<String> obvious = WorldStore.defaultRoles(block);
            if (!obvious.isEmpty()) {
                return obvious;
            }
            String p = Ids.path(block == null ? "" : block);
            if (p.equals("chest") || p.equals("barrel") || p.endsWith("shulker_box")) {
                return List.of(inHome(serverId, dim, pos) ? "storage" : "found");
            }
            return List.of();
        };
    }

    /** Within {@code homeRadius} of the {@code home} waypoint or any bot's home waypoint of the server. */
    boolean inHome(String serverId, String dim, Pos pos) {
        int r = m.config.get().autopilot().homeRadius();
        if (r <= 0) {
            return false;
        }
        for (WorldDoc.Waypoint w : homes(serverId)) {
            if (Dims.normalize(w.dim()).equals(Dims.normalize(dim)) && w.pos().distance(pos) <= r) {
                return true;
            }
        }
        return false;
    }

    List<WorldDoc.Waypoint> homes(String serverId) {
        WorldDoc doc = m.worlds.get(serverId);
        Set<String> names = new java.util.HashSet<>();
        names.add("home");
        for (BotState b : m.bots.all()) {
            if (serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))) {
                names.add(b.def.homeWaypointName().toLowerCase(Locale.ROOT));
            }
        }
        return doc.waypoints.stream().filter(w -> names.contains(w.name().toLowerCase(Locale.ROOT))).toList();
    }

    // ------------------------------------------------------------------ helpers shared by supply / goals / sources

    /** Live inventory (query); the last status when the query fails or times out. Calls back on the loop. */
    public void inventory(BotState b, Consumer<Inventory> cb) {
        int statusFree = b.status == null ? 36 : b.status.freeSlots();
        planner.queryVia(b, QueryKinds.INVENTORY, new JsonObject(), QUERY_TIMEOUT_MS, (r, t) -> {
            Inventory inv = r != null && r.ok() && r.data() != null && r.data().has("slots")
                    ? Inventory.fromQuery(r.data(), statusFree) : Inventory.fromStatus(b.status);
            cb.accept(inv);
        });
    }

    /** May auto-supply / goals take from this container? */
    boolean isSource(WorldDoc.Container c, boolean useFound) {
        for (String role : SOURCE_ROLES) {
            if (c.hasRole(role)) {
                return true;
            }
        }
        return c.sortedCategory() != null || useFound && c.hasRole("found");
    }

    /** Containers a bot may take from, nearest first; {@code available == null} = never inspected. */
    public List<SupplyPlanner.Source> supplySources(BotState b) {
        WorldDoc doc = b.def.serverId() == null ? null : m.worlds.get(b.def.serverId());
        if (doc == null) {
            return List.of();
        }
        String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        Pos at = Planner.posOf(b);
        boolean found = cfg(b).useFound();
        List<WorldDoc.Container> list = new ArrayList<>();
        for (WorldDoc.Container c : doc.containers) {
            if (Dims.normalize(c.dim()).equals(dim) && isSource(c, found)
                    && (at == null || c.pos().distance(at) <= SUPPLY_RADIUS)
                    && !planner.locks().heldByOther(Planner.containerKey(c), b.id)) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparingDouble(c -> at == null ? 0 : c.pos().distance(at)));
        List<SupplyPlanner.Source> out = new ArrayList<>();
        for (WorldDoc.Container c : list) {
            out.add(new SupplyPlanner.Source(c, planner.available(c, null)));
        }
        return out;
    }

    /**
     * {@code deposit} args that put the bot's items straight into their category chests (used by
     * {@code deposit_storage} when sorting is on and no inbox exists); empty when that does not apply.
     */
    public List<JsonObject> categoryDeposits(BotState b, List<String> keep) {
        if (!cfg(b).sort() || b.status == null || b.def.serverId() == null) {
            return List.of();
        }
        WorldDoc doc = m.worlds.get(b.def.serverId());
        String dim = b.status.dim() == null ? Dims.OVERWORLD : Dims.normalize(b.status.dim());
        Map<String, List<Pos>> byCat = new LinkedHashMap<>();
        for (WorldDoc.Container c : doc.containers) {
            if (!Dims.normalize(c.dim()).equals(dim)) {
                continue;
            }
            if (c.hasRole("inbox")) {
                return List.of(); // inboxes exist: everything goes there and gets sorted
            }
            String cat = c.sortedCategory();
            if (cat != null) {
                byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(c.pos());
            }
        }
        if (byCat.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> items = new LinkedHashMap<>();
        b.status.items().forEach((id, n) -> {
            if (!Ids.matchesAny(keep, id)) {
                items.put(id, n);
            }
        });
        List<JsonObject> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> g : categories().group(items).entrySet()) {
            List<Pos> chests = byCat.get(g.getKey());
            if (chests == null) {
                continue;
            }
            List<Pos> ordered = KitPlanner.nearestNeighbour(chests, Planner.posOf(b), p -> p);
            out.add(Json.obj("containers", Json.arrOf(ordered), "only", Json.arrOf(g.getValue().keySet())));
        }
        return out;
    }

    /** The {@code sort_storage} manager step: one sorting round over the bot's inboxes, as {@code transfer}s. */
    private AutopilotSource source(String serverId) {
        return serverId == null ? null : sources.get(serverId.toLowerCase(Locale.ROOT));
    }

    /** Sorting targets of a dimension: category chests and storage chests (not inbox / kit / supply / fuel). */
    public List<WorldDoc.Container> sortTargets(String serverId, String dim) {
        WorldDoc doc = m.worlds.get(serverId);
        return doc == null ? List.of() : AutopilotSource.sortTargets(doc, dim);
    }

    /** One sorting round's plan for an inbox (also used by {@code sort} projects). */
    public SortPlanner.Plan sortPlan(String serverId, WorldDoc.Container inbox, int slots) {
        return SortPlanner.plan(inbox, sortTargets(serverId, inbox.dim()), categories(), slots);
    }

    /** {@code transfer} entries (origin = the assignment's source) emptying one inbox, sized to the bot's free slots. */
    public List<QueueEntry> sortMoves(String serverId, io.github.krekerdm.baritonebots.manager.planner.Assignment a,
                                      BotState b, WorldDoc.Container inbox) {
        AutopilotSource src = source(serverId);
        return src == null ? List.of() : src.moves(a, b, inbox);
    }

    /** Category adoption of unassigned storage chests (what auto-sort does every tick). */
    public void adoptCategories(String serverId) {
        AutopilotSource src = source(serverId);
        WorldDoc doc = m.worlds.get(serverId);
        if (src != null && doc != null) {
            src.adopt(doc);
        }
    }

    public void sortNow(BotState b, QueueEntry e) {
        m.dispatcher.startStep(b, e);
        m.loop.post(() -> {
            AutopilotSource src = b.def.serverId() == null ? null : sources.get(b.def.serverId().toLowerCase(Locale.ROOT));
            List<QueueEntry> children = src == null ? List.of() : src.sortEntries(b, e);
            m.dispatcher.finishStep(b, e, true, null, children.isEmpty() ? "nothing to sort" : "sorting",
                    null, children, false);
        });
    }

    /** {@code true} at most once per {@code everyMs} for a key (event throttling). */
    boolean once(String key, long everyMs) {
        long now = System.currentTimeMillis();
        Long last = throttle.get(key);
        if (last != null && now - last < everyMs) {
            return false;
        }
        throttle.put(key, now);
        if (throttle.size() > 1000) {
            throttle.values().removeIf(t -> now - t > 3_600_000);
        }
        return true;
    }

    void forgetThrottle(String key) {
        throttle.remove(key);
    }

    // ------------------------------------------------------------------ views

    public JsonObject view() {
        JsonArray srcs = new JsonArray();
        sources.values().forEach(s -> srcs.add(s.view()));
        JsonObject sc = new JsonObject();
        scans.forEach((k, v) -> sc.add(k, Json.obj("at", v.at(), "pos", v.pos(), "dim", v.dim())));
        JsonObject stuckView = new JsonObject();
        long now = System.currentTimeMillis();
        for (BotState b : m.bots.all()) {
            long still = stuck.stillFor(b.id, now);
            if (still > 0) {
                stuckView.addProperty(b.id, still / 1000);
            }
        }
        return Json.obj("settings", Json.toTree(m.config.get().autopilot()), "servers", srcs, "scans", sc,
                "supply", supply.view(), "stillSec", stuckView, "goals", m.goals.view().get("goals"),
                "categories", Json.arrOf(categories().names()));
    }

    /** Standing orders with their live state (GET /api/orders). */
    public JsonObject ordersView() {
        JsonArray out = new JsonArray();
        for (ManagerConfig.OrderDef o : m.config.get().orders()) {
            JsonObject v = Json.toObject(o);
            OrdersSource src = o.serverId() == null ? null : orders.get(o.serverId().toLowerCase(Locale.ROOT));
            JsonObject st = src == null ? null : src.status(o.id());
            if (st != null) {
                v.add("status", st);
            }
            out.add(v);
        }
        return Json.obj("orders", out);
    }
}
