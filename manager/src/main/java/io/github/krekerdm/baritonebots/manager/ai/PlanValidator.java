package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.refs.Refs;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks everything a model returns against the live catalog (SPEC §5.7c/d) before it can reach a queue: task
 * types, argument names, types and limits, references (waypoints, areas, bots), kits, keep profiles, schematics,
 * bots and the supervisor's action whitelist. Bad items are dropped with a reason code; nothing unvalidated is ever
 * returned. Pure (reads an {@link AiContext}); the run step calls it again on the live state.
 */
public final class PlanValidator {
    /** Raw Baritone commands cannot be checked; {@code recover} belongs to death recovery. */
    public static final Set<String> EXCLUDED = Set.of("baritone", "recover");
    public static final int MAX_STEPS = 20;
    public static final int MAX_ORDERS = 10;
    public static final int MAX_ACTIONS = 5;
    public static final int MAX_COUNT = 100_000;
    /** Supervisor actions (SPEC §5.7d); anything else is dropped. */
    public static final List<String> ACTIONS = List.of("set_priority", "reassign", "add_standing_order",
            "pause_project", "resume_project", "add_task", "notify");
    public static final String ANY = "any";

    private static final Pattern ITEM = Pattern.compile("#?[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern GLOB = Pattern.compile("#?[a-z0-9_.*-]+:[a-z0-9_./*-]+|\\*");
    private static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** Integer arguments that are coordinates (they must appear in the request text). */
    private static final Set<String> COORD_ARGS = Set.of("goto.x", "goto.y", "goto.z", "explore.x", "explore.z");

    private PlanValidator() {
    }

    /** A dropped item: reason code (i18n {@code ai.reason.<code>} in the panel) and a detail such as the argument. */
    public static final class Reject extends RuntimeException {
        private final String reason;
        private final String detail;

        public Reject(String reason, String detail) {
            super(reason + (detail == null ? "" : ": " + detail));
            this.reason = reason;
            this.detail = detail;
        }

        public String reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }
    }

    /** A validated plan: steps {@code {botId, step:{type,args}}}, standing orders, an optional project draft. */
    public record Plan(JsonArray steps, JsonArray orders, JsonObject project, String notes, JsonArray rejected) {
        public boolean empty() {
            return steps.isEmpty() && orders.isEmpty() && project == null;
        }

        public JsonObject toJson() {
            return Json.obj("plan", steps, "orders", orders, "project", project, "notes", notes, "rejected", rejected);
        }
    }

    // ------------------------------------------------------------------ plans (command box)

    /**
     * The model's plan ({@code {steps:[{bot,type,args}], orders, project, notes}}; the validated shape
     * {@code {plan:[{botId, step}]}} is accepted too, for re-checks before running).
     */
    public static Plan plan(JsonObject out, AiContext ctx) {
        JsonArray steps = new JsonArray();
        JsonArray orders = new JsonArray();
        JsonArray rejected = new JsonArray();
        JsonArray rawSteps = Json.getArr(out, "steps") != null ? Json.getArr(out, "steps") : Json.getArr(out, "plan");
        for (int i = 0; rawSteps != null && i < rawSteps.size(); i++) {
            JsonElement e = rawSteps.get(i);
            try {
                if (i >= MAX_STEPS) {
                    throw new Reject("too_many", String.valueOf(MAX_STEPS));
                }
                if (!e.isJsonObject()) {
                    throw new Reject("bad_step", null);
                }
                JsonObject s = e.getAsJsonObject();
                JsonObject inner = Json.getObj(s, "step");
                JsonObject src = inner != null ? inner : s;
                String bot = bot(firstString(s, "botId", "bot"), ctx, true);
                String server = ANY.equals(bot) ? ctx.defaultServer() : ctx.serverOf(bot);
                JsonObject tpl = step(Json.getString(src, "type", Json.getString(src, "step", null)),
                        Json.getObj(src, "args"), server, ctx);
                steps.add(Json.obj("botId", bot, "step", tpl));
            } catch (Reject r) {
                rejected.add(rejected("step", e, r));
            }
        }
        JsonArray rawOrders = Json.getArr(out, "orders");
        for (int i = 0; rawOrders != null && i < rawOrders.size(); i++) {
            JsonElement e = rawOrders.get(i);
            try {
                if (i >= MAX_ORDERS) {
                    throw new Reject("too_many", String.valueOf(MAX_ORDERS));
                }
                if (!e.isJsonObject()) {
                    throw new Reject("bad_order", null);
                }
                orders.add(order(e.getAsJsonObject(), ctx.defaultServer(), ctx));
            } catch (Reject r) {
                rejected.add(rejected("order", e, r));
            }
        }
        JsonObject project = null;
        JsonElement rawProject = out.get("project");
        if (rawProject != null && rawProject.isJsonObject() && !rawProject.getAsJsonObject().isEmpty()) {
            try {
                project = project(rawProject.getAsJsonObject(), ctx);
            } catch (Reject r) {
                rejected.add(rejected("project", rawProject, r));
            }
        }
        String notes = Json.getString(out, "notes", "");
        return new Plan(steps, orders, project, cut(notes == null ? "" : notes.trim(), 500), rejected);
    }

