package io.github.krekerdm.baritonebots.manager.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC §5.7c / §5.7d against a fake Ollama ({@code com.sun.net.httpserver}) answering with canned JSON: plan
 * validation (good and bad model output), running a plan through the normal queues, the supervisor's action
 * whitelist with Apply / Dismiss and auto mode, event triggers with debounce, and every failure mode (unreachable,
 * timeout, invalid JSON, missing model, switched off).
 */
class AiTest {
    @TempDir
    Path dir;
    private Manager m;
    private HttpServer fake;
    private int port;

    /** One canned answer of the fake server. */
    private record Answer(int status, String body, long delayMs) {
    }

    private final ConcurrentLinkedDeque<Answer> answers = new ConcurrentLinkedDeque<>();
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/api/chat", this::chat);
        fake.createContext("/api/tags", ex -> respond(ex, 200,
                "{\"models\":[{\"name\":\"qwen2.5:7b-instruct\"},{\"name\":\"llama3:latest\"}]}"));
        fake.setExecutor(Executors.newCachedThreadPool());
        fake.start();
        port = fake.getAddress().getPort();
        Files.createDirectories(dir.resolve("schematics"));
        Files.writeString(dir.resolve("schematics").resolve("castle.schem"), "not parsed by the validator");
        m = new Manager(dir, true, true);
        m.initState();
        m.loop.awaitRun(() -> {
            m.config.patch(Json.obj("autopilot", Json.obj("supply", false, "sort", false, "idleWork", false,
                    "discovery", false, "stuckSec", 0),
                    "ai", Json.obj("enabled", true, "endpoint", "http://127.0.0.1:" + port, "timeoutSec", 3)));
            m.config.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main"));
            m.config.addItem("bots", Json.obj("id", "bot2", "username", "Bot2", "serverId", "main",
                    "roles", Json.arr("miner")));
            WorldDoc doc = m.worlds.get("main");
            doc.waypoints.add(new WorldDoc.Waypoint("mine", "minecraft:overworld", new Pos(10, 12, 10)));
            doc.areas.add(new WorldDoc.Area("field", "minecraft:overworld", new Box(new Pos(0, 60, 0), new Pos(8, 62, 8))));
        });
    }

    @AfterEach
    void tearDown() {
        fake.stop(0);
        m.loop.shutdown(1_000);
    }

    private void chat(HttpExchange ex) throws IOException {
        requests.add(Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Answer a = answers.poll();
        if (a == null) {
            respond(ex, 500, "{\"error\":\"no canned answer\"}");
            return;
        }
        if (a.delayMs() > 0) {
            try {
                Thread.sleep(a.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(ex, a.status(), a.body());
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        } catch (IOException e) {
            // the client gave up (timeout test)
        }
    }

    /** The model's answer as Ollama wraps it: message.content holds the JSON text. */
    private void answer(JsonObject content) {
        answerText(Json.toJson(content));
    }

    private void answerText(String content) {
        answers.add(new Answer(200, Json.toJson(Json.obj("model", "qwen2.5:7b-instruct", "done", true,
                "message", Json.obj("role", "assistant", "content", content))), 0));
    }

    private JsonObject plan(String text, String... botIds) throws Exception {
        CompletableFuture<JsonObject> f = m.loop.await(() -> m.ai.plan(Json.obj("text", text, "botIds",
                botIds.length == 0 ? null : Json.arr((Object[]) botIds))));
        return get(f);
    }

    private static JsonObject get(CompletableFuture<JsonObject> f) throws Exception {
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static List<String> reasons(JsonObject plan) {
        List<String> out = new ArrayList<>();
        for (JsonElement r : plan.getAsJsonArray("rejected")) {
            out.add(Json.getString(r.getAsJsonObject(), "reason", "") + ":" + Json.getString(r.getAsJsonObject(), "detail", ""));
        }
        return out;
    }

    private static JsonObject step(String bot, String type, JsonObject args) {
        return Json.obj("bot", bot, "type", type, "args", args);
    }

    private String startGather() {
        JsonObject p = m.loop.await(() -> m.projects.create(Json.obj("name", "Iron", "kind", "gather", "serverId", "main",
                "bots", "any", "config", Json.obj("quotas", Json.obj("minecraft:iron_ingot", 64)), "start", true)));
        return Json.getString(p, "id", "");
    }

    private static void waitFor(Supplier<Boolean> cond, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 8_000;
        while (!cond.get()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------------------------ command box

    @Test
    void planKeepsValidItemsAndDropsEveryBadOneWithAReason() throws Exception {
        answer(Json.obj("steps", Json.arr(
                step("any", "progress", Json.obj("tier", "iron")),
                step("Bot1", "obtain", Json.obj("item", "iron_ingot", "count", 64)),
                step("bot1", "goto_waypoint", Json.obj("name", "MINE")),
                step("bot1", "farm", Json.obj("center", Json.obj("ref", "home"), "range", "12")),
                step("bot1", "teleport", new JsonObject()),
                step("bot1", "baritone", Json.obj("command", "mine diamond_ore")),
                step("bot1", "supply", new JsonObject()),
                step("bot1", "obtain", Json.obj("item", "minecraft:iron_ingot", "count", 64, "speed", "fast")),
                step("bot1", "goto", Json.obj("pos", Json.obj("ref", "waypoint", "name", "castle"))),
                step("bot1", "goto", Json.obj("pos", Json.obj("x", 100, "y", 64, "z", -20))),
                step("bot9", "home", new JsonObject()),
                step("bot1", "obtain", Json.obj("item", "minecraft:iron_ingot", "count", 99999)),
                step("bot1", "kit", Json.obj("kitId", "nope")),
                step("bot1", "shear", Json.obj("box", Json.obj("ref", "area", "name", "field")))),
                "orders", Json.arr(Json.obj("item", "torch", "min", 128, "into", "storage"),
                        Json.obj("item", "minecraft:torch", "min", 0, "into", "storage"),
                        Json.obj("item", "minecraft:bread", "min", 10, "into", "chest_42")),
                "project", Json.obj("kind", "gather", "name", "Iron", "bots", "any",
                        "config", Json.obj("quotas", Json.obj("iron_ingot", 64))),
                "notes", "ok"));
        JsonObject r = plan("развиться до железки и собрать железо");
        JsonArray plan = r.getAsJsonArray("plan");
        assertEquals(5, plan.size(), "progress, obtain, goto_waypoint, farm, shear: " + plan);
        assertEquals("any", Json.getString(plan.get(0).getAsJsonObject(), "botId", ""));
        JsonObject obtain = plan.get(1).getAsJsonObject();
        assertEquals("bot1", Json.getString(obtain, "botId", ""), "username → id");
        assertEquals("minecraft:iron_ingot", obtain.getAsJsonObject("step").getAsJsonObject("args").get("item").getAsString());
        assertEquals("mine", plan.get(2).getAsJsonObject().getAsJsonObject("step").getAsJsonObject("args").get("name")
                .getAsString(), "waypoint names are matched case-insensitively and stored exactly");
        assertEquals(12, plan.get(3).getAsJsonObject().getAsJsonObject("step").getAsJsonObject("args").get("range").getAsInt());
        assertEquals(List.of("unknown_type:teleport", "not_allowed_type:baritone", "not_allowed_type:supply",
                "unknown_arg:speed", "unknown_waypoint:castle", "invented_coordinates:pos", "unknown_bot:bot9",
                "bad_arg:count", "unknown_kit:nope", "bad_order:min", "bad_order:into"), reasons(r));
        assertEquals(1, r.getAsJsonArray("orders").size());
        JsonObject order = r.getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("minecraft:torch", order.get("item").getAsString());
        assertEquals("main", order.get("serverId").getAsString());
        JsonObject project = r.getAsJsonObject("project");
        assertEquals("gather", project.get("kind").getAsString());
        assertEquals(64, project.getAsJsonObject("config").getAsJsonObject("quotas").get("minecraft:iron_ingot").getAsInt());
        assertEquals("ok", r.get("notes").getAsString());
        assertNotNull(r.get("id"));

        JsonObject req = requests.getFirst();
        assertFalse(req.get("stream").getAsBoolean());
        assertEquals("object", req.getAsJsonObject("format").get("type").getAsString(), "format = a JSON schema");
        assertTrue(req.getAsJsonObject("options").get("temperature").getAsDouble() <= 0.2);
        String system = req.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("obtain(item*:item"), system);
        assertTrue(system.contains("bot2 offline roles=miner"), system);
        assertTrue(system.contains("Waypoints: mine") && system.contains("Schematics: castle.schem"), system);
        assertFalse(system.contains("baritone("), "raw Baritone commands are not offered");
        assertTrue(system.contains("Write \"notes\" in Russian"));
        System.out.println("plan system prompt: " + system.length() + " chars, schema: "
                + Json.toJson(req.getAsJsonObject("format")).length() + " chars");
        assertTrue(system.length() < 12_000, "a 7B model reads the whole prompt on every request: " + system.length());
    }

    @Test
    void coordinatesFromTheRequestProjectDraftsAndBotLimits() throws Exception {
        AiContext ctx = m.loop.await(() -> AiContext.capture(m, "иди на 100 64 -20", List.of("bot2")));
        JsonObject s = PlanValidator.step("goto", Json.obj("pos", Json.obj("x", 100, "y", 64, "z", -20)), "main", ctx);
        assertEquals(-20, s.getAsJsonObject("args").getAsJsonObject("pos").get("z").getAsInt(), "numbers the owner typed");
        PlanValidator.Reject r = assertThrows(PlanValidator.Reject.class, () -> PlanValidator.bot("bot1", ctx, true));
        assertEquals("bot_not_allowed", r.reason(), "the box on bot2's page only plans for bot2");

        AiContext all = m.loop.await(() -> AiContext.capture(m, "построй замок из castle.schem у дома всеми ботами", null));
        JsonObject draft = PlanValidator.project(Json.obj("kind", "build", "bots", "any",
                "config", Json.obj("schematic", "castle", "rotation", 90)), all);
        assertEquals("castle.schem", draft.getAsJsonObject("config").get("schematic").getAsString());
        assertEquals("auto", draft.getAsJsonObject("config").getAsJsonObject("origin").get("ref").getAsString(),
                "no origin → the nearest flat spot near home");
        assertEquals(90, draft.getAsJsonObject("config").get("rotation").getAsInt());
        assertEquals("castle", draft.get("name").getAsString());
        assertEquals("any", draft.get("bots").getAsString());
        assertEquals("unknown_schematic", assertThrows(PlanValidator.Reject.class, () -> PlanValidator.project(
                Json.obj("kind", "build", "config", Json.obj("schematic", "tower")), all)).reason());
        assertEquals("unknown_kind", assertThrows(PlanValidator.Reject.class, () -> PlanValidator.project(
                Json.obj("kind", "teleport", "config", new JsonObject()), all)).reason());
        assertEquals("unknown_area", assertThrows(PlanValidator.Reject.class, () -> PlanValidator.project(
                Json.obj("kind", "clear", "config", Json.obj("box", Json.obj("ref", "area", "name", "moon"))), all)).reason());
        JsonObject limited = PlanValidator.project(Json.obj("kind", "gather", "bots", "any",
                "config", Json.obj("quotas", Json.obj("minecraft:stone", 10))), ctx);
        assertEquals(Json.arr("bot2"), limited.get("bots"), "\"all bots\" from a bot page = that bot");
    }

    @Test
    void runQueuesThroughTheNormalQueuesOrdersAndProjects() throws Exception {
        answer(Json.obj("steps", Json.arr(
                        step("bot1", "obtain", Json.obj("item", "minecraft:iron_ingot", "count", 64)),
                        step("bot1", "deposit_storage", new JsonObject()),
                        step("any", "progress", Json.obj("tier", "stone"))),
                "orders", Json.arr(Json.obj("item", "minecraft:torch", "min", 128, "into", "storage")),
                "project", Json.obj("kind", "gather", "name", "Stone", "bots", Json.arr("bot1"),
                        "config", Json.obj("quotas", Json.obj("minecraft:stone", 32)))));
        JsonObject planned = plan("собери 64 железа и сложи в склад");
        String id = planned.get("id").getAsString();
        BotState b1 = m.loop.await(() -> m.bots.get("bot1"));
        assertEquals(0, (int) m.loop.await(() -> b1.queue.size()), "nothing runs before the confirmation");

        JsonObject run = get(m.loop.await(() -> m.ai.run(Json.obj("id", id))));
        assertEquals(2, run.getAsJsonArray("queued").size());
        assertEquals("ai:" + id, run.get("origin").getAsString());
        assertEquals(2, (int) m.loop.await(() -> (int) b1.queue.items().stream()
                .filter(e -> e.hasOrigin("ai:" + id)).count()), "obtain + deposit_storage on bot1, origin ai:<id>");
        assertEquals(List.of("no_free_bot"), run.getAsJsonArray("failed").asList().stream()
                .map(e -> Json.getString(e.getAsJsonObject(), "reason", "")).toList(), "no bot is online for \"any\"");
        assertEquals("torch", run.getAsJsonArray("orders").get(0).getAsString());
        assertTrue(m.loop.await(() -> m.config.get().orders().stream().anyMatch(o -> o.item().equals("minecraft:torch")
                && o.min() == 128)));
        JsonObject project = run.getAsJsonObject("project");
        assertEquals("running", project.get("status").getAsString(), "a confirmed project starts");
        assertEquals(Json.arr("bot1"), project.get("bots"));
        assertTrue(m.loop.await(() -> m.events.query(50, null, null).stream().anyMatch(e -> "ai_plan_run".equals(e.kind()))));

        ApiException again = assertThrows(ApiException.class, () -> get(m.loop.await(() -> m.ai.run(Json.obj("id", id)))));
        assertEquals(404, again.status(), "a plan runs once");

        // an edited copy from the client is checked again: the invented step never reaches a queue
        JsonObject edited = Json.obj("plan", Json.arr(Json.obj("botId", "bot1", "step", Json.obj("type", "baritone",
                "args", Json.obj("command", "mine diamond_ore")))));
        JsonObject run2 = get(m.loop.await(() -> m.ai.run(edited)));
        assertEquals(0, run2.getAsJsonArray("queued").size());
        assertEquals("not_allowed_type", run2.getAsJsonArray("failed").get(0).getAsJsonObject().get("reason").getAsString());

        // a second order for the same item raises the existing one instead of adding a duplicate
        String again2 = m.loop.await(() -> m.ai.addOrder(Json.obj("item", "minecraft:torch", "min", 256, "into", "storage",
                "serverId", "main")));
        assertEquals("torch", again2);
        assertEquals(1, (long) m.loop.await(() -> m.config.get().orders().stream()
                .filter(o -> o.item().equals("minecraft:torch")).count()));
    }

    // ------------------------------------------------------------------ supervisor

    private JsonObject supervise(String pid) throws Exception {
        return get(m.loop.await(() -> m.ai.superviseNow(pid)));
    }

    private static JsonObject action(JsonObject entry, int i) {
        return entry.getAsJsonArray("actions").get(i).getAsJsonObject();
    }

    @Test
    void supervisorSuggestsOnlyWhitelistedActionsAndAppliesOrDismissesThem() throws Exception {
        String pid = startGather();
        answer(Json.obj("summary", "43 %, Bot2 ждёт стекло, песка нет", "actions", Json.arr(
                Json.obj("type", "set_priority", "priority", 7, "reason", "проект важнее"),
                Json.obj("type", "notify", "text", "Нужен песок", "reason", "ручные материалы"),
                Json.obj("type", "delete_world", "reason", "x"),
                Json.obj("type", "reassign", "bot", "bot2", "role", "builder", "reason", "x"),
                Json.obj("type", "add_standing_order", "item", "minecraft:sand", "min", 64, "into", "storage",
                        "reason", "песок для стекла"),
                Json.obj("type", "reassign", "bot", "bot1", "role", "miner", "reason", "sixth"))));
        JsonObject entry = supervise(pid);
        assertEquals("summary", entry.get("kind").getAsString());
        assertEquals("43 %, Bot2 ждёт стекло, песка нет", entry.get("summary").getAsString());
        List<String> statuses = entry.getAsJsonArray("actions").asList().stream()
                .map(a -> Json.getString(a.getAsJsonObject(), "status", "") + "/" + Json.getString(a.getAsJsonObject(), "why", ""))
                .toList();
        assertEquals(List.of("pending/", "pending/", "rejected/unknown_action", "rejected/role_not_allowed",
                "pending/", "rejected/too_many"), statuses, "whitelist, live checks, at most 5 actions per round");
        assertEquals(3, Json.getInt(m.loop.await(() -> m.ai.feed(pid)), "pending", -1));
        JsonObject digest = requests.getFirst();
        String user = digest.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(user.contains("\"progress\"") && user.contains("\"assignments\"") && user.contains("\"events\""), user);

        String prio = action(entry, 0).get("id").getAsString();
        JsonObject after = m.loop.await(() -> m.ai.decide(prio, true));
        assertEquals("applied", Json.getString(action(after, 0), "status", ""));
        assertEquals(7, (int) m.loop.await(() -> m.projects.get(pid).priority));
        assertTrue(m.loop.await(() -> m.events.query(50, null, null).stream().anyMatch(e -> "ai_action".equals(e.kind())
                && e.message().contains("проект важнее"))), "applied actions are logged with their reason");

        String note = action(entry, 1).get("id").getAsString();
        assertEquals("dismissed", Json.getString(action(m.loop.await(() -> m.ai.decide(note, false)), 1), "status", ""));
        ApiException twice = assertThrows(ApiException.class, () -> m.loop.await(() -> m.ai.decide(note, true)));
        assertEquals("not_pending", twice.code());
        assertFalse(m.loop.await(() -> m.events.query(50, null, null).stream().anyMatch(e -> "ai_notify".equals(e.kind()))));

        // a new round supersedes the open suggestion (the order) instead of piling up
        answer(Json.obj("summary", "50 %", "actions", new JsonArray()));
        supervise(pid);
        String order = action(entry, 4).get("id").getAsString();
        JsonObject feed = m.loop.await(() -> m.ai.feed(pid));
        assertEquals(0, Json.getInt(feed, "pending", -1));
        assertEquals("50 %", feed.getAsJsonArray("entries").get(0).getAsJsonObject().get("summary").getAsString(),
                "newest first");
        assertEquals("not_pending", assertThrows(ApiException.class, () -> m.loop.await(() -> m.ai.decide(order, true))).code());
        assertTrue(m.loop.await(() -> m.config.get().orders().isEmpty()));
    }

    @Test
    void autoModeAppliesAtOnceAndLogsEveryActionWithItsReason() throws Exception {
        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("mode", "auto"))));
        String pid = startGather();
        answer(Json.obj("summary", "Bot1 простаивает", "actions", Json.arr(
                Json.obj("type", "set_priority", "priority", 9, "reason", "успеть к вечеру"),
                Json.obj("type", "add_task", "bot", "bot1", "step", Json.obj("type", "obtain",
                        "args", Json.obj("item", "minecraft:iron_pickaxe", "count", 1)), "reason", "нужна кирка"),
                Json.obj("type", "add_standing_order", "item", "minecraft:coal", "min", 32, "into", "fuel", "reason", "топливо"),
                Json.obj("type", "add_task", "bot", "bot1", "step", Json.obj("type", "goto",
                        "args", Json.obj("pos", Json.obj("x", 5, "y", 70, "z", 5))), "reason", "x"),
                Json.obj("type", "reassign", "bot", "bot2", "role", "miner", "reason", "руда кончилась"))));
        JsonObject entry = supervise(pid);
        assertEquals("applied", Json.getString(action(entry, 4), "status", ""));
        assertEquals("miner", m.loop.await(() -> m.planner.roles().role("bot2")), "the planner keeps bot2 in the role");
        assertEquals("applied", Json.getString(action(entry, 0), "status", ""));
        assertEquals("auto", Json.getString(action(entry, 0), "by", ""));
        assertEquals("applied", Json.getString(action(entry, 1), "status", ""));
        assertEquals("applied", Json.getString(action(entry, 2), "status", ""));
        assertEquals("rejected", Json.getString(action(entry, 3), "status", ""));
        assertEquals("invented_coordinates", Json.getString(action(entry, 3), "why", ""), "the supervisor has no text with numbers");
        assertEquals(9, (int) m.loop.await(() -> m.projects.get(pid).priority));
        String taskId = Json.getString(action(entry, 1), "taskId", "");
        assertTrue(m.loop.await(() -> m.bots.get("bot1").queue.items().stream().anyMatch(e -> e.id().equals(taskId)
                && e.origin().startsWith("ai:act-") && e.type().equals("obtain"))));
        assertTrue(m.loop.await(() -> m.config.get().orders().stream().anyMatch(o -> o.item().equals("minecraft:coal")
                && o.into().equals("fuel"))));
        assertEquals(4, (long) m.loop.await(() -> m.events.query(100, null, null).stream()
                .filter(e -> "ai_action".equals(e.kind())).count()));
    }

    @Test
    void blockedEventsTriggerAnEarlyRoundDebounced() throws Exception {
        String pid = startGather();
        answer(Json.obj("summary", "заблокировано", "actions", new JsonArray()));
        ManagerEvent blocked = new ManagerEvent(1, System.currentTimeMillis(), "project_blocked", "warn", "manager", null,
                "blocked", null, null, Json.obj("projectId", pid));
        m.loop.awaitRun(() -> m.ai.onEvent(blocked));
        waitFor(() -> m.loop.await(() -> m.ai.feed(pid).getAsJsonArray("entries").size()) == 1, "an early round");
        assertEquals("blocked", m.loop.await(() -> m.ai.feed(pid)).getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("trigger").getAsString());
        m.loop.awaitRun(() -> m.ai.onEvent(blocked));
        m.loop.awaitRun(() -> { });
        Thread.sleep(200);
        assertEquals(1, requests.size(), "a second trigger within 30 s waits");
        // the first periodic round only comes after superviseSec
        m.loop.awaitRun(() -> m.ai.tick());
        assertEquals(1, requests.size());
    }

    // ------------------------------------------------------------------ failures

    @Test
    void failuresAreReportedAndNothingElseChanges() throws Exception {
        answerText("Sure! Here is the plan: obtain iron");
        ApiException invalid = assertThrows(ApiException.class, () -> plan("собери железо"));
        assertEquals("ai_invalid_json", invalid.code());
        assertEquals(502, invalid.status());

        answers.add(new Answer(404, "{\"error\":\"model 'qwen2.5:7b-instruct' not found, try pulling it first\"}", 0));
        assertEquals("ai_model_missing", assertThrows(ApiException.class, () -> plan("собери железо")).code());

        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("timeoutSec", 1))));
        answers.add(new Answer(200, "{}", 2_500));
        ApiException timeout = assertThrows(ApiException.class, () -> plan("собери железо"));
        assertEquals("ai_timeout", timeout.code());
        assertEquals(504, timeout.status());

        JsonObject ok = get(m.loop.await(() -> m.ai.status()));
        assertTrue(ok.get("reachable").getAsBoolean());
        assertEquals("ollama", ok.get("provider").getAsString(), "auto: no /v1/models, /api/tags answers");
        assertTrue(ok.get("modelPresent").getAsBoolean());
        assertEquals(2, ok.getAsJsonArray("models").size());

        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("endpoint", "http://127.0.0.1:" + closed))));
        assertEquals("ai_unreachable", assertThrows(ApiException.class, () -> plan("собери железо")).code());
        JsonObject down = get(m.loop.await(() -> m.ai.status()));
        assertFalse(down.get("reachable").getAsBoolean());
        assertEquals("ai_unreachable", down.getAsJsonObject("error").get("code").getAsString());

        String pid = startGather();
        JsonObject e1 = supervise(pid);
        assertEquals("error", e1.get("kind").getAsString());
        assertEquals("ai_unreachable", e1.get("code").getAsString());
        JsonObject e2 = supervise(pid);
        assertEquals(e1.get("id"), e2.get("id"), "the same failure is counted on one entry");
        assertEquals(2, e2.get("count").getAsInt());
        assertEquals("running", m.loop.await(() -> m.projects.get(pid).status), "the planner keeps going alone");

        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("enabled", false))));
        ApiException off = assertThrows(ApiException.class, () -> plan("собери железо"));
        assertEquals("ai_disabled", off.code());
        assertEquals(409, off.status());
        assertFalse(m.loop.await(() -> m.stateSnapshot()).getAsJsonObject("ai").get("enabled").getAsBoolean());
    }

    /** The model's answer as an OpenAI-compatible server wraps it: choices[0].message.content holds the JSON text. */
    private static Answer openAi(String content) {
        return new Answer(200, Json.toJson(Json.obj("object", "chat.completion", "choices", Json.arr(Json.obj("index", 0,
                "message", Json.obj("role", "assistant", "content", content), "finish_reason", "stop")))), 0);
    }

    @Test
    void openAiCompatibleServerIsDetectedAndUsedWithASchemaFallback() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        fake.createContext("/v1/models", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"qwen2.5-7b-instruct\",\"object\":\"model\"}]}");
        });
        fake.createContext("/v1/chat/completions", this::chat);
        JsonObject both = get(m.loop.await(() -> m.ai.status()));
        assertEquals("ollama", both.get("provider").getAsString(), "Ollama serves both APIs: its own one is kept");

        // LM Studio: 200 with an error object on Ollama's paths, the endpoint typed with /v1/
        fake.removeContext("/api/tags");
        fake.createContext("/api/tags", ex -> respond(ex, 200,
                "{\"error\":\"Unexpected endpoint or method. (GET /api/tags)\"}"));
        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("endpoint", "http://127.0.0.1:" + port + "/v1/",
                "model", "qwen2.5-7b-instruct", "apiKey", "sk-test-key"))));
        JsonObject st = get(m.loop.await(() -> m.ai.status()));
        assertEquals("openai", st.get("provider").getAsString());
        assertEquals("http://127.0.0.1:" + port, st.get("endpoint").getAsString());
        assertTrue(st.get("modelPresent").getAsBoolean());
        assertEquals("Bearer sk-test-key", auth.get());
        assertFalse(m.loop.await(() -> m.config.get().ai()).toString().contains("sk-test-key"), "the key is never printed");

        JsonObject planJson = Json.obj("steps", Json.arr(
                step("bot1", "obtain", Json.obj("item", "minecraft:iron_ingot", "count", 64))));
        answers.add(openAi(Json.toJson(planJson)));
        JsonObject p = plan("собери железо");
        assertTrue(p.has("id"));
        assertEquals(List.of(), reasons(p));
        JsonObject req = requests.get(requests.size() - 1);
        assertEquals("json_schema", req.getAsJsonObject("response_format").get("type").getAsString());
        assertTrue(req.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("strict").getAsBoolean());
        assertEquals("qwen2.5-7b-instruct", req.get("model").getAsString());
        assertTrue(req.has("temperature") && req.has("max_tokens"));

        answers.add(new Answer(400, "{\"error\":{\"message\":\"response_format json_schema is not supported\"}}", 0));
        answers.add(openAi("```json\n" + Json.toJson(planJson) + "\n```"));
        int before = requests.size();
        assertEquals(List.of(), reasons(plan("собери железо")));
        assertEquals(before + 2, requests.size());
        JsonObject retry = requests.get(requests.size() - 1);
        assertEquals("json_object", retry.getAsJsonObject("response_format").get("type").getAsString());
        assertTrue(retry.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()
                .contains("JSON schema"), "the schema moves into the system prompt");

        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("model", "llama-3.1-8b-instruct"))));
        JsonObject missing = get(m.loop.await(() -> m.ai.status()));
        assertTrue(missing.get("reachable").getAsBoolean());
        assertFalse(missing.get("modelPresent").getAsBoolean());
        String msg = missing.get("message").getAsString();
        assertTrue(msg.contains("not loaded") && msg.contains("qwen2.5-7b-instruct"), msg);

        fake.removeContext("/v1/models");
        fake.createContext("/v1/models", ex -> respond(ex, 502, "<html>Bad Gateway from the proxy</html>"));
        m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", Json.obj("provider", "openai"))));
        JsonObject bad = get(m.loop.await(() -> m.ai.status()));
        assertFalse(bad.get("reachable").getAsBoolean());
        assertEquals("HTTP 502: <html>Bad Gateway from the proxy</html>",
                bad.getAsJsonObject("error").get("message").getAsString(), "a real reason instead of a bare 502");
    }

    @Test
    void settingsHaveDefaultsAndAreValidated() {
        var ai = m.loop.await(() -> m.config.get().ai());
        assertEquals("qwen2.5:7b-instruct", ai.model());
        assertEquals(90, ai.superviseSec());
        assertEquals("suggest", ai.mode());
        assertEquals("auto", ai.provider());
        assertEquals("", ai.apiKey());
        for (JsonObject bad : List.of(Json.obj("endpoint", "ftp://127.0.0.1:11434"), Json.obj("endpoint", "localhost"),
                Json.obj("mode", "yolo"), Json.obj("timeoutSec", 0), Json.obj("provider", "gpt"))) {
            assertThrows(ValidationException.class, () -> m.loop.awaitRun(() -> m.config.patch(Json.obj("ai", bad))),
                    bad.toString());
        }
        assertTrue(AiService.sameModel("llama3:latest", "llama3"));
        assertFalse(AiService.sameModel("qwen2.5:14b", "qwen2.5:7b-instruct"));
        assertEquals(1, OllamaClient.parseContent("```json\n{\"a\":1}\n```").get("a").getAsInt(),
                "a Markdown fence around the JSON is tolerated");
    }
}
