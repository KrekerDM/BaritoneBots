package io.github.krekerdm.baritonebots.manager.projects;

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
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
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
 * The new project kinds and {@code smelt_all} end to end against a scripted bot over the real link: gather hauls a
 * quota into storage, clear clears + verifies a slab, farm runs its rounds, sort empties an inbox, smelt_all loads a
 * furnace from storage.
 */
class KindsFlowTest {
    @TempDir
    Path dir;
    private Manager m;
    private Bot bot;

    /** Finishes every task successfully and keeps chests / inventory in sync with snapshots. */
    private final class Bot implements AutoCloseable {
        final Socket socket;
        final LineCodec in;
        final OutputStream out;
        final List<JsonObject> tasks = new CopyOnWriteArrayList<>();
        /** "x,y,z" → slot contents (chests: item → count; furnaces use slots 0/1/2). */
        final Map<String, Map<String, Integer>> chests = new ConcurrentHashMap<>();
        final Map<String, String> blocks = new ConcurrentHashMap<>();
        final Map<String, Integer> inv = new ConcurrentHashMap<>();

        Bot() throws IOException {
            socket = new Socket("127.0.0.1", m.link.port());
            in = new LineCodec(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            out = socket.getOutputStream();
            send(Envelope.of(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", "bot1", "secret", m.secrets.linkSecret(),
                    "username", "bot1", "modVersion", "0.1.0", "mcVersion", "26.2", "baritoneVersion", "1.19.0", "pid", 1)));
            status();
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        handle(Envelope.decode(line));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // closed
                }
            }, "scripted-bot");
            t.setDaemon(true);
            t.start();
        }

        synchronized void send(Envelope e) throws IOException {
            LineCodec.writeLine(out, e.encode(), Protocol.MAX_LINE_BYTES);
        }

        void status() throws IOException {
            send(Envelope.of(MessageTypes.STATUS, Json.obj("botId", "bot1", "username", "bot1", "state", "online",
                    "dim", "minecraft:overworld", "pos", Json.obj("x", 3.5, "y", 64, "z", 2.5), "freeSlots", 30,
                    "health", 20, "items", Json.toObject(new HashMap<>(inv)), "armor", Json.arr(), "time", System.currentTimeMillis())));
        }

        void chest(Pos p, String block, Map<String, Integer> items) throws IOException {
            chests.put(key(p), new ConcurrentHashMap<>(items));
            blocks.put(key(p), block);
            snapshot(key(p));
        }

        private void handle(Envelope e) throws IOException {
            if (MessageTypes.QUERY.equals(e.t())) {
                String kind = Json.getString(e.d(), "kind", "");
                QueryResult r = switch (kind) {
                    case "block_at" -> QueryResult.success(Json.obj("block", "minecraft:air", "loaded", true));
                    case "player" -> QueryResult.success(Json.obj("found", false));
                    default -> QueryResult.failure("unsupported");
                };
                send(Envelope.reply(MessageTypes.RESULT, e.id(), r));
                return;
            }
            if (!MessageTypes.TASK.equals(e.t())) {
                return;
            }
            tasks.add(e.d());
            String type = Json.getString(e.d(), "type", "");
            JsonObject args = e.d().getAsJsonObject("args");
            JsonObject data = new JsonObject();
            switch (type) {
                case "take" -> {
                    String k = key(args.get("container"));
                    JsonObject taken = new JsonObject();
                    for (JsonElement it : args.getAsJsonArray("items")) {
                        String item = Json.getString(it.getAsJsonObject(), "item", "");
                        int n = Math.min(Json.getInt(it.getAsJsonObject(), "count", 0), chests.get(k).getOrDefault(item, 0));
                        chests.get(k).merge(item, -n, Integer::sum);
                        inv.merge(item, n, Integer::sum);
                        taken.addProperty(item, n);
                    }
                    data = Json.obj("taken", taken, "missing", Json.obj());
                    snapshot(k);
                }
                case "deposit" -> {
                    String k = key(args.getAsJsonArray("containers").get(0));
                    List<String> only = Json.getStringList(args, "only");
                    JsonObject moved = new JsonObject();
                    for (var en : new HashMap<>(inv).entrySet()) {
                        if (only.isEmpty() || only.contains(en.getKey())) {
                            chests.computeIfAbsent(k, x -> new ConcurrentHashMap<>()).merge(en.getKey(), en.getValue(), Integer::sum);
                            moved.addProperty(en.getKey(), en.getValue());
                            inv.remove(en.getKey());
                        }
                    }
                    data = Json.obj("moved", moved, "left", Json.obj());
                    snapshot(k);
                }
                case "transfer" -> {
                    String from = key(args.get("from"));
                    String to = key(args.getAsJsonArray("to").get(0));
                    JsonObject moved = new JsonObject();
                    for (JsonElement it : args.getAsJsonArray("items")) {
                        String item = Json.getString(it.getAsJsonObject(), "item", "");
                        int n = Math.min(Json.getInt(it.getAsJsonObject(), "count", 0), chests.get(from).getOrDefault(item, 0));
                        chests.get(from).merge(item, -n, Integer::sum);
                        chests.get(to).merge(item, n, Integer::sum);
                        moved.addProperty(item, n);
                    }
                    data = Json.obj("moved", moved);
                    snapshot(from);
                    snapshot(to);
                }
                case "farm" -> inv.merge("minecraft:wheat", 10, Integer::sum);
                case "smelt_load" -> {
                    int count = Json.getInt(args, "count", 0);
                    inv.merge(Json.getString(args, "input", ""), -count, Integer::sum);
                    inv.merge(Json.getString(args, "fuel", ""), -Json.getInt(args, "fuelCount", 0), Integer::sum);
                    data = Json.obj("loaded", count, "fuel", Json.getInt(args, "fuelCount", 0), "collected", Json.obj());
                }
                default -> {
                    // selection, inspect, ...: nothing to simulate
                }
            }
            inv.values().removeIf(v -> v <= 0);
            status();
            send(Envelope.of(MessageTypes.TASK_DONE, Json.obj("id", Json.getString(e.d(), "id", ""), "type", type,
                    "ok", true, "data", data, "durationMs", 5)));
        }

        private String key(JsonElement pos) {
            Pos p = Pos.fromJson(pos);
            return key(p);
        }

        private String key(Pos p) {
            return p.x() + "," + p.y() + "," + p.z();
        }

        private void snapshot(String k) throws IOException {
            Pos p = Pos.parse(k);
            JsonArray items = new JsonArray();
            int slot = 0;
            for (var en : chests.getOrDefault(k, Map.of()).entrySet()) {
                if (en.getValue() > 0) {
                    items.add(Json.obj("slot", slot++, "item", en.getKey(), "count", en.getValue()));
                }
            }
            send(Envelope.of(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld", "pos", p,
                    "block", blocks.getOrDefault(k, "minecraft:chest"), "size", 27, "free", 27 - slot, "items", items,
                    "time", System.currentTimeMillis(), "open", false)));
        }

        long count(String type) {
            return tasks.stream().filter(t -> type.equals(Json.getString(t, "type", ""))).count();
        }

        List<String> types() {
            List<String> out = new ArrayList<>();
            tasks.forEach(t -> out.add(Json.getString(t, "type", "")));
            return out;
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
        m.loop.awaitRun(() -> {
            m.config.patch(Json.obj("autopilot", Json.obj("supply", false, "sort", false, "idleWork", false,
                    "discovery", false, "stuckSec", 0)));
            m.config.addItem("bots", Json.obj("id", "bot1", "username", "bot1", "serverId", "main"));
        });
        m.link.start("127.0.0.1", 0);
        bot = new Bot();
        tickUntil(() -> m.loop.await(() -> m.bots.require("bot1").online()), "bot online");
    }

    @AfterEach
    void tearDown() throws IOException {
        bot.close();
        m.link.stop();
        m.loop.shutdown(1_000);
    }

    /** A container known through a snapshot from the bot, then given its role. */
    private void container(Pos p, String block, String role, Map<String, Integer> items) throws Exception {
        bot.chest(p, block, items);
        tickUntil(() -> m.loop.await(() -> m.worlds.get("main").containerAt("minecraft:overworld", p) != null),
                "snapshot of " + p);
        m.loop.awaitRun(() -> m.worlds.setRoles("main", m.worlds.get("main").containerAt("minecraft:overworld", p),
                List.of(role)));
    }

    private void tickUntil(BooleanSupplier done, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            m.loop.awaitRun(m.planner::tick);
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what + "; tasks " + bot.types() + "; projects "
                + m.loop.await(() -> m.projects.list().toString()));
    }

    private String start(String kind, JsonObject config) {
        JsonObject p = m.loop.await(() -> m.projects.create(Json.obj("name", kind, "kind", kind, "serverId", "main",
                "bots", "any", "config", config, "start", true)));
        return Json.getString(p, "id", "");
    }

    private String status(String id) {
        return m.loop.await(() -> m.projects.get(id).status);
    }

    private JsonObject progress(String id) {
        return m.loop.await(() -> m.projects.view(m.projects.get(id), false).getAsJsonObject("progress"));
    }

    @Test
    void gatherHaulsTheQuotaIntoStorage() throws Exception {
        Pos storage = new Pos(10, 64, 0);
        container(storage, "minecraft:chest", "storage", Map.of("minecraft:stone", 4));
        container(new Pos(12, 64, 0), "minecraft:chest", "inbox", Map.of("minecraft:stone", 40));
        String id = start("gather", Json.obj("quotas", Json.obj("minecraft:stone", 16)));
        tickUntil(() -> "done".equals(status(id)), "gather done");
        assertEquals(20, bot.chests.get("10,64,0").get("minecraft:stone"), "baseline 4 + quota 16");
        assertTrue(bot.count("take") >= 1 && bot.count("deposit") >= 1, bot.types().toString());
        JsonObject pr = progress(id);
        assertEquals(16, pr.get("done").getAsInt());
        assertEquals(100.0, pr.get("percent").getAsDouble());
    }

    @Test
    void clearRunsSelectionVerifiesAndFinishes() throws Exception {
        container(new Pos(10, 64, 0), "minecraft:chest", "storage", Map.of());
        String id = start("clear", Json.obj("box", Json.obj("a", Json.obj("x", 0, "y", 64, "z", 5),
                "b", Json.obj("x", 7, "y", 65, "z", 5))));
        tickUntil(() -> "done".equals(status(id)), "clear done");
        JsonObject sel = bot.tasks.stream().filter(t -> "selection".equals(Json.getString(t, "type", ""))).findFirst().orElseThrow();
        assertEquals("clear", Json.getString(sel.getAsJsonObject("args"), "op", ""));
        assertTrue(bot.types().indexOf("deposit") > bot.types().indexOf("selection"), bot.types().toString());
        assertEquals(16, progress(id).get("done").getAsInt());
    }

    @Test
    void farmRunsItsRoundsAndCountsTheHarvest() throws Exception {
        container(new Pos(10, 64, 0), "minecraft:chest", "storage", Map.of());
        String id = start("farm", Json.obj("center", Json.obj("x", 0, "y", 64, "z", 0), "range", 5, "durationSec", 30,
                "cycles", 2));
        tickUntil(() -> "done".equals(status(id)), "farm done");
        assertEquals(2, bot.count("farm"));
        JsonObject pr = progress(id);
        assertEquals(2, pr.get("rounds").getAsInt());
        assertEquals(20, pr.get("harvested").getAsInt());
    }

    @Test
    void sortProjectEmptiesTheInbox() throws Exception {
        container(new Pos(20, 64, 0), "minecraft:chest", "sorted:stone_building", Map.of("minecraft:cobblestone", 1));
        container(new Pos(22, 64, 0), "minecraft:chest", "inbox", Map.of("minecraft:dirt", 10));
        String id = start("sort", Json.obj());
        tickUntil(() -> "done".equals(status(id)), "sort done");
        assertEquals(10, bot.chests.get("20,64,0").get("minecraft:dirt"));
        assertEquals(1, bot.count("transfer"));
    }

    @Test
    void smeltAllLoadsFurnacesFromStorage() throws Exception {
        container(new Pos(4, 64, 4), "minecraft:furnace", "furnace", Map.of());
        container(new Pos(10, 64, 0), "minecraft:chest", "storage", Map.of("minecraft:raw_iron", 10, "minecraft:coal", 5));
        m.loop.await(() -> m.dispatcher.addTemplate(m.bots.require("bot1"), Json.obj("type", "smelt_all"),
                TaskQueue.Mode.APPEND, "panel"));
        tickUntil(() -> bot.count("smelt_load") == 1, "smelt_load");
        JsonObject load = bot.tasks.stream().filter(t -> "smelt_load".equals(Json.getString(t, "type", ""))).findFirst()
                .orElseThrow().getAsJsonObject("args");
        assertEquals("minecraft:raw_iron", Json.getString(load, "input", ""));
        assertEquals(10, Json.getInt(load, "count", 0));
        assertEquals("minecraft:coal", Json.getString(load, "fuel", ""));
        assertEquals(2, Json.getInt(load, "fuelCount", 0));
        assertEquals(List.of("take", "smelt_load"), bot.types());
    }
}