    static JsonObject rejected(String kind, JsonElement raw, Reject r) {
        return Json.obj("kind", kind, "what", cut(raw == null ? "" : Json.toJson(raw), 200), "reason", r.reason(),
                "detail", r.detail());
    }

    // ------------------------------------------------------------------ bots

    /**
     * Canonical bot id for a name the model used.
     *
     * @param anyOk whether {@code "any"} (the manager picks a free bot) is accepted
     */
    static String bot(String raw, AiContext ctx, boolean anyOk) {
        if (raw == null || raw.isBlank() || ANY.equalsIgnoreCase(raw.trim())) {
            if (anyOk) {
                return ANY;
            }
            throw new Reject("unknown_bot", raw == null ? "" : raw);
        }
        AiContext.Bot b = ctx.bot(raw);
        if (b == null) {
            throw new Reject("unknown_bot", raw);
        }
        if (!ctx.allowedBots().isEmpty() && !ctx.allowedBots().contains(b.id())) {
            throw new Reject("bot_not_allowed", b.id());
        }
        return b.id();
    }

    // ------------------------------------------------------------------ steps

    /**
     * One task template or manager step, normalised: known and allowed type, only declared arguments, values of the
     * declared type within limits, references that exist.
     */
    public static JsonObject step(String type, JsonObject rawArgs, String server, AiContext ctx) {
        TaskCatalog cat = ctx.catalog();
        if (type == null || type.isBlank() || !cat.isKnown(type.trim())) {
            throw new Reject("unknown_type", type);
        }
        type = type.trim();
        if (cat.isInternal(type) || EXCLUDED.contains(type) || !cat.isSupported(type)) {
            throw new Reject("not_allowed_type", type);
        }
        Map<String, JsonObject> defs = argDefs(cat.definition(type));
        JsonObject args = new JsonObject();
        if (rawArgs != null) {
            for (Map.Entry<String, JsonElement> e : rawArgs.entrySet()) {
                if (AiContext.blank(e.getValue())) {
                    continue;
                }
                JsonObject d = defs.get(e.getKey());
                if (d == null) {
                    throw new Reject("unknown_arg", e.getKey());
                }
                if ("text".equals(Json.getString(d, "type", ""))) {
                    throw new Reject("bad_arg", e.getKey()); // free text (craft grids) is never taken from a model
                }
                args.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        JsonObject norm;
        try {
            norm = cat.normalize(type, args);
        } catch (TaskCatalog.BadArgsException e) {
            throw new Reject("required".equals(e.code()) ? "missing_arg" : "bad_arg", e.arg());
        }
        for (String name : List.copyOf(norm.keySet())) {
            JsonObject d = defs.get(name);
            if (d != null) {
                norm.add(name, value(type, name, d, norm.get(name), server, ctx));
            }
        }
        return Json.obj("type", type, "args", norm);
    }

    private static Map<String, JsonObject> argDefs(JsonObject def) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        JsonArray args = def == null ? null : Json.getArr(def, "args");
        if (args != null) {
            args.forEach(a -> out.put(Json.getString(a.getAsJsonObject(), "name", ""), a.getAsJsonObject()));
        }
        return out;
    }

    /** One checked (and normalised) argument value; {@code owner} = task type or project kind. */
    private static JsonElement value(String owner, String name, JsonObject d, JsonElement v, String server,
                                     AiContext ctx) {
        String t = Json.getString(d, "type", "string");
        switch (t) {
            case "int", "double" -> {
                double n = number(v, name);
                if ("int".equals(t) && n != Math.rint(n)
                        || d.has("min") && n < d.get("min").getAsDouble()
                        || d.has("max") && n > d.get("max").getAsDouble()) {
                    throw new Reject("bad_arg", name);
                }
                if (COORD_ARGS.contains(owner + "." + name) && !ctx.numbers().contains((long) n)) {
                    throw new Reject("invented_coordinates", name);
                }
                return "int".equals(t) ? new JsonPrimitive((long) n) : new JsonPrimitive(n);
            }
            case "bool" -> {
                if (v.isJsonPrimitive() && (v.getAsJsonPrimitive().isBoolean()
                        || "true".equalsIgnoreCase(v.getAsString()) || "false".equalsIgnoreCase(v.getAsString()))) {
                    return new JsonPrimitive(Boolean.parseBoolean(v.getAsString()));
                }
                throw new Reject("bad_arg", name);
            }
            case "enum" -> {
                JsonArray allowed = Json.getArr(d, "enum");
                String s = v.isJsonPrimitive() ? v.getAsString() : "";
                if (allowed != null) {
                    for (JsonElement a : allowed) {
                        if (a.getAsString().equalsIgnoreCase(s)) {
                            return a.getAsJsonPrimitive().isNumber() || v.getAsJsonPrimitive().isNumber() && isLong(s)
                                    ? new JsonPrimitive(Long.parseLong(a.getAsString())) : new JsonPrimitive(a.getAsString());
                        }
                    }
                }
                throw new Reject("bad_arg", name);
            }
            case "dim" -> {
                String dim = v.isJsonPrimitive() ? Dims.normalize(v.getAsString()) : null;
                if (dim == null || !Dims.VANILLA.contains(dim)) {
                    throw new Reject("bad_arg", name);
                }
                return new JsonPrimitive(dim);
            }
            case "item" -> {
                return new JsonPrimitive(item(v, name, false));
            }
            case "ids", "items" -> {
                JsonArray out = new JsonArray();
                for (JsonElement e : list(v)) {
                    out.add(item(e, name, "items".equals(t)));
                }
                if (out.isEmpty()) {
                    throw new Reject("bad_arg", name);
                }
                return out;
            }
            case "item_counts" -> {
                if (!v.isJsonObject() || v.getAsJsonObject().isEmpty()) {
                    throw new Reject("bad_arg", name);
                }
                JsonObject out = new JsonObject();
                for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                    double n = number(e.getValue(), name);
                    if (n != Math.rint(n) || n < 1 || n > MAX_COUNT) {
                        throw new Reject("bad_arg", name);
                    }
                    out.addProperty(item(new JsonPrimitive(e.getKey()), name, true), (long) n);
                }
                return out;
            }
            case "pos", "container" -> {
                return place(v, name, server, ctx);
            }
            case "containers" -> {
                JsonArray out = new JsonArray();
                for (JsonElement e : list(v)) {
                    out.add(place(e, name, server, ctx));
                }
                if (out.isEmpty()) {
                    throw new Reject("bad_arg", name);
                }
                return out;
            }
            case "box" -> {
                return box(v, name, server, ctx);
            }
            case "player" -> {
                String p = v.isJsonPrimitive() ? v.getAsString().trim() : "";
                if (!PLAYER.matcher(p).matches()) {
                    throw new Reject("bad_arg", name);
                }
                return new JsonPrimitive(p);
            }
            default -> {
                return string(owner, name, v, server, ctx);
            }
        }
    }

