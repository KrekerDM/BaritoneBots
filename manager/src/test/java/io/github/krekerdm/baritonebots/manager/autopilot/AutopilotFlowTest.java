package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The autopilot end to end against a scripted bot over the real link: auto-supply before a task, the manual event,
 * inbox sorting through the planner with category adoption, an {@code obtain} goal re-planned from the live
 * inventory, and stuck recovery (step back + one retry, then {@code stuck}).
 */
class AutopilotFlowTest {
    @TempDir
    Path dir;
    private Manager m;
    private Bot bot;

    /** A bot with an inventory and chests; finishes tasks right away unless told to hold mining. */
    private final class Bot implements AutoCloseable {
        final String id = "bot1";
        final Socket socket;
        final LineCodec in;
        final OutputStream out;
        final List<JsonObject> tasks = new CopyOnWriteArrayList<>();
        final List<String> cancels = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, Integer>> chests = new ConcurrentHashMap<>();
        final Map<String, Integer> inv = new ConcurrentHashMap<>();
        final JsonArray nearby = new JsonArray();
        volatile boolean holdMine;
        volatile JsonObject running;

        Bot() throws IOException {
            socket = new Socket("127.0.0.1", m.link.port());
            in = new LineCodec(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            out = socket.getOutputStream();
            send(Envelope.of(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", id, "secret", m.secrets.linkSecret(),
                    "username", id, "modVersion", "0.1.0", "mcVersion", "26.2", "baritoneVersion", "1.19.0", "pid", 1)));
            status();
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        handle(Envelope.decode(line));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // closed
                }
            }, "autopilot-bot");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized void send(Envelope e) throws IOException {
            LineCodec.writeLine(out, e.encode(), Protocol.MAX_LINE_BYTES);
        }

        void status() throws IOException {
            JsonObject st = Json.obj("botId", id, "username", id, "state", "online", "dim", "minecraft:overworld",
                    "pos", Json.obj("x", 0.5, "y", 64, "z", 0.5), "yaw", 0, "freeSlots", 30, "items", Json.toObject(inv),
                    "armor", Json.arr(), "time", System.currentTimeMillis());
            JsonObject r = running;
            if (r != null) {
                st.add("task", Json.obj("id", Json.getString(r, "id", ""), "type", Json.getString(r, "type", ""),
                        "state", "running", "step", "mining", "progress", -1));
            }
            send(Envelope.of(MessageTypes.STATUS, st));
        }

        private void handle(Envelope e) throws IOException {
            switch (e.t()) {
                case MessageTypes.QUERY -> send(Envelope.reply(MessageTypes.RESULT, e.id(),
                        answer(Json.getString(e.d(), "kind", ""), Json.getObj(e.d(), "args"))));
                case MessageTypes.TASK -> task(e.d());
                case MessageTypes.CANCEL -> {
                    String taskId = Json.getString(e.d(), "taskId", "");
                    cancels.add(taskId);
                    running = null;
                    done(taskId, "mine", false, "cancelled", new JsonObject());
                }
                default -> {
                }
            }
        }

        private QueryResult answer(String kind, JsonObject args) {
            return switch (kind) {
                case "inventory" -> {
                    JsonArray slots = new JsonArray();
                    int i = 0;
                    for (Map.Entry<String, Integer> en : inv.entrySet()) {
                        if (en.getValue() > 0) {
                            slots.add(Json.obj("slot", i++, "item", en.getKey(), "count", en.getValue(), "damage", 0,
                                    "maxDamage", 0));
                        }
                    }
                    yield QueryResult.success(Json.obj("slots", slots, "armor", Json.arr(), "selected", 0));
                }
                case "containers_nearby" -> QueryResult.success(Json.obj("containers", nearby.deepCopy()));
                case "block_at" -> {
                    Pos p = Pos.fromJson(args.get("pos"));
                    yield QueryResult.success(Json.obj("block", p != null && p.y() >= 64 ? "minecraft:air" : "minecraft:stone",
                            "loaded", true));
                }
                default -> QueryResult.failure("unsupported");
            };
        }

        private void task(JsonObject t) throws IOException {
            tasks.add(t);
            String type = Json.getString(t, "type", "");
            JsonObject args = Json.getObj(t, "args");
            JsonObject data = new JsonObject();
            switch (type) {
                case "inspect" -> {
                    for (JsonElement p : args.getAsJsonArray("containers")) {
                        snapshot(key(p));
                    }
                }
                case "take" -> {
                    String key = key(args.get("container"));
                    JsonObject taken = new JsonObject();
                    for (JsonElement it : args.getAsJsonArray("items")) {
                        String item = Json.getString(it.getAsJsonObject(), "item", "");
                        int n = Math.min(Json.getInt(it.getAsJsonObject(), "count", 0),
                                chests.computeIfAbsent(key, k -> new HashMap<>()).getOrDefault(item, 0));
                        chests.get(key).merge(item, -n, Integer::sum);
                        inv.merge(item, n, Integer::sum);
                        taken.addProperty(item, n);
                    }
                    data = Json.obj("taken", taken, "missing", Json.obj());
                    snapshot(key);
                }
                case "transfer" -> {
                    String from = key(args.get("from"));
                    String to = key(args.getAsJsonArray("to").get(0));
                    for (JsonElement it : args.getAsJsonArray("items")) {
                        String item = Json.getString(it.getAsJsonObject(), "item", "");
                        int n = Math.min(Json.getInt(it.getAsJsonObject(), "count", 0),
                                chests.computeIfAbsent(from, k -> new HashMap<>()).getOrDefault(item, 0));
                        chests.get(from).merge(item, -n, Integer::sum);
                        chests.computeIfAbsent(to, k -> new HashMap<>()).merge(item, n, Integer::sum);
                    }
                    snapshot(from);
                    snapshot(to);
                }
                case "mine" -> {
                    if (holdMine) {
                        running = t;
                        return;
                    }
                    String first = args.getAsJsonArray("blocks").get(0).getAsString();
                    String drop = "minecraft:stone".equals(first) ? "minecraft:cobblestone" : first;
                    inv.merge(drop, Json.getInt(args, "amount", 1), Integer::sum);
                    data = Json.obj("collected", Json.getInt(args, "amount", 1));
                }
                case "craft" -> {
                    String item = Json.getString(args, "item", "");
                    int count = Json.getInt(args, "count", 1);
                    if (item.endsWith("_planks")) {
                        inv.merge("minecraft:oak_log", -((count + 3) / 4), Integer::sum);
                    }
                    inv.merge(item, count, Integer::sum);
                    data = Json.obj("crafted", count);
                }
                default -> {
                }
            }
            status();
            done(Json.getString(t, "id", ""), type, true, null, data);
        }

        void done(String taskId, String type, boolean ok, String reason, JsonObject data) throws IOException {
            send(Envelope.of(MessageTypes.TASK_DONE, Json.obj("id", taskId, "type", type, "ok", ok, "reason", reason,
                    "data", data, "durationMs", 5)));
        }

        private String key(JsonElement pos) {
            JsonObject p = pos.getAsJsonObject();
            return p.get("x").getAsInt() + "," + p.get("y").getAsInt() + "," + p.get("z").getAsInt();
        }

        void snapshot(String key) throws IOException {
            String[] p = key.split(",");
            JsonArray items = new JsonArray();
            int slot = 0;
            for (Map.Entry<String, Integer> en : chests.getOrDefault(key, Map.of()).entrySet()) {
                if (en.getValue() > 0) {
                    items.add(Json.obj("slot", slot++, "item", en.getKey(), "count", en.getValue()));
                }
            }
            send(Envelope.of(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld",
                    "pos", Json.obj("x", Integer.parseInt(p[0]), "y", Integer.parseInt(p[1]), "z", Integer.parseInt(p[2])),
                    "block", "minecraft:chest", "size", 27, "free", 27 - slot, "items", items,
                    "time", System.currentTimeMillis(), "open", false)));
        }

        List<String> types() {
            return tasks.stream().map(x -> Json.getString(x, "type", "")).toList();
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
        m.loop.awaitRun(() -> m.config.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main")));
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

    /** Only the parts under test; game data from the fixture tree. */
    private void autopilot(JsonObject flags) throws InterruptedException {
        JsonObject ap = Json.obj("supply", false, "sort", false, "idleWork", false, "discovery", false, "stuckSec", 0);
        flags.entrySet().forEach(e -> ap.add(e.getKey(), e.getValue()));
        m.loop.awaitRun(() -> m.config.patch(Json.obj("autopilot", ap,
                "runtime", Json.obj("gameDataPath", Fixtures.root().toString()))));
        m.loop.awaitRun(m.gameData::ensureLoaded);
        until(() -> m.loop.await(() -> m.gameData.current() != null), "game data loaded");
    }

    /** A chest in the index with a snapshot; the bot knows the same contents. */
    private void chest(int x, List<String> roles, Map<String, Integer> items) {
        bot.chests.put(x + ",64,0", new HashMap<>(items));
        m.loop.awaitRun(() -> {
            WorldDoc doc = m.worlds.get("main");
            List<JsonObject> list = new ArrayList<>();
            doc.containers.forEach(c -> list.add(Json.toObject(c)));
            list.add(Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", x, "y", 64, "z", 0),
                    "block", "minecraft:chest", "roles", Json.arrOf(roles)));
            doc.applySections(Json.obj("containers", Json.arrOf(list)));
            List<ContainerSnapshot.SlotItem> slots = new ArrayList<>();
            items.forEach((k, v) -> slots.add(new ContainerSnapshot.SlotItem(slots.size(), k, v)));
            m.worlds.onSnapshot("main", new ContainerSnapshot("minecraft:overworld", new Pos(x, 64, 0), "minecraft:chest",
                    27, 27 - slots.size(), slots, System.currentTimeMillis(), false));
        });
    }

    private void until(BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what + (bot == null ? "" : "; tasks " + bot.types()));
    }

    private void tickUntil(BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            m.loop.awaitRun(m.planner::tick);
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what + "; tasks " + bot.types());
    }

    private void queue(JsonObject template) {
        m.loop.awaitRun(() -> m.dispatcher.addTemplate(m.bots.require("bot1"), template, TaskQueue.Mode.APPEND, "panel"));
    }

    private boolean idle() {
        return m.loop.await(() -> m.bots.require("bot1").queue.isIdle());
    }

    private boolean event(String kind) {
        return m.loop.await(() -> m.events.query(200, null, null)).stream().anyMatch(e -> kind.equals(e.kind()));
    }

    @Test
    void supplyFetchesToolFoodAndBlocksBeforeTheTask() throws Exception {
        bot = new Bot();
        autopilot(Json.obj("supply", true));
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        chest(5, List.of("storage"), Map.of("minecraft:stone_pickaxe", 1, "minecraft:wooden_pickaxe", 1,
                "minecraft:bread", 20, "minecraft:cobblestone", 64));
        queue(Json.obj("type", "mine", "args", Json.obj("blocks", Json.arr("minecraft:stone"), "amount", 10)));
        until(() -> bot.types().contains("mine"), "mine dispatched");
        assertEquals(List.of("take", "mine"), bot.types());
        JsonObject take = bot.tasks.getFirst();
        assertEquals(Dispatcher.ORIGIN_SUPPLY, Json.getString(take, "origin", ""));
        Map<String, Integer> items = new HashMap<>();
        take.getAsJsonObject("args").getAsJsonArray("items").forEach(it -> items.put(
                Json.getString(it.getAsJsonObject(), "item", ""), Json.getInt(it.getAsJsonObject(), "count", 0)));
        assertEquals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:bread", 16, "minecraft:cobblestone", 64), items,
                "best pickaxe, food up to 2 × foodMin, blocks up to 2 × blocksMin");
        until(this::idle, "queue done");

        // nothing in storage: one manual event, the task still runs
        queue(Json.obj("type", "shear", "args", Json.obj("box", Json.obj("a", Json.obj("x", 0, "y", 64, "z", 0),
                "b", Json.obj("x", 4, "y", 66, "z", 4)))));
        until(() -> bot.types().contains("shear"), "shear dispatched");
        assertTrue(event("manual"), "missing shears reported");
        assertEquals(List.of("take", "mine", "shear"), bot.types());
    }

    @Test
    void inboxIsSortedThroughThePlannerAndChestsAdoptCategories() throws Exception {
        bot = new Bot();
        autopilot(Json.obj("sort", true));
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        chest(10, List.of("inbox"), Map.of("minecraft:bread", 5, "minecraft:cobblestone", 64));
        chest(12, List.of("storage"), Map.of());
        chest(14, List.of("storage"), Map.of("minecraft:raw_iron", 10));
        tickUntil(() -> bot.types().contains("transfer"), "transfer");
        JsonObject tr = bot.tasks.stream().filter(t -> "transfer".equals(Json.getString(t, "type", ""))).findFirst().orElseThrow();
        assertTrue(Json.getString(tr, "origin", "").startsWith("auto:"), "planner work with an autopilot origin");
        assertEquals(12, tr.getAsJsonObject("args").getAsJsonArray("to").get(0).getAsJsonObject().get("x").getAsInt(),
                "the empty storage chest takes the food");
        tickUntil(() -> m.loop.await(() -> m.worlds.get("main").containerAt("minecraft:overworld", new Pos(12, 64, 0))
                .hasRole("sorted:food")), "chest 12 adopts food");
        assertTrue(m.loop.await(() -> m.worlds.get("main").containerAt("minecraft:overworld", new Pos(14, 64, 0))
                .hasRole("sorted:ores_ingots")), "the iron chest adopted ores");
        assertTrue(event("sort_full"), "no chest for stone: reported");
    }

    @Test
    void obtainReplansFromTheLiveInventory() throws Exception {
        bot = new Bot();
        autopilot(new JsonObject());
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        queue(Json.obj("type", "obtain", "args", Json.obj("item", "minecraft:oak_planks", "count", 4)));
        until(() -> event("goal_done"), "goal done");
        assertEquals(List.of("mine", "craft"), bot.types());
        assertTrue(bot.tasks.getFirst().getAsJsonObject("args").getAsJsonArray("blocks").toString().contains("oak_log"));
        assertEquals(1, Json.getInt(bot.tasks.getFirst().getAsJsonObject("args"), "amount", 0));
        assertEquals(4, Json.getInt(bot.tasks.get(1).getAsJsonObject("args"), "count", 0));
        until(this::idle, "queue done");
        assertTrue(bot.inv.getOrDefault("minecraft:oak_planks", 0) >= 4);
    }

    private WorldDoc.Container containerAt(int x) {
        return m.loop.await(() -> m.worlds.get("main").containerAt("minecraft:overworld", new Pos(x, 64, 0)));
    }

    private static JsonObject seen(int x, String block) {
        return Json.obj("pos", Json.obj("x", x, "y", 64, "z", 0), "dim", "minecraft:overworld", "block", block);
    }

    /** An index entry without a snapshot (contents unknown). */
    private void unknown(int x, String block, String role) {
        m.loop.awaitRun(() -> {
            WorldDoc doc = m.worlds.get("main");
            List<JsonObject> list = new ArrayList<>();
            doc.containers.forEach(c -> list.add(Json.toObject(c)));
            list.add(Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", x, "y", 64, "z", 0), "block", block,
                    "roles", Json.arrOf(List.of(role))));
            doc.applySections(Json.obj("containers", Json.arrOf(list)));
        });
    }

    @Test
    void discoveryWithoutHomeSetsOneAndRequestedContainersAreStorage() throws Exception {
        bot = new Bot();
        bot.nearby.add(seen(4, "minecraft:chest"));
        autopilot(Json.obj("discovery", true));
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        tickUntil(() -> containerAt(4) != null, "chest discovered");
        assertTrue(event("home_auto"), "home set automatically");
        WorldDoc.Waypoint home = m.loop.await(() -> m.worlds.get("main").waypoint("home"));
        assertTrue(home != null && home.pos().distance(new Pos(0, 64, 0)) < 2, "home at the bot");
        assertEquals(List.of("storage"), containerAt(4).roles(), "within homeRadius: storage, not found");

        // POST discover: what the user asked for is storage, a known 'found' chest too; own roles stay
        chest(300, List.of("found"), Map.of());
        chest(310, List.of("kit"), Map.of());
        JsonArray asked = new JsonArray();
        asked.add(seen(200, "minecraft:barrel"));
        asked.add(seen(300, "minecraft:chest"));
        asked.add(seen(310, "minecraft:chest"));
        asked.add(seen(320, "minecraft:furnace"));
        assertEquals(2, m.loop.await(() -> m.autopilot.mergeRequested("main", asked)));
        assertEquals(List.of("storage"), containerAt(200).roles());
        assertEquals(List.of("storage"), containerAt(300).roles());
        assertEquals(List.of("kit"), containerAt(310).roles());
        assertEquals(List.of("furnace"), containerAt(320).roles());
    }

    @Test
    void supplyInspectsUnknownContentsBeforeCallingItemsMissing() throws Exception {
        bot = new Bot();
        autopilot(Json.obj("supply", true));
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        bot.chests.put("5,64,0", new HashMap<>(Map.of("minecraft:stone_pickaxe", 1, "minecraft:bread", 20,
                "minecraft:cobblestone", 64)));
        unknown(5, "minecraft:chest", "storage");
        assertTrue(containerAt(5).snapshot() == null, "contents unknown");
        queue(Json.obj("type", "mine", "args", Json.obj("blocks", Json.arr("minecraft:stone"), "amount", 10)));
        until(() -> bot.types().contains("mine"), "mine dispatched");
        assertEquals(List.of("inspect", "take", "mine"), bot.types(), "inspect first, then take what it holds");
        assertTrue(!event("manual"), "nothing reported missing");
    }

    @Test
    void craftWithoutGridGetsTheRecipeGridAndTable() throws Exception {
        bot = new Bot();
        bot.inv.put("minecraft:oak_log", 1);
        autopilot(new JsonObject());
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        unknown(3, "minecraft:crafting_table", "crafting");
        queue(Json.obj("type", "craft", "args", Json.obj("item", "minecraft:oak_planks", "count", 4)));
        queue(Json.obj("type", "craft", "args", Json.obj("item", "minecraft:wooden_pickaxe", "count", 1)));
        until(() -> bot.types().size() >= 2, "both crafts dispatched");
        JsonObject planks = bot.tasks.get(0).getAsJsonObject("args");
        assertTrue(planks.has("grid") && planks.get("grid").toString().contains("minecraft:oak_log"), planks.toString());
        assertTrue(!planks.has("table"), "2×2 fits the inventory grid");
        JsonObject pick = bot.tasks.get(1).getAsJsonObject("args");
        assertTrue(pick.has("grid"), pick.toString());
        assertEquals(3, pick.getAsJsonObject("table").get("x").getAsInt(), "3×3: the known crafting table");
    }

    @Test
    void stuckTaskStepsBackRetriesOnceThenFails() throws Exception {
        bot = new Bot();
        autopilot(Json.obj("stuckSec", 1));
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
        bot.holdMine = true;
        queue(Json.obj("type", "mine", "args", Json.obj("blocks", Json.arr("minecraft:stone"), "amount", 5)));
        until(() -> bot.running != null, "mine running");
        bot.status();
        Thread.sleep(1_200);
        bot.status();
        until(() -> bot.cancels.size() == 1, "first stuck cancel");
        until(() -> bot.types().size() >= 3 && bot.running != null, "step back + retry");
        assertEquals(List.of("mine", "goto", "mine"), bot.types());
        JsonObject back = bot.tasks.get(1);
        assertEquals("recovery", Json.getString(back, "origin", ""));
        assertEquals(-3, Json.getInt(back.getAsJsonObject("args"), "z", 0), "yaw 0 faces +z: step back to −z");
        bot.status();
        Thread.sleep(1_200);
        bot.status();
        until(() -> bot.cancels.size() == 2, "second stuck cancel");
        until(this::idle, "given up");
        assertTrue(event("stuck_retry") && event("stuck"));
        assertTrue(m.loop.await(() -> m.events.query(200, null, null)).stream()
                .anyMatch(e -> "task_failed".equals(e.kind()) && e.message().contains("stuck")), "failed with reason stuck");
    }
}
