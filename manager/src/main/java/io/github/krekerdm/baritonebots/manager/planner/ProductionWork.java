package io.github.krekerdm.baritonebots.manager.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Production work items that cover deficits (SPEC §5.7 steps 4–5), shared by every work source: {@code haul}
 * (storage → supply), {@code mine} (blocks whose loot drops the item; legit mining on anti-xray servers),
 * {@code craft} (recipe grid from game data, table from containers with role crafting or any indexed crafting table),
 * {@code smelt} (role furnace containers, fuel from role fuel or storage). Results are deposited into the host's
 * supply containers (storage when it has none). Loop-owned.
 */
public final class ProductionWork {
    public static final String HAUL = "haul";
    public static final String MINE = "mine";
    public static final String CRAFT = "craft";
    public static final String SMELT = "smelt";
    public static final Set<String> KINDS = Set.of(HAUL, MINE, CRAFT, SMELT);
    static final int TASK_TIMEOUT_SEC = 1800;
    static final int MAX_SMELT = 64;
    private static final List<String> TIER_PREFIX = List.of("wooden_", "stone_", "iron_", "diamond_", "netherite_");

    /** Where a source keeps its materials. */
    public interface Host {
        String serverId();

        String dim();

        /** Delivery targets (may be empty). */
        List<WorldDoc.Container> supply();

        /** Containers the work may take from, preferred first (supply, storage, fuel, ...). */
        List<WorldDoc.Container> sources();

        /** Server profile anti-xray mode: none | hide | fake. */
        String antiXray();
    }

    private final Manager m;
    private final Planner planner;

    public ProductionWork(Manager m, Planner planner) {
        this.m = m;
        this.planner = planner;
    }

    /** Per-assignment state. */
    static final class Ctx {
        String step = "start";
        boolean inspected;
        int depositTries;
    }

    public static boolean handles(WorkItem w) {
        return KINDS.contains(w.kind());
    }

    // ------------------------------------------------------------------ items

