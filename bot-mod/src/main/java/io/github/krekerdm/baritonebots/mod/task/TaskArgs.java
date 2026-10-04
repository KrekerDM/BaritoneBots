package io.github.krekerdm.baritonebots.mod.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsing helpers for {@code TaskSpec.args}; return {@code null}/empty on missing or malformed input. */
public final class TaskArgs {
    private TaskArgs() {
    }

    public static BlockPos pos(JsonObject args, String key) {
        Pos p = Pos.fromJson(args.get(key));
        return p == null ? null : Positions.toBlockPos(p);
    }

    public static List<BlockPos> posList(JsonObject args, String key) {
        List<BlockPos> out = new ArrayList<>();
        JsonArray a = Json.getArr(args, key);
        if (a != null) {
            for (JsonElement e : a) {
                Pos p = Pos.fromJson(e);
                if (p != null) {
                    out.add(Positions.toBlockPos(p));
                }
            }
        }
        return out;
    }

    /** {@code [glob]} normalised with {@link Ids#normalizeGlob}. */
    public static List<String> globs(JsonObject args, String key) {
        List<String> out = new ArrayList<>();
        for (String s : Json.getStringList(args, key)) {
            if (!s.isBlank()) {
                out.add(Ids.normalizeGlob(s.trim()));
            }
        }
        return out;
    }

    /** {@code {glob: count}} with normalised globs, insertion order kept. */
    public static Map<String, Integer> globCounts(JsonObject args, String key) {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject o = Json.getObj(args, key);
        if (o != null) {
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                int n = Json.getInt(o, e.getKey(), 0);
                if (n > 0) {
                    out.put(Ids.normalizeGlob(e.getKey().trim()), n);
                }
            }
        }
        return out;
    }

    /** {@code [{item: glob, count: int}]}; count -1 = all. */
    public static List<ItemRequest> itemRequests(JsonObject args, String key) {
        List<ItemRequest> out = new ArrayList<>();
        JsonArray a = Json.getArr(args, key);
        if (a != null) {
            for (JsonElement e : a) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    String item = Json.getString(o, "item", null);
                    if (item != null && !item.isBlank()) {
                        out.add(new ItemRequest(Ids.normalizeGlob(item.trim()), Json.getInt(o, "count", -1)));
                    }
                }
            }
        }
        return out;
    }

    public record ItemRequest(String glob, int count) {
        public boolean all() {
            return count < 0;
        }
    }

    /**
     * {@code [{slot, item, count?}]} (player inventory slot 0..35, item id expected there, count -1 / absent = the
     * whole stack), keyed by slot; later duplicates of a slot are ignored.
     */
    public static Map<Integer, SlotPick> slotPicks(JsonObject args, String key) {
        Map<Integer, SlotPick> out = new LinkedHashMap<>();
        JsonArray a = Json.getArr(args, key);
        if (a != null) {
            for (JsonElement e : a) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    int slot = Json.getInt(o, "slot", -1);
                    String item = Json.getString(o, "item", null);
                    if (slot >= 0 && item != null && !item.isBlank()) {
                        out.putIfAbsent(slot, new SlotPick(slot, Ids.normalize(item.trim()), Json.getInt(o, "count", -1)));
                    }
                }
            }
        }
        return out;
    }

    /** One stack chosen by the manager (trash, SPEC §5.7f). */
    public record SlotPick(int slot, String item, int count) {
        public boolean whole() {
            return count < 0;
        }
    }

    /** {@code {id: count}} as a JSON object. */
    public static JsonObject counts(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }
}
