package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.config.ConfigValidator;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.events.SseHub;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Projects (SPEC §5.7, §6 {@code /api/projects}): CRUD, start / pause / resume / stop, persistence in
 * {@code projects/<id>.json} (atomic, delayed writes), SSE {@code project} updates and the {@code project_*} events.
 * Running projects are registered with the {@link Planner} as work sources. Loop-owned.
 */
public final class ProjectService {
    public static final int DEFAULT_PRIORITY = 5;
    static final long BLOCKED_EVENT_EVERY_MS = 10 * 60_000L;

    private final Manager m;
    private final Planner planner;
    private final Path dir;
    private final Map<String, ProjectKind> kinds = new LinkedHashMap<>();
    private final Map<String, Project> projects = new LinkedHashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();
    private final Map<String, Long> blockedAt = new HashMap<>();
    private boolean saveScheduled;

    public ProjectService(Manager m, Planner planner, Path dir) {
        this.m = m;
        this.planner = planner;
        this.dir = dir;
        register(new BuildKind(m));
    }

    public void register(ProjectKind kind) {
        kinds.put(kind.kind(), kind);
    }

    public Manager manager() {
        return m;
    }

    public Planner planner() {
        return planner;
    }

    public Set<String> kinds() {
        return kinds.keySet();
    }

    // ------------------------------------------------------------------ persistence

