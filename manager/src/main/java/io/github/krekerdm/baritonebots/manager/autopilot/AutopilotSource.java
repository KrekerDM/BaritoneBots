package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.Restock;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.planner.WorkSource;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Autopilot work of one server profile (SPEC §5.7a), offered to idle bots through the planner with role-neutral,
 * low-priority items (projects and orders win; manual tasks cancel them): {@code refill} (tool_low / food_low
 * wishes, high priority), {@code sort} (inbox → category chests), {@code collect} (finished furnaces),
 * {@code refuel} (load furnaces with {@code autopilot.smeltInputs} from storage), {@code inspect} (containers with
 * unknown or old contents, a few per tick, batched per area) and {@code home} (idle for {@code idleHomeSec}). It also
 * lets unassigned storage chests adopt the category of what they hold. Loop-owned.
 */
final class AutopilotSource implements WorkSource {
    static final String REFILL = "refill";
    static final String SORT = "sort";
    static final String COLLECT = "collect";
    static final String REFUEL = "refuel";
    static final String INSPECT = "inspect";
    static final String HOME = "home";
    static final double P_REFILL = 5;
    static final double P_SORT = -1;
    static final double P_COLLECT = -1.5;
    static final double P_REFUEL = -2;
    static final double P_INSPECT = -2.5;
    static final double P_HOME = -5;
    /** Containers one inspect item opens at most, and how close they must be to each other. */
    static final int INSPECT_BATCH = 8;
    static final int INSPECT_AREA = 16;
    static final long SKIP_INSPECT_MS = 3_600_000;
    static final long EVENT_EVERY_MS = 10 * 60_000;
    /** Roles that make a container worth an idle inspection wherever it is (besides {@code sorted:*}). */
    static final List<String> INSPECT_ROLES = List.of("storage", "kit", "supply", "inbox", "trash", "fuel", "furnace",
            "crafting");
    /** A bot this close to its home waypoint is home. */
    static final double AT_HOME = 4;

    private final Manager m;
    private final Autopilot ap;
    private final String serverId;
    private final Map<String, Long> idleSince = new HashMap<>();
    /** furnace key → when the load we put in should be done. */
    private final Map<String, Long> furnaceReadyAt = new HashMap<>();
    /** "dim:pos" of containers an inspection could not open → until when they are skipped. */
    private final Map<String, Long> skipInspect = new HashMap<>();
    private final Map<String, String> notes = new LinkedHashMap<>();
    private int lastOffered;

    AutopilotSource(Manager m, Autopilot ap, String serverId) {
        this.m = m;
        this.ap = ap;
        this.serverId = serverId;
    }

    @Override
    public String id() {
        return "autopilot:" + serverId;
    }

    @Override
    public String origin() {
        return Planner.ORIGIN_AUTOPILOT_PREFIX + serverId;
    }

    @Override
    public String serverId() {
        return serverId;
    }

    private Planner planner() {
        return m.planner;
    }

    private WorldDoc doc() {
        return m.worlds.get(serverId);
    }