    /**
     * Work items for the plan's ready actions.
     *
     * @param notes receives "why not" texts for actions that cannot be offered (no tool, no furnace free, ...)
     */
    public List<WorkItem> items(String sourceId, Host host, Resolver.Plan plan, double priority, GameData data,
                                Map<String, String> notes) {
        List<WorkItem> out = new ArrayList<>();
        for (Resolver.Action act : plan.actions()) {
            if (!act.ready()) {
                continue;
            }
            String id = act.kind() + ":" + act.item();
            switch (act.kind()) {
                case Resolver.HAUL -> {
                    Pos at = null;
                    for (WorldDoc.Container c : host.sources()) {
                        if (!c.hasRole("supply") && c.snapshot() != null
                                && c.snapshot().totals().getOrDefault(act.item(), 0) > 0) {
                            at = c.pos();
                            break;
                        }
                    }
                    out.add(new WorkItem(id, sourceId, HAUL, "hauler", priority,
                            new WorkItem.Needs(Map.of(act.item(), act.count()), List.of()), host.dim(), at, null, 1,
                            "haul " + act.count() + " × " + Ids.path(act.item()),
                            Json.obj("item", act.item(), "count", act.count())));
                }
                case Resolver.MINE -> {
                    GameData.ToolReq tool = data == null ? GameData.ToolReq.NONE : data.toolFor(act.blocks().getFirst());
                    List<String> tools = tool.required() ? List.of(tool.kind() + ":" + tool.minTier()) : List.of();
                    if (tool.required() && !toolAnywhere(host, tool)) {
                        notes.put(id, "needs a " + tool.minTier() + " " + tool.kind() + " or better for "
                                + Ids.path(act.blocks().getFirst()));
                        continue;
                    }
                    boolean logs = data != null && act.blocks().stream()
                            .anyMatch(b -> data.blockTag("minecraft:logs").contains(b));
                    out.add(new WorkItem(id, sourceId, MINE, logs ? "lumberjack" : "miner", priority,
                            new WorkItem.Needs(Map.of(), tools), host.dim(), null, null, 1,
                            "mine " + act.count() + " × " + Ids.path(act.item()),
                            Json.obj("item", act.item(), "count", act.count(), "blocks", Json.arrOf(act.blocks()),
                                    "tool", tool.toJson())));
                }
                case Resolver.CRAFT -> {
                    Pos table = null;
                    if (GameData.needsTable(act.recipe())) {
                        table = craftingTable(host);
                        if (table == null) {
                            notes.put(id, "needs a crafting table (container role 'crafting')");
                            continue;
                        }
                    }
                    JsonObject d = Json.obj("item", act.item(), "count", act.count(), "crafts", act.crafts(),
                            "recipe", act.recipe().id(), "grid", GameData.craftingGridJson(act.recipe()),
                            "inputs", Json.toTree(act.inputs()));
                    if (table != null) {
                        d.add("table", Json.toTree(table));
                    }
                    out.add(new WorkItem(id, sourceId, CRAFT, "crafter", priority,
                            new WorkItem.Needs(act.inputs(), List.of()), host.dim(), table, null, 1,
                            "craft " + act.count() + " × " + Ids.path(act.item()), d));
                }
                case Resolver.SMELT -> {
                    WorldDoc.Container furnace = freeFurnace(host, act.recipe().type());
                    if (furnace == null) {
                        notes.put(id, "every furnace for " + Ids.path(act.recipe().type()) + " is busy or missing");
                        continue;
                    }
                    int count = Math.min(MAX_SMELT, act.count());
                    // the resolver puts the chosen input first (it may also be the fuel, e.g. logs → charcoal)
                    String input = act.inputs().keySet().iterator().next();
                    int fuelCount = (int) Math.ceil(count / ItemStacks.burnItems(act.fuel()));
                    out.add(new WorkItem(id, sourceId, SMELT, "smelter", priority,
                            new WorkItem.Needs(Map.of(input, count, act.fuel(), fuelCount), List.of()), host.dim(),
                            furnace.pos(), Planner.containerKey(furnace), 1,
                            "smelt " + count + " × " + Ids.path(act.item()),
                            Json.obj("item", act.item(), "count", count, "input", input, "fuel", act.fuel(),
                                    "fuelCount", fuelCount, "furnace", furnace.pos(), "furnaceBlock", furnace.block(),
                                    "type", act.recipe().type())));
                }
                default -> {
                }
            }
        }
        return out;
    }

    private Pos craftingTable(Host host) {
        WorldDoc doc = planner.world(host.serverId());
        if (doc == null) {
            return null;
        }
        for (WorldDoc.Container c : planner.containers(host.serverId(), host.dim(), "crafting")) {
            return c.pos();
        }
        return doc.containers.stream().filter(c -> "minecraft:crafting_table".equals(c.block())
                && io.github.krekerdm.baritonebots.common.geom.Dims.normalize(c.dim())
                .equals(io.github.krekerdm.baritonebots.common.geom.Dims.normalize(host.dim())))
                .map(WorldDoc.Container::pos).findFirst().orElse(null);
    }

    /** Recipe types the furnace containers of a host can run. */
    public Set<String> furnaceTypes(Host host) {
        Set<String> out = new java.util.HashSet<>();
        for (WorldDoc.Container c : planner.containers(host.serverId(), host.dim(), "furnace")) {
            out.add(recipeTypeOf(c.block()));
        }
        return out;
    }

    public boolean hasCraftingTable(Host host) {
        return craftingTable(host) != null;
    }

    static String recipeTypeOf(String block) {
        String b = block == null ? "" : block;
        if (b.endsWith("blast_furnace")) {
            return GameData.BLASTING;
        }
        if (b.endsWith("smoker")) {
            return GameData.SMOKING;
        }
        return GameData.SMELTING;
    }

    private WorldDoc.Container freeFurnace(Host host, String type) {
        for (WorldDoc.Container c : planner.containers(host.serverId(), host.dim(), "furnace")) {
            if (recipeTypeOf(c.block()).equals(type) && planner.locks().count(Planner.containerKey(c)) == 0) {
                return c;
            }
        }
        return null;
    }