    /** Plain string arguments that name something: kits, waypoints, keep profiles, schematics, animals, areas. */
    private static JsonElement string(String owner, String name, JsonElement v, String server, AiContext ctx) {
        if (!v.isJsonPrimitive()) {
            throw new Reject("bad_arg", name);
        }
        String s = v.getAsString().trim();
        String key = owner + "." + name;
        String out = switch (key) {
            case "kit.kitId" -> require(ctx.kit(s), "unknown_kit", s);
            case "goto_waypoint.name" -> require(ctx.waypoint(server, s), "unknown_waypoint", s);
            case "trash.profile" -> require(ctx.profile(s), "unknown_profile", s);
            case "build.file", "build.schematic" -> require(ctx.schematic(s), "unknown_schematic", s);
            case "breed.animal", "slaughter.animal", "ranch.animal" -> item(v, name, false);
            case "clear.area", "farm.area", "ranch.area" -> require(ctx.area(server, s), "unknown_area", s);
            case "gather.into" -> {
                if (!SettingsSchema.ORDER_ROLES.contains(s) && !SettingsSchema.ROLES.contains(s)
                        && !(s.startsWith("sorted:") && ctx.hasCategory(s.substring(7)))) {
                    throw new Reject("bad_arg", name);
                }
                yield s;
            }
            default -> {
                if (s.length() > 80) {
                    throw new Reject("bad_arg", name);
                }
                yield s;
            }
        };
        return new JsonPrimitive(out);
    }

