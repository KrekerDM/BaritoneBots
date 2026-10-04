package io.github.krekerdm.baritonebots.manager.autopilot;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.manager.planner.ItemStacks;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns missing {@link TaskNeeds.Need}s into {@code take}s from indexed containers (SPEC §5.7a): containers nearest
 * first, the best tool tier on offer, food / throwaway blocks / materials by glob, all within a slot budget.
 * Containers with unknown contents are returned for inspection when something stays missing. Pure.
 */
public final class SupplyPlanner {
    /**
     * A container that may be taken from.
     *
     * @param available items there minus other bots' reservations; {@code null} = never inspected
     */
    public record Source(WorldDoc.Container container, Map<String, Integer> available) {
    }

    public record Take(WorldDoc.Container container, Map<String, Integer> items) {
    }

    /**
     * @param missing required needs (by label) that no known container covers
     * @param inspect containers with unknown contents, nearest first (only when something stays unmet)
     */
    public record Plan(List<Take> takes, Map<String, Integer> missing, List<WorldDoc.Container> inspect) {
        public boolean isEmpty() {
            return takes.isEmpty();
        }
    }

    private SupplyPlanner() {
    }

    /**
     * @param sources nearest first
     * @param slots   inventory slots the takes may fill
     */
    public static Plan plan(List<TaskNeeds.Need> needs, List<Source> sources, TaskNeeds.Context ctx, int slots) {
        Map<WorldDoc.Container, Map<String, Integer>> left = new LinkedHashMap<>();
        List<WorldDoc.Container> unknown = new ArrayList<>();
        for (Source s : sources) {
            if (s.available() == null) {
                unknown.add(s.container());
            } else {
                left.put(s.container(), new LinkedHashMap<>(s.available()));
            }
        }
        Map<WorldDoc.Container, Map<String, Integer>> takes = new LinkedHashMap<>();
        Map<String, Integer> planned = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        boolean unmet = false;
        for (TaskNeeds.Need n : needs) {
            int got = switch (n.kind()) {
                case TOOL -> tool(n, left, takes, planned, slots);
                case FOOD -> byFilter(ctx::isFood, n.count(), left, takes, planned, slots);
                case BLOCKS -> byFilter(ctx::isThrowaway, n.count(), left, takes, planned, slots);
                case ITEM -> byFilter(id -> Ids.matchesAny(n.globs(), id), n.count(), left, takes, planned, slots);
            };
            if (got < n.count()) {
                unmet = true;
                if (!n.optional()) {
                    missing.merge(n.label(), n.count() - got, Integer::sum);
                }
            }
        }
        List<Take> out = new ArrayList<>();
        takes.forEach((c, items) -> out.add(new Take(c, items)));
        return new Plan(out, missing, unmet ? unknown : List.of());
    }

    /** The highest tier of the kind anywhere (nearest container on ties), one item. */
    private static int tool(TaskNeeds.Need n, Map<WorldDoc.Container, Map<String, Integer>> left,
                            Map<WorldDoc.Container, Map<String, Integer>> takes, Map<String, Integer> planned, int slots) {
        int need = ProductionWork.tierRank(n.minTier());
        WorldDoc.Container best = null;
        String bestId = null;
        int bestTier = -1;
        for (Map.Entry<WorldDoc.Container, Map<String, Integer>> e : left.entrySet()) {
            for (Map.Entry<String, Integer> it : e.getValue().entrySet()) {
                int tier = ProductionWork.toolTier(it.getKey(), n.toolKind());
                if (it.getValue() > 0 && tier >= need && tier > bestTier) {
                    best = e.getKey();
                    bestId = it.getKey();
                    bestTier = tier;
                }
            }
        }
        if (best == null || room(planned, bestId, slots) < 1) {
            return 0;
        }
        add(best, bestId, 1, left, takes, planned);
        return 1;
    }

    private static int byFilter(Predicate<String> filter, int want, Map<WorldDoc.Container, Map<String, Integer>> left,
                                Map<WorldDoc.Container, Map<String, Integer>> takes, Map<String, Integer> planned,
                                int slots) {
        int got = 0;
        for (Map.Entry<WorldDoc.Container, Map<String, Integer>> e : left.entrySet()) {
            for (Map.Entry<String, Integer> it : List.copyOf(e.getValue().entrySet())) {
                if (got >= want) {
                    return got;
                }
                if (it.getValue() <= 0 || !filter.test(it.getKey())) {
                    continue;
                }
                int n = Math.min(want - got, Math.min(it.getValue(), room(planned, it.getKey(), slots)));
                if (n > 0) {
                    add(e.getKey(), it.getKey(), n, left, takes, planned);
                    got += n;
                }
            }
        }
        return got;
    }

    private static void add(WorldDoc.Container c, String id, int n, Map<WorldDoc.Container, Map<String, Integer>> left,
                            Map<WorldDoc.Container, Map<String, Integer>> takes, Map<String, Integer> planned) {
        left.get(c).merge(id, -n, Integer::sum);
        takes.computeIfAbsent(c, k -> new LinkedHashMap<>()).merge(id, n, Integer::sum);
        planned.merge(id, n, Integer::sum);
    }

    /** How many more of {@code item} fit when {@code planned} already uses part of the slot budget. */
    static int room(Map<String, Integer> planned, String item, int slots) {
        int used = 0;
        for (Map.Entry<String, Integer> e : planned.entrySet()) {
            if (!e.getKey().equals(item)) {
                used += ItemStacks.slots(e.getKey(), e.getValue());
            }
        }
        int max = ItemStacks.maxStack(item);
        return Math.max(0, (slots - used) * max - planned.getOrDefault(item, 0));
    }
}