    private boolean toolAnywhere(Host host, GameData.ToolReq tool) {
        for (BotState b : m.bots.all()) {
            if (b.status != null && host.serverId().equalsIgnoreCase(String.valueOf(b.def.serverId()))
                    && hasTool(b.status.items(), tool.kind(), tool.minTier())) {
                return true;
            }
        }
        return toolContainer(host, tool, null) != null;
    }

    private WorldDoc.Container toolContainer(Host host, GameData.ToolReq tool, Assignment a) {
        for (WorldDoc.Container c : host.sources()) {
            Map<String, Integer> av = planner.available(c, a);
            if (av != null && av.keySet().stream().anyMatch(id -> toolTier(id, tool.kind()) >= tierRank(tool.minTier()))) {
                return c;
            }
        }
        return null;
    }

    /** 0 wood/gold, 1 stone, 2 iron, 3 diamond, 4 netherite; -1 not a tool of that kind. */
    public static int toolTier(String itemId, String kind) {
        String path = Ids.path(itemId);
        if (!path.endsWith("_" + kind)) {
            return -1;
        }
        if (path.startsWith("golden_")) {
            return 0;
        }
        for (int i = 0; i < TIER_PREFIX.size(); i++) {
            if (path.startsWith(TIER_PREFIX.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int tierRank(String tier) {
        int i = GameData.TOOL_TIERS.indexOf(tier == null ? "wood" : tier);
        return Math.max(0, i);
    }

    /** The plain tool item of a tier: {@code (pickaxe, stone)} → {@code minecraft:stone_pickaxe}. */
    public static String toolItem(String kind, String tier) {
        String prefix = switch (tier == null ? "wood" : tier) {
            case "stone" -> "stone_";
            case "iron" -> "iron_";
            case "diamond" -> "diamond_";
            case "netherite" -> "netherite_";
            default -> "wooden_";
        };
        return "minecraft:" + prefix + kind;
    }

    public static boolean hasTool(Map<String, Integer> items, String kind, String minTier) {
        if (items == null || "none".equals(kind)) {
            return true;
        }
        int need = tierRank(minTier);
        return items.keySet().stream().anyMatch(id -> toolTier(id, kind) >= need);
    }

    /** A bot can take a mine item when it has the tool or one is in the host's containers. */
    public boolean eligible(BotState b, WorkItem w, Host host) {
        if (!MINE.equals(w.kind())) {
            return true;
        }
        GameData.ToolReq tool = toolOf(w);
        return !tool.required() || (b.status != null && hasTool(b.status.items(), tool.kind(), tool.minTier()))
                || toolContainer(host, tool, null) != null;
    }

    private static GameData.ToolReq toolOf(WorkItem w) {
        JsonObject t = Json.getObj(w.data(), "tool");
        if (t == null) {
            return GameData.ToolReq.NONE;
        }
        return new GameData.ToolReq(Json.getString(t, "kind", "none"), Json.getString(t, "minTier", null),
                Json.getBool(t, "required", false));
    }

    // ------------------------------------------------------------------ running

    private int slotBudget(BotState b) {
        ManagerConfig.PlannerCfg p = m.config.get().planner();
        int free = b.status == null ? 0 : b.status.freeSlots();
        return Math.max(1, free - p.restockFreeSlotsTarget());
    }

    public void begin(Assignment a, BotState b, Host host) {
        if (!(a.ctx instanceof Ctx)) {
            a.ctx = new Ctx(); // kept across an inspect round
        }
        switch (a.item.kind()) {
            case HAUL -> haulTake(a, b, host);
            case MINE -> mineStart(a, b, host);
            case CRAFT, SMELT -> takeInputs(a, b, host);
            default -> planner.fail(a, Reasons.UNSUPPORTED, a.item.kind());
        }
    }

    public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results, Host host) {
        Ctx c = (Ctx) a.ctx;
        JsonObject d = a.item.data();
        planner.releaseContainers(a);
        switch (c.step) {
            case "inspect" -> begin(a, b, host);
            case "haul_take" -> {
                Map<String, Integer> taken = Restock.taken(results);
                if (taken.getOrDefault(Json.getString(d, "item", ""), 0) <= 0) {
                    planner.fail(a, Reasons.NOT_FOUND, "nothing taken from storage");
                } else {
                    deposit(a, b, host);
                }
            }
            case "tool" -> mineRun(a, b, host);
            case "mine" -> {
                Assignment.Result r = last(results, TaskTypes.MINE);
                if (r == null) {
                    planner.fail(a, Reasons.CANCELLED, "mine did not run");
                } else if (r.ok() || Reasons.TIMEOUT.equals(r.reason())) {
                    deposit(a, b, host);
                } else {
                    planner.fail(a, r.reason(), r.message());
                }
            }
            case "inputs" -> {
                Map<String, Integer> taken = Restock.taken(results);
                Map<String, Integer> need = inputsOf(d, a.item.kind());
                for (Map.Entry<String, Integer> e : need.entrySet()) {
                    int have = taken.getOrDefault(e.getKey(), 0)
                            + (b.status == null ? 0 : b.status.items().getOrDefault(e.getKey(), 0));
                    if (have < e.getValue()) {
                        planner.fail(a, Reasons.MISSING_MATERIALS, Ids.path(e.getKey()) + " " + have + "/" + e.getValue());
                        return;
                    }
                }
                if (CRAFT.equals(a.item.kind())) {
                    craftRun(a);
                } else {
                    smeltRun(a);
                }
            }
            case "craft" -> {
                Assignment.Result r = last(results, TaskTypes.CRAFT);
                if (r != null && r.ok()) {
                    deposit(a, b, host);
                } else {
                    planner.fail(a, r == null ? Reasons.CANCELLED : r.reason(), r == null ? null : r.message());
                }
            }
            case "smelt" -> {
                Assignment.Result load = last(results, TaskTypes.SMELT_LOAD);
                if (load == null || !load.ok() || Json.getInt(load.data(), "loaded", 1) <= 0) {
                    planner.fail(a, load == null ? Reasons.CANCELLED : load.ok() ? Reasons.MISSING_MATERIALS : load.reason(),
                            load == null ? null : load.message());
                } else {
                    deposit(a, b, host);
                }
            }
            case "deposit" -> planner.finish(a);
            default -> planner.fail(a, Reasons.ERROR, "unexpected step " + c.step);
        }
    }

    private static Assignment.Result last(List<Assignment.Result> results, String type) {
        Assignment.Result out = null;
        for (Assignment.Result r : results) {
            if (type.equals(r.type())) {
                out = r;
            }
        }
        return out;
    }

    private boolean inspectFirst(Assignment a, BotState b, Restock.Plan plan) {
        Ctx c = (Ctx) a.ctx;
        if (plan.isEmpty() && !plan.inspect().isEmpty() && !c.inspected) {
            c.inspected = true;
            c.step = "inspect";
            a.phase("inspect");
            planner.push(a, List.of(Restock.inspectEntry(a, plan.inspect())));
            return true;
        }
        return false;
    }

    private Restock.Plan plan(Assignment a, BotState b, Host host, List<Restock.Need> needs, int slots) {
        return Restock.plan(needs, host.sources(), c -> planner.available(c, a),
                c -> planner.locks().heldByOther(Planner.containerKey(c), a.botId), slots);
    }

    // haul ------------------------------------------------------------

    private void haulTake(Assignment a, BotState b, Host host) {
        JsonObject d = a.item.data();
        String item = Json.getString(d, "item", "");
        List<WorldDoc.Container> storage = host.sources().stream().filter(c -> !c.hasRole("supply")).toList();
        Restock.Plan plan = Restock.plan(List.of(new Restock.Need(item, Json.getInt(d, "count", 0))), storage,
                c -> planner.available(c, a), c -> planner.locks().heldByOther(Planner.containerKey(c), a.botId),
                slotBudget(b));
        if (inspectFirst(a, b, plan)) {
            return;
        }
        List<QueueEntry> takes = Restock.entries(planner, a, plan, Planner.posOf(b));
        if (takes.isEmpty()) {
            planner.fail(a, Reasons.NOT_FOUND, "no free storage container holds " + Ids.path(item));
            return;
        }
        a.promised.put(item, plan.total());
        ((Ctx) a.ctx).step = "haul_take";
        a.phase("take");
        planner.push(a, takes);
    }

    // mine ------------------------------------------------------------

    private void mineStart(Assignment a, BotState b, Host host) {
        GameData.ToolReq tool = toolOf(a.item);
        if (tool.required() && (b.status == null || !hasTool(b.status.items(), tool.kind(), tool.minTier()))) {
            WorldDoc.Container c = toolContainer(host, tool, a);
            if (c == null || !planner.holdLock(a, Planner.containerKey(c), 1)) {
                planner.fail(a, Reasons.MISSING_MATERIALS, "no " + tool.minTier() + " " + tool.kind() + " available");
                return;
            }
            String id = planner.available(c, a).keySet().stream()
                    .filter(x -> toolTier(x, tool.kind()) >= tierRank(tool.minTier()))
                    .max(java.util.Comparator.comparingInt(x -> toolTier(x, tool.kind()))).orElseThrow();
            Restock.Take t = new Restock.Take(c, Map.of(id, 1));
            a.reserved.put(Planner.containerKey(c), Map.of(id, 1));
            ((Ctx) a.ctx).step = "tool";
            a.phase("fetch tool");
            planner.push(a, List.of(Planner.entry(a, TaskTypes.TAKE, Restock.takeArgs(t), Restock.TAKE_TIMEOUT_SEC, null)));
            return;
        }
        mineRun(a, b, host);
    }

    /**
     * {@code mine} task arguments; ores on an anti-xray server (SPEC §5.7b3) switch to legit mining, with fake-ore
     * detection on {@code fake} servers.
     */
    public static JsonObject mineArgs(JsonArray blocks, int amount, String antiXray) {
        JsonArray b = blocks == null ? new JsonArray() : blocks.deepCopy();
        JsonObject args = Json.obj("blocks", b, "amount", Math.max(1, amount));
        boolean ores = false;
        for (JsonElement e : b) {
            String id = e.getAsString();
            ores |= id.endsWith("_ore") || id.endsWith("ancient_debris");
        }
        if (ores && antiXray != null && !ManagerConfig.ServerProfile.ANTI_XRAY_NONE.equals(antiXray)) {
            args.addProperty("strategy", "legit");
            if (ManagerConfig.ServerProfile.ANTI_XRAY_FAKE.equals(antiXray)) {
                args.addProperty("fakeOres", true);
            }
        }
        return args;
    }

    private void mineRun(Assignment a, BotState b, Host host) {
        JsonObject d = a.item.data();
        String item = Json.getString(d, "item", "");
        int amount = Math.min(Json.getInt(d, "count", 1), slotBudget(b) * ItemStacks.maxStack(item));
        JsonObject args = mineArgs(Json.getArr(d, "blocks"), amount, host.antiXray());
        a.promised.put(item, amount);
        ((Ctx) a.ctx).step = "mine";
        a.phase("mine");
        planner.push(a, List.of(Planner.entry(a, TaskTypes.MINE, args, TASK_TIMEOUT_SEC, a.item.label())));
    }

    // craft / smelt ---------------------------------------------------

    private static Map<String, Integer> inputsOf(JsonObject d, String kind) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (CRAFT.equals(kind)) {
            JsonObject in = Json.getObj(d, "inputs");
            if (in != null) {
                in.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsInt()));
            }
        } else {
            out.put(Json.getString(d, "input", ""), Json.getInt(d, "count", 0));
            out.merge(Json.getString(d, "fuel", "minecraft:coal"), Json.getInt(d, "fuelCount", 1), Integer::sum);
        }
        return out;
    }

