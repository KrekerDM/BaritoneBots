package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.List;

/**
 * One editable setting (SPEC §5.3) as shown by the panel. {@code path} uses dots for nesting and {@code []}
 * for "every element of this list" ({@code servers[].address}). Labels are i18n keys derived from the path.
 *
 * @param type     bool | int | double | string | secret | enum | string_list | enum_list | map | json | list
 * @param applies  live | bot_restart | manager_restart | reinstall — when a change takes effect
 */
public record SchemaField(String path, String type, JsonElement def, Number min, Number max, String unit,
                          List<String> values, boolean nullable, String applies) {
    public static final String BOOL = "bool";
    public static final String INT = "int";
    public static final String DOUBLE = "double";
    public static final String STRING = "string";
    /** String stored in secrets.json instead of config.json (still shown to the authenticated panel). */
    public static final String SECRET = "secret";
    public static final String ENUM = "enum";
    public static final String STRING_LIST = "string_list";
    public static final String ENUM_LIST = "enum_list";
    /** Free-form object of setting name to scalar value (Baritone settings). */
    public static final String MAP = "map";
    /** Any JSON value, edited as text. */
    public static final String JSON = "json";
    /** List of objects whose fields are the schema entries {@code <path>[].<field>}. */
    public static final String LIST = "list";

    public static final String LIVE = "live";
    public static final String BOT_RESTART = "bot_restart";
    public static final String MANAGER_RESTART = "manager_restart";
    public static final String REINSTALL = "reinstall";

    public SchemaField {
        values = values == null ? List.of() : List.copyOf(values);
        applies = applies == null ? LIVE : applies;
    }

    public String labelKey() {
        return "settings." + path.replace("[]", "");
    }

    public String descriptionKey() {
        return labelKey() + ".desc";
    }

    /** True for fields of list items ({@code servers[].id}). */
    public boolean isItemField() {
        return path.contains("[]");
    }

    public SchemaField applies(String when) {
        return new SchemaField(path, type, def, min, max, unit, values, nullable, when);
    }

    public SchemaField asNullable() {
        return new SchemaField(path, type, def, min, max, unit, values, true, applies);
    }

    public SchemaField unit(String u) {
        return new SchemaField(path, type, def, min, max, u, values, nullable, applies);
    }

    public JsonObject toJson() {
        JsonObject o = Json.obj("path", path, "type", type, "label", labelKey(), "description", descriptionKey(),
                "applies", applies);
        if (def != null) {
            o.add("default", def.deepCopy());
        }
        if (min != null) {
            o.addProperty("min", min);
        }
        if (max != null) {
            o.addProperty("max", max);
        }
        if (unit != null) {
            o.addProperty("unit", unit);
        }
        if (!values.isEmpty()) {
            o.add("enum", Json.arrOf(values));
        }
        if (nullable) {
            o.addProperty("nullable", true);
        }
        return o;
    }
}