    private static String require(String found, String reason, String detail) {
        if (found == null) {
            throw new Reject(reason, detail);
        }
        return found;
    }

    /** A position: a reference that exists, or literal coordinates the request itself contains. */
    static JsonElement place(JsonElement v, String arg, String server, AiContext ctx) {
        if (Refs.isRef(v)) {
            JsonObject r = v.getAsJsonObject();
            String kind = Json.getString(r, "ref", "");
            if (!Refs.POS_KINDS.contains(kind)) {
                throw new Reject("bad_ref", arg);
            }
            JsonObject out = Json.obj("ref", kind);
            if (Refs.WAYPOINT.equals(kind)) {
                String name = Json.getString(r, "name", "");
                out.addProperty("name", require(ctx.waypoint(server, name), "unknown_waypoint", name));
            } else if (Refs.BOT.equals(kind)) {
                String id = Json.getString(r, "id", Json.getString(r, "name", ""));
                AiContext.Bot b = ctx.bot(id);
                if (b == null) {
                    throw new Reject("unknown_bot", id);
                }
                out.addProperty("id", b.id());
            }
            return out;
        }
        if (v.isJsonObject() && v.getAsJsonObject().has("x") && v.getAsJsonObject().has("z")) {
            JsonObject o = v.getAsJsonObject();
            JsonObject out = new JsonObject();
            Set<Long> said = ctx.numbers();
            for (String axis : List.of("x", "y", "z")) {
                if (!o.has(axis)) {
                    throw new Reject("bad_ref", arg);
                }
                double n = number(o.get(axis), arg);
                if (n != Math.rint(n) || !said.contains((long) n)) {
                    throw new Reject("invented_coordinates", arg);
                }
                out.addProperty(axis, (long) n);
            }
            return out;
        }
        throw new Reject("bad_ref", arg);
    }

    /** A box: area / auto reference, or two positions. */
    static JsonElement box(JsonElement v, String arg, String server, AiContext ctx) {
        if (Refs.isRef(v)) {
            JsonObject r = v.getAsJsonObject();
            String kind = Json.getString(r, "ref", "");
            if (Refs.AREA.equals(kind)) {
                String name = Json.getString(r, "name", "");
                return Json.obj("ref", Refs.AREA, "name", require(ctx.area(server, name), "unknown_area", name));
            }
            if (Refs.AUTO.equals(kind)) {
                return Json.obj("ref", Refs.AUTO);
            }
            throw new Reject("bad_ref", arg);
        }
        if (v.isJsonObject() && v.getAsJsonObject().has("a") && v.getAsJsonObject().has("b")) {
            return Json.obj("a", place(v.getAsJsonObject().get("a"), arg, server, ctx),
                    "b", place(v.getAsJsonObject().get("b"), arg, server, ctx));
        }
        if (v.isJsonArray() && v.getAsJsonArray().size() == 2) {
            return Json.obj("a", place(v.getAsJsonArray().get(0), arg, server, ctx),
                    "b", place(v.getAsJsonArray().get(1), arg, server, ctx));
        }
        throw new Reject("bad_ref", arg);
    }

    // ------------------------------------------------------------------ standing orders

