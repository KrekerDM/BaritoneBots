package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import java.util.List;

/**
 * One project (SPEC §5.7), persisted as {@code projects/<id>.json}. Loop-owned and mutable; {@link #runtime} is the
 * live part while the project runs (or is paused).
 */
public final class Project {
    public static final String DRAFT = "draft";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String ANY = "any";

    public final String id;
    public String name;
    public final String kind;
    public String status = DRAFT;
    /** Why it is failed / blocked / stopped (free text, may be null). */
    public String message;
    public String serverId;
    /** {@code null} = any bot of the server, else the allowed bot ids. */
    public List<String> bots;
    public int priority;
    /** Kind-specific settings, normalised by the {@link ProjectKind}. */
    public JsonObject config;
    public long createdAt;
    public long updatedAt;
    public long startedAt;
    public long finishedAt;
    /** Kind runtime state saved across restarts (sectors, BOM cache, ...). */
    public JsonObject state = new JsonObject();
    public ProjectRuntime runtime;

    public Project(String id, String kind) {
        this.id = id;
        this.kind = kind;
    }

    public boolean allows(BotState b) {
        return b.def.serverId() != null && b.def.serverId().equalsIgnoreCase(serverId)
                && (bots == null || bots.stream().anyMatch(x -> x.equalsIgnoreCase(b.id)));
    }

    public JsonElement botsJson() {
        return bots == null ? new JsonPrimitive(ANY) : Json.arrOf(bots);
    }

    /** What is written to disk. */
    public JsonObject toJson() {
        if (runtime != null) {
            state = runtime.persist();
        }
        JsonObject o = definition();
        o.add("state", state == null ? new JsonObject() : state.deepCopy());
        return o;
    }

    /** Definition and status, without kind state or live data. */
    public JsonObject definition() {
        return Json.obj("id", id, "name", name, "kind", kind, "status", status, "message", message,
                "serverId", serverId, "bots", botsJson(), "priority", priority,
                "config", config == null ? new JsonObject() : config.deepCopy(), "createdAt", createdAt,
                "updatedAt", updatedAt, "startedAt", startedAt == 0 ? null : startedAt,
                "finishedAt", finishedAt == 0 ? null : finishedAt);
    }

    /** Reads a saved project (trusted file; fields were validated when written). */
    public static Project fromJson(JsonObject o) {
        Project p = new Project(Json.getString(o, "id", ""), Json.getString(o, "kind", ""));
        p.name = Json.getString(o, "name", p.id);
        p.status = Json.getString(o, "status", DRAFT);
        p.message = Json.getString(o, "message", null);
        p.serverId = Json.getString(o, "serverId", null);
        JsonElement bots = o.get("bots");
        if (bots != null && bots.isJsonArray()) {
            JsonArray a = bots.getAsJsonArray();
            p.bots = a.asList().stream().map(JsonElement::getAsString).toList();
        }
        p.priority = Json.getInt(o, "priority", ProjectService.DEFAULT_PRIORITY);
        JsonObject cfg = Json.getObj(o, "config");
        p.config = cfg == null ? new JsonObject() : cfg;
        p.createdAt = Json.getLong(o, "createdAt", 0);
        p.updatedAt = Json.getLong(o, "updatedAt", 0);
        p.startedAt = Json.getLong(o, "startedAt", 0);
        p.finishedAt = Json.getLong(o, "finishedAt", 0);
        JsonObject st = Json.getObj(o, "state");
        p.state = st == null ? new JsonObject() : st;
        return p;
    }
}
