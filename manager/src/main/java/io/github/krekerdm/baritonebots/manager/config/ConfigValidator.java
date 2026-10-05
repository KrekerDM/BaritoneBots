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

        noCoordinates(root, errors);

        JsonArray categories = Json.getArr(Json.getObj(root, "autopilot"), "categories");
        Set<String> catNames = new HashSet<>();
        for (int i = 0; categories != null && i < categories.size(); i++) {
            if (categories.get(i).isJsonObject()) {
                String name = Json.getString(categories.get(i).getAsJsonObject(), "name", "");
                if (!CATEGORY.matcher(name).matches()) {
                    errors.putIfAbsent("autopilot.categories[" + i + "].name", "pattern");
                } else if (!catNames.add(name)) {
                    errors.putIfAbsent("autopilot.categories[" + i + "].name", "duplicate");
                }
            }
        }
        Set<String> orderIds = new HashSet<>();
        JsonArray orders = Json.getArr(root, "orders");
        for (int i = 0; orders != null && i < orders.size(); i++) {
            if (!orders.get(i).isJsonObject()) {
                continue;
            }
            JsonObject o = orders.get(i).getAsJsonObject();
            String p = "orders[" + i + "]";
            uniqueId(Json.getString(o, "id", ""), p + ".id", orderIds, errors);
            if (!serverIds.contains(Json.getString(o, "serverId", "").toLowerCase(Locale.ROOT))) {
                errors.putIfAbsent(p + ".serverId", "unknown_server");
            }
            if (!ITEM.matcher(Json.getString(o, "item", "")).matches()) {
                errors.putIfAbsent(p + ".item", "pattern");
            }
            JsonElement max = o.get("max");
            if (max != null && max.isJsonPrimitive() && max.getAsJsonPrimitive().isNumber()
                    && max.getAsInt() < Json.getInt(o, "min", 0)) {
                errors.putIfAbsent(p + ".max", "range");
            }
            String into = Json.getString(o, "into", "");
            if (!validInto(into)) {
                errors.putIfAbsent(p + ".into", "pattern");
            }
        }
        automation(root, serverIds, botIds, errors);
        if (!validEndpoint(Json.getString(Json.getObj(root, "ai"), "endpoint", ""))) {
            errors.putIfAbsent("ai.endpoint", "pattern");
        }
    }

    /** {@code ai.endpoint}: an http(s) URL with a host and no query or fragment (SPEC §5.7c). */
    public static boolean validEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        try {
            java.net.URI u = new java.net.URI(endpoint.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null && u.getQuery() == null
                    && u.getFragment() == null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** Container roles a sign word may stand for (besides {@code sorted:<category>}). */
    public static final List<String> SIGN_ROLES = List.of("storage", "inbox", "kit", "supply", "fuel", "trash");
    /** Tool kinds a keep profile may list. */
    public static final List<String> TOOL_KINDS = List.of("pickaxe", "axe", "shovel", "hoe", "sword", "shears");

    /** Owner commands, sign words, auto-trash and keep profiles (SPEC §5.7e, §5.7f). */
    private static void noCoordinates(JsonObject root, Map<String, String> errors) {
        JsonObject general = Json.getObj(root, "general");
        String prefix = Json.getString(general, "commandPrefix", "!b");
        if (prefix.isBlank() || prefix.length() > 8 || prefix.chars().anyMatch(Character::isWhitespace)) {
            errors.putIfAbsent("general.commandPrefix", "pattern");
        }
        regexes(Json.getStringList(general, "ownerChatPatterns"), "general.ownerChatPatterns", errors);
        String reply = Json.getString(general, "ownerReplyCommand", "/msg {player} {text}");
        if (!reply.startsWith("/") || !reply.contains("{player}") || !reply.contains("{text}")) {
            errors.putIfAbsent("general.ownerReplyCommand", "pattern");
        }
        JsonObject autopilot = Json.getObj(root, "autopilot");
        JsonObject words = Json.getObj(autopilot, "signWords");
        if (words != null) {
            for (Map.Entry<String, JsonElement> e : words.entrySet()) {
                String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString().trim() : "";
                boolean ok = SIGN_ROLES.contains(v) || CATEGORY.matcher(v.startsWith("sorted:") ? v.substring(7) : v).matches();
                if (e.getKey().isBlank() || e.getKey().length() > 32 || !ok) {
                    errors.putIfAbsent("autopilot.signWords." + e.getKey(), "pattern");
                }
            }
        }
        JsonObject keep = Json.getObj(Json.getObj(autopilot, "autoTrash"), "keepCounts");
        if (keep != null) {
            for (Map.Entry<String, JsonElement> e : keep.entrySet()) {
                if (!isNonNegativeInt(e.getValue())) {
                    errors.putIfAbsent("autopilot.autoTrash.keepCounts." + e.getKey(), "range");
                }
            }
        }
        JsonElement profiles = root.get("keepProfiles");
        if (profiles == null || profiles.isJsonNull()) {
            return;
        }
        if (!profiles.isJsonObject()) {
            errors.putIfAbsent("keepProfiles", "type");
            return;
        }
        for (Map.Entry<String, JsonElement> e : profiles.getAsJsonObject().entrySet()) {
            String p = "keepProfiles." + e.getKey();
            if (e.getKey().isBlank() || e.getKey().length() > 32) {
                errors.putIfAbsent(p, "pattern");
            }
            if (!e.getValue().isJsonObject()) {
                errors.putIfAbsent(p, "type");
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            if (!List.of("worn", "none").contains(Json.getString(o, "armor", "worn"))) {
                errors.putIfAbsent(p + ".armor", "enum");
            }
            if (!List.of("best", "none").contains(Json.getString(o, "weapon", "best"))) {
                errors.putIfAbsent(p + ".weapon", "enum");
            }
            JsonElement tools = o.get("tools");
            if (tools != null && !tools.isJsonNull()) {
                if (!tools.isJsonArray()) {
                    errors.putIfAbsent(p + ".tools", "type");
                } else {
                    for (JsonElement t : tools.getAsJsonArray()) {
                        if (!t.isJsonPrimitive() || !TOOL_KINDS.contains(t.getAsString())) {
                            errors.putIfAbsent(p + ".tools", "enum");
                        }
                    }
                }
            }
            JsonObject food = Json.getObj(o, "food");
            if (food != null && !isNonNegativeInt(food.get("max"))) {
                errors.putIfAbsent(p + ".food.max", "range");
            }
            JsonObject blocks = Json.getObj(o, "blocks");
            if (blocks != null && blocks.has("count") && !isNonNegativeInt(blocks.get("count"))) {
                errors.putIfAbsent(p + ".blocks.count", "range");
            }
        }
    }

    private static boolean isNonNegativeInt(JsonElement v) {
        try {
            return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() >= 0
                    && v.getAsDouble() == Math.rint(v.getAsDouble()) && v.getAsDouble() <= 100_000;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog catalog;

    /** The task catalog for step validation (loaded once from the classpath). */
    private static synchronized io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog catalog() {
        if (catalog == null) {
            catalog = io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog.load();
        }
        return catalog;
    }

    /** Schedules and rules (SPEC §5.7b) plus the servers' day / night times. */
    private static void automation(JsonObject root, Set<String> serverIds, Set<String> botIds, Map<String, String> errors) {
        JsonArray servers = Json.getArr(root, "servers");
        for (int i = 0; servers != null && i < servers.size(); i++) {
            if (servers.get(i).isJsonObject()) {
                for (String key : List.of("dayStart", "nightStart")) {
                    String v = Json.getString(servers.get(i).getAsJsonObject(), key, "07:00");
                    if (io.github.krekerdm.baritonebots.manager.automation.DayNight.parse(v) == null) {
                        errors.putIfAbsent("servers[" + i + "]." + key, "pattern");
                    }
                }
            }
        }
        Set<String> ids = new HashSet<>();
        JsonArray schedules = Json.getArr(root, "schedules");
        for (int i = 0; schedules != null && i < schedules.size(); i++) {
            if (!schedules.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = schedules.get(i).getAsJsonObject();
            String p = "schedules[" + i + "]";
            uniqueId(Json.getString(s, "id", ""), p + ".id", ids, errors);
            serverRef(s, p, serverIds, errors);
            String when = Json.getString(s, "when", "").trim();
            if (!"day".equalsIgnoreCase(when) && !"night".equalsIgnoreCase(when)
                    && !io.github.krekerdm.baritonebots.manager.automation.Cron.valid(when)) {
                errors.putIfAbsent(p + ".when", "cron");
            }
            targetAndSteps(s, "steps", p, botIds, errors);
        }
        ids.clear();
        JsonArray rules = Json.getArr(root, "rules");
        for (int i = 0; rules != null && i < rules.size(); i++) {
            if (!rules.get(i).isJsonObject()) {
                continue;
            }
            JsonObject r = rules.get(i).getAsJsonObject();
            String p = "rules[" + i + "]";
            uniqueId(Json.getString(r, "id", ""), p + ".id", ids, errors);
            serverRef(r, p, serverIds, errors);
            JsonElement cond = r.get("if");
            try {
                io.github.krekerdm.baritonebots.manager.automation.Trigger.parse(
                        cond != null && cond.isJsonObject() ? cond.getAsJsonObject() : null);
            } catch (IllegalArgumentException e) {
                String[] pc = String.valueOf(e.getMessage()).split(":", 2);
                errors.putIfAbsent(p + ".if" + (pc[0].isEmpty() ? "" : "." + pc[0]), pc.length > 1 ? pc[1] : "type");
            }
            targetAndSteps(r, "then", p, botIds, errors);
        }
    }

    private static void serverRef(JsonObject o, String p, Set<String> serverIds, Map<String, String> errors) {
        String server = Json.getString(o, "serverId", null);
        if (server != null && !server.isBlank() && !serverIds.contains(server.toLowerCase(Locale.ROOT))) {
            errors.putIfAbsent(p + ".serverId", "unknown_server");
        }
    }

    private static void targetAndSteps(JsonObject o, String stepsKey, String p, Set<String> botIds,
                                       Map<String, String> errors) {
        String bad = io.github.krekerdm.baritonebots.manager.automation.StepRunner.checkTarget(o.get("botIds"), botIds);
        if (bad != null) {
            errors.putIfAbsent(p + ".botIds", bad);
        }
        String steps = io.github.krekerdm.baritonebots.manager.automation.StepRunner.checkSteps(catalog(), o.get(stepsKey));
        if (steps != null) {
            String[] pc = steps.split(":", 2);
            errors.putIfAbsent(p + "." + stepsKey + pc[0], pc[1]);
        }
    }

    /** Category names: lowercase ids usable in {@code sorted:<name>}. */
    public static final Pattern CATEGORY = Pattern.compile("[a-z0-9_]{1,32}");
    /** An item id, with or without namespace. */
    public static final Pattern ITEM = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    /** {@code into} of a standing order: a container role, {@code sorted:<category>} or a container id. */
    public static boolean validInto(String into) {
        if (into == null || into.isBlank()) {
            return false;
        }
        if (SettingsSchema.ORDER_ROLES.contains(into)) {
            return true;
        }
        if (into.startsWith("sorted:")) {
            return CATEGORY.matcher(into.substring("sorted:".length())).matches();
        }
        return ID.matcher(into).matches();
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
