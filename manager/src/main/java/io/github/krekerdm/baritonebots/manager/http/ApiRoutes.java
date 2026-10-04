package io.github.krekerdm.baritonebots.manager.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ConfigValidator;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.tasks.JsonItemStore;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.tasks.Validators;
import io.github.krekerdm.baritonebots.manager.util.Os;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Every endpoint of SPEC §6. Loop state is only touched inside {@link #loop(Callable)}; bot queries wait for the
 * reply on the HTTP thread.
 */
final class ApiRoutes {
    private static final long QUERY_TIMEOUT_MS = 9_000;
    private static final long MS_CODE_WAIT_MS = 45_000;
    private static final long DISK_CACHE_MS = 60_000;

    private final Manager m;
    private final Schematics schematics;
    private volatile long diskAt;
    private volatile long diskBytes;

    ApiRoutes(Manager m, Schematics schematics) {
        this.m = m;
        this.schematics = schematics;
    }

    private <T> T loop(Callable<T> c) {
        return m.loop.await(c);
    }

    void register(Router r) {
        r.get("/api/state", q -> loop(m::stateSnapshot));
        r.get("/api/stream", q -> {
            m.sse.serve(q.ex);
            return HttpApi.HANDLED;
        });
        r.get("/api/catalog", q -> m.catalog.json());
        r.get("/api/i18n/{lang}", q -> m.i18n.merged(q.param("lang")));

        r.get("/api/settings", q -> loop(this::settingsView));
        r.put("/api/settings", q -> {
            JsonObject patch = q.json();
            return loop(() -> {
                m.config.patch(patch);
                return settingsView();
            });
        });

        servers(r);
        bots(r);
        tasks(r);
        store(r, "/api/scenarios", "scenarios", m.scenarios);
        r.post("/api/scenarios/{id}/run", this::runScenario);
        store(r, "/api/kits", "kits", m.kits);
        world(r);
        schematicRoutes(r);
        projects(r);

        r.get("/api/runtime", q -> {
            JsonObject view = loop(m.installer::view);
            view.addProperty("diskBytes", runtimeDisk());
            return view;
        });
        r.post("/api/runtime/install", q -> loop(() -> {
            m.installer.install(true);
            return new HttpApi.Status(202, m.installer.view());
        }));
        r.get("/api/events", q -> {
            int limit = q.queryInt("limit", 200, 1, 5000);
            String bot = q.query("bot", null);
            String level = q.query("level", null);
            String project = q.query("project", null);
            return loop(() -> Json.obj("events", Json.arrOf(m.events.query(limit, bot, level, project))));
        });
    }

    private JsonObject settingsView() {
        return Json.obj("config", m.config.viewForPanel(), "schema", SettingsSchema.toJson());
    }

    private long runtimeDisk() {
        long now = System.currentTimeMillis();
        if (now - diskAt > DISK_CACHE_MS) {
            diskBytes = Os.directorySize(m.installer.root());
            diskAt = now;
        }
        return diskBytes;
    }

    // ------------------------------------------------------------------ servers

    private void servers(Router r) {
        r.get("/api/servers", q -> loop(() -> listOf("servers")));
        r.post("/api/servers", q -> {
            JsonObject body = q.json();
            return loop(() -> {
                if (Json.getString(body, "id", "").isBlank()) {
                    body.addProperty("id", uniqueId(slug(Json.getString(body, "name", "server")), "servers"));
                }
                return m.config.addItem("servers", body);
            });
        });
        r.put("/api/servers/{id}", q -> {
            JsonObject body = q.json();
            return loop(() -> m.config.updateItem("servers", q.param("id"), body));
        });
        r.delete("/api/servers/{id}", q -> loop(() -> {
            String id = q.param("id");
            for (ManagerConfig.BotDef b : m.config.get().bots()) {
                if (id.equalsIgnoreCase(b.serverId())) {
                    throw ApiException.conflict("in_use", "bot '" + b.id() + "' uses this server");
                }
            }
            m.config.removeItem("servers", id);
            m.secrets.forgetServer(id);
            return null;
        }));
    }

    private JsonArray listOf(String list) {
        JsonArray a = Json.getArr(m.config.viewForPanel(), list);
        return a == null ? new JsonArray() : a;
    }

    static String slug(String s) {
        String out = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (out.isEmpty()) {
            out = "item";
        }
        return out.length() > 28 ? out.substring(0, 28) : out;
    }

    private String uniqueId(String base, String list) {
        JsonArray items = listOf(list);
        String id = base;
        for (int n = 2; contains(items, id); n++) {
            id = base + n;
        }
        return id;
    }

    private static boolean contains(JsonArray items, String id) {
        for (JsonElement e : items) {
            if (id.equalsIgnoreCase(Json.getString(e.getAsJsonObject(), "id", ""))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ bots

    private void bots(Router r) {
        r.get("/api/bots", q -> loop(() -> {
            JsonArray a = new JsonArray();
            m.bots.all().forEach(b -> a.add(m.botView(b)));
            return a;
        }));
        // literal routes before {id} routes of the same shape
        r.post("/api/bots/start-all", q -> loop(() -> {
            m.supervisor.startAll();
            return null;
        }));
        r.post("/api/bots/stop-all", q -> loop(() -> {
            m.supervisor.stopAll();
            return null;
        }));
        r.post("/api/bots", q -> {
            JsonObject body = q.json();
            return loop(() -> {
                String password = takePassword(body);
                if (Json.getString(body, "id", "").isBlank()) {
                    body.addProperty("id", uniqueId(slug(Json.getString(body, "username", "bot")), "bots"));
                }
                if (Json.getString(body, "serverId", "").isBlank() && m.config.get().servers().size() == 1) {
                    body.addProperty("serverId", m.config.get().servers().getFirst().id());
                }
                JsonObject stored = m.config.addItem("bots", body);
                String id = Json.getString(stored, "id", "");
                if (password != null) {
                    m.secrets.setBotPassword(id, password);
                }
                return m.botView(m.bots.require(id));
            });
        });
        r.get("/api/bots/{id}", q -> loop(() -> m.botView(m.bots.require(q.param("id")))));
        r.put("/api/bots/{id}", q -> {
            JsonObject body = q.json();
            return loop(() -> {
                String password = takePassword(body);
                m.config.updateItem("bots", q.param("id"), body);
                BotState b = m.bots.require(q.param("id"));
                if (password != null) {
                    m.secrets.setBotPassword(b.id, password);
                    m.pushConfig(b);
                }
                return m.botView(b);
            });
        });
        r.delete("/api/bots/{id}", q -> loop(() -> {
            BotState b = m.bots.require(q.param("id"));
            m.config.removeItem("bots", b.id);
            m.secrets.forgetBot(b.id);
            return null;
        }));
        r.post("/api/bots/{id}/start", q -> botAction(q, b -> m.supervisor.start(b)));
        r.post("/api/bots/{id}/stop", q -> botAction(q, b -> m.supervisor.stop(b)));
        r.post("/api/bots/{id}/restart", q -> botAction(q, b -> m.supervisor.restart(b)));
        r.post("/api/bots/{id}/kill", q -> botAction(q, b -> m.supervisor.kill(b)));
        r.post("/api/bots/{id}/connect", q -> {
            String address = Json.getString(q.json(), "address", null);
            return botAction(q, b -> linked(b).session.send(MessageTypes.CONNECT,
                    address == null || address.isBlank() ? Json.obj() : Json.obj("address", address.trim())));
        });
        r.post("/api/bots/{id}/disconnect", q -> botAction(q, b -> linked(b).session.send(MessageTypes.DISCONNECT, Json.obj())));
        r.post("/api/bots/{id}/chat", q -> {
            String text = Json.getString(q.json(), "text", "").strip();
            if (text.isEmpty() || text.length() > 256 || text.contains("\n")) {
                throw ApiException.badRequest("bad_text", "chat text must be 1-256 characters on one line");
            }
            return botAction(q, b -> linked(b).session.send(MessageTypes.CHAT, Json.obj("text", text)));
        });
        r.post("/api/bots/{id}/microsoft-login", q -> {
            CompletableFuture<JsonObject> f = loop(() -> m.msLogin.start(m.bots.require(q.param("id"))));
            try {
                return f.get(MS_CODE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new ApiException(504, "timeout", "HeadlessMC printed no device code within "
                        + MS_CODE_WAIT_MS / 1000 + " s; see the bot's events");
            } catch (ExecutionException e) {
                throw new ApiException(502, "login_failed", String.valueOf(e.getCause().getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "busy", "interrupted");
            }
        });
        r.get("/api/bots/{id}/microsoft-login", q -> loop(() -> m.msLogin.view(m.bots.require(q.param("id")))));
        r.delete("/api/bots/{id}/microsoft-login", q -> loop(() -> {
            BotState b = m.bots.require(q.param("id"));
            m.msLogin.cancel(b);
            return m.msLogin.view(b);
        }));
        r.get("/api/bots/{id}/log", q -> {
            int lines = q.queryInt("lines", 200, 1, 2000);
            BotState b = loop(() -> m.bots.require(q.param("id")));
            return Json.obj("lines", Json.arrOf(m.supervisor.logTail(b, lines)));
        });
        r.get("/api/bots/{id}/inventory", q -> query(q.param("id"), QueryKinds.INVENTORY, new JsonObject()));
        r.post("/api/bots/{id}/query", q -> {
            JsonObject body = q.json();
            String kind = Json.getString(body, "kind", "");
            if (!QueryKinds.ALL.contains(kind)) {
                throw ApiException.badRequest("bad_kind", "unknown query kind '" + kind + "'");
            }
            JsonObject args = Json.getObj(body, "args");
            return query(q.param("id"), kind, args == null ? new JsonObject() : args);
        });
        r.get("/api/bots/{id}/plugin", q -> loop(() -> Json.obj("plugin", Json.toTree(m.bots.require(q.param("id")).plugin))));
        r.post("/api/bots/{id}/plugin", q -> {
            JsonObject payload = Json.getObj(q.json(), "payload");
            if (payload == null) {
                throw ApiException.badRequest("bad_payload", "payload must be an object");
            }
            botAction(q, b -> linked(b).session.send(MessageTypes.PLUGIN, Json.obj("payload", payload)));
            return Json.obj("ok", true, "sent", true);
        });
    }

    private static String takePassword(JsonObject body) {
        JsonElement p = body.remove("password");
        if (p == null || p.isJsonNull()) {
            return null;
        }
        String s = p.getAsString();
        if (s.length() > 64 || s.contains(" ")) {
            throw ValidationException.of("password", "pattern");
        }
        return s;
    }

    private interface BotOp {
        void run(BotState b);
    }

    private Object botAction(Req q, BotOp op) {
        return loop(() -> {
            BotState b = m.bots.require(q.param("id"));
            op.run(b);
            return m.botView(b);
        });
    }

    private static BotState linked(BotState b) {
        if (!b.linked()) {
            throw ApiException.conflict("not_linked", "bot '" + b.id + "' is not connected to the manager");
        }
        return b;
    }

    /** Sends a query on the loop, waits for the reply here. Returns the {@code result} payload. */
    private JsonObject query(String botId, String kind, JsonObject args) {
        CompletableFuture<QueryResult> f = loop(() -> linked(m.bots.require(botId)).session.query(kind, args, QUERY_TIMEOUT_MS));
        try {
            return Json.toObject(f.get(QUERY_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            throw new ApiException(504, "timeout", "the bot did not answer within " + QUERY_TIMEOUT_MS / 1000 + " s");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) {
                throw new ApiException(504, "timeout", "the bot did not answer within " + QUERY_TIMEOUT_MS / 1000 + " s");
            }
            throw ApiException.conflict("not_linked", "link closed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "busy", "interrupted");
        }
    }

    // ------------------------------------------------------------------ tasks

    private void tasks(Router r) {
        r.post("/api/bots/{id}/tasks/reorder", q -> {
            List<String> ids = Json.getStringList(q.json(), "ids");
            return loop(() -> {
                BotState b = m.bots.require(q.param("id"));
                m.dispatcher.reorder(b, ids);
                return m.dispatcher.queueView(b);
            });
        });
        r.post("/api/bots/{id}/tasks", q -> {
            JsonObject body = q.json();
            JsonObject template = templateOf(body);
            TaskQueue.Mode mode = mode(body);
            return loop(() -> {
                BotState b = m.bots.require(q.param("id"));
                QueueEntry e = m.dispatcher.addTemplate(b, template, mode, TaskSpec.ORIGIN_PANEL);
                return Json.obj("queued", Json.arr(e.toJson()), "queue", m.dispatcher.queueView(b));
            });
        });
        r.delete("/api/bots/{id}/tasks/{taskId}", q -> loop(() -> {
            BotState b = m.bots.require(q.param("id"));
            m.dispatcher.remove(b, q.param("taskId"));
            return m.dispatcher.queueView(b);
        }));
        r.post("/api/bots/{id}/cancel", q -> loop(() -> {
            BotState b = m.bots.require(q.param("id"));
            m.dispatcher.cancelCurrent(b);
            return m.dispatcher.queueView(b);
        }));
        r.post("/api/bots/{id}/clear", q -> loop(() -> {
            BotState b = m.bots.require(q.param("id"));
            m.dispatcher.clear(b);
            return m.dispatcher.queueView(b);
        }));
        r.post("/api/tasks/batch", q -> {
            JsonObject body = q.json();
            JsonObject template = templateOf(body);
            TaskQueue.Mode mode = mode(body);
            List<String> ids = Json.getStringList(body, "botIds");
            if (ids.isEmpty()) {
                throw ApiException.badRequest("no_bots", "botIds is empty");
            }
            return loop(() -> {
                Validators.template(m.catalog, template, "task");
                JsonObject results = new JsonObject();
                for (String id : ids) {
                    try {
                        QueueEntry e = m.dispatcher.addTemplate(m.bots.require(id), template.deepCopy(), mode,
                                TaskSpec.ORIGIN_PANEL);
                        results.add(id, Json.obj("ok", true, "taskId", e.id()));
                    } catch (ApiException e) {
                        results.add(id, Json.obj("ok", false, "error", e.code(), "message", e.getMessage()));
                    }
                }
                return Json.obj("results", results);
            });
        });
    }

    /** {@code {task, mode}}; a bare template ({@code {type, args}}) is accepted too. */
    private static JsonObject templateOf(JsonObject body) {
        JsonObject t = Json.getObj(body, "task");
        if (t == null && body.has("type")) {
            t = body;
        }
        if (t == null) {
            throw ApiException.badRequest("bad_request", "missing 'task'");
        }
        return t;
    }

    private static TaskQueue.Mode mode(JsonObject body) {
        try {
            return TaskQueue.Mode.parse(Json.getString(body, "mode", null));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_mode", "mode must be append, front or replace");
        }
    }

    // ------------------------------------------------------------------ scenarios / kits

    private void store(Router r, String base, String key, JsonItemStore store) {
        r.get(base, q -> loop(() -> Json.obj(key, Json.arrOf(store.list()))));
        r.get(base + "/{id}", q -> loop(() -> store.require(q.param("id"))));
        r.post(base, q -> {
            JsonObject body = q.json();
            return loop(() -> new HttpApi.Status(201, store.create(body)));
        });
        r.put(base + "/{id}", q -> {
            JsonObject body = q.json();
            return loop(() -> store.replace(q.param("id"), body));
        });
        r.delete(base + "/{id}", q -> loop(() -> {
            store.delete(q.param("id"));
            return null;
        }));
    }

    private Object runScenario(Req q) throws IOException {
        JsonObject body = q.json();
        List<String> ids = Json.getStringList(body, "botIds");
        if (ids.isEmpty()) {
            throw ApiException.badRequest("no_bots", "botIds is empty");
        }
        return loop(() -> {
            JsonObject sc = m.scenarios.require(q.param("id"));
            boolean repeat = Json.getBool(body, "repeat", Json.getBool(sc, "repeat", false));
            List<BotState> targets = new ArrayList<>();
            for (String id : ids) {
                targets.add(m.bots.require(id));
            }
            Map<String, String> runs = new LinkedHashMap<>();
            for (BotState b : targets) {
                runs.put(b.id, m.dispatcher.runScenario(sc, b, repeat));
            }
            return Json.obj("runs", Json.toTree(runs));
        });
    }

    // ------------------------------------------------------------------ world

    private ManagerConfig.ServerProfile server(String id) {
        return m.config.get().server(id).orElseThrow(() -> ApiException.notFound("server '" + id + "'"));
    }

    private void world(Router r) {
        r.get("/api/world/{serverId}", q -> loop(() -> m.worlds.get(server(q.param("serverId")).id()).toJson()));
        r.put("/api/world/{serverId}", q -> {
            JsonObject body = q.json();
            return loop(() -> {
                String sid = server(q.param("serverId")).id();
                WorldDoc doc = m.worlds.get(sid);
                WorldDoc.fromJson(doc.toJson()).applySections(body); // validate on a copy first
                doc.applySections(body);
                m.worlds.markDirty(sid);
                if (body.has("zones")) {
                    m.pushConfigForServer(sid);
                }
                m.broadcastWorld(sid);
                return doc.toJson();
            });
        });
        r.post("/api/world/{serverId}/discover", q -> {
            JsonObject body = q.json();
            String botId = Json.getString(body, "botId", "");
            int radius = Math.max(1, Math.min(128, Json.getInt(body, "radius", 32)));
            String sid = loop(() -> {
                String s = server(q.param("serverId")).id();
                BotState b = m.bots.require(botId);
                if (!s.equalsIgnoreCase(String.valueOf(b.def.serverId()))) {
                    throw ApiException.badRequest("wrong_server", "bot '" + b.id + "' plays on another server");
                }
                return s;
            });
            JsonObject res = query(botId, QueryKinds.CONTAINERS_NEARBY, Json.obj("radius", radius));
            if (!Json.getBool(res, "ok", false)) {
                throw new ApiException(502, "query_failed", Json.getString(res, "error", "error"));
            }
            JsonArray found = Json.getArr(Json.getObj(res, "data"), "containers");
            JsonArray list = found == null ? new JsonArray() : found;
            return loop(() -> {
                m.autopilot.ensureHome(m.bots.require(botId));
                int added = m.autopilot.mergeRequested(sid, list);
                m.broadcastWorld(sid);
                return Json.obj("found", list.size(), "added", added, "total", m.worlds.get(sid).containers.size(),
                        "world", m.worlds.get(sid).toJson());
            });
        });
    }

    // ------------------------------------------------------------------ schematics / projects

    private void schematicRoutes(Router r) {
        r.get("/api/schematics", q -> Json.obj("schematics", Json.arrOf(schematics.list())));
        r.post("/api/schematics", q -> new HttpApi.Status(201, schematics.save(q.query("name", ""), q.stream(),
                Boolean.parseBoolean(q.query("overwrite", "false")))));
        r.delete("/api/schematics/{name}", q -> {
            schematics.delete(q.param("name"));
            return null;
        });
    }

    private void projects(Router r) {
        r.get("/api/projects", q -> loop(() -> Json.obj("projects", Json.arrOf(m.projects.list()))));
        r.post("/api/projects", q -> {
            JsonObject body = q.json();
            return loop(() -> new HttpApi.Status(201, m.projects.create(body)));
        });
        r.get("/api/projects/{id}", q -> loop(() -> m.projects.view(m.projects.require(q.param("id")), true)));
        r.put("/api/projects/{id}", q -> {
            JsonObject body = q.json();
            return loop(() -> m.projects.replace(q.param("id"), body));
        });
        r.delete("/api/projects/{id}", q -> loop(() -> {
            m.projects.delete(q.param("id"));
            return null;
        }));
        r.post("/api/projects/{id}/{action}", q -> loop(() -> m.projects.action(q.param("id"), q.param("action"))));

        // autopilot and standing orders (orders live in config.json → orders[]; these routes edit that list)
        r.get("/api/autopilot", q -> loop(m.autopilot::view));
        r.get("/api/orders", q -> loop(m.autopilot::ordersView));
        r.post("/api/orders", q -> {
            JsonObject body = q.json();
            return loop(() -> {
                if (Json.getString(body, "serverId", "").isBlank() && m.config.get().servers().size() == 1) {
                    body.addProperty("serverId", m.config.get().servers().getFirst().id());
                }
                if (Json.getString(body, "id", "").isBlank()) {
                    String item = Json.getString(body, "item", "order");
                    body.addProperty("id", uniqueId(slug(item.contains(":") ? item.substring(item.indexOf(':') + 1) : item),
                            "orders"));
                }
                return new HttpApi.Status(201, m.config.addItem("orders", body));
            });
        });
        r.put("/api/orders/{id}", q -> {
            JsonObject body = q.json();
            return loop(() -> m.config.updateItem("orders", q.param("id"), body));
        });
        r.delete("/api/orders/{id}", q -> loop(() -> {
            m.config.removeItem("orders", q.param("id"));
            return null;
        }));

        // schedules and rules (config.json schedules[] / rules[]; §5.7b)
        configList(r, "/api/schedules", "schedules", "schedule", () -> m.automation.schedules().view());
        configList(r, "/api/rules", "rules", "rule", () -> m.automation.rules().view());
        r.post("/api/schedules/{id}/run", q -> loop(() -> {
            String id = q.param("id");
            var def = m.config.get().schedules().stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("schedule '" + id + "'"));
            return Json.obj("bots", Json.arrOf(m.automation.schedules().fire(def, def.serverId(), "manual")));
        }));
        r.post("/api/rules/{id}/run", q -> loop(() -> {
            String id = q.param("id");
            var def = m.config.get().rules().stream().filter(x -> x.id().equalsIgnoreCase(id)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("rule '" + id + "'"));
            return Json.obj("bots", Json.arrOf(m.automation.rules().runNow(def)));
        }));

        // extras (not in SPEC §6): planner overview and game data lookups
        r.get("/api/planner", q -> loop(m.planner::view));
        r.get("/api/gamedata", q -> loop(m.gameData::view));
        r.get("/api/gamedata/item/{id}", q -> {
            var data = m.gameData.current();
            if (data == null) {
                throw ApiException.conflict("not_loaded", "game data is not loaded (no client jar yet)");
            }
            return data.describe(q.param("id"));
        });
    }

    /**
     * GET (live view) / POST (id from the name when absent) / PUT (merge patch; an {@code if} object replaces the old
     * one) / DELETE over a config.json list.
     */
    private void configList(Router r, String path, String list, String fallback, Callable<Object> view) {
        r.get(path, q -> loop(view));
        r.post(path, q -> {
            JsonObject body = q.json();
            return loop(() -> {
                if (Json.getString(body, "id", "").isBlank()) {
                    body.addProperty("id", uniqueId(slug(Json.getString(body, "name", fallback)), list));
                }
                return new HttpApi.Status(201, m.config.addItem(list, body));
            });
        });
        r.put(path + "/{id}", q -> {
            JsonObject body = q.json();
            return loop(() -> {
                JsonObject cond = Json.getObj(body, "if");
                if (cond != null) { // a new trigger replaces the old one instead of merging into it
                    m.config.findItem(list, q.param("id")).map(old -> Json.getObj(old, "if")).ifPresent(old ->
                            old.keySet().forEach(k -> {
                                if (!cond.has(k)) {
                                    cond.add(k, com.google.gson.JsonNull.INSTANCE);
                                }
                            }));
                }
                return m.config.updateItem(list, q.param("id"), body);
            });
        });
        r.delete(path + "/{id}", q -> loop(() -> {
            m.config.removeItem(list, q.param("id"));
            return null;
        }));
    }

    /** Keeps the id rules in one place for callers that build ids. */
    static boolean validId(String id) {
        return ConfigValidator.ID.matcher(id).matches();
    }
}