    /** {@code {item, min, max?, into}} → a standing order for {@code server} (SPEC §5.7b). */
    public static JsonObject order(JsonObject o, String server, AiContext ctx) {
        String item = item(o.get("item"), "item", false);
        if (item.startsWith("#")) {
            throw new Reject("bad_item", item);
        }
        double min = number(o.get("min"), "min");
        if (min != Math.rint(min) || min < 1 || min > MAX_COUNT) {
            throw new Reject("bad_order", "min");
        }
        JsonObject out = Json.obj("item", item, "min", (long) min);
        JsonElement maxEl = o.get("max");
        if (!AiContext.blank(maxEl)) {
            double max = number(maxEl, "max");
            if (max != 0 && (max != Math.rint(max) || max < min || max > MAX_COUNT)) {
                throw new Reject("bad_order", "max");
            }
            if (max != 0) {
                out.addProperty("max", (long) max);
            }
        }
        String into = Json.getString(o, "into", "storage").trim();
        if (into.isEmpty()) {
            into = "storage";
        }
        if (!SettingsSchema.ORDER_ROLES.contains(into) && !(into.startsWith("sorted:") && ctx.hasCategory(into.substring(7)))) {
            throw new Reject("bad_order", "into");
        }
        out.addProperty("into", into);
        if (server == null) {
            throw new Reject("bad_order", "serverId");
        }
        out.addProperty("serverId", server);
        return out;
    }

    // ------------------------------------------------------------------ project drafts