    private void takeInputs(Assignment a, BotState b, Host host) {
        JsonObject d = a.item.data();
        Map<String, Integer> need = inputsOf(d, a.item.kind());
        List<Restock.Need> needs = new ArrayList<>();
        Map<String, Integer> have = b.status == null ? Map.of() : b.status.items();
        need.forEach((id, n) -> {
            int missing = n - have.getOrDefault(id, 0);
            if (missing > 0) {
                needs.add(new Restock.Need(id, missing));
            }
        });
        a.promised.put(Json.getString(d, "item", ""), Json.getInt(d, "count", 0));
        if (needs.isEmpty()) {
            if (CRAFT.equals(a.item.kind())) {
                craftRun(a);
            } else {
                smeltRun(a);
            }
            return;
        }
        Restock.Plan plan = plan(a, b, host, needs, Math.max(slotBudget(b), 1));
        if (inspectFirst(a, b, plan)) {
            return;
        }
        if (!plan.missing().isEmpty()) {
            planner.fail(a, Reasons.MISSING_MATERIALS, "inputs not in stock: " + plan.missing());
            return;
        }
        List<QueueEntry> takes = Restock.entries(planner, a, plan, Planner.posOf(b));
        if (takes.size() < plan.takes().size()) {
            planner.fail(a, Reasons.CONTAINER_FAILED, "a container is in use");
            return;
        }
        ((Ctx) a.ctx).step = "inputs";
        a.phase("take inputs");
        planner.push(a, takes);
    }

