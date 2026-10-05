package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.automation.StepRunner;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ConfigValidator;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.events.SseHub;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Optional local AI (SPEC §5.7c command box, §5.7d supervisor), off by default. A convenience layer only: the planner
 * stays deterministic, nothing the model says runs before {@link PlanValidator} accepted it, and without Ollama the
 * rest of the manager behaves exactly as before. Loop-owned; model rounds run on the HTTP client's threads and post
 * their results back.
 */
public final class AiService {
    /** Queue origin of everything the AI queued: {@code ai:<plan id>} or {@code ai:<action id>}. */
    public static final String ORIGIN_PREFIX = "ai:";
    static final long PLAN_TTL_MS = 30 * 60_000;
    static final int PLANS_KEPT = 50;
    static final int STATUS_TIMEOUT_SEC = 5;
    static final int TEXT_MAX = 2000;

    private record StoredPlan(long at, PlanValidator.Plan plan, String text, List<String> botIds) {
    }

    private final Manager m;
    private final OllamaClient client;
    private final Supervisor supervisor;
    private final Map<String, StoredPlan> plans = new LinkedHashMap<>();

    public AiService(Manager m) {
        this(m, new OllamaClient());
    }

    AiService(Manager m, OllamaClient client) {
        this.m = m;
        this.client = client;
        this.supervisor = new Supervisor(m, this);
    }

    OllamaClient client() {
        return client;
    }

    private ManagerConfig.AiCfg cfg() {
        return m.config.get().ai();
    }

    /** Starts the supervisor timer (with the planner). */
    public void start() {
        supervisor.start();
    }

    public void stop() {
        supervisor.stop();
    }

    /** For {@code GET /api/state} and SSE {@code ai} {@code {type:"status"}}. */
    public JsonObject brief() {
        ManagerConfig.AiCfg c = cfg();
        return Json.obj("enabled", c.enabled(), "mode", c.mode(), "model", c.model(), "timeoutSec", c.timeoutSec(),
                "superviseSec", c.superviseSec());
    }

    public void onConfigChanged(ManagerConfig old, ManagerConfig nu) {
        if (old == null || !old.ai().equals(nu.ai())) {
            JsonObject o = brief();
            o.addProperty("type", "status");
            m.sse.broadcast(SseHub.AI, o);
        }
    }

    /** Event log listener (loop). */
    public void onEvent(ManagerEvent e) {
        supervisor.onEvent(e);
    }

    /** How long an HTTP handler waits for one model round. */
    public long waitMs() {
        return (cfg().timeoutSec() + 5) * 1000L;
    }

    void broadcast(String projectId, JsonObject entry) {
        m.sse.broadcast(SseHub.AI, Json.obj("type", "feed", "projectId", projectId, "entry", entry.deepCopy()));
    }

