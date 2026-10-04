package io.github.krekerdm.baritonebots.manager.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.tasks.KitPlanner;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Plans {@code take} tasks for a list of item needs from ordered containers within a slot budget, and turns a plan
 * into locked, reserved queue entries for an assignment. {@link #plan} is pure.
 */
public final class Restock {
    public static final int TAKE_TIMEOUT_SEC = 180;

    public record Need(String item, int count) {
    }

    public record Take(WorldDoc.Container container, Map<String, Integer> items) {
    }

    /**
     * @param inspect containers whose contents are unknown (never inspected)
     * @param missing what could not be planned (no stock or no slots)
     */
    public record Plan(List<Take> takes, List<WorldDoc.Container> inspect, Map<String, Integer> missing) {
        public boolean isEmpty() {
            return takes.isEmpty();
        }

        public int total() {
            return takes.stream().mapToInt(t -> t.items().values().stream().mapToInt(Integer::intValue).sum()).sum();
        }
    }

    private Restock() {
    }

    /**
     * Needs in order (first = most important), containers in preference order. Never plans more than
     * {@code slots} inventory slots in total (partial stacks of one item are combined).
     *
     * @param avail   container → items available there, {@code null} = unknown contents
     * @param blocked containers that may not be used now (locked by another bot)
     */
    public static Plan plan(List<Need> needs, List<WorldDoc.Container> sources,
                            Function<WorldDoc.Container, Map<String, Integer>> avail,
                            Predicate<WorldDoc.Container> blocked, int slots) {
        Map<WorldDoc.Container, Map<String, Integer>> left = new LinkedHashMap<>();
        List<WorldDoc.Container> inspect = new ArrayList<>();
        for (WorldDoc.Container c : sources) {
            Map<String, Integer> a = avail.apply(c);
            if (a == null) {
                inspect.add(c);
            } else if (!blocked.test(c)) {
                left.put(c, new LinkedHashMap<>(a));
            }
        }
        Map<String, Integer> planned = new LinkedHashMap<>();
        Map<WorldDoc.Container, Map<String, Integer>> takes = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Need n : needs) {
            int want = n.count();
            for (Map.Entry<WorldDoc.Container, Map<String, Integer>> e : left.entrySet()) {
                if (want <= 0) {
                    break;
                }
                int have = e.getValue().getOrDefault(n.item(), 0);
                int room = room(planned, n.item(), slots);
                int take = Math.min(want, Math.min(have, room));
                if (take <= 0) {
                    continue;
                }
                e.getValue().merge(n.item(), -take, Integer::sum);
                planned.merge(n.item(), take, Integer::sum);
                takes.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).merge(n.item(), take, Integer::sum);
                want -= take;
            }
            if (want > 0) {
                missing.merge(n.item(), want, Integer::sum);
            }
        }
        List<Take> out = new ArrayList<>();
        takes.forEach((c, items) -> out.add(new Take(c, items)));
        return new Plan(out, inspect, missing);
    }

    /** How many more of {@code item} fit when {@code planned} is already in the budget. */
    static int room(Map<String, Integer> planned, String item, int slots) {
        int used = 0;
        for (Map.Entry<String, Integer> e : planned.entrySet()) {
            if (!e.getKey().equals(item)) {
                used += ItemStacks.slots(e.getKey(), e.getValue());
            }
        }
        int max = ItemStacks.maxStack(item);
        int free = slots - used;
        return Math.max(0, free * max - planned.getOrDefault(item, 0));
    }

    /**
     * Locks the plan's containers for the assignment, reserves the items and returns {@code take} entries in
     * nearest-neighbour order from {@code from}. Containers whose lock is taken meanwhile are skipped.
     */
    public static List<QueueEntry> entries(Planner planner, Assignment a, Plan plan, Pos from) {
        List<Take> ordered = KitPlanner.nearestNeighbour(plan.takes(), from, t -> t.container().pos());
        List<QueueEntry> out = new ArrayList<>();
        for (Take t : ordered) {
            String key = Planner.containerKey(t.container());
            if (!planner.holdLock(a, key, 1)) {
                continue;
            }
            a.reserved.put(key, new LinkedHashMap<>(t.items()));
            out.add(Planner.entry(a, TaskTypes.TAKE, takeArgs(t), TAKE_TIMEOUT_SEC, null));
        }
        return out;
    }

    public static JsonObject takeArgs(Take t) {
        JsonArray items = new JsonArray();
        t.items().forEach((item, count) -> items.add(Json.obj("item", item, "count", count)));
        return Json.obj("container", t.container().pos(), "items", items);
    }

    public static QueueEntry inspectEntry(Assignment a, List<WorldDoc.Container> containers) {
        return Planner.entry(a, TaskTypes.INSPECT,
                Json.obj("containers", Json.arrOf(containers.stream().map(WorldDoc.Container::pos).toList())),
                TAKE_TIMEOUT_SEC, null);
    }

    /** Sum of {@code taken} over the ok take results of a batch. */
    public static Map<String, Integer> taken(List<Assignment.Result> results) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Assignment.Result r : results) {
            if (r.ok() && TaskTypes.TAKE.equals(r.type()) && r.data() != null) {
                JsonObject t = Json.getObj(r.data(), "taken");
                if (t != null) {
                    t.entrySet().forEach(e -> out.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum));
                }
            }
        }
        return out;
    }
}
