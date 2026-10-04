package io.github.krekerdm.baritonebots.manager.automation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.util.List;

/**
 * The {@code if} of a rule (SPEC §5.7b), exactly one of {@code {event: kind}}, {@code {containerFull: containerId |
 * "x,y,z" | pos}}, {@code {itemBelow: {item, count}}}, {@code {playerOnline: name}}, {@code {healthBelow: n}}.
 * Immutable, pure.
 */
public record Trigger(String type, String event, String container, Pos pos, String item, int count, String player,
                      double health) {
    public static final String EVENT = "event";
    public static final String CONTAINER_FULL = "containerFull";
    public static final String ITEM_BELOW = "itemBelow";
    public static final String PLAYER_ONLINE = "playerOnline";
    public static final String HEALTH_BELOW = "healthBelow";
    public static final List<String> TYPES = List.of(EVENT, CONTAINER_FULL, ITEM_BELOW, PLAYER_ONLINE, HEALTH_BELOW);
    private static final java.util.regex.Pattern KIND = java.util.regex.Pattern.compile("[a-z0-9_]{1,48}");
    private static final java.util.regex.Pattern PLAYER = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A trigger that concerns one bot (the rule runs on that bot). */
    public boolean botScoped() {
        return HEALTH_BELOW.equals(type);
    }

    /**
     * Parses and checks a rule condition.
     *
     * @throws IllegalArgumentException with message {@code "<subpath>:<code>"} (subpath may be empty)
     */
    public static Trigger parse(JsonObject o) {
        if (o == null || o.isEmpty()) {
            throw new IllegalArgumentException(":required");
        }
        String found = null;
        for (String t : TYPES) {
            if (o.has(t)) {
                if (found != null) {
                    throw new IllegalArgumentException(":one_trigger");
                }
                found = t;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(":unknown_trigger");
        }
        JsonElement v = o.get(found);
        try {
            switch (found) {
                case EVENT -> {
                    String kind = v.getAsString().trim();
                    if (!KIND.matcher(kind).matches()) {
                        throw new IllegalArgumentException(EVENT + ":pattern");
                    }
                    return new Trigger(EVENT, kind, null, null, null, 0, null, 0);
                }
                case CONTAINER_FULL -> {
                    Pos p = v.isJsonObject() ? Pos.fromJson(v) : null;
                    String id = null;
                    if (p == null) {
                        String s = v.getAsString().trim();
                        p = s.matches("-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+") ? Pos.parse(s.replace(" ", "")) : null;
                        id = p == null ? s : null;
                        if (id != null && id.isEmpty()) {
                            throw new IllegalArgumentException(CONTAINER_FULL + ":required");
                        }
                    }
                    return new Trigger(CONTAINER_FULL, null, id, p, null, 0, null, 0);
                }
                case ITEM_BELOW -> {
                    if (!v.isJsonObject()) {
                        throw new IllegalArgumentException(ITEM_BELOW + ":type");
                    }
                    JsonObject ib = v.getAsJsonObject();
                    String item = Json.getString(ib, "item", "").trim();
                    if (item.isEmpty() || !item.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
                        throw new IllegalArgumentException(ITEM_BELOW + ".item:pattern");
                    }
                    int count = Json.getInt(ib, "count", -1);
                    if (count < 1 || count > 1_000_000) {
                        throw new IllegalArgumentException(ITEM_BELOW + ".count:range");
                    }
                    return new Trigger(ITEM_BELOW, null, null, null, Ids.normalize(item), count, null, 0);
                }
                case PLAYER_ONLINE -> {
                    String name = v.getAsString().trim();
                    if (!PLAYER.matcher(name).matches()) {
                        throw new IllegalArgumentException(PLAYER_ONLINE + ":pattern");
                    }
                    return new Trigger(PLAYER_ONLINE, null, null, null, null, 0, name, 0);
                }
                default -> {
                    double h = v.getAsDouble();
                    if (!(h > 0 && h <= 1024)) {
                        throw new IllegalArgumentException(HEALTH_BELOW + ":range");
                    }
                    return new Trigger(HEALTH_BELOW, null, null, null, null, 0, null, h);
                }
            }
        } catch (IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            throw new IllegalArgumentException(found + ":type");
        }
    }

    public String describe() {
        return switch (type) {
            case EVENT -> "event " + event;
            case CONTAINER_FULL -> "container full " + (container != null ? container : pos);
            case ITEM_BELOW -> Ids.path(item) + " < " + count;
            case PLAYER_ONLINE -> player + " online";
            default -> "health < " + (health == Math.rint(health) ? String.valueOf((int) health) : String.valueOf(health));
        };
    }
}