    /** {@code {kind, name?, bots, config}} → a project draft (still resolved and fully validated when it runs). */
    public static JsonObject project(JsonObject p, AiContext ctx) {
        String kind = Json.getString(p, "kind", "").trim();
        JsonObject kindDef = null;
        JsonArray kinds = Json.getArr(ctx.catalog().json(), "projectKinds");
        for (int i = 0; kinds != null && i < kinds.size(); i++) {
            JsonObject k = kinds.get(i).getAsJsonObject();
            if (kind.equals(Json.getString(k, "kind", "")) && Json.getBool(k, "supported", true)) {
                kindDef = k;
            }
        }
        if (kindDef == null) {
            throw new Reject("unknown_kind", kind);
        }
        Map<String, JsonObject> defs = argDefs(kindDef);
        if ("build".equals(kind)) {
            defs.put("schematic", Json.obj("name", "schematic", "type", "string", "required", true));
        }
        // bots first: the server follows the bots
        JsonElement bots;
        JsonElement rawBots = p.get("bots");
        String server = null;
        if (rawBots != null && rawBots.isJsonArray() && !rawBots.getAsJsonArray().isEmpty()) {
            JsonArray ids = new JsonArray();
            for (JsonElement e : rawBots.getAsJsonArray()) {
                String id = bot(e.isJsonPrimitive() ? e.getAsString() : null, ctx, false);
                if (!ids.contains(new JsonPrimitive(id))) {
                    ids.add(id);
                }
                server = server == null ? ctx.serverOf(id) : server;
            }
            bots = ids;
        } else if (!ctx.allowedBots().isEmpty()) {
            bots = Json.arrOf(ctx.allowedBots().stream().sorted().toList());
        } else {
            bots = new JsonPrimitive(ANY);
        }
        if (server == null) {
            server = ctx.defaultServer();
        }
        if (server == null) {
            throw new Reject("bad_project", "serverId");
        }
        JsonObject raw = Json.getObj(p, "config");
        JsonObject config = new JsonObject();
        if (raw != null) {
            for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
                if (AiContext.blank(e.getValue())) {
                    continue;
                }
                JsonObject d = defs.get(e.getKey());
                if (d == null) {
                    throw new Reject("unknown_arg", e.getKey());
                }
                config.add(e.getKey(), value(kind, e.getKey(), d, e.getValue(), server, ctx));
            }
        }
        for (JsonObject d : defs.values()) {
            String n = Json.getString(d, "name", "");
            if (!config.has(n) && Json.getBool(d, "required", false)) {
                if ("origin".equals(n)) {
                    config.add(n, Json.obj("ref", Refs.AUTO)); // nearest flat spot near home (SPEC §5.7e)
                } else if (d.has("default")) {
                    config.add(n, d.get("default").deepCopy());
                } else {
                    throw new Reject("missing_arg", n);
                }
            }
        }
        String name = Json.getString(p, "name", "").trim();
        if (name.isEmpty()) {
            String file = Json.getString(config, "schematic", null);
            name = file != null ? (file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file) : kind;
        }
        return Json.obj("name", cut(name, 80), "kind", kind, "serverId", server, "bots", bots, "config", config);
    }

    // ------------------------------------------------------------------ supervisor actions

    /**
     * One supervisor action from the whitelist, with its arguments checked against the project and the live state.
     *
     * @param server      the project's server
     * @param projectBots the bots the project may use (null = any bot of the server)
     * @return {@code {type, args}}
     */
    public static JsonObject action(JsonObject a, AiContext ctx, String server, List<String> projectBots) {
        String type = Json.getString(a, "type", "").trim();
        if (!ACTIONS.contains(type)) {
            throw new Reject("unknown_action", type);
        }
        JsonObject args = switch (type) {
            case "set_priority" -> {
                double n = number(a.get("priority"), "priority");
                if (n != Math.rint(n) || n < 0 || n > 100) {
                    throw new Reject("bad_priority", String.valueOf(n));
                }
                yield Json.obj("priority", (long) n);
            }
            case "reassign" -> {
                String bot = projectBot(Json.getString(a, "bot", null), ctx, server, projectBots);
                String role = Json.getString(a, "role", "").trim();
                if (!SettingsSchema.ROLES.contains(role)) {
                    throw new Reject("unknown_role", role);
                }
                List<String> roles = ctx.bot(bot).roles();
                if (roles != null && !roles.isEmpty() && !roles.contains(role)) {
                    throw new Reject("role_not_allowed", bot + " → " + role);
                }
                yield Json.obj("bot", bot, "role", role);
            }
            case "add_standing_order" -> order(a, server, ctx);
            case "pause_project", "resume_project" -> new JsonObject();
            case "add_task" -> {
                String bot = projectBot(Json.getString(a, "bot", null), ctx, server, projectBots);
                JsonObject s = Json.getObj(a, "step");
                if (s == null) {
                    throw new Reject("missing_arg", "step");
                }
                yield Json.obj("bot", bot, "step", step(Json.getString(s, "type", null), Json.getObj(s, "args"), server, ctx));
            }
            case "notify" -> {
                String text = Json.getString(a, "text", "").trim();
                if (text.isEmpty()) {
                    throw new Reject("missing_arg", "text");
                }
                yield Json.obj("text", cut(text, 300));
            }
            default -> throw new Reject("unknown_action", type);
        };
        return Json.obj("type", type, "args", args);
    }

    private static String projectBot(String raw, AiContext ctx, String server, List<String> projectBots) {
        String bot = bot(raw, ctx, false);
        AiContext.Bot b = ctx.bot(bot);
        if (server != null && !server.equalsIgnoreCase(String.valueOf(b.serverId()))
                || projectBots != null && projectBots.stream().noneMatch(x -> x.equalsIgnoreCase(bot))) {
            throw new Reject("bot_not_allowed", bot);
        }
        return bot;
    }

    // ------------------------------------------------------------------ helpers

    private static String item(JsonElement v, String arg, boolean glob) {
        String s = v != null && v.isJsonPrimitive() ? v.getAsString().trim() : "";
        String id = glob && "*".equals(s) ? "*" : Ids.normalize(s);
        if (id == null || id.isEmpty() || !(glob ? GLOB : ITEM).matcher(id).matches()) {
            throw new Reject("bad_item", s.isEmpty() ? arg : s);
        }
        return id;
    }

    private static List<JsonElement> list(JsonElement v) {
        if (v.isJsonArray()) {
            return v.getAsJsonArray().asList();
        }
        if (v.isJsonPrimitive()) {
            return java.util.Arrays.stream(v.getAsString().split("[,\\s]+")).filter(s -> !s.isBlank())
                    .map(s -> (JsonElement) new JsonPrimitive(s)).toList();
        }
        return List.of(v);
    }

    private static double number(JsonElement v, String arg) {
        if (v != null && v.isJsonPrimitive()) {
            try {
                double d = Double.parseDouble(v.getAsString().trim());
                if (Double.isFinite(d)) {
                    return d;
                }
            } catch (NumberFormatException ignored) {
                // below
            }
        }
        throw new Reject("bad_arg", arg);
    }

    private static boolean isLong(String s) {
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = Json.getString(o, k, null);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
