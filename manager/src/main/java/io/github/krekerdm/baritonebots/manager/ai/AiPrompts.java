package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * System prompts and output schemas (Ollama {@code format}) built from the live catalog (SPEC §5.7c/d). The prompts
 * are compact on purpose: a 7B model reads every token of them on every request.
 */
final class AiPrompts {
    private AiPrompts() {
    }

    // ------------------------------------------------------------------ command box

    static String planSystem(AiContext ctx) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("""
                You turn a request from the owner of Minecraft bots into one JSON object for the BaritoneBots manager. \
                The manager checks it and the owner confirms it before anything runs.
                Rules:
                - Prefer high-level work: "obtain" (one bot gets an item and crafts, smelts and mines the whole chain \
                itself), "progress" (tools and armor of a tier), standing orders (keep an item in stock), projects \
                (work shared by several bots). Use low-level bot tasks only when the request asks for exactly that.
                - Use only the types, argument names, bots, waypoints, areas, kits, profiles and schematics listed \
                below. Item ids look like minecraft:iron_ingot (ingots, not ores, when the owner wants metal).
                - Never invent coordinates. Positions are references: {"ref":"home"}, {"ref":"waypoint","name":W}, \
                {"ref":"owner"} (where the owner stands), {"ref":"owner_look"} (block the owner looks at), \
                {"ref":"bot","id":B}, {"ref":"auto"} (found automatically: build site near home, farm field, pen). \
                Boxes: {"ref":"area","name":A} or {"ref":"auto"}.
                - "bot": a bot id, or "any" for one free bot the manager picks; all "any" steps go to that one bot, \
                in order. "All bots" in a project = "bots":"any".
                - When the request cannot be done with this catalog, return empty lists and explain in "notes".
                """);
        sb.append("- Write \"notes\" in ").append(ctx.languageName()).append(", one short sentence or empty.\n");
        TaskCatalog cat = ctx.catalog();
        JsonObject json = cat.json();
        sb.append("\nBot tasks:\n");
        for (JsonElement t : Json.getArr(json, "tasks")) {
            String type = Json.getString(t.getAsJsonObject(), "type", "");
            if (allowed(cat, type)) {
                sb.append(signature(type, t.getAsJsonObject())).append('\n');
            }
        }
        sb.append("\nManager steps (high level first):\n");
        List<String> steps = new ArrayList<>();
        for (JsonElement s : Json.getArr(json, "managerSteps")) {
            String step = Json.getString(s.getAsJsonObject(), "step", "");
            if (allowed(cat, step)) {
                String sig = signature(step, s.getAsJsonObject());
                if ("obtain".equals(step) || "progress".equals(step)) {
                    steps.addFirst(sig);
                } else {
                    steps.add(sig);
                }
            }
        }
        steps.forEach(s -> sb.append(s).append('\n'));
        sb.append("\nProject kinds (\"config\" keys):\n");
        for (JsonElement k : Json.getArr(json, "projectKinds")) {
            JsonObject o = k.getAsJsonObject();
            String kind = Json.getString(o, "kind", "");
            String sig = signature(kind, o);
            if ("build".equals(kind)) {
                sig = sig.replaceFirst("\\(", "(schematic*:file, ");
            }
            sb.append(sig).append('\n');
        }
        sb.append("""
                Standing order: {"item":ID,"min":N,"max":N or omitted,"into":"storage|supply|kit|fuel|inbox|sorted:<category>"}
                Types: pos = position reference, box = box reference, containers = list of position references, \
                item = item id, ids/items = list of ids, item_counts = {"id":count}; * = required.
                """);
        context(sb, ctx);
        sb.append("""

                Examples:
                «развиться до железки» -> {"steps":[{"bot":"any","type":"progress","args":{"tier":"iron"}}],"orders":[],"project":null,"notes":""}
                "get 64 iron and put it in storage" -> {"steps":[{"bot":"any","type":"obtain","args":{"item":"minecraft:iron_ingot","count":64}},{"bot":"any","type":"deposit_storage","args":{}}],"orders":[],"project":null,"notes":""}
                «построй замок из castle.schem у дома всеми ботами» -> {"steps":[],"orders":[],"project":{"kind":"build","name":"castle","bots":"any","config":{"schematic":"castle.schem","origin":{"ref":"auto"},"rotation":0}},"notes":""}
                «всегда держи 128 факелов на складе» -> {"steps":[],"orders":[{"item":"minecraft:torch","min":128,"into":"storage"}],"project":null,"notes":""}
                """);
        return sb.toString();
    }

    static JsonObject planSchema(AiContext ctx) {
        JsonArray types = new JsonArray();
        TaskCatalog cat = ctx.catalog();
        JsonObject json = cat.json();
        for (JsonElement t : Json.getArr(json, "tasks")) {
            String type = Json.getString(t.getAsJsonObject(), "type", "");
            if (allowed(cat, type)) {
                types.add(type);
            }
        }
        for (JsonElement s : Json.getArr(json, "managerSteps")) {
            String step = Json.getString(s.getAsJsonObject(), "step", "");
            if (allowed(cat, step)) {
                types.add(step);
            }
        }
        JsonArray kinds = new JsonArray();
        Json.getArr(json, "projectKinds").forEach(k -> kinds.add(Json.getString(k.getAsJsonObject(), "kind", "")));
        JsonArray bots = new JsonArray();
        bots.add(PlanValidator.ANY);
        ctx.usableBots().forEach(b -> bots.add(b.id()));
        JsonArray botIds = new JsonArray();
        ctx.usableBots().forEach(b -> botIds.add(b.id()));
        JsonObject step = object(Json.obj("bot", Json.obj("type", "string", "enum", bots),
                "type", Json.obj("type", "string", "enum", types), "args", Json.obj("type", "object")),
                "bot", "type", "args");
        JsonObject order = object(Json.obj("item", str(), "min", Json.obj("type", "integer"),
                "max", Json.obj("type", "integer"), "into", str()), "item", "min", "into");
        JsonObject projectBots = botIds.isEmpty() ? Json.obj("type", "string", "enum", Json.arr(PlanValidator.ANY))
                : Json.obj("anyOf", Json.arr(Json.obj("type", "string", "enum", Json.arr(PlanValidator.ANY)),
                Json.obj("type", "array", "items", Json.obj("type", "string", "enum", botIds))));
        JsonObject project = object(Json.obj("kind", Json.obj("type", "string", "enum", kinds), "name", str(),
                "bots", projectBots, "config", Json.obj("type", "object")), "kind", "bots", "config");
        return object(Json.obj("steps", Json.obj("type", "array", "items", step),
                "orders", Json.obj("type", "array", "items", order),
                "project", Json.obj("anyOf", Json.arr(Json.obj("type", "null"), project)),
                "notes", str()), "steps", "orders", "project", "notes");
    }

    // ------------------------------------------------------------------ supervisor

    static String superviseSystem(AiContext ctx) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("""
                You supervise one BaritoneBots project. A deterministic planner already gives the bots work every \
                few seconds; you only step in like a foreman. The user message is a JSON digest of the project.
                Answer with one JSON object:
                - "summary": one or two short sentences for the owner: progress %, who waits for what, what is \
                missing and where it comes from, ETA. No greetings.
                - "actions": at most 3, only when they clearly help, each with a short "reason"; usually [].
                Allowed actions (nothing else):
                - set_priority {"priority":0..100} project priority (default 5; higher gets bots before other projects)
                - reassign {"bot","role"} the bot works in that role for a while
                - add_standing_order {"item","min","max"?,"into"} keep an item in stock, e.g. a missing material
                - pause_project {} / resume_project {}
                - add_task {"bot","step":{"type","args"}} one step for one bot; prefer {"type":"obtain","args":{"item","count"}}
                - notify {"text"} something only a person can fix, e.g. materials marked manual
                Never invent bots, items or coordinates; use ids from the digest.
                """);
        sb.append("Write the summary, reasons and notify texts in ").append(ctx.languageName()).append(".\n");
        sb.append("Roles: ").append(String.join(", ", SettingsSchema.ROLES)).append('\n');
        sb.append("Step types for add_task: ").append(String.join(", ", List.of("obtain", "progress", "deposit_storage",
                "smelt_all", "sort_storage", "home", "kit", "mine", "goto_waypoint"))).append('\n');
        bots(sb, ctx);
        return sb.toString();
    }

    static JsonObject superviseSchema(AiContext ctx) {
        JsonArray roles = Json.arrOf(SettingsSchema.ROLES);
        JsonObject step = Json.obj("type", "object", "properties",
                Json.obj("type", str(), "args", Json.obj("type", "object")), "required", Json.arr("type", "args"));
        JsonObject action = object(Json.obj(
                "type", Json.obj("type", "string", "enum", Json.arrOf(PlanValidator.ACTIONS)),
                "reason", str(), "bot", str(), "role", Json.obj("type", "string", "enum", roles),
                "priority", Json.obj("type", "integer"), "item", str(), "min", Json.obj("type", "integer"),
                "max", Json.obj("type", "integer"), "into", str(), "step", step, "text", str()), "type", "reason");
        return object(Json.obj("summary", str(), "actions", Json.obj("type", "array", "items", action)),
                "summary", "actions");
    }

    // ------------------------------------------------------------------ helpers

    static boolean allowed(TaskCatalog cat, String type) {
        return cat.isSupported(type) && !cat.isInternal(type) && !PlanValidator.EXCLUDED.contains(type);
    }

    /** {@code obtain(item*:item, count:int 1..2304=1)}. */
    static String signature(String name, JsonObject def) {
        List<String> parts = new ArrayList<>();
        JsonArray args = Json.getArr(def, "args");
        for (int i = 0; args != null && i < args.size(); i++) {
            JsonObject a = args.get(i).getAsJsonObject();
            String type = Json.getString(a, "type", "string");
            if ("text".equals(type)) {
                continue;
            }
            StringBuilder p = new StringBuilder(Json.getString(a, "name", ""));
            if (Json.getBool(a, "required", false) && !a.has("default")) {
                p.append('*');
            }
            JsonArray en = Json.getArr(a, "enum");
            if (en != null) {
                List<String> vals = new ArrayList<>();
                en.forEach(e -> vals.add(e.getAsString()));
                p.append(':').append(String.join("|", vals));
            } else {
                p.append(':').append(type);
                if (a.has("min") && a.has("max")) {
                    p.append(' ').append(a.get("min").getAsString()).append("..").append(a.get("max").getAsString());
                }
            }
            if (a.has("default") && !a.get("default").isJsonArray()) {
                p.append('=').append(a.get("default").getAsString());
            }
            parts.add(p.toString());
        }
        return name + "(" + String.join(", ", parts) + ")";
    }

    private static void context(StringBuilder sb, AiContext ctx) {
        sb.append("\nRoles: ").append(String.join(", ", SettingsSchema.ROLES)).append('\n');
        bots(sb, ctx);
        for (String server : ctx.servers()) {
            String prefix = ctx.servers().size() > 1 ? " (" + server + ")" : "";
            sb.append("Waypoints").append(prefix).append(": ").append(list(ctx.waypoints().get(server))).append('\n');
            sb.append("Areas").append(prefix).append(": ").append(list(ctx.areas().get(server))).append('\n');
        }
        List<String> kits = new ArrayList<>();
        for (Map.Entry<String, String> e : ctx.kits().entrySet()) {
            kits.add(e.getValue().isBlank() || e.getValue().equalsIgnoreCase(e.getKey()) ? e.getKey()
                    : e.getKey() + " (" + e.getValue() + ")");
        }
        sb.append("Kits (kitId): ").append(list(kits)).append('\n');
        sb.append("Keep profiles: ").append(list(ctx.profiles())).append('\n');
        sb.append("Schematics: ").append(list(ctx.schematics())).append('\n');
        sb.append("Categories: ").append(list(ctx.categories())).append('\n');
        if (ctx.owner() != null) {
            sb.append("Owner player: ").append(ctx.owner()).append('\n');
        }
    }

    private static void bots(StringBuilder sb, AiContext ctx) {
        List<String> lines = new ArrayList<>();
        for (AiContext.Bot b : ctx.usableBots()) {
            lines.add(b.id() + (b.username() != null && !b.username().equalsIgnoreCase(b.id()) ? " \"" + b.username() + "\"" : "")
                    + (b.online() ? " online" : " offline")
                    + (b.roles() == null || b.roles().isEmpty() ? "" : " roles=" + String.join("|", b.roles()))
                    + (b.task() != null ? " busy:" + b.task() : ""));
        }
        sb.append("Bots: ").append(lines.isEmpty() ? "none" : String.join("; ", lines)).append('\n');
    }

    private static String list(List<String> l) {
        return l == null || l.isEmpty() ? "none" : String.join(", ", l);
    }

    private static JsonObject str() {
        return Json.obj("type", "string");
    }

    private static JsonObject object(JsonObject properties, String... required) {
        return Json.obj("type", "object", "properties", properties, "required", Json.arr((Object[]) required));
    }
}