    private void craftRun(Assignment a) {
        JsonObject d = a.item.data();
        JsonObject args = Json.obj("item", Json.getString(d, "item", ""), "count", Json.getInt(d, "count", 1));
        if (d.has("table")) {
            args.add("table", d.get("table").deepCopy());
        }
        if (d.has("grid")) {
            args.add("grid", d.get("grid").deepCopy());
        }
        ((Ctx) a.ctx).step = "craft";
        a.phase("craft");
        planner.push(a, List.of(Planner.entry(a, TaskTypes.CRAFT, args, 600, a.item.label())));
    }

    private void smeltRun(Assignment a) {
        JsonObject d = a.item.data();
        int count = Json.getInt(d, "count", 1);
        boolean fast = !GameData.SMELTING.equals(Json.getString(d, "type", GameData.SMELTING));
        int sec = count * (fast ? 5 : 10) + 5;
        JsonElement furnace = d.get("furnace").deepCopy();
        List<QueueEntry> batch = List.of(
                Planner.entry(a, TaskTypes.SMELT_LOAD, Json.obj("furnace", furnace, "input", Json.getString(d, "input", ""),
                        "count", count, "fuel", Json.getString(d, "fuel", "minecraft:coal"),
                        "fuelCount", Json.getInt(d, "fuelCount", 1)), 300, a.item.label()),
                Planner.entry(a, "wait", Json.obj("sec", sec), 0, a.item.label()),
                Planner.entry(a, TaskTypes.SMELT_COLLECT, Json.obj("furnace", furnace.deepCopy(), "all", true), 300,
                        a.item.label()));
        ((Ctx) a.ctx).step = "smelt";
        a.phase("smelt");
        planner.push(a, batch);
    }