    /** Reads every projects/*.json (loop). Unknown kinds are kept on disk but not loaded. */
    public void load() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                try {
                    JsonElement e = AtomicFiles.readJson(f);
                    if (e == null || !e.isJsonObject()) {
                        continue;
                    }
                    Project p = Project.fromJson(e.getAsJsonObject());
                    ProjectKind k = kinds.get(p.kind);
                    if (p.id.isBlank() || k == null) {
                        Log.warn("project file %s skipped (unknown kind '%s')", f.getFileName(), p.kind);
                        continue;
                    }
                    p.runtime = k.runtime(p, this);
                    projects.put(p.id, p);
                } catch (IOException | RuntimeException ex) {
                    Log.warn("project file %s unreadable: %s", f.getFileName(), ex.getMessage());
                }
            }
        } catch (IOException e) {
            Log.warn("cannot list %s: %s", dir, e.getMessage());
        }
    }

    /** Re-registers projects that were running when the manager stopped (after the planner started). */
    public void resumeRunning() {
        for (Project p : projects.values()) {
            if (Project.RUNNING.equals(p.status)) {
                p.runtime.start(false);
                planner.addSource(p.runtime);
            }
        }
    }

    /** Marks a project for saving and pushes an SSE update. */
    public void changed(Project p) {
        p.updatedAt = System.currentTimeMillis();
        dirty.add(p.id);
        if (!saveScheduled) {
            saveScheduled = true;
            m.loop.schedule(this::flush, 1, TimeUnit.SECONDS);
        }
        broadcast(p);
    }

    public void flush() {
        saveScheduled = false;
        for (String id : List.copyOf(dirty)) {
            Project p = projects.get(id);
            dirty.remove(id);
            if (p == null) {
                continue;
            }
            try {
                Files.createDirectories(dir);
                AtomicFiles.writeJson(dir.resolve(id + ".json"), p.toJson());
            } catch (IOException e) {
                Log.error("cannot write project " + id, e);
            }
        }
    }

    public void broadcast(Project p) {
        m.sse.broadcast(SseHub.PROJECT, Json.obj("project", view(p, false)));
    }

    /** Planner tick hook: progress of running projects to the panel, state to disk now and then. */
    public void afterTick() {
        for (Project p : projects.values()) {
            if (Project.RUNNING.equals(p.status)) {
                broadcast(p);
                dirty.add(p.id);
            }
        }
        if (!dirty.isEmpty() && !saveScheduled) {
            saveScheduled = true;
            m.loop.schedule(this::flush, 1, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ views

    public JsonObject view(Project p, boolean full) {
        JsonObject o = p.definition();
        if (p.runtime != null) {
            p.runtime.view(full).entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        }
        o.add("assignments", planner.assignmentsView(p.id));
        return o;
    }

    public List<JsonObject> list() {
        List<JsonObject> out = new ArrayList<>();
        projects.values().forEach(p -> out.add(view(p, false)));
        return out;
    }

    public Project require(String id) {
        Project p = projects.get(id);
        if (p == null) {
            throw ApiException.notFound("project '" + id + "'");
        }
        return p;
    }

    public Project get(String id) {
        return projects.get(id);
    }

    // ------------------------------------------------------------------ CRUD

    public JsonObject create(JsonObject body) {
        String id = Json.getString(body, "id", null);
        if (id == null || id.isBlank()) {
            id = Tokens.id("p");
        } else if (!ConfigValidator.ID.matcher(id).matches()) {
            throw ValidationException.of("id", "pattern");
        } else if (projects.containsKey(id)) {
            throw ValidationException.of("id", "duplicate");
        }
        String kind = Json.getString(body, "kind", "");
        ProjectKind k = kinds.get(kind);
        if (k == null) {
            throw ValidationException.of("kind", "enum");
        }
        Project p = new Project(id, kind);
        apply(p, body, k);
        p.createdAt = System.currentTimeMillis();
        p.runtime = k.runtime(p, this);
        projects.put(id, p);
        changed(p);
        if (Json.getBool(body, "start", false)) {
            action(id, "start");
        }
        return view(p, true);
    }

    public JsonObject replace(String id, JsonObject body) {
        Project p = require(id);
        String kind = Json.getString(body, "kind", p.kind);
        if (!p.kind.equals(kind)) {
            throw ValidationException.of("kind", "immutable");
        }
        ProjectKind k = kinds.get(p.kind);
        boolean active = Project.RUNNING.equals(p.status) || Project.PAUSED.equals(p.status);
        JsonObject oldConfig = p.config;
        String oldServer = p.serverId;
        Project probe = new Project(p.id, p.kind);
        apply(probe, merged(p, body), k); // validate everything first
        if (active && (!oldConfig.equals(probe.config) || !oldServer.equalsIgnoreCase(probe.serverId))) {
            throw ApiException.conflict("bad_status", "stop the project before changing its server or settings");
        }
        p.name = probe.name;
        p.bots = probe.bots;
        p.priority = probe.priority;
        if (!active) {
            boolean configChanged = !oldConfig.equals(probe.config) || !oldServer.equalsIgnoreCase(probe.serverId);
            p.config = probe.config;
            p.serverId = probe.serverId;
            if (configChanged) {
                p.state = new JsonObject(); // old sectors / BOM do not fit the new placement
            }
            p.runtime = k.runtime(p, this);
        }
        changed(p);
        planner.tickSoon();
        return view(p, true);
    }

    private static JsonObject merged(Project p, JsonObject body) {
        JsonObject o = p.definition();
        body.entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return o;
    }

    private void apply(Project p, JsonObject body, ProjectKind k) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = Json.getString(body, "name", "").trim();
        if (name.isEmpty() || name.length() > 80) {
            errors.put("name", name.isEmpty() ? "required" : "range");
        }
        String serverId = Json.getString(body, "serverId", "");
        var server = m.config.get().server(serverId);
        if (server.isEmpty()) {
            errors.put("serverId", serverId.isBlank() ? "required" : "unknown_server");
        }
        List<String> bots = null;
        JsonElement b = body.get("bots");
        if (b != null && b.isJsonArray()) {
            bots = new ArrayList<>();
            for (int i = 0; i < b.getAsJsonArray().size(); i++) {
                String bid = b.getAsJsonArray().get(i).getAsString();
                var def = m.config.get().bot(bid);
                if (def.isEmpty()) {
                    errors.put("bots[" + i + "]", "not_found");
                } else {
                    bots.add(def.get().id());
                }
            }
            if (bots.isEmpty() && !errors.containsKey("bots")) {
                errors.put("bots", "required");
            }
        } else if (b != null && !(b.isJsonPrimitive() && Project.ANY.equals(b.getAsString())) && !b.isJsonNull()) {
            errors.put("bots", "type");
        }
        int priority = Json.getInt(body, "priority", DEFAULT_PRIORITY);
        if (priority < 0 || priority > 100) {
            errors.put("priority", "range");
        }
        JsonObject cfg = Json.getObj(body, "config");
        JsonObject placement = Json.getObj(body, "placement"); // alias: build placement at the top level
        JsonObject config = cfg == null ? new JsonObject() : cfg.deepCopy();
        if (placement != null) {
            placement.entrySet().forEach(e -> config.add(e.getKey(), e.getValue()));
        }
        JsonObject normalized = null;
        if (server.isPresent()) {
            try {
                normalized = k.normalizeConfig(config, server.get().id());
            } catch (ValidationException e) {
                e.fields().forEach((path, code) -> errors.put("config." + path, code));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        p.name = name;
        p.serverId = server.get().id();
        p.bots = bots;
        p.priority = priority;
        p.config = normalized;
    }

    public void delete(String id) {
        Project p = require(id);
        if (p.runtime != null && planner.hasSource(p.id)) {
            planner.removeSource(p.id, "deleted");
            p.runtime.stop();
        }
        projects.remove(id);
        dirty.remove(id);
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (IOException e) {
            Log.warn("cannot delete project file %s: %s", id, e.getMessage());
        }
        m.sse.broadcast(SseHub.PROJECT, Json.obj("id", id, "deleted", true));
    }

    // ------------------------------------------------------------------ lifecycle

    /** start | pause | resume | stop. */
    public JsonObject action(String id, String action) {
        Project p = require(id);
        String st = p.status;
        switch (action.toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (Project.RUNNING.equals(st)) {
                    throw ApiException.conflict("bad_status", "the project is already running");
                }
                if (Project.PAUSED.equals(st)) {
                    return action(id, "resume");
                }
                p.status = Project.RUNNING;
                p.message = null;
                p.startedAt = System.currentTimeMillis();
                p.finishedAt = 0;
                p.runtime.start(true);
                planner.addSource(p.runtime);
                event(p, "project_started", Levels.INFO, "event.project.started", null);
            }
            case "pause" -> {
                requireStatus(p, Project.RUNNING);
                p.status = Project.PAUSED;
                planner.removeSource(p.id, "paused");
                p.runtime.stop();
                event(p, "project_paused", Levels.INFO, "event.project.paused", null);
            }
            case "resume" -> {
                requireStatus(p, Project.PAUSED);
                p.status = Project.RUNNING;
                p.message = null;
                p.runtime.start(false);
                planner.addSource(p.runtime);
                event(p, "project_resumed", Levels.INFO, "event.project.resumed", null);
            }
            case "stop" -> {
                if (!Project.RUNNING.equals(st) && !Project.PAUSED.equals(st)) {
                    throw ApiException.conflict("bad_status", "the project is not running");
                }
                planner.removeSource(p.id, "stopped");
                p.runtime.stop();
                p.status = Project.DRAFT;
                p.message = "stopped";
                event(p, "project_stopped", Levels.INFO, "event.project.stopped", null);
            }
            default -> throw ApiException.notFound("project action '" + action + "'");
        }
        changed(p);
        return view(p, true);
    }

    private static void requireStatus(Project p, String status) {
        if (!status.equals(p.status)) {
            throw ApiException.conflict("bad_status", "the project is " + p.status + ", not " + status);
        }
    }

    // ------------------------------------------------------------------ runtime callbacks

    /** Every part is finished. */
    public void done(Project p) {
        if (!Project.RUNNING.equals(p.status)) {
            return;
        }
        planner.removeSource(p.id, "done");
        p.runtime.stop();
        p.status = Project.DONE;
        p.message = null;
        p.finishedAt = System.currentTimeMillis();
        event(p, "project_done", Levels.INFO, "event.project.done", null);
        changed(p);
    }

    /** A problem the planner cannot work around (schematic missing, ...). */
    public void failed(Project p, String message) {
        if (!Project.RUNNING.equals(p.status)) {
            return;
        }
        planner.removeSource(p.id, "failed");
        p.runtime.stop();
        p.status = Project.FAILED;
        p.message = message;
        p.finishedAt = System.currentTimeMillis();
        event(p, "project_failed", Levels.ERROR, "event.project.failed", Map.of("reason", message));
        changed(p);
    }

    /**
     * Work is stuck for {@code reason} (manual materials, an item failed 3 times, no containers, ...). The event is
     * sent at most every 10 minutes per {@code key}.
     */
    public void blocked(Project p, String key, String reason, JsonObject data) {
        long now = System.currentTimeMillis();
        String k = p.id + "/" + key;
        Long last = blockedAt.get(k);
        if (last != null && now - last < BLOCKED_EVENT_EVERY_MS) {
            return;
        }
        blockedAt.put(k, now);
        JsonObject d = data == null ? new JsonObject() : data.deepCopy();
        d.addProperty("key", key);
        event(p, "project_blocked", Levels.WARN, "event.project.blocked", Map.of("reason", reason), d);
    }

    /** Forget throttling for a key once the problem is gone, so a new occurrence is reported. */
    public void unblocked(Project p, String key) {
        blockedAt.remove(p.id + "/" + key);
    }

    private void event(Project p, String kind, String level, String key, Map<String, ?> extra) {
        event(p, kind, level, key, extra, null);
    }

    private void event(Project p, String kind, String level, String key, Map<String, ?> extra, JsonObject data) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", p.name);
        if (extra != null) {
            args.putAll(extra);
        }
        JsonObject d = data == null ? new JsonObject() : data;
        d.addProperty("projectId", p.id);
        m.event(kind, level, null, key, args, d);
    }
}
