package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.KitPlanner;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Auto-supply (SPEC §5.7a). The dispatcher asks {@link #wants} before it sends a bot task; when yes it queues a
 * {@code supply} manager step in front, which {@link #run}s on the manager: live inventory (query, status as
 * fallback) → {@link TaskNeeds} → {@link SupplyPlanner} over the indexed containers → {@code take}s queued before the
 * task (origin {@link Dispatcher#ORIGIN_SUPPLY}: a failed take never stops the task, its scenario or its project).
 * Unknown containers are inspected first (once); what nobody has becomes one throttled {@code manual} event and the
 * task runs anyway. {@code tool_low} / {@code food_low} events mark a refill for the next task (or the autopilot's
 * idle {@code refill} item). Loop-owned.
 */
public final class Supply {
    /** Same missing list for the same bot and task type: one event per this long. */
    static final long MANUAL_EVENT_EVERY_MS = 10 * 60_000;
    private static final int SUPPLIED_KEPT = 2_000;

    private final Manager m;
    private final Autopilot ap;
    private final Set<String> supplied = new LinkedHashSet<>();
    /** botId → refill wishes: {@code food}, {@code tool:<kind>}. */
    private final Map<String, Set<String>> refill = new HashMap<>();
    private final Map<String, Long> manualAt = new HashMap<>();

    Supply(Manager m, Autopilot ap) {
        this.m = m;
        this.ap = ap;
    }

    /** Should a {@code supply} step go in front of this entry? Marks the entry so it is asked only once. */
    public boolean wants(BotState b, QueueEntry e) {
        ManagerConfig.AutopilotCfg cfg = ap.cfg(b);
        if (!cfg.supply() || e.origin() != null && (e.origin().startsWith(Planner.ORIGIN_AUTOPILOT_PREFIX)
                || QueueEntry.ORIGIN_RECOVERY.equals(e.origin()) || Dispatcher.ORIGIN_SUPPLY.equals(e.origin()))) {
            return false;
        }
        boolean pending = refill.containsKey(b.id) && !TaskTypes.TAKE.equals(e.type()) && !TaskTypes.DEPOSIT.equals(e.type());
        if (!TaskNeeds.TYPES.contains(e.type()) && !pending) {
            return false;
        }
        if (!pending && Planner.isPlannerOrigin(e.origin())
                && (TaskTypes.CRAFT.equals(e.type()) || TaskTypes.SMELT_LOAD.equals(e.type()))) {
            return false; // planner work took its inputs already; only materials could be missing here
        }
        if (!supplied.add(e.id())) {
            return false;
        }
        while (supplied.size() > SUPPLIED_KEPT) {
            supplied.remove(supplied.iterator().next());
        }
        return true;
    }

    public boolean refillPending(String botId) {
        return refill.containsKey(botId);
    }

    /** {@code tool_low} (data.item) / {@code food_low}: fetch at the next safe point. */
    public void onLow(BotState b, String what) {
        if (ap.cfg(b).supply()) {
            refill.computeIfAbsent(b.id, k -> new LinkedHashSet<>()).add(what);
        }
    }

    void forget(String botId) {
        refill.remove(botId);
    }

    /** The {@code supply} step: {@code {taskId, type, args, inspected?, refill?}}. */
    public void run(BotState b, QueueEntry step) {
        m.dispatcher.startStep(b, step);
        ap.inventory(b, inv -> {
            if (!m.dispatcher.isRunning(b, step)) {
                return;
            }
            String type = Json.getString(step.args(), "type", "");
            JsonObject args = Json.getObj(step.args(), "args");
            boolean materials = !Planner.isPlannerOrigin(step.origin());
            if (TaskTypes.BUILD.equals(type) && materials && args != null && args.has("file")) {
                JsonObject q = Json.obj("file", args.get("file"), "origin", args.get("origin"),
                        "rotation", Json.getInt(args, "rotation", 0), "mirror", Json.getString(args, "mirror", "none"));
                if (args.has("box")) {
                    q.add("box", args.get("box").deepCopy());
                }
                // what is still to place in the (masked) region, not the whole bill of materials
                m.planner.queryVia(b, QueryKinds.PROGRESS, q, 60_000, (r, t) -> {
                    List<TaskNeeds.Need> extra = r != null && r.ok() ? TaskNeeds.fromBom(r.data()) : List.of();
                    compute(b, step, type, args, inv, materials, extra);
                });
                return;
            }
            compute(b, step, type, args, inv, materials, List.of());
        });
    }

    private void compute(BotState b, QueueEntry step, String type, JsonObject args, Inventory inv, boolean materials,
                         List<TaskNeeds.Need> extra) {
        if (!m.dispatcher.isRunning(b, step)) {
            return;
        }
        TaskNeeds.Context ctx = ap.needsContext(b);
        List<TaskNeeds.Need> needs = new ArrayList<>(TaskNeeds.of(type, args, ctx, materials));
        needs.addAll(extra);
        Set<String> wishes = refill.getOrDefault(b.id, Set.of());
        for (String w : wishes) {
            if ("food".equals(w)) {
                ManagerConfig.AutopilotCfg cfg = ap.cfg(b);
                int min = Math.max(1, cfg.foodMin());
                needs.add(new TaskNeeds.Need(TaskNeeds.Kind.FOOD, List.of(), min * 2, min, null, null, false));
            } else if (w.startsWith("tool:")) {
                needs.add(new TaskNeeds.Need(TaskNeeds.Kind.TOOL, List.of(), 1, 1, w.substring(5), "wood", false));
            }
        }
        List<TaskNeeds.Need> missing = TaskNeeds.missing(needs, inv, ctx);
        if (missing.isEmpty()) {
            refill.remove(b.id);
            m.dispatcher.finishStep(b, step, true, null, "nothing needed", null, null, false);
            return;
        }
        List<SupplyPlanner.Source> sources = ap.supplySources(b);
        SupplyPlanner.Plan plan = SupplyPlanner.plan(missing, sources, ctx, Math.max(1, inv.freeSlots() - 1));
        List<WorldDoc.Container> inspect = new ArrayList<>(plan.inspect());
        if (!plan.missing().isEmpty()) {
            // stale snapshots may be wrong too: look again before calling anything missing
            long maxAge = ap.cfg(b).inspectMaxAgeMin() * 60_000L;
            long now = System.currentTimeMillis();
            for (SupplyPlanner.Source s : sources) {
                WorldDoc.Snapshot snap = s.container().snapshot();
                if (snap != null && maxAge > 0 && now - snap.time() > maxAge && !inspect.contains(s.container())) {
                    inspect.add(s.container());
                }
            }
        }
        if (!inspect.isEmpty() && !Json.getBool(step.args(), "inspected", false)) {
            // unknown / stale contents first, then plan again with fresh snapshots
            List<WorldDoc.Container> look = inspect.subList(0, Math.min(8, inspect.size()));
            JsonObject again = step.args().deepCopy();
            again.addProperty("inspected", true);
            List<QueueEntry> children = List.of(
                    QueueEntry.of(TaskTypes.INSPECT, Json.obj("containers", Json.arrOf(look.stream().map(WorldDoc.Container::pos).toList())),
                            180, step.label(), Dispatcher.ORIGIN_SUPPLY),
                    m.dispatcher.childEntry(step, Dispatcher.STEP_SUPPLY, again, 0));
            m.dispatcher.finishStep(b, step, true, null, "inspecting", null, children, true);
            return;
        }
        List<SupplyPlanner.Take> ordered = KitPlanner.nearestNeighbour(plan.takes(), Planner.posOf(b),
                t -> t.container().pos());
        List<QueueEntry> children = new ArrayList<>();
        Map<String, Integer> planned = new TreeMap<>();
        for (SupplyPlanner.Take t : ordered) {
            JsonArray items = new JsonArray();
            t.items().forEach((id, n) -> {
                items.add(Json.obj("item", id, "count", n));
                planned.merge(id, n, Integer::sum);
            });
            children.add(QueueEntry.of(TaskTypes.TAKE, Json.obj("container", t.container().pos(), "items", items),
                    180, step.label(), Dispatcher.ORIGIN_SUPPLY));
        }
        refill.remove(b.id);
        if (!plan.missing().isEmpty()) {
            manual(b, type, plan.missing());
        }
        JsonObject data = Json.obj("taking", Json.toTree(planned), "missing", Json.toTree(plan.missing()));
        String msg = planned.isEmpty() ? "nothing on hand in storage" : "taking " + planned.size() + " item types";
        m.dispatcher.finishStep(b, step, true, null, msg, data, children, false);
    }

    /** One {@code manual} event per bot + task type + missing list per {@link #MANUAL_EVENT_EVERY_MS}. */
    private void manual(BotState b, String type, Map<String, Integer> missing) {
        String key = b.id + "/" + type + "/" + new TreeMap<>(missing);
        long now = System.currentTimeMillis();
        Long last = manualAt.get(key);
        if (last != null && now - last < MANUAL_EVENT_EVERY_MS) {
            return;
        }
        manualAt.put(key, now);
        manualAt.values().removeIf(t -> now - t > MANUAL_EVENT_EVERY_MS * 6);
        List<String> parts = new ArrayList<>();
        missing.forEach((k, v) -> parts.add(v + " × " + Ids.path(k)));
        m.event("manual", Levels.WARN, b.id, "event.autopilot.manual", Map.of("bot", b.id, "task", type,
                "items", String.join(", ", parts)), Json.obj("missing", Json.toTree(new LinkedHashMap<>(missing)), "task", type));
    }

    public JsonObject view() {
        JsonObject r = new JsonObject();
        refill.forEach((k, v) -> r.add(k, Json.arrOf(v)));
        return Json.obj("refill", r);
    }
}