    // deposit ---------------------------------------------------------

    private void deposit(Assignment a, BotState b, Host host) {
        Ctx c = (Ctx) a.ctx;
        String item = Json.getString(a.item.data(), "item", "");
        List<WorldDoc.Container> targets = host.supply().isEmpty()
                ? host.sources().stream().filter(x -> x.hasRole("storage")).toList() : host.supply();
        if (targets.isEmpty()) {
            planner.fail(a, Reasons.NOT_FOUND, "no supply or storage container to deliver to");
            return;
        }
        List<Pos> free = new ArrayList<>();
        for (WorldDoc.Container t : targets) {
            if (planner.holdLock(a, Planner.containerKey(t), 1)) {
                free.add(t.pos());
            }
        }
        if (free.isEmpty() && c.depositTries++ < 24) {
            a.phase("waiting for a free container");
            planner.later(a, 5_000, () -> deposit(a, b, host));
            return;
        }
        if (free.isEmpty()) {
            targets.forEach(t -> free.add(t.pos()));
        }
        c.step = "deposit";
        a.phase("deposit");
        planner.push(a, List.of(Planner.entry(a, TaskTypes.DEPOSIT,
                Json.obj("containers", Json.arrOf(free), "only", Json.arr(item)), Restock.TAKE_TIMEOUT_SEC, null)));
    }
}
