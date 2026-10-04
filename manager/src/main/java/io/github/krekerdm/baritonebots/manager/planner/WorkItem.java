package io.github.krekerdm.baritonebots.manager.planner;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;
import java.util.Map;

/**
 * One unit of work a {@link WorkSource} offers on a planner tick (SPEC §5.7). Immutable; sources build fresh items
 * every tick and keep their state elsewhere. {@code id} is stable across ticks (e.g. {@code sector:3},
 * {@code mine:minecraft:cobblestone}) so retries/backoff and assignments can refer to it.
 *
 * @param source   the {@link WorkSource#id()} that emitted it (a project id)
 * @param kind     what the bot will do: {@code build_sector}, {@code verify_sector}, {@code haul}, {@code mine},
 *                 {@code craft}, {@code smelt}, ...
 * @param role     the role a bot takes on for it (SPEC §5.3 roles)
 * @param priority higher wins; matching subtracts distance/64 and 2 for a role change
 * @param needs    items / tools the work consumes (informational; sources check availability themselves)
 * @param dim      dimension of {@code location} (null = anywhere)
 * @param location where the work happens (null = no distance term)
 * @param lock     lock key ({@code sector:<project>:<i>}, {@code container:<dim>:<x>,<y>,<z>}) or null
 * @param capacity how many bots may hold {@code lock} / take this item at once
 * @param label    short text for the panel
 * @param data     source-specific details, handed back in {@link WorkSource#begin}
 */
public record WorkItem(String id, String source, String kind, String role, double priority, Needs needs, String dim,
                       Pos location, String lock, int capacity, String label, JsonObject data) {
    public WorkItem {
        needs = needs == null ? Needs.NONE : needs;
        capacity = Math.max(1, capacity);
        data = data == null ? new JsonObject() : data;
    }

    /** Key for retry bookkeeping: unique across sources. */
    public String key() {
        return source + "/" + id;
    }

    public record Needs(Map<String, Integer> items, List<String> tools) {
        public static final Needs NONE = new Needs(Map.of(), List.of());

        public Needs {
            items = items == null ? Map.of() : Map.copyOf(items);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public JsonObject view() {
        JsonObject o = Json.obj("id", id, "kind", kind, "role", role, "label", label, "priority", priority);
        if (location != null) {
            o.add("location", Json.obj("dim", dim, "pos", location));
        }
        if (!needs.items().isEmpty() || !needs.tools().isEmpty()) {
            o.add("needs", Json.obj("items", Json.toTree(needs.items()), "tools", Json.arrOf(needs.tools())));
        }
        return o;
    }
}
