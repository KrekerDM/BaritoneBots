package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotStatus;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a bot carries, for auto-supply and goals: item totals (main inventory + armor + offhand), the best remaining
 * durability per damageable item id (0..1) and free main-inventory slots. Built from a live {@code inventory} query
 * or, when that fails, from the last status (no durability then: every tool counts as usable). Immutable.
 */
public record Inventory(Map<String, Integer> items, Map<String, Double> durability, int freeSlots, boolean live) {
    public static final Inventory EMPTY = new Inventory(Map.of(), Map.of(), 36, false);
    private static final int MAIN_SLOTS = 36;

    public Inventory {
        items = Collections.unmodifiableMap(new LinkedHashMap<>(items == null ? Map.of() : items));
        durability = Collections.unmodifiableMap(new LinkedHashMap<>(durability == null ? Map.of() : durability));
    }

    /** From an {@code inventory} query answer (SPEC §2.4). */
    public static Inventory fromQuery(JsonObject data, int statusFreeSlots) {
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Double> dur = new LinkedHashMap<>();
        int used = 0;
        JsonArray slots = Json.getArr(data, "slots");
        for (JsonElement e : slots == null ? new JsonArray() : slots) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            String id = Json.getString(s, "item", null);
            int count = Json.getInt(s, "count", 0);
            if (id == null || count <= 0 || Ids.isAir(id)) {
                continue;
            }
            id = Ids.normalize(id);
            int slot = Json.getInt(s, "slot", -1);
            if (slot >= 0 && slot < MAIN_SLOTS) {
                used++;
            }
            items.merge(id, count, Integer::sum);
            int max = Json.getInt(s, "maxDamage", 0);
            if (max > 0) {
                double left = Math.max(0, max - Json.getInt(s, "damage", 0)) / (double) max;
                dur.merge(id, left, Math::max);
            }
        }
        JsonArray armor = Json.getArr(data, "armor");
        for (JsonElement e : armor == null ? new JsonArray() : armor) {
            if (e.isJsonPrimitive()) {
                items.merge(Ids.normalize(e.getAsString()), 1, Integer::sum);
            }
        }
        String off = Json.getString(data, "offhand", null);
        if (off != null && !Ids.isAir(off)) {
            items.merge(Ids.normalize(off), 1, Integer::sum);
        }
        int free = slots == null ? statusFreeSlots : Math.max(0, MAIN_SLOTS - used);
        return new Inventory(items, dur, free, true);
    }

    /** From the last status: whole-inventory totals plus armor; durability unknown. */
    public static Inventory fromStatus(BotStatus s) {
        if (s == null) {
            return EMPTY;
        }
        Map<String, Integer> items = new LinkedHashMap<>(s.items());
        for (String a : s.armor()) {
            if (a != null && !Ids.isAir(a)) {
                items.merge(Ids.normalize(a), 1, Integer::sum);
            }
        }
        return new Inventory(items, Map.of(), s.freeSlots(), false);
    }

    public int count(String item) {
        return items.getOrDefault(Ids.normalize(item), 0);
    }

    public int count(Predicate<String> filter) {
        int n = 0;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (filter.test(e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** A tool of {@code kind} with at least {@code minTier} and {@code minDurability} left (unknown = usable). */
    public boolean hasTool(String kind, String minTier, double minDurability) {
        int need = ProductionWork.tierRank(minTier);
        for (String id : items.keySet()) {
            if (ProductionWork.toolTier(id, kind) >= need && durability.getOrDefault(id, 1.0) >= minDurability) {
                return true;
            }
        }
        return false;
    }

    /** Copy with more items (planning). */
    public Inventory plus(Map<String, Integer> more) {
        Map<String, Integer> m = new LinkedHashMap<>(items);
        more.forEach((k, v) -> m.merge(k, v, Integer::sum));
        return new Inventory(m, durability, freeSlots, live);
    }
}