    private List<BotState> bots() {
        return m.bots.all().stream().filter(b -> serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))).toList();
    }

    @Override
    public boolean allows(BotState b) {
        ManagerConfig.AutopilotCfg c = ap.cfg(b);
        return c.sort() || c.idleWork() || c.discovery() || ap.supply().refillPending(b.id);
    }

    // ------------------------------------------------------------------ work items

    @Override
    public List<WorkItem> workItems(long now) {
        List<BotState> bots = bots();
        List<WorkItem> out = new ArrayList<>();
        notes.clear();
        if (bots.isEmpty()) {
            return out;
        }
        boolean sort = false;
        boolean idle = false;
        boolean discovery = false;
        for (BotState b : bots) {
            ManagerConfig.AutopilotCfg c = ap.cfg(b);
            sort |= c.sort();
            idle |= c.idleWork();
            discovery |= c.discovery();
            if (planner().isIdle(b, now)) {
                idleSince.putIfAbsent(b.id, now);
            } else {
                idleSince.remove(b.id);
            }
            if (ap.supply().refillPending(b.id)) {
                out.add(new WorkItem(REFILL + ":" + b.id, id(), REFILL, null, P_REFILL, null, null, null, null, 1,
                        "refill " + b.id, Json.obj("bot", b.id)));
            }
        }
        WorldDoc doc = doc();
        if (sort) {
            adopt(doc);
            sortItems(doc, out);
        }
        if (idle) {
            furnaceItems(doc, out, now);
        }
        if (discovery) {
            inspectItems(doc, bots, out, now);
        }
        if (idle) {
            homeItems(doc, bots, out, now);
        }
        lastOffered = out.size();
        return out;
    }

    /** Unassigned storage chests take the category most of their slots belong to. */
    void adopt(WorldDoc doc) {
        Categories cats = ap.categories();
        boolean changed = false;
        for (WorldDoc.Container c : List.copyOf(doc.containers)) {
            if (!c.hasRole("storage") || c.sortedCategory() != null || c.hasRole("inbox") || c.hasRole("kit")
                    || c.hasRole("supply") || c.hasRole("fuel") || c.hasRole("trash") || c.fromSign()
                    || c.snapshot() == null || c.snapshot().items().isEmpty()) {
                continue;
            }
            String cat = cats.adopt(c.snapshot().totals());
            if (cat == null) {
                continue;
            }
            List<String> roles = new ArrayList<>(c.roles());
            roles.add(WorldDoc.SORTED_PREFIX + cat);
            m.worlds.setRoles(serverId, c, roles);
            changed = true;
            m.event("container_category", Levels.INFO, null, "event.autopilot.adopted",
                    Map.of("x", c.pos().x(), "y", c.pos().y(), "z", c.pos().z(), "category", cat));
        }
        if (changed) {
            m.broadcastWorld(serverId);
        }
    }

    /** Category chests and empty unassigned storage chests of a dimension. */
    static List<WorldDoc.Container> sortTargets(WorldDoc doc, String dim) {
        List<WorldDoc.Container> out = new ArrayList<>();
        for (WorldDoc.Container c : doc.containers) {
            if (!Dims.normalize(c.dim()).equals(Dims.normalize(dim)) || c.hasRole("inbox") || c.snapshot() == null) {
                continue;
            }
            if (c.sortedCategory() != null || c.hasRole("storage") && !c.hasRole("kit") && !c.hasRole("supply")
                    && !c.hasRole("fuel")) {
                out.add(c);
            }
        }
        return out;
    }

    private void sortItems(WorldDoc doc, List<WorkItem> out) {
        for (WorldDoc.Container inbox : doc.containers) {
            if (!inbox.hasRole("inbox") || inbox.snapshot() == null || inbox.snapshot().items().isEmpty()) {
                continue;
            }
            SortPlanner.Plan plan = SortPlanner.plan(inbox, sortTargets(doc, inbox.dim()), ap.categories(), 27);
            String key = Planner.containerKey(inbox);
            if (!plan.unsorted().isEmpty()) {
                notes.put(SORT + ":" + key, "no room for " + plan.unsorted());
                if (ap.once("sort_full/" + key, EVENT_EVERY_MS)) {
                    m.event("sort_full", Levels.WARN, null, "event.autopilot.sortFull", Map.of("x", inbox.pos().x(),
                            "y", inbox.pos().y(), "z", inbox.pos().z(), "items", describe(plan.unsorted())),
                            Json.obj("unsorted", Json.toTree(plan.unsorted())));
                }
            } else {
                ap.forgetThrottle("sort_full/" + key);
            }
            if (plan.isEmpty()) {
                continue;
            }
            out.add(new WorkItem(SORT + ":" + key, id(), SORT, null, P_SORT, null, inbox.dim(), inbox.pos(), key, 1,
                    "sort inbox " + inbox.pos(), Json.obj("container", inbox.id())));
        }
    }

    private void furnaceItems(WorldDoc doc, List<WorkItem> out, long now) {
        ManagerConfig.AutopilotCfg g = m.config.get().autopilot();
        for (WorldDoc.Container f : doc.containers) {
            if (!f.hasRole("furnace") || f.snapshot() == null) {
                continue;
            }
            String key = Planner.containerKey(f);
            if (planner().locks().count(key) > 0) {
                continue;
            }
            ContainerSnapshot.SlotItem input = slot(f, 0);
            ContainerSnapshot.SlotItem output = slot(f, 2);
            Long ready = furnaceReadyAt.get(key);
            if (output != null || ready != null && now >= ready) {
                out.add(new WorkItem(COLLECT + ":" + key, id(), COLLECT, null, P_COLLECT, null, f.dim(), f.pos(), key, 1,
                        "collect furnace " + f.pos(), Json.obj("furnace", f.pos())));
                continue;
            }
            if (input != null || ready != null || g.smeltInputs().isEmpty()) {
                continue;
            }
            JsonObject load = planLoad(doc, f);
            if (load != null) {
                out.add(new WorkItem(REFUEL + ":" + key, id(), REFUEL, null, P_REFUEL, null, f.dim(), f.pos(), key, 1,
                        "load furnace " + f.pos() + " with " + Ids.path(Json.getString(load, "input", "")), load));
            }
        }
    }

    private static ContainerSnapshot.SlotItem slot(WorldDoc.Container c, int slot) {
        for (ContainerSnapshot.SlotItem it : c.snapshot().items()) {
            if (it.slot() == slot && it.count() > 0) {
                return it;
            }
        }
        return null;
    }

    /** Input (most stock, smeltable in this furnace) and fuel from the sources; null when nothing to load. */
    private JsonObject planLoad(WorldDoc doc, WorldDoc.Container f) {
        ManagerConfig.AutopilotCfg g = m.config.get().autopilot();
        GameData data = m.gameData.current() == null ? GameData.empty() : m.gameData.current();
        String type = recipeType(f.block());
        Map<String, Integer> stock = new HashMap<>();
        for (WorldDoc.Container c : doc.containers) {
            if (c == f || !Dims.normalize(c.dim()).equals(Dims.normalize(f.dim())) || !ap.isSource(c, g.useFound())) {
                continue;
            }
            Map<String, Integer> av = planner().available(c, null);
            if (av != null) {
                av.forEach((k, v) -> stock.merge(k, v, Integer::sum));
            }
        }
        // same choice as smelt projects and smelt_all (shared code)
        io.github.krekerdm.baritonebots.manager.projects.Smelter.Load load =
                io.github.krekerdm.baritonebots.manager.projects.Smelter.plan(type, stock, g.smeltInputs(), List.of(), data, 64);
        if (load == null) {
            return null;
        }
        if (load.fuel() == null) {
            notes.put(REFUEL + ":" + Planner.containerKey(f), "no fuel in storage for " + load.count() + " × "
                    + Ids.path(load.input()));
            return null;
        }
        return Json.obj("furnace", f.pos(), "type", type, "input", load.input(), "count", load.count(),
                "fuel", load.fuel(), "fuelCount", load.fuelCount());
    }

    static String recipeType(String block) {
        String b = block == null ? "" : block;
        if (b.endsWith("blast_furnace")) {
            return GameData.BLASTING;
        }
        return b.endsWith("smoker") ? GameData.SMOKING : GameData.SMELTING;
    }

    /**
     * May idle inspection open this container? Within {@code homeRadius} of a home, or with a usable role; a
     * {@code found} one outside home only with {@code useFound}. Crafting tables hold nothing to look at.
     */
    static boolean inspectable(WorldDoc.Container c, boolean inHome, boolean useFound) {
        if (Ids.path(c.block() == null ? "" : c.block()).equals("crafting_table")) {
            return false;
        }
        if (c.sortedCategory() != null || INSPECT_ROLES.stream().anyMatch(c::hasRole)) {
            return true;
        }
        return inHome || useFound && c.hasRole("found");
    }

    /** Is {@code target} within {@code max} blocks of a bot at {@code at} ({@code max <= 0} = no limit)? */
    static boolean inReach(Pos at, String atDim, String dim, Pos target, int max) {
        if (max <= 0) {
            return true;
        }
        return at != null && target != null && Dims.normalize(atDim).equals(Dims.normalize(dim))
                && at.distance(target) <= max;
    }

    private static String dimOf(BotState b) {
        return b.status == null || b.status.dim() == null ? Dims.OVERWORLD : Dims.normalize(b.status.dim());
    }

    /**
     * Unknown or stale containers, unknown first, batched per area, at most {@code maxInspectPerTick} items; only
     * {@link #inspectable} ones within {@code inspectMaxDistance} of a bot that may inspect now.
     */
    private void inspectItems(WorldDoc doc, List<BotState> bots, List<WorkItem> out, long now) {
        ManagerConfig.AutopilotCfg g = m.config.get().autopilot();
        if (g.maxInspectPerTick() <= 0) {
            return;
        }
        long maxAge = g.inspectMaxAgeMin() * 60_000L;
        skipInspect.values().removeIf(t -> t < now);
        List<BotState> seekers = bots.stream().filter(b -> b.online() && ap.cfg(b).discovery() && !ap.held(b, now))
                .toList();
        List<WorldDoc.Container> due = new ArrayList<>();
        for (WorldDoc.Container c : doc.containers) {
            if (skipInspect.containsKey(c.dim() + ":" + c.pos())
                    || !inspectable(c, ap.inHome(serverId, c.dim(), c.pos()), g.useFound())
                    || seekers.stream().noneMatch(b -> inReach(Planner.posOf(b), dimOf(b), c.dim(), c.pos(),
                    g.inspectMaxDistance()))) {
                continue;
            }
            WorldDoc.Snapshot s = c.snapshot();
            if (s == null || maxAge > 0 && now - s.time() > maxAge) {
                due.add(c);
            }
        }
        due.sort(Comparator.comparingLong(c -> c.snapshot() == null ? Long.MIN_VALUE : c.snapshot().time()));
        Set<WorldDoc.Container> used = new HashSet<>();
        int offered = 0;
        for (WorldDoc.Container first : due) {
            if (offered >= g.maxInspectPerTick()) {
                break;
            }
            if (used.contains(first)) {
                continue;
            }
            List<WorldDoc.Container> batch = new ArrayList<>();
            for (WorldDoc.Container c : due) {
                if (!used.contains(c) && batch.size() < INSPECT_BATCH && c.dim().equals(first.dim())
                        && c.pos().distance(first.pos()) <= INSPECT_AREA) {
                    batch.add(c);
                    used.add(c);
                }
            }
            String key = "inspect:" + first.dim() + ":" + first.pos();
            out.add(new WorkItem(key, id(), INSPECT, null, P_INSPECT, null, first.dim(), first.pos(), key, 1,
                    "inspect " + batch.size() + " containers near " + first.pos(),
                    Json.obj("containers", Json.arrOf(batch.stream().map(WorldDoc.Container::pos).toList()),
                            "dim", first.dim())));
            offered++;
        }
    }

    private void homeItems(WorldDoc doc, List<BotState> bots, List<WorkItem> out, long now) {
        for (BotState b : bots) {
            ManagerConfig.AutopilotCfg c = ap.cfg(b);
            Long since = idleSince.get(b.id);
            Pos at = Planner.posOf(b);
            if (!c.idleWork() || since == null || now - since < c.idleHomeSec() * 1000L || at == null) {
                continue;
            }
            WorldDoc.Waypoint home = doc.waypoint(b.def.homeWaypointName());
            String dim = b.status.dim() == null ? Dims.OVERWORLD : Dims.normalize(b.status.dim());
            if (home == null || !Dims.normalize(home.dim()).equals(dim) || home.pos().distance(at) <= AT_HOME) {
                continue;
            }
            out.add(new WorkItem(HOME + ":" + b.id, id(), HOME, null, P_HOME, null, home.dim(), home.pos(), null, 1,
                    "go home", Json.obj("bot", b.id)));
        }
    }

    // ------------------------------------------------------------------ matching

    @Override
    public boolean eligible(BotState b, WorkItem w) {
        ManagerConfig.AutopilotCfg c = ap.cfg(b);
        List<String> roles = b.def.roles();
        if (!REFILL.equals(w.kind()) && ap.held(b, System.currentTimeMillis())) {
            return false; // attention hold after an owner come / follow
        }
        return switch (w.kind()) {
            case REFILL -> b.id.equals(Json.getString(w.data(), "bot", ""));
            case HOME -> c.idleWork() && b.id.equals(Json.getString(w.data(), "bot", ""));
            case SORT -> c.sort() && (roles.isEmpty() || roles.contains("sorter") || roles.contains("hauler"));
            case COLLECT, REFUEL -> c.idleWork() && (roles.isEmpty() || roles.contains("smelter") || roles.contains("hauler"));
            case INSPECT -> c.discovery()
                    && inReach(Planner.posOf(b), dimOf(b), w.dim(), w.location(), c.inspectMaxDistance());
            default -> false;
        };
    }

    // ------------------------------------------------------------------ running

    @Override
    public void begin(Assignment a, BotState b) {
        JsonObject d = a.item.data();
        switch (a.item.kind()) {
            case REFILL -> {
                a.phase("refill");
                planner().push(a, List.of(Planner.entry(a, Dispatcher.STEP_SUPPLY,
                        Json.obj("type", REFILL, "args", new JsonObject()), 0, "refill")));
            }
            case SORT -> {
                WorldDoc.Container inbox = byId(Json.getString(d, "container", ""));
                List<QueueEntry> moves = inbox == null ? List.of() : moves(a, b, inbox);
                if (moves.isEmpty()) {
                    planner().finish(a);
                    return;
                }
                a.phase("sort");
                planner().push(a, moves);
            }
            case COLLECT -> {
                furnaceReadyAt.remove(a.item.lock());
                a.phase("collect");
                planner().push(a, List.of(
                        Planner.entry(a, TaskTypes.SMELT_COLLECT, Json.obj("furnace", d.get("furnace").deepCopy(), "all", false), 300, null),
                        Planner.entry(a, Dispatcher.STEP_DEPOSIT_STORAGE, new JsonObject(), 0, null)));
            }
            case REFUEL -> refuelTake(a, b);
            case INSPECT -> {
                a.phase("inspect");
                planner().push(a, List.of(Planner.entry(a, TaskTypes.INSPECT,
                        Json.obj("containers", d.get("containers").deepCopy()), Restock.TAKE_TIMEOUT_SEC, null)));
            }
            case HOME -> {
                a.phase("home");
                planner().push(a, List.of(Planner.entry(a, Dispatcher.STEP_HOME, new JsonObject(), 0, null)));
            }
            default -> planner().fail(a, Reasons.UNSUPPORTED, a.item.kind());
        }
    }

    private WorldDoc.Container byId(String id) {
        return doc().containers.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    /** {@code transfer} entries emptying one inbox, sized to the bot's free slots. */
    List<QueueEntry> moves(Assignment a, BotState b, WorldDoc.Container inbox) {
        int slots = Math.max(1, (b.status == null ? 27 : b.status.freeSlots()) - 1);
        SortPlanner.Plan plan = SortPlanner.plan(inbox, sortTargets(doc(), inbox.dim()), ap.categories(), slots);
        List<QueueEntry> out = new ArrayList<>();
        for (SortPlanner.Move mv : plan.moves()) {
            out.add(transfer(mv, a == null ? null : a, null));
        }
        return out;
    }

    private QueueEntry transfer(SortPlanner.Move mv, Assignment a, QueueEntry step) {
        JsonArray items = new JsonArray();
        mv.items().forEach((id, n) -> items.add(Json.obj("item", id, "count", n)));
        JsonObject args = Json.obj("from", mv.from().pos(), "to", Json.arrOf(mv.to().stream().map(WorldDoc.Container::pos).toList()),
                "items", items);
        String label = "sort → " + mv.category();
        return a != null ? Planner.entry(a, TaskTypes.TRANSFER, args, 300, label)
                : m.dispatcher.childEntry(step, TaskTypes.TRANSFER, args, 300);
    }

    /** {@code sort_storage}: transfers for every inbox of the bot's dimension (one round each). */
    List<QueueEntry> sortEntries(BotState b, QueueEntry step) {
        WorldDoc doc = doc();
        String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        int slots = Math.max(1, (b.status == null ? 27 : b.status.freeSlots()) - 1);
        List<QueueEntry> out = new ArrayList<>();
        for (WorldDoc.Container inbox : doc.containers) {
            if (inbox.hasRole("inbox") && inbox.snapshot() != null && Dims.normalize(inbox.dim()).equals(dim)) {
                for (SortPlanner.Move mv : SortPlanner.plan(inbox, sortTargets(doc, dim), ap.categories(), slots).moves()) {
                    out.add(transfer(mv, null, step));
                }
            }
        }
        return out;
    }

    private static final class RefuelCtx {
        String step = "take";
    }

    private void refuelTake(Assignment a, BotState b) {
        JsonObject d = a.item.data();
        String input = Json.getString(d, "input", "");
        String fuel = Json.getString(d, "fuel", "minecraft:coal");
        int count = Json.getInt(d, "count", 1);
        int fuelCount = Json.getInt(d, "fuelCount", 1);
        Map<String, Integer> have = b.status == null ? Map.of() : b.status.items();
        List<Restock.Need> needs = new ArrayList<>();
        if (have.getOrDefault(input, 0) < count) {
            needs.add(new Restock.Need(input, count - have.getOrDefault(input, 0)));
        }
        if (have.getOrDefault(fuel, 0) < fuelCount) {
            needs.add(new Restock.Need(fuel, fuelCount - have.getOrDefault(fuel, 0)));
        }
        a.ctx = new RefuelCtx();
        if (needs.isEmpty()) {
            refuelLoad(a);
            return;
        }
        boolean found = ap.cfg(b).useFound();
        List<WorldDoc.Container> sources = doc().containers.stream()
                .filter(c -> ap.isSource(c, found) && Dims.normalize(c.dim()).equals(Dims.normalize(a.item.dim())))
                .sorted(Comparator.comparingInt((WorldDoc.Container c) -> c.hasRole("fuel") ? 0 : 1)
                        .thenComparingDouble(c -> c.pos().distance(a.item.location())))
                .toList();
        Restock.Plan plan = Restock.plan(needs, sources, c -> planner().available(c, a),
                c -> planner().locks().heldByOther(Planner.containerKey(c), a.botId),
                Math.max(1, (b.status == null ? 27 : b.status.freeSlots()) - 1));
        if (!plan.missing().isEmpty()) {
            planner().fail(a, Reasons.MISSING_MATERIALS, "not in storage any more: " + plan.missing());
            return;
        }
        List<QueueEntry> takes = Restock.entries(planner(), a, plan, Planner.posOf(b));
        if (takes.isEmpty()) {
            planner().fail(a, Reasons.CONTAINER_FAILED, "containers in use");
            return;
        }
        a.phase("take inputs");
        planner().push(a, takes);
    }

    private void refuelLoad(Assignment a) {
        JsonObject d = a.item.data();
        ((RefuelCtx) a.ctx).step = "load";
        a.phase("load furnace");
        planner().push(a, List.of(Planner.entry(a, TaskTypes.SMELT_LOAD, Json.obj("furnace", d.get("furnace").deepCopy(),
                "input", Json.getString(d, "input", ""), "count", Json.getInt(d, "count", 1),
                "fuel", Json.getString(d, "fuel", "minecraft:coal"), "fuelCount", Json.getInt(d, "fuelCount", 1)),
                300, a.item.label())));
    }

    @Override
    public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
        planner().releaseContainers(a);
        Assignment.Result bad = results.stream().filter(r -> !r.ok()).findFirst().orElse(null);
        switch (a.item.kind()) {
            case SORT -> {
                boolean any = results.stream().anyMatch(Assignment.Result::ok);
                if (!any && bad != null) {
                    planner().fail(a, bad.reason(), bad.message());
                } else {
                    planner().finish(a);
                }
            }
            case REFUEL -> {
                RefuelCtx c = (RefuelCtx) a.ctx;
                if ("take".equals(c.step)) {
                    if (Restock.taken(results).isEmpty()) {
                        planner().fail(a, bad == null ? Reasons.MISSING_MATERIALS : bad.reason(), "nothing taken");
                    } else {
                        refuelLoad(a);
                    }
                    return;
                }
                Assignment.Result load = results.stream().filter(r -> TaskTypes.SMELT_LOAD.equals(r.type())).findFirst().orElse(null);
                if (load == null || !load.ok()) {
                    planner().fail(a, load == null ? Reasons.CANCELLED : load.reason(), load == null ? null : load.message());
                    return;
                }
                int loaded = Math.max(1, Json.getInt(load.data(), "loaded", Json.getInt(a.item.data(), "count", 1)));
                boolean fast = !GameData.SMELTING.equals(Json.getString(a.item.data(), "type", GameData.SMELTING));
                furnaceReadyAt.put(a.item.lock(), System.currentTimeMillis() + loaded * (fast ? 5_000L : 10_000L) + 5_000);
                planner().finish(a);
            }
            case INSPECT -> {
                for (Assignment.Result r : results) {
                    JsonArray failed = r.data() == null ? null : Json.getArr(r.data(), "failed");
                    for (JsonElement e : failed == null ? new JsonArray() : failed) {
                        Pos p = Pos.fromJson(e);
                        if (p != null) {
                            skipInspect.put(Json.getString(a.item.data(), "dim", Dims.OVERWORLD) + ":" + p,
                                    System.currentTimeMillis() + SKIP_INSPECT_MS);
                        }
                    }
                }
                if (bad != null && results.stream().noneMatch(Assignment.Result::ok)) {
                    planner().fail(a, bad.reason(), bad.message());
                } else {
                    planner().finish(a);
                }
            }
            case HOME -> {
                if (bad != null) {
                    planner().fail(a, bad.reason(), bad.message());
                } else {
                    ap.scanNow(b); // look around home for containers
                    idleSince.remove(b.id);
                    planner().finish(a);
                }
            }
            default -> {
                if (bad != null && results.stream().noneMatch(Assignment.Result::ok)) {
                    planner().fail(a, bad.reason(), bad.message());
                } else {
                    planner().finish(a);
                }
            }
        }
    }

    @Override
    public void onReleased(Assignment a, String why) {
        if (!"done".equals(why) && REFUEL.equals(a.item.kind())) {
            furnaceReadyAt.remove(a.item.lock());
        }
    }

    private static String describe(Map<String, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.forEach((k, v) -> parts.add(v + " × " + Ids.path(k)));
        return String.join(", ", parts);
    }

    JsonObject view() {
        JsonObject f = new JsonObject();
        furnaceReadyAt.forEach((k, v) -> f.addProperty(k, v));
        return Json.obj("serverId", serverId, "offered", lastOffered, "notes", Json.toTree(notes),
                "furnaceReadyAt", f, "skipInspect", Json.arrOf(skipInspect.keySet()),
                "assignments", planner().assignmentsView(id()));
    }
}