    static AiException unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c instanceof AiException ae ? ae : new AiException(AiException.UNREACHABLE, String.valueOf(c.getMessage()));
    }

    // ------------------------------------------------------------------ connection check

    /** {@code GET /api/ai/status}: is the endpoint up, which models it has, is ours among them (works while off too). */
    public CompletableFuture<JsonObject> status() {
        ManagerConfig.AiCfg c = cfg();
        JsonObject base = brief();
        base.addProperty("endpoint", c.base());
        return client.tags(c.base(), STATUS_TIMEOUT_SEC).handle((models, err) -> {
            JsonObject o = base.deepCopy();
            if (err != null) {
                AiException e = unwrap(err);
                o.addProperty("reachable", false);
                o.add("error", Json.obj("code", e.code(), "message", e.getMessage()));
                return o;
            }
            o.addProperty("reachable", true);
            o.add("models", Json.arrOf(models));
            o.addProperty("modelPresent", models.stream().anyMatch(n -> sameModel(n, c.model())));
            return o;
        });
    }

    /** {@code qwen2.5:7b-instruct} = {@code qwen2.5:7b-instruct}; {@code llama3} = {@code llama3:latest}. */
    static boolean sameModel(String installed, String wanted) {
        String a = installed.toLowerCase(Locale.ROOT);
        String b = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        return a.equals(b) || !b.contains(":") && a.equals(b + ":latest");
    }

    // ------------------------------------------------------------------ command box (SPEC §5.7c)

    /**
     * {@code POST /api/ai/plan {text, botIds?}} (loop): asks the model, validates its answer against the live state and
     * remembers the plan for {@link #run}. Completes with {@code {id, plan, orders, project, notes, rejected}}.
     */
    public CompletableFuture<JsonObject> plan(JsonObject body) {
        ManagerConfig.AiCfg c = cfg();
        if (!c.enabled()) {
            throw new AiException(AiException.DISABLED, "the AI is switched off (settings → ai)").toApi();
        }
        String text = Json.getString(body, "text", "").trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("empty_text", "type what the bots should do");
        }
        if (text.length() > TEXT_MAX) {
            throw ApiException.badRequest("text_too_long", "at most " + TEXT_MAX + " characters");
        }
        List<String> botIds = Json.getStringList(body, "botIds");
        AiContext ctx = AiContext.capture(m, text, botIds);
        String user = "Request: " + text + (ctx.allowedBots().isEmpty() ? ""
                : "\nUse only these bots: " + String.join(", ", ctx.allowedBots().stream().sorted().toList()));
        long started = System.currentTimeMillis();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        client.chat(c.base(), c.model(), AiPrompts.planSystem(ctx), user, AiPrompts.planSchema(ctx), c.timeoutSec())
                .whenComplete((out, err) -> m.loop.post(() -> {
                    if (err != null) {
                        result.completeExceptionally(unwrap(err).toApi());
                        return;
                    }
                    PlanValidator.Plan plan = PlanValidator.plan(out, AiContext.capture(m, text, botIds));
                    String id = Tokens.id("ai");
                    remember(id, new StoredPlan(System.currentTimeMillis(), plan, text, botIds));
                    JsonObject r = plan.toJson();
                    r.addProperty("id", id);
                    r.addProperty("text", text);
                    r.addProperty("model", c.model());
                    r.addProperty("ms", System.currentTimeMillis() - started);
                    result.complete(r);
                }));
        return result;
    }

    private void remember(String id, StoredPlan p) {
        long now = System.currentTimeMillis();
        for (Iterator<StoredPlan> it = plans.values().iterator(); it.hasNext(); ) {
            if (now - it.next().at() > PLAN_TTL_MS) {
                it.remove();
            }
        }
        while (plans.size() >= PLANS_KEPT) {
            plans.remove(plans.keySet().iterator().next());
        }
        plans.put(id, p);
    }

    /**
     * {@code POST /api/ai/run {id} | {plan, orders?, project?, text?, botIds?}} (loop): checks the plan again on the
     * live state and queues it through the normal paths: tasks / steps with origin {@code ai:<id>} (all {@code any}
     * steps on one free bot, in order), standing orders into {@code orders[]}, the project created and started.
     * Completes with {@code {id, queued, orders, project, failed}}.
     */
    public CompletableFuture<JsonObject> run(JsonObject body) {
        if (!cfg().enabled()) {
            throw new AiException(AiException.DISABLED, "the AI is switched off (settings → ai)").toApi();
        }
        String id = Json.getString(body, "id", null);
        StoredPlan stored = id == null ? null : plans.remove(id);
        PlanValidator.Plan plan;
        AiContext ctx;
        if (stored != null) {
            ctx = AiContext.capture(m, stored.text(), stored.botIds());
            plan = PlanValidator.plan(stored.plan().toJson(), ctx);
        } else {
            if (Json.getArr(body, "plan") == null && Json.getArr(body, "orders") == null && Json.getObj(body, "project") == null) {
                throw ApiException.notFound("plan '" + id + "' (expired or already run)");
            }
            id = Tokens.id("ai");
            ctx = AiContext.capture(m, Json.getString(body, "text", null), Json.getStringList(body, "botIds"));
            plan = PlanValidator.plan(body, ctx);
        }
        String origin = ORIGIN_PREFIX + id;
        JsonArray queued = new JsonArray();
        JsonArray failed = new JsonArray();
        plan.rejected().forEach(failed::add);
        BotState any = null;
        boolean anyPicked = false;
        for (JsonElement e : plan.steps()) {
            JsonObject s = e.getAsJsonObject();
            String botId = Json.getString(s, "botId", PlanValidator.ANY);
            JsonObject tpl = Json.getObj(s, "step");
            BotState b;
            if (PlanValidator.ANY.equals(botId)) {
                if (!anyPicked) {
                    any = pickAny(ctx);
                    anyPicked = true;
                }
                b = any;
            } else {
                b = m.bots.get(botId);
            }
            if (b == null) {
                failed.add(fail("step", s, PlanValidator.ANY.equals(botId) ? "no_free_bot" : "unknown_bot", botId));
                continue;
            }
            try {
                QueueEntry q = m.dispatcher.addTemplate(b, tpl.deepCopy(), TaskQueue.Mode.APPEND, origin);
                queued.add(Json.obj("botId", b.id, "taskId", q.id(), "type", q.type()));
            } catch (ApiException ex) {
                failed.add(fail("step", s, ex.code(), ex.getMessage()));
            } catch (ValidationException | TaskCatalog.BadArgsException ex) {
                failed.add(fail("step", s, "bad_arg", ex.getMessage()));
            }
        }
        JsonArray orders = new JsonArray();
        for (JsonElement e : plan.orders()) {
            try {
                orders.add(addOrder(e.getAsJsonObject()));
            } catch (ValidationException | ApiException ex) {
                failed.add(fail("order", e, "bad_order", ex.getMessage()));
            }
        }
        JsonObject project = plan.project();
        String runId = id;
        CompletableFuture<JsonObject> projectDone;
        if (project == null) {
            projectDone = CompletableFuture.completedFuture(null);
        } else {
            JsonObject pbody = project.deepCopy();
            pbody.addProperty("start", true);
            projectDone = new CompletableFuture<>();
            CompletableFuture<JsonObject> pd = projectDone;
            m.refs.resolveProject(pbody).whenComplete((resolved, err) -> m.loop.post(() -> {
                if (err != null) {
                    Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                    failed.add(fail("project", project, c instanceof ApiException ae ? ae.code() : "bad_project",
                            c.getMessage()));
                    pd.complete(null);
                    return;
                }
                try {
                    pd.complete(m.projects.create(resolved));
                } catch (ValidationException ex) {
                    failed.add(fail("project", project, "bad_project", ex.fields().toString()));
                    pd.complete(null);
                } catch (RuntimeException ex) {
                    failed.add(fail("project", project, ex instanceof ApiException ae ? ae.code() : "bad_project",
                            ex.getMessage()));
                    pd.complete(null);
                }
            }));
        }
        return projectDone.thenApply(created -> m.loop.await(() -> {
            String projectName = created == null ? "-" : Json.getString(created, "name", "-");
            m.event("ai_plan_run", Levels.INFO, null, "event.ai.planRun", Map.of("steps", queued.size(),
                    "orders", orders.size(), "project", projectName, "failed", failed.size()),
                    created == null ? Json.obj("origin", origin) : Json.obj("origin", origin, "projectId",
                            Json.getString(created, "id", null)));
            return Json.obj("id", runId, "origin", origin, "queued", queued, "orders", orders, "project", created,
                    "failed", failed);
        }));
    }

    private static JsonObject fail(String kind, JsonElement what, String reason, String detail) {
        return Json.obj("kind", kind, "what", PlanValidator.cut(Json.toJson(what), 200), "reason", reason,
                "detail", PlanValidator.cut(detail, 300));
    }

    /** One free online bot among the ones the request may use (idle first, as schedules pick). */
    private BotState pickAny(AiContext ctx) {
        List<BotState> picked = new StepRunner(m).pick(new StepRunner.Target(StepRunner.ANY, List.of()),
                ctx.defaultServer(), null, b -> !ctx.allowedBots().isEmpty() && !ctx.allowedBots().contains(b.id));
        return picked.isEmpty() ? null : picked.getFirst();
    }

    /**
     * Adds a standing order (validated shape {@code {item, min, max?, into, serverId}}); an existing order for the same
     * item, target and server is raised instead of duplicated. Returns the order id.
     */
    String addOrder(JsonObject o) {
        String item = Json.getString(o, "item", "");
        String into = Json.getString(o, "into", "storage");
        String server = Json.getString(o, "serverId", "");
        for (ManagerConfig.OrderDef d : m.config.get().orders()) {
            if (d.item().equalsIgnoreCase(item) && d.into().equalsIgnoreCase(into) && d.serverId().equalsIgnoreCase(server)) {
                JsonObject patch = Json.obj("enabled", true, "min", Math.max(d.min(), Json.getInt(o, "min", d.min())));
                if (o.has("max")) {
                    patch.addProperty("max", Math.max(Json.getInt(o, "max", 0), patch.get("min").getAsInt()));
                }
                m.config.updateItem("orders", d.id(), patch);
                return d.id();
            }
        }
        String base = item.substring(item.indexOf(':') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        base = base.isEmpty() ? "order" : base.length() > 26 ? base.substring(0, 26) : base;
        String id = base;
        for (int n = 2; exists(id); n++) {
            id = base + n;
        }
        JsonObject body = o.deepCopy();
        body.addProperty("id", id);
        if (!ConfigValidator.ID.matcher(id).matches()) {
            throw ValidationException.of("id", "pattern");
        }
        m.config.addItem("orders", body);
        return id;
    }

    private boolean exists(String orderId) {
        return m.config.get().orders().stream().anyMatch(o -> o.id().equalsIgnoreCase(orderId));
    }

    /** A manager catalog text in the configured language. */
    String label(String key) {
        return m.i18n.t(m.config.get().general().language(), key, null);
    }

    /** Short readable form of an action for event texts: localised name + arguments. */
    String describe(String type, JsonObject args) {
        StringBuilder sb = new StringBuilder(label("aiAct." + type));
        for (Map.Entry<String, JsonElement> e : args.entrySet()) {
            if ("serverId".equals(e.getKey())) {
                continue;
            }
            JsonElement v = e.getValue();
            String s = v.isJsonPrimitive() ? v.getAsString() : v.isJsonObject() && v.getAsJsonObject().has("type")
                    ? Json.getString(v.getAsJsonObject(), "type", "") + " " + Json.toJson(v.getAsJsonObject().get("args"))
                    : Json.toJson(v);
            sb.append(' ').append(e.getKey()).append('=').append(PlanValidator.cut(s, 80));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ supervisor (SPEC §5.7d)

    /** {@code GET /api/ai/feed?project=} (loop). */
    public JsonObject feed(String projectId) {
        m.projects.require(projectId);
        return supervisor.feed(projectId);
    }

    /** Apply / Dismiss a pending suggestion (loop). */
    public JsonObject decide(String actionId, boolean apply) {
        return supervisor.decide(Objects.requireNonNull(actionId), apply);
    }

    /** {@code POST /api/ai/supervise {project}} (loop): one round now; completes with the feed entry. */
    public CompletableFuture<JsonObject> superviseNow(String projectId) {
        if (!cfg().enabled()) {
            throw new AiException(AiException.DISABLED, "the AI is switched off (settings → ai)").toApi();
        }
        return supervisor.runNow(m.projects.require(projectId));
    }

    /** One supervisor pass over every running project (tests; the timer calls it every 5 s). */
    void tick() {
        supervisor.tick();
    }
}
