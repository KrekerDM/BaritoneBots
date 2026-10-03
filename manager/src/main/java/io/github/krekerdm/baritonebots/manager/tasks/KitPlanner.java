package io.github.krekerdm.baritonebots.manager.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kit planning (SPEC §5.5), pure and side-effect free: which slots the bot already satisfies, which containers to
 * take the rest from, in what order. The caller turns the plan into {@code inspect} / {@code take} / {@code equip}
 * tasks.
 */
public final class KitPlanner {
    private KitPlanner() {
    }

    /** One kit slot: acceptable item globs in rank order (best first) and the count wanted. */
    public record Slot(String name, List<String> any, int count) {
    }

    /**
     * A container that may supply items.
     *
     * @param stock item totals of the last snapshot, or {@code null} when the contents are unknown
     */
    public record Source(Pos pos, String role, Map<String, Integer> stock) {
    }

    /** Items to take from one container, exact ids. */
    public record Take(Pos container, Map<String, Integer> items) {
    }

    /**
     * @param inspect containers to inspect before a real plan is possible (then nothing else is planned)
     * @param takes   take tasks in visiting order (nearest neighbour from the bot)
     * @param missing slot name → count no known container can supply
     * @param offhand glob for {@code equip.offhand}, from a slot named {@code offhand}, or null
     */
    public record Plan(List<Pos> inspect, List<Take> takes, Map<String, Integer> missing, String offhand) {
        public boolean needsInspect() {
            return !inspect.isEmpty();
        }
    }

    /** Reads {@code {slots:{name:{any:[glob], count}}}}; malformed slots are skipped. */
    public static List<Slot> slotsOf(JsonObject kit) {
        List<Slot> out = new ArrayList<>();
        JsonObject slots = Json.getObj(kit, "slots");
        if (slots == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : slots.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject s = e.getValue().getAsJsonObject();
            List<String> any = new ArrayList<>();
            for (String g : Json.getStringList(s, "any")) {
                if (!g.isBlank()) {
                    any.add(Ids.normalizeGlob(g.trim()));
                }
            }
            int count = Math.max(1, Json.getInt(s, "count", 1));
            if (!any.isEmpty()) {
                out.add(new Slot(e.getKey(), any, count));
            }
        }
        return out;
    }

    /**
     * @param possessed   the bot's items (inventory + offhand + worn armor) by id
     * @param sources     containers in priority order: role {@code kit} first, then {@code storage}
     * @param from        the bot's position (null = unknown; order then stays as given)
     * @param skipUnknown true after an inspection round: containers with unknown contents are ignored
     */
    public static Plan plan(List<Slot> slots, Map<String, Integer> possessed, List<Source> sources, Pos from,
                            boolean skipUnknown) {
        Map<String, Integer> have = new LinkedHashMap<>(possessed);
        Map<Slot, Integer> needs = new LinkedHashMap<>();
        String offhand = null;
        for (Slot slot : slots) {
            if ("offhand".equalsIgnoreCase(slot.name())) {
                offhand = slot.any().getFirst();
            }
            int got = consume(have, slot.any(), slot.count());
            if (got < slot.count()) {
                needs.put(slot, slot.count() - got);
            }
        }
        if (needs.isEmpty()) {
            return new Plan(List.of(), List.of(), Map.of(), offhand);
        }
        List<Source> ordered = prioritise(sources, from);
        if (!skipUnknown) {
            List<Pos> unknown = ordered.stream().filter(s -> s.stock() == null).map(Source::pos).toList();
            if (!unknown.isEmpty()) {
                return new Plan(nearestNeighbour(unknown, from, p -> p), List.of(), Map.of(), offhand);
            }
        }
        Map<Pos, Map<String, Integer>> stock = new LinkedHashMap<>();
        for (Source s : ordered) {
            if (s.stock() != null) {
                stock.put(s.pos(), new LinkedHashMap<>(s.stock()));
            }
        }
        Map<Pos, Map<String, Integer>> takes = new LinkedHashMap<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Slot, Integer> n : needs.entrySet()) {
            int need = n.getValue();
            for (String glob : n.getKey().any()) {
                for (Map.Entry<Pos, Map<String, Integer>> c : stock.entrySet()) {
                    if (need <= 0) {
                        break;
                    }
                    for (Map.Entry<String, Integer> item : c.getValue().entrySet()) {
                        if (need <= 0) {
                            break;
                        }
                        if (item.getValue() <= 0 || !Ids.matches(glob, item.getKey())) {
                            continue;
                        }
                        int take = Math.min(need, item.getValue());
                        item.setValue(item.getValue() - take);
                        takes.computeIfAbsent(c.getKey(), k -> new LinkedHashMap<>()).merge(item.getKey(), take, Integer::sum);
                        need -= take;
                    }
                }
                if (need <= 0) {
                    break;
                }
            }
            if (need > 0) {
                missing.put(n.getKey().name(), need);
            }
        }
        List<Take> list = new ArrayList<>();
        takes.forEach((pos, items) -> list.add(new Take(pos, items)));
        return new Plan(List.of(), nearestNeighbour(list, from, Take::container), missing, offhand);
    }

    /** Takes up to {@code count} items matching the globs (rank order) out of {@code have}; returns how many. */
    private static int consume(Map<String, Integer> have, List<String> any, int count) {
        int got = 0;
        for (String glob : any) {
            for (Map.Entry<String, Integer> e : have.entrySet()) {
                if (got >= count) {
                    return got;
                }
                if (e.getValue() > 0 && Ids.matches(glob, e.getKey())) {
                    int use = Math.min(count - got, e.getValue());
                    e.setValue(e.getValue() - use);
                    got += use;
                }
            }
        }
        return got;
    }

    /** Kit containers before storage; within a role nearest first. Stable for equal keys. */
    private static List<Source> prioritise(List<Source> sources, Pos from) {
        List<Source> out = new ArrayList<>(sources);
        Comparator<Source> byRole = Comparator.comparingInt(s -> "kit".equals(s.role()) ? 0 : 1);
        if (from != null) {
            byRole = byRole.thenComparingLong(s -> s.pos().distSq(from));
        }
        out.sort(byRole);
        return out;
    }

    /** Greedy nearest-neighbour tour starting at {@code from}. */
    public static <T> List<T> nearestNeighbour(List<T> items, Pos from, java.util.function.Function<T, Pos> posOf) {
        if (from == null || items.size() < 2) {
            return List.copyOf(items);
        }
        List<T> left = new ArrayList<>(items);
        List<T> out = new ArrayList<>();
        Pos cur = from;
        while (!left.isEmpty()) {
            T best = null;
            long bestD = Long.MAX_VALUE;
            for (T t : left) {
                long d = posOf.apply(t).distSq(cur);
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
            left.remove(best);
            out.add(best);
            cur = posOf.apply(best);
        }
        return out;
    }

    /** {@code take} args for one container. */
    public static JsonObject takeArgs(Take t) {
        JsonArray items = new JsonArray();
        t.items().forEach((id, n) -> items.add(Json.obj("item", id, "count", n)));
        return Json.obj("container", t.container(), "items", items);
    }
}
