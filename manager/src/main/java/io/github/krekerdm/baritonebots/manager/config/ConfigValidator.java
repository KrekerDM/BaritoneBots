package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a normalised config.json against {@link SettingsSchema} plus the cross-field rules (unique ids,
 * server references, regexes that compile). Errors are {@code path → code}; the panel translates
 * {@code error.field.<code>}.
 */
public final class ConfigValidator {
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    /** Vanilla player name rules; offline servers accept the same set. */
    public static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private ConfigValidator() {
    }

    public static Map<String, String> validate(JsonObject root) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (SchemaField f : SettingsSchema.fields()) {
            Map<String, JsonElement> values = new LinkedHashMap<>();
            collect(root, f.path().split("\\."), 0, "", values);
            for (Map.Entry<String, JsonElement> e : values.entrySet()) {
                String code = check(f, e.getValue());
                if (code != null) {
                    errors.putIfAbsent(e.getKey(), code);
                }
            }
        }
        semantic(root, errors);
        return errors;
    }

    /** Resolves a schema path to concrete paths, expanding {@code []} over list elements. */
    private static void collect(JsonElement node, String[] parts, int i, String prefix, Map<String, JsonElement> out) {
        if (i == parts.length) {
            out.put(prefix, node);
            return;
        }
        String part = parts[i];
        boolean each = part.endsWith("[]");
        String key = each ? part.substring(0, part.length() - 2) : part;
        JsonElement child = node != null && node.isJsonObject() ? node.getAsJsonObject().get(key) : null;
        String path = prefix.isEmpty() ? key : prefix + "." + key;
        if (!each) {
            collect(child, parts, i + 1, path, out);
            return;
        }
        if (child == null || !child.isJsonArray()) {
            return;
        }
        JsonArray arr = child.getAsJsonArray();
        for (int k = 0; k < arr.size(); k++) {
            collect(arr.get(k), parts, i + 1, path + "[" + k + "]", out);
        }
    }

    private static String check(SchemaField f, JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return f.nullable() ? null : "required";
        }
        return switch (f.type()) {
            case SchemaField.BOOL -> isBool(v) ? null : "type";
            case SchemaField.INT -> {
                if (!isNumber(v) || v.getAsDouble() != Math.rint(v.getAsDouble())) {
                    yield "type";
                }
                yield inRange(f, v.getAsDouble()) ? null : "range";
            }
            case SchemaField.DOUBLE -> {
                if (!isNumber(v) || !Double.isFinite(v.getAsDouble())) {
                    yield "type";
                }
                yield inRange(f, v.getAsDouble()) ? null : "range";
            }
            case SchemaField.STRING, SchemaField.SECRET -> isString(v) ? null : "type";
            case SchemaField.ENUM -> !isString(v) ? "type" : f.values().contains(v.getAsString()) ? null : "enum";
            case SchemaField.STRING_LIST -> stringList(v, null);
            case SchemaField.ENUM_LIST -> stringList(v, f.values());
            case SchemaField.MAP -> {
                if (!v.isJsonObject()) {
                    yield "type";
                }
                for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) {
                        yield "type";
                    }
                }
                yield null;
            }
            case SchemaField.LIST -> {
                if (!v.isJsonArray()) {
                    yield "type";
                }
                for (JsonElement e : v.getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        yield "type";
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    private static boolean inRange(SchemaField f, double d) {
        return (f.min() == null || d >= f.min().doubleValue()) && (f.max() == null || d <= f.max().doubleValue());
    }

    private static String stringList(JsonElement v, List<String> allowed) {
        if (!v.isJsonArray()) {
            return "type";
        }
        for (JsonElement e : v.getAsJsonArray()) {
            if (!isString(e)) {
                return "type";
            }
            if (allowed != null && !allowed.contains(e.getAsString())) {
                return "enum";
            }
        }
        return null;
    }

    private static boolean isBool(JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isNumber(JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement v) {
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
    }

    // ---------------------------------------------------------------- cross-field rules

    private static void semantic(JsonObject root, Map<String, String> errors) {
        JsonObject runtime = Json.getObj(root, "runtime");
        String javaPath = Json.getString(runtime, "javaPath", null);
        if (javaPath != null && !javaPath.isBlank()) {
            try {
                if (!Files.isRegularFile(Path.of(javaPath))) {
                    errors.putIfAbsent("runtime.javaPath", "not_found");
                }
            } catch (InvalidPathException e) {
                errors.putIfAbsent("runtime.javaPath", "pattern");
            }
        }
        JsonArray mods = Json.getArr(runtime, "mods");
        Set<String> modIds = new HashSet<>();
        for (int i = 0; mods != null && i < mods.size(); i++) {
            if (!mods.get(i).isJsonObject()) {
                continue;
            }
            JsonObject m = mods.get(i).getAsJsonObject();
            String id = Json.getString(m, "id", "");
            if (!ID.matcher(id).matches()) {
                errors.putIfAbsent("runtime.mods[" + i + "].id", "pattern");
            } else if (!modIds.add(id.toLowerCase(Locale.ROOT))) {
                errors.putIfAbsent("runtime.mods[" + i + "].id", "duplicate");
            }
            if (blank(Json.getString(m, "url", null)) && blank(Json.getString(m, "modrinth", null))) {
                errors.putIfAbsent("runtime.mods[" + i + "].url", "missing_source");
            }
        }

        Set<String> serverIds = new HashSet<>();
        JsonArray servers = Json.getArr(root, "servers");
        for (int i = 0; servers != null && i < servers.size(); i++) {
            if (!servers.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = servers.get(i).getAsJsonObject();
            String p = "servers[" + i + "]";
            uniqueId(Json.getString(s, "id", ""), p + ".id", serverIds, errors);
            if (blank(Json.getString(s, "address", null))) {
                errors.putIfAbsent(p + ".address", "required");
            }
            JsonObject login = Json.getObj(s, "login");
            for (String key : List.of("loginPatterns", "registerPatterns", "successPatterns", "failurePatterns")) {
                regexes(Json.getStringList(login, key), p + ".login." + key, errors);
            }
            JsonObject protection = Json.getObj(s, "protection");
            JsonArray zones = Json.getArr(protection, "zones");
            for (int z = 0; zones != null && z < zones.size(); z++) {
                JsonElement zone = zones.get(z);
                if (!zone.isJsonObject() || blank(Json.getString(zone.getAsJsonObject(), "dim", null))
                        || Box.fromJson(zone.getAsJsonObject().get("box")) == null) {
                    errors.putIfAbsent(p + ".protection.zones", "type");
                }
            }
        }

        Set<String> botIds = new HashSet<>();
        Map<String, Integer> usernames = new HashMap<>();
        JsonArray bots = Json.getArr(root, "bots");
        for (int i = 0; bots != null && i < bots.size(); i++) {
            if (!bots.get(i).isJsonObject()) {
                continue;
            }
            JsonObject b = bots.get(i).getAsJsonObject();
            String p = "bots[" + i + "]";
            uniqueId(Json.getString(b, "id", ""), p + ".id", botIds, errors);
            String username = Json.getString(b, "username", "");
            if (!USERNAME.matcher(username).matches()) {
                errors.putIfAbsent(p + ".username", "pattern");
            }
            String serverId = Json.getString(b, "serverId", "");
            if (!serverIds.contains(serverId.toLowerCase(Locale.ROOT))) {
                errors.putIfAbsent(p + ".serverId", "unknown_server");
            }
            String key = serverId.toLowerCase(Locale.ROOT) + "/" + username.toLowerCase(Locale.ROOT);
            if (usernames.putIfAbsent(key, i) != null) {
                errors.putIfAbsent(p + ".username", "duplicate");
            }
            JsonElement behaviour = b.get("behaviour");
            if (behaviour != null && !behaviour.isJsonNull() && !behaviour.isJsonObject()) {
                errors.putIfAbsent(p + ".behaviour", "type");
            }
        }
        JsonObject defense = Json.getObj(Json.getObj(root, "behaviour"), "defense");
        regexes(Json.getStringList(defense, "avoidNamePatterns"), "behaviour.defense.avoidNamePatterns", errors);
    }

    private static void uniqueId(String id, String path, Set<String> seen, Map<String, String> errors) {
        if (!ID.matcher(id).matches()) {
            errors.putIfAbsent(path, "pattern");
        } else if (!seen.add(id.toLowerCase(Locale.ROOT))) {
            // Ids name directories; Windows file names are case-insensitive.
            errors.putIfAbsent(path, "duplicate");
        }
    }

    private static void regexes(List<String> patterns, String path, Map<String, String> errors) {
        for (String p : patterns) {
            try {
                Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                errors.putIfAbsent(path, "regex");
                return;
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
