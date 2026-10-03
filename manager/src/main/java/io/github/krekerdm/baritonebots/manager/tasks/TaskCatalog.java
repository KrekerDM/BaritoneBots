package io.github.krekerdm.baritonebots.manager.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task types and manager steps with their argument schema, read from {@code /catalog.json} (served as
 * {@code /api/catalog}). Also normalises arguments typed in forms (numbers as strings, comma lists, JSON text)
 * into the shapes SPEC §3 expects, and checks required arguments before anything is queued.
 */
public final class TaskCatalog {
    private final JsonObject json;
    private final Map<String, JsonObject> tasks = new LinkedHashMap<>();
    private final Map<String, JsonObject> steps = new LinkedHashMap<>();

    public TaskCatalog(JsonObject json) {
        this.json = json;
        JsonArray t = Json.getArr(json, "tasks");
        if (t != null) {
            t.forEach(e -> tasks.put(Json.getString(e.getAsJsonObject(), "type", ""), e.getAsJsonObject()));
        }
        JsonArray s = Json.getArr(json, "managerSteps");
        if (s != null) {
            s.forEach(e -> steps.put(Json.getString(e.getAsJsonObject(), "step", ""), e.getAsJsonObject()));
        }
    }

    public static TaskCatalog load() {
        try (InputStream in = TaskCatalog.class.getClassLoader().getResourceAsStream("catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("catalog.json missing from the manager jar");
            }
            return new TaskCatalog(Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonObject json() {
        return json.deepCopy();
    }

    public boolean isTask(String type) {
        return tasks.containsKey(type);
    }

    public boolean isStep(String type) {
        return steps.containsKey(type);
    }

    public boolean isKnown(String type) {
        return isTask(type) || isStep(type);
    }

    /** Counted against {@code runtime.maxHeavyTasks}. */
    public boolean isHeavy(String type) {
        JsonObject t = tasks.get(type);
        return t != null && Json.getBool(t, "heavy", false);
    }

    /** False for task types the bot mod / manager answers with {@code unsupported} (catalog flag {@code supported}). */
    public boolean isSupported(String type) {
        JsonObject t = tasks.containsKey(type) ? tasks.get(type) : steps.get(type);
        return t != null && Json.getBool(t, "supported", true);
    }

    public boolean isContinuous(String type) {
        JsonObject t = tasks.get(type);
        return t != null && Json.getBool(t, "continuous", false);
    }

    /**
     * Copy of {@code args} with form values coerced to their declared types.
     *
     * @throws BadArgsException for an unknown type, a missing required argument or an unparsable value
     */
    public JsonObject normalize(String type, JsonObject args) {
        JsonObject def = tasks.containsKey(type) ? tasks.get(type) : steps.get(type);
        if (def == null) {
            throw new BadArgsException(type, null, "unknown_type");
        }
        JsonObject out = args == null ? new JsonObject() : args.deepCopy();
        JsonArray argDefs = Json.getArr(def, "args");
        if (argDefs == null) {
            return out;
        }
        for (JsonElement ae : argDefs) {
            JsonObject a = ae.getAsJsonObject();
            String name = Json.getString(a, "name", "");
            JsonElement v = out.get(name);
            boolean blank = v == null || v.isJsonNull() || (v.isJsonPrimitive() && v.getAsString().isBlank());
            if (blank) {
                out.remove(name);
                if (Json.getBool(a, "required", false)) {
                    if (!a.has("default")) {
                        throw new BadArgsException(type, name, "required");
                    }
                    // required with a default: the executor may not know the default, so send it
                    out.add(name, coerce(type, name, Json.getString(a, "type", "string"), a.get("default")));
                }
                continue;
            }
            JsonElement value = coerce(type, name, Json.getString(a, "type", "string"), v);
            JsonArray supported = Json.getArr(a, "supportedEnum");
            if (supported != null && value.isJsonPrimitive() && !supported.contains(value)) {
                throw new BadArgsException(type, name, "unsupported");
            }
            out.add(name, value);
        }
        if ("build".equals(type) && out.has("rotation")) {
            out.addProperty("rotation", Json.getInt(out, "rotation", 0));
        }
        return out;
    }

    private static JsonElement coerce(String type, String name, String argType, JsonElement v) {
        try {
            return switch (argType) {
                case "int" -> {
                    double d = v.isJsonPrimitive() ? Double.parseDouble(v.getAsString().trim()) : Double.NaN;
                    yield d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : bad(type, name);
                }
                case "double" -> {
                    double d = v.isJsonPrimitive() ? Double.parseDouble(v.getAsString().trim()) : Double.NaN;
                    yield Double.isFinite(d) ? new JsonPrimitive(d) : bad(type, name);
                }
                case "bool" -> {
                    String s = v.isJsonPrimitive() ? v.getAsString().trim() : "";
                    yield s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")
                            ? new JsonPrimitive(Boolean.parseBoolean(s)) : bad(type, name);
                }
                case "items", "ids" -> v.isJsonPrimitive() ? splitList(v.getAsString()) : v;
                case "item_counts" -> "keepCounts".equals(name) && v.isJsonArray() ? countsToMap(v.getAsJsonArray()) : v;
                case "text" -> "grid".equals(name) && v.isJsonPrimitive() ? Json.parse(v.getAsString()) : v;
                default -> v;
            };
        } catch (NumberFormatException | JsonParseException e) {
            return bad(type, name);
        }
    }

    private static JsonElement bad(String type, String name) {
        throw new BadArgsException(type, name, "type");
    }

    private static JsonArray splitList(String s) {
        JsonArray a = new JsonArray();
        for (String part : s.split("[,\\s]+")) {
            if (!part.isBlank()) {
                a.add(part.trim());
            }
        }
        return a;
    }

    /** {@code [{item,count}]} (form shape) → {@code {glob:count}} (SPEC §3 deposit.keepCounts). */
    private static JsonObject countsToMap(JsonArray list) {
        JsonObject m = new JsonObject();
        for (JsonElement e : list) {
            if (e.isJsonObject()) {
                m.addProperty(Json.getString(e.getAsJsonObject(), "item", ""), Json.getInt(e.getAsJsonObject(), "count", 0));
            }
        }
        return m;
    }

    /** Invalid task template; HTTP maps it to 400 {@code bad_args}. */
    public static final class BadArgsException extends RuntimeException {
        private final String type;
        private final String arg;
        private final String code;

        public BadArgsException(String type, String arg, String code) {
            super(type + (arg == null ? "" : "." + arg) + ": " + code);
            this.type = type;
            this.arg = arg;
            this.code = code;
        }

        public String type() {
            return type;
        }

        public String arg() {
            return arg;
        }

        public String code() {
            return code;
        }
    }
}
