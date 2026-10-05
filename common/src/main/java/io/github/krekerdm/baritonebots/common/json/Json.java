package io.github.krekerdm.baritonebots.common.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one shared Gson instance plus tolerant tree helpers.
 * Nulls are not serialised, HTML is not escaped, records map 1:1 to JSON objects.
 * Non-finite doubles (NaN, Infinity) are rejected by Gson; callers must not put them in payloads.
 */
public final class Json {
    /** Shared, thread-safe Gson. */
    /**
     * NaN and ±Infinity become JSON null instead of throwing. Found live: Baritone's ETA can be Infinity, and one
     * such value made every status message of a bot fail, so the manager stopped seeing that bot.
     */
    private static final TypeAdapter<Double> FINITE_DOUBLE = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Double v) throws IOException {
            if (v == null || v.isNaN() || v.isInfinite()) {
                out.nullValue();
            } else {
                out.value(v);
            }
        }

        @Override
        public Double read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null; // Gson leaves primitive fields at their default for null
            }
            return in.nextDouble();
        }
    };

    private static final TypeAdapter<Float> FINITE_FLOAT = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Float v) throws IOException {
            if (v == null || v.isNaN() || v.isInfinite()) {
                out.nullValue();
            } else {
                out.value(v);
            }
        }

        @Override
        public Float read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return (float) in.nextDouble();
        }
    };

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .registerTypeAdapter(Double.class, FINITE_DOUBLE).registerTypeAdapter(double.class, FINITE_DOUBLE)
            .registerTypeAdapter(Float.class, FINITE_FLOAT).registerTypeAdapter(float.class, FINITE_FLOAT)
            .create();

    private Json() {
    }

    /** Serialises any value (record, map, JsonElement) to a single-line JSON string. */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(JsonElement json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(JsonElement json, Type type) {
        return GSON.fromJson(json, type);
    }

    /** Converts a value to a JSON tree; {@code null} becomes {@link JsonNull}. */
    public static JsonElement toTree(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement e) {
            return e;
        }
        return GSON.toJsonTree(value);
    }

    /** Converts a value that serialises to a JSON object; {@code null} gives an empty object. */
    public static JsonObject toObject(Object value) {
        if (value == null) {
            return new JsonObject();
        }
        JsonElement tree = toTree(value);
        if (!tree.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object: " + value.getClass().getName());
        }
        return tree.getAsJsonObject();
    }

    /** Parses any JSON text. */
    public static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    /** Parses JSON text that must be an object. */
    public static JsonObject parseObject(String json) {
        JsonElement e = parse(json);
        if (!e.isJsonObject()) {
            throw new JsonParseException("expected a JSON object");
        }
        return e.getAsJsonObject();
    }

    /** Builds an object from alternating key/value arguments; {@code null} values are skipped. */
    public static JsonObject obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        JsonObject o = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                o.add(String.valueOf(keyValues[i]), toTree(value));
            }
        }
        return o;
    }

    /** Builds an array; {@code null} elements become JSON null. */
    public static JsonArray arr(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            a.add(toTree(v));
        }
        return a;
    }

    /** Builds an array from a collection. */
    public static JsonArray arrOf(Iterable<?> values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            a.add(toTree(v));
        }
        return a;
    }

    private static JsonPrimitive primitive(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }

    /** String value, or {@code def} when absent, null or not a primitive. Numbers and booleans are stringified. */
    public static String getString(JsonObject o, String key, String def) {
        JsonPrimitive p = primitive(o, key);
        return p == null ? def : p.getAsString();
    }

    /** Integer value (numeric strings accepted, fractions truncated), or {@code def}. */
    public static int getInt(JsonObject o, String key, int def) {
        JsonPrimitive p = primitive(o, key);
        if (p == null || p.isBoolean()) {
            return def;
        }
        try {
            return p.isNumber() ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, p.getAsDouble()))
                    : Integer.parseInt(p.getAsString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Long value (numeric strings accepted), or {@code def}. */
    public static long getLong(JsonObject o, String key, long def) {
        JsonPrimitive p = primitive(o, key);
        if (p == null || p.isBoolean()) {
            return def;
        }
        try {
            return p.isNumber() ? p.getAsNumber().longValue() : Long.parseLong(p.getAsString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Double value (numeric strings accepted), or {@code def}. */
    public static double getDouble(JsonObject o, String key, double def) {
        JsonPrimitive p = primitive(o, key);
        if (p == null || p.isBoolean()) {
            return def;
        }
        try {
            double d = p.isNumber() ? p.getAsDouble() : Double.parseDouble(p.getAsString().trim());
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Boolean value ({@code "true"}/{@code "false"} strings accepted), or {@code def}. */
    public static boolean getBool(JsonObject o, String key, boolean def) {
        JsonPrimitive p = primitive(o, key);
        if (p == null) {
            return def;
        }
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        String s = p.getAsString().trim();
        if (s.equalsIgnoreCase("true")) {
            return true;
        }
        if (s.equalsIgnoreCase("false")) {
            return false;
        }
        return def;
    }

    /** Nested object, or {@code null} when absent or not an object. */
    public static JsonObject getObj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** Nested array, or {@code null} when absent or not an array. */
    public static JsonArray getArr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** Array of strings; non-primitive elements are skipped, a single string becomes a one-element list. */
    public static List<String> getStringList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonPrimitive()) {
            out.add(e.getAsString());
            return out;
        }
        if (e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    /**
     * JSON Merge Patch (RFC 7396): returns {@code target} with {@code patch} applied.
     * Objects merge recursively, {@code null} members delete, everything else (arrays included) replaces.
     * Neither argument is modified.
     */
    public static JsonElement deepMerge(JsonElement target, JsonElement patch) {
        if (patch == null || !patch.isJsonObject()) {
            return patch == null ? JsonNull.INSTANCE : patch.deepCopy();
        }
        JsonObject result = target != null && target.isJsonObject() ? target.getAsJsonObject().deepCopy() : new JsonObject();
        for (Map.Entry<String, JsonElement> e : patch.getAsJsonObject().entrySet()) {
            JsonElement value = e.getValue();
            if (value == null || value.isJsonNull()) {
                result.remove(e.getKey());
            } else {
                result.add(e.getKey(), deepMerge(result.get(e.getKey()), value));
            }
        }
        return result;
    }

    /** {@link #deepMerge(JsonElement, JsonElement)} for two objects. */
    public static JsonObject deepMerge(JsonObject target, JsonObject patch) {
        return deepMerge((JsonElement) target, (JsonElement) patch).getAsJsonObject();
    }
}
