package io.github.krekerdm.baritonebots.manager.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build project end to end against a scripted bot over the real link: BOM, sector progress, restock from the
 * supply chest, build, verify, final pass, done; plus "manual tasks win" and the project API rules.
 */
class BuildProjectFlowTest {
    /** The schematic: 8 × 2 × 1 stone placed at (0,64,5). */
    private static final Box FOOTPRINT = new Box(new io.github.krekerdm.baritonebots.common.geom.Pos(0, 64, 5),
            new io.github.krekerdm.baritonebots.common.geom.Pos(7, 65, 5));

    @TempDir
    Path dir;
    private Manager m;
    private ScriptedBot bot;

    /** Answers queries from a tiny world model and finishes every task successfully. */
    private final class ScriptedBot implements AutoCloseable {
        final Socket socket;
        final LineCodec in;
        final OutputStream out;
        final List<JsonObject> tasks = new CopyOnWriteArrayList<>();
        final List<String> cancels = new CopyOnWriteArrayList<>();
        volatile boolean built;
        volatile boolean holdTake; // do not finish take tasks (to test cancellation)
        /** "x,y,z" → contents; the bot's inventory. Touched by the reader thread only. */
        final Map<String, Map<String, Integer>> chests = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Integer> inv = new java.util.concurrent.ConcurrentHashMap<>();
        final Thread reader;

        ScriptedBot(String id) throws IOException {
            socket = new Socket("127.0.0.1", m.link.port());
            in = new LineCodec(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            out = socket.getOutputStream();
            send(Envelope.of(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", id, "secret", m.secrets.linkSecret(),
                    "username", id, "modVersion", "0.1.0", "mcVersion", "26.2", "baritoneVersion", "1.19.0", "pid", 1)));
            status(id, Json.obj());
            reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        handle(id, Envelope.decode(line));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // socket closed
                }
            }, "scripted-bot");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized void send(Envelope e) throws IOException {
            LineCodec.writeLine(out, e.encode(), Protocol.MAX_LINE_BYTES);
        }

        void status(String id, JsonObject items) throws IOException {
            send(Envelope.of(MessageTypes.STATUS, Json.obj("botId", id, "username", id, "state", "online",
                    "dim", "minecraft:overworld", "pos", Json.obj("x", 3.5, "y", 64, "z", 2.5), "freeSlots", 30,
                    "items", items, "armor", Json.arr(), "time", System.currentTimeMillis())));
        }

        private void handle(String id, Envelope e) throws IOException {
            switch (e.t()) {
                case MessageTypes.QUERY -> {
                    String kind = Json.getString(e.d(), "kind", "");
                    JsonObject args = Json.getObj(e.d(), "args");
                    send(Envelope.reply(MessageTypes.RESULT, e.id(), answer(kind, args)));
                }
                case MessageTypes.TASK -> {
                    tasks.add(e.d());
                    String type = Json.getString(e.d(), "type", "");
                    JsonObject data = new JsonObject();
                    JsonObject args = e.d().getAsJsonObject("args");
                    if ("take".equals(type)) {
                        if (holdTake) {
                            return;
                        }
                        String key = key(args.getAsJsonObject("container"));
                        JsonObject taken = new JsonObject();
                        for (var it : args.getAsJsonArray("items")) {
                            String item = it.getAsJsonObject().get("item").getAsString();
                            int want = it.getAsJsonObject().get("count").getAsInt();
                            int have = chests.getOrDefault(key, new java.util.HashMap<>()).getOrDefault(item, 0);
                            int n = Math.min(want, have);
                            chests.get(key).merge(item, -n, Integer::sum);
                            inv.merge(item, n, Integer::sum);
                            taken.addProperty(item, n);
                        }
                        data = Json.obj("taken", taken, "missing", Json.obj());
                        status(id, Json.toObject(inv));
                        snapshot(key);
                    } else if ("deposit".equals(type)) {
                        String key = key(args.getAsJsonArray("containers").get(0).getAsJsonObject());
                        JsonObject moved = new JsonObject();
                        for (var en : new java.util.HashMap<>(inv).entrySet()) {
                            chests.computeIfAbsent(key, k -> new java.util.HashMap<>()).merge(en.getKey(), en.getValue(), Integer::sum);
                            moved.addProperty(en.getKey(), en.getValue());
                        }
                        inv.clear();
                        data = Json.obj("moved", moved, "left", Json.obj());
                        status(id, Json.obj());
                        snapshot(key);
                    } else if ("build".equals(type)) {
                        built = true;
                        inv.clear();
                        status(id, Json.obj());
                    }
                    send(Envelope.of(MessageTypes.TASK_DONE, Json.obj("id", Json.getString(e.d(), "id", ""), "type", type,
                            "ok", true, "data", data, "durationMs", 5)));
                }
                case MessageTypes.CANCEL -> {
                    String taskId = Json.getString(e.d(), "taskId", "");
                    cancels.add(taskId);
                    JsonObject t = tasks.stream().filter(x -> taskId.equals(Json.getString(x, "id", ""))).findFirst()
                            .orElse(new JsonObject());
                    send(Envelope.of(MessageTypes.TASK_DONE, Json.obj("id", taskId, "type", Json.getString(t, "type", "take"),
                            "ok", false, "reason", "cancelled", "durationMs", 5)));
                }
                default -> {
                    // welcome, config, ...
                }
            }
        }

        private QueryResult answer(String kind, JsonObject args) {
            if ("bom".equals(kind)) {
                return QueryResult.success(Json.obj("items", Json.obj("minecraft:stone", 16), "blocks", 16));
            }
            if ("progress".equals(kind)) {
                Box box = args.has("box") ? Box.fromJson(args.get("box")) : FOOTPRINT;
                int n = (int) box.intersection(FOOTPRINT).map(Box::volume).orElse(0L).longValue();
                return built
                        ? QueryResult.success(Json.obj("total", n, "correct", n, "missing", 0, "wrong", 0, "unloaded", 0,
                                "toClear", 0, "remaining", Json.obj()))
                        : QueryResult.success(Json.obj("total", n, "correct", 0, "missing", n, "wrong", 0, "unloaded", 0,
                                "toClear", 0, "remaining", Json.obj("minecraft:stone", n)));
            }
            return QueryResult.failure("unsupported");
        }

        long count(String type) {
            return tasks.stream().filter(t -> type.equals(Json.getString(t, "type", ""))).count();
        }

        private String key(JsonObject pos) {
            return pos.get("x").getAsInt() + "," + pos.get("y").getAsInt() + "," + pos.get("z").getAsInt();
        }

        private void snapshot(String key) throws IOException {
            String[] p = key.split(",");
            com.google.gson.JsonArray items = new com.google.gson.JsonArray();
            int slot = 0;
            for (var en : chests.getOrDefault(key, Map.of()).entrySet()) {
                if (en.getValue() > 0) {
                    items.add(Json.obj("slot", slot++, "item", en.getKey(), "count", en.getValue()));
                }
            }
            send(Envelope.of(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld",
                    "pos", Json.obj("x", Integer.parseInt(p[0]), "y", Integer.parseInt(p[1]), "z", Integer.parseInt(p[2])),
                    "block", "minecraft:chest", "size", 27, "free", 27 - slot, "items", items,
                    "time", System.currentTimeMillis(), "open", false)));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        m = new Manager(dir, true, true);
        m.initState();
        TestSchematic.spongeV2(dir.resolve("schematics/wall.schem"), 8, 2, 1, "minecraft:stone");
        m.loop.awaitRun(() -> {
            m.config.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main"));
            m.worlds.get("main").applySections(Json.obj("containers", Json.arr(Json.obj("dim", "minecraft:overworld",
                    "pos", Json.obj("x", 10, "y", 64, "z", 0), "block", "minecraft:chest", "roles", Json.arr("supply")))));
            m.worlds.onSnapshot("main", new io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot("minecraft:overworld",
                    new io.github.krekerdm.baritonebots.common.geom.Pos(10, 64, 0), "minecraft:chest", 27, 26,
                    List.of(new io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot.SlotItem(0, "minecraft:stone", 64)),
                    1, false));
        });
        m.link.start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (bot != null) {
            bot.close();
        }
        m.link.stop();
        m.loop.shutdown(1_000);
    }

    private JsonObject project() {
        return Json.obj("name", "Wall", "kind", "build", "serverId", "main", "bots", "any",
                "config", Json.obj("schematic", "wall.schem", "origin", Json.obj("x", 0, "y", 64, "z", 5),
                        "dim", "minecraft:overworld", "rotation", 0, "mirror", "none"));
    }

    /** Ticks the planner until {@code done} holds (the timer is not started in tests). */
    private void tickUntil(BooleanSupplier done, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            m.loop.awaitRun(m.planner::tick);
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what + "; project: "
                + m.loop.await(() -> m.projects.view(m.projects.list().isEmpty() ? null
                : m.projects.get(Json.getString(m.projects.list().getFirst(), "id", "")), true)));
    }

    private String status(String id) {
        return m.loop.await(() -> m.projects.get(id).status);
    }

    @Test
    void buildsVerifiesAndFinishes() throws Exception {
        bot = new ScriptedBot("bot1");
        bot.chests.put("10,64,0", new java.util.HashMap<>(Map.of("minecraft:stone", 64)));
        tickUntil(() -> m.loop.await(() -> m.bots.require("bot1").online()), "bot online");
        String id = Json.getString(m.loop.await(() -> m.projects.create(project())), "id", "");
        assertEquals("draft", status(id));
        m.loop.await(() -> m.projects.action(id, "start"));
        tickUntil(() -> "done".equals(status(id)), "project done");

        assertEquals(1, bot.count("take"), "one restock from the supply chest");
        JsonObject take = bot.tasks.stream().filter(t -> "take".equals(Json.getString(t, "type", ""))).findFirst().orElseThrow();
        assertEquals(10, take.getAsJsonObject("args").getAsJsonObject("container").get("x").getAsInt());
        assertEquals(16, take.getAsJsonObject("args").getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
        assertTrue(Json.getString(take, "origin", "").equals("project:" + id));
        JsonObject build = bot.tasks.stream().filter(t -> "build".equals(Json.getString(t, "type", ""))).findFirst().orElseThrow();
        assertEquals(FOOTPRINT, Box.fromJson(build.getAsJsonObject("args").get("box")), "one builder = one sector");
        assertTrue(build.getAsJsonObject("args").get("file").getAsString().endsWith("wall.schem"));

        JsonObject view = m.loop.await(() -> m.projects.view(m.projects.get(id), true));
        JsonObject progress = view.getAsJsonObject("progress");
        assertEquals(16, progress.get("placed").getAsInt());
        assertEquals(16, progress.get("total").getAsInt());
        assertEquals(100.0, progress.get("percent").getAsDouble());
        assertTrue(view.getAsJsonArray("sectors").get(0).getAsJsonObject().get("verified").getAsBoolean());
        assertEquals("minecraft:stone", view.getAsJsonArray("bom").get(0).getAsJsonObject().get("item").getAsString());
        assertTrue(m.loop.await(() -> m.events.query(100, null, null, id)).stream()
                .anyMatch(e -> "project_done".equals(e.kind())));
        m.loop.awaitRun(m.projects::flush);
        JsonObject saved = Json.parseObject(Files.readString(dir.resolve("projects/" + id + ".json")));
        assertEquals("done", saved.get("status").getAsString());
        assertTrue(saved.getAsJsonObject("state").getAsJsonObject("bom").has("minecraft:stone"), "BOM cached");
    }

    @Test
    void manualTaskWinsOverProjectWork() throws Exception {
        bot = new ScriptedBot("bot1");
        bot.holdTake = true;
        tickUntil(() -> m.loop.await(() -> m.bots.require("bot1").online()), "bot online");
        String id = Json.getString(m.loop.await(() -> m.projects.create(project())), "id", "");
        m.loop.await(() -> m.projects.action(id, "start"));
        tickUntil(() -> bot.count("take") == 1, "restock task");
        String takeId = Json.getString(bot.tasks.getFirst(), "id", "");
        m.loop.awaitRun(() -> m.dispatcher.addTemplate(m.bots.require("bot1"), Json.obj("type", "idle"),
                TaskQueue.Mode.APPEND, "panel"));
        tickUntil(() -> bot.count("idle") == 1, "manual task dispatched");
        assertTrue(bot.cancels.contains(takeId), "the project task was cancelled");
        List<String> types = bot.tasks.stream().map(t -> Json.getString(t, "type", "")).toList();
        assertEquals(List.of("take", "idle"), types.subList(0, 2), "manual work ran before any new project work");
        assertEquals("running", status(id), "the project itself keeps running");

        m.loop.await(() -> m.projects.action(id, "pause"));
        assertEquals("paused", status(id));
        assertThrows(io.github.krekerdm.baritonebots.manager.http.ApiException.class,
                () -> m.loop.await(() -> m.projects.replace(id, Json.obj("config", Json.obj("schematic", "wall.schem",
                        "origin", Json.obj("x", 1, "y", 64, "z", 5))))));
        m.loop.await(() -> m.projects.replace(id, Json.obj("name", "Wall 2", "priority", 9)));
        m.loop.await(() -> m.projects.action(id, "stop"));
        assertEquals("draft", status(id));
        m.loop.awaitRun(() -> m.projects.delete(id));
        assertTrue(m.loop.await(() -> m.projects.list()).isEmpty());
    }

    @Test
    void haulerMovesStorageStockIntoSupply() throws Exception {
        m.loop.awaitRun(() -> {
            m.config.patch(Json.obj("bots", Json.arr(Json.obj("id", "bot1", "username", "Bot1", "serverId", "main",
                    "roles", Json.arr("hauler")))));
            m.worlds.get("main").applySections(Json.obj("containers", Json.arr(
                    Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", 10, "y", 64, "z", 0),
                            "block", "minecraft:chest", "roles", Json.arr("supply")),
                    Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", 12, "y", 64, "z", 0),
                            "block", "minecraft:chest", "roles", Json.arr("storage")))));
            var ow = "minecraft:overworld";
            m.worlds.onSnapshot("main", new io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot(ow,
                    new io.github.krekerdm.baritonebots.common.geom.Pos(10, 64, 0), "minecraft:chest", 27, 27, List.of(), 1, false));
            m.worlds.onSnapshot("main", new io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot(ow,
                    new io.github.krekerdm.baritonebots.common.geom.Pos(12, 64, 0), "minecraft:chest", 27, 26,
                    List.of(new io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot.SlotItem(0, "minecraft:stone", 64)),
                    1, false));
        });
        bot = new ScriptedBot("bot1");
        bot.chests.put("12,64,0", new java.util.HashMap<>(Map.of("minecraft:stone", 64)));
        tickUntil(() -> m.loop.await(() -> m.bots.require("bot1").online()), "bot online");
        String id = Json.getString(m.loop.await(() -> m.projects.create(project())), "id", "");
        m.loop.await(() -> m.projects.action(id, "start"));
        tickUntil(() -> bot.count("deposit") == 1, "haul delivered");
        for (int i = 0; i < 5; i++) {
            m.loop.awaitRun(m.planner::tick);
            Thread.sleep(100);
        }
        assertEquals(1, bot.count("take"), "the deficit is covered after one haul");
        JsonObject take = bot.tasks.getFirst();
        assertEquals(12, take.getAsJsonObject("args").getAsJsonObject("container").get("x").getAsInt(), "from storage");
        JsonObject deposit = bot.tasks.stream().filter(t -> "deposit".equals(Json.getString(t, "type", ""))).findFirst().orElseThrow();
        assertEquals(10, deposit.getAsJsonObject("args").getAsJsonArray("containers").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals("minecraft:stone", deposit.getAsJsonObject("args").getAsJsonArray("only").get(0).getAsString());
        assertEquals(0, bot.count("build"), "a hauler-only bot never builds");
        JsonObject row = m.loop.await(() -> m.projects.view(m.projects.get(id), true)).getAsJsonArray("bom").get(0).getAsJsonObject();
        assertEquals(16, row.get("inSupply").getAsInt());
        assertEquals(0, row.get("deficit").getAsInt());
    }

    @Test
    void validation() {
        JsonObject bad = project();
        bad.getAsJsonObject("config").addProperty("schematic", "missing.schem");
        bad.getAsJsonObject("config").addProperty("rotation", 45);
        bad.addProperty("serverId", "main");
        ValidationException e = assertThrows(ValidationException.class, () -> m.loop.await(() -> m.projects.create(bad)));
        assertEquals("not_found", e.fields().get("config.schematic"));
        assertEquals("enum", e.fields().get("config.rotation"));
        JsonObject noServer = project();
        noServer.addProperty("serverId", "nope");
        noServer.add("bots", Json.arr("ghost"));
        ValidationException e2 = assertThrows(ValidationException.class, () -> m.loop.await(() -> m.projects.create(noServer)));
        assertEquals("unknown_server", e2.fields().get("serverId"));
        assertEquals("not_found", e2.fields().get("bots[0]"));
        JsonObject alias = project();
        alias.remove("config");
        alias.add("placement", Json.obj("schematic", "wall.schem", "origin", Json.arr(0, 64, 5), "rotation", -90));
        JsonObject created = m.loop.await(() -> m.projects.create(alias));
        assertEquals(270, created.getAsJsonObject("config").get("rotation").getAsInt());
        assertEquals("none", created.getAsJsonObject("config").get("mirror").getAsString());
    }
}
