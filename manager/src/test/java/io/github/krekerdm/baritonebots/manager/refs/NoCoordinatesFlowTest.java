package io.github.krekerdm.baritonebots.manager.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
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
 * SPEC §5.7e / §5.7f end to end against a scripted bot over the real link: references resolved at dispatch time
 * (home, owner, a missing waypoint, an automatically found farm field), owner chat commands with whispered replies,
 * sign labels on container snapshots, the {@code trash} step and auto-trash on {@code inventory_full}.
 */
class NoCoordinatesFlowTest {
    @TempDir
    Path dir;
    private Manager m;
    private Bot bot;

    private final class Bot implements AutoCloseable {
        final String id = "bot1";
        final Socket socket;
        final LineCodec in;
        final OutputStream out;
        final List<JsonObject> tasks = new CopyOnWriteArrayList<>();
        final List<String> chat = new CopyOnWriteArrayList<>();
        final List<String> queries = new CopyOnWriteArrayList<>();
        final Map<String, String> failOnce = new ConcurrentHashMap<>();
        volatile JsonArray slots = new JsonArray();
        volatile JsonObject statusItems = new JsonObject();

        Bot() throws IOException {
            socket = new Socket("127.0.0.1", m.link.port());
            in = new LineCodec(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            out = socket.getOutputStream();
            send(Envelope.of(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", id, "secret", m.secrets.linkSecret(),
                    "username", "Bot1", "modVersion", "0.1.0", "mcVersion", "26.2", "baritoneVersion", "1.19.0", "pid", 1)));
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
            }, "refs-bot");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized void send(Envelope e) throws IOException {
            LineCodec.writeLine(out, e.encode(), Protocol.MAX_LINE_BYTES);
        }

        void status() throws IOException {
            send(Envelope.of(MessageTypes.STATUS, Json.obj("botId", id, "username", "Bot1", "state", "online",
                    "dim", "minecraft:overworld", "pos", Json.obj("x", 10.5, "y", 64, "z", 10.5), "yaw", 0,
                    "freeSlots", 1, "items", statusItems, "armor", Json.arr(), "time", System.currentTimeMillis())));
        }

        void event(String kind, JsonObject data) throws IOException {
            send(Envelope.of(MessageTypes.EVENT, Json.obj("kind", kind, "level", "info", "message", kind, "data", data,
                    "time", System.currentTimeMillis())));
        }

        private void handle(Envelope e) throws IOException {
            switch (e.t()) {
                case MessageTypes.QUERY -> {
                    String kind = Json.getString(e.d(), "kind", "");
                    queries.add(kind);
                    send(Envelope.reply(MessageTypes.RESULT, e.id(), answer(kind, Json.getObj(e.d(), "args"))));
                }
                case MessageTypes.TASK -> {
                    tasks.add(e.d());
                    String type = Json.getString(e.d(), "type", "");
                    String fail = failOnce.remove(type);
                    send(Envelope.of(MessageTypes.TASK_DONE, Json.obj("id", Json.getString(e.d(), "id", ""), "type", type,
                            "ok", fail == null, "reason", fail, "data", new JsonObject(), "durationMs", 5)));
                }
                case MessageTypes.CHAT -> chat.add(Json.getString(e.d(), "text", ""));
                default -> {
                }
            }
        }

        private QueryResult answer(String kind, JsonObject args) {
            return switch (kind) {
                case "owner" -> QueryResult.success(Json.obj("found", true, "pos", Json.obj("x", 100, "y", 70, "z", -20),
                        "dim", "minecraft:overworld", "yaw", 90.0, "pitch", 30.0,
                        "lookBlock", Json.obj("x", 103, "y", 69, "z", -20), "lookBlockId", "minecraft:grass_block"));
                case "inventory" -> QueryResult.success(Json.obj("slots", slots.deepCopy(), "armor",
                        Json.arr("minecraft:iron_helmet", null, null, null), "selected", 0));
                case "scan_blocks" -> QueryResult.success(Json.obj("clusters", Json.arr(
                        Json.obj("box", Json.obj("a", Json.obj("x", 40, "y", 63, "z", 40), "b", Json.obj("x", 48, "y", 63,
                                "z", 52)), "count", 100, "ids", Json.obj("minecraft:farmland", 100)),
                        Json.obj("box", Json.obj("a", Json.obj("x", 0, "y", 63, "z", 0), "b", Json.obj("x", 1, "y", 63,
                                "z", 1)), "count", 3, "ids", Json.obj("minecraft:farmland", 3))),
                        "total", 103, "truncated", false, "unloadedChunks", 0));
                default -> QueryResult.failure("unsupported");
            };
        }

        List<String> types() {
            return tasks.stream().map(x -> Json.getString(x, "type", "")).toList();
        }

        JsonObject last(String type) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (type.equals(Json.getString(tasks.get(i), "type", ""))) {
                    return tasks.get(i).getAsJsonObject("args");
                }
            }
            return null;
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
            m.config.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main"));
            m.config.patch(Json.obj("general", Json.obj("ownerPlayer", "Owner"),
                    "autopilot", Json.obj("supply", false, "sort", false, "idleWork", false, "discovery", false,
                            "stuckSec", 0)));
            WorldDoc doc = m.worlds.get("main");
            doc.waypoints.add(new WorldDoc.Waypoint("home", "minecraft:overworld", new Pos(30, 64, 30)));
            doc.applySections(Json.obj("containers", Json.arr(Json.obj("dim", "minecraft:overworld",
                    "pos", Json.obj("x", 31, "y", 64, "z", 30), "block", "minecraft:chest", "roles", Json.arr("storage")))));
        });
        m.link.start("127.0.0.1", 0);
        bot = new Bot();
        until(() -> m.loop.await(() -> m.bots.require("bot1").online()), "online");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (bot != null) {
            bot.close();
        }
        m.link.stop();
        m.loop.shutdown(1_000);
    }

    private void until(BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("timed out waiting for " + what + "; tasks " + bot.types() + ", chat " + bot.chat);
    }

    private void queue(JsonObject template) {
        m.loop.awaitRun(() -> m.dispatcher.addTemplate(m.bots.require("bot1"), template, TaskQueue.Mode.APPEND, "panel"));
    }

    private boolean event(String kind) {
        return m.loop.await(() -> m.events.query(500, null, null)).stream().anyMatch(e -> kind.equals(e.kind()));
    }

    private boolean idle() {
        return m.loop.await(() -> m.bots.require("bot1").queue.isIdle());
    }

    @Test
    void referencesAreResolvedAtDispatchTime() throws Exception {
        queue(Json.obj("type", "goto", "args", Json.obj("pos", Json.obj("ref", "home"))));
        until(() -> bot.types().contains("goto"), "goto with home");
        JsonObject g = bot.last("goto");
        assertEquals(30, g.get("x").getAsInt());
        assertEquals(64, g.get("y").getAsInt());
        assertEquals(30, g.get("z").getAsInt());
        assertFalse(g.has("pos"), "the bot never sees references");

        queue(Json.obj("type", "goto", "args", Json.obj("pos", Json.obj("ref", "owner_look"), "range", 1)));
        until(() -> bot.tasks.size() == 2, "goto to where the owner looks");
        assertEquals(103, bot.last("goto").get("x").getAsInt());
        assertTrue(bot.queries.contains("owner"));

        queue(Json.obj("type", "deposit", "args", Json.obj("containers", Json.arr(Json.obj("ref", "waypoint",
                "name", "nowhere")))));
        until(() -> event("ref_failed"), "ref_failed");
        until(this::idle, "queue idle");
        assertEquals(2, bot.tasks.size(), "an unresolvable task never reaches the bot");

        queue(Json.obj("type", "farm", "args", Json.obj("center", Json.obj("ref", "auto"), "durationSec", 60)));
        until(() -> bot.types().contains("farm"), "farm at the detected field");
        JsonObject farm = bot.last("farm");
        assertEquals(new Pos(44, 63, 46), Pos.fromJson(farm.get("center")), "centre of the nearest big farmland cluster");
        assertEquals(7, farm.get("range").getAsInt());
    }

    @Test
    void ownerCommandsSetPlacesAndMoveBots() throws Exception {
        JsonObject seen = Json.obj("pos", Json.obj("x", 100, "y", 70, "z", -20), "dim", "minecraft:overworld",
                "yaw", 90.0, "pitch", 30.0, "lookBlock", Json.obj("x", 103, "y", 69, "z", -20),
                "lookBlockId", "minecraft:chest", "player", "Owner");
        JsonObject here = seen.deepCopy();
        here.addProperty("text", "here шахта");
        here.addProperty("via", "chat");
        bot.event("owner_command", here);
        until(() -> m.loop.await(() -> m.worlds.get("main").waypoint("шахта") != null), "waypoint");
        assertEquals(new Pos(100, 70, -20), m.loop.await(() -> m.worlds.get("main").waypoint("шахта").pos()));
        until(() -> !bot.chat.isEmpty(), "reply");
        assertTrue(bot.chat.getFirst().startsWith("/msg Owner Точка «шахта» сохранена"), bot.chat.getFirst());

        bot.event("owner_command", here.deepCopy()); // the same line heard twice (another bot) is handled once
        Thread.sleep(300);
        assertEquals(1, bot.chat.size());

        Thread.sleep(1_100); // reply rate limit
        JsonObject chest = seen.deepCopy();
        chest.addProperty("text", "сундук мусор");
        chest.addProperty("via", "whisper");
        bot.event("owner_command", chest);
        until(() -> m.loop.await(() -> {
            WorldDoc.Container c = m.worlds.get("main").containerAt("minecraft:overworld", new Pos(103, 69, -20));
            return c != null && c.hasRole("trash") && c.manual();
        }), "chest marked as trash by hand");

        JsonObject come = seen.deepCopy();
        come.addProperty("text", "ко мне");
        come.addProperty("via", "whisper");
        bot.event("owner_command", come);
        until(() -> bot.types().contains("goto"), "come");
        assertEquals(100, bot.last("goto").get("x").getAsInt());
        assertEquals(-20, bot.last("goto").get("z").getAsInt());
    }

    @Test
    void signsLabelContainersButManualRolesWin() throws Exception {
        bot.send(Envelope.of(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld", "pos",
                Json.obj("x", 5, "y", 64, "z", 5), "block", "minecraft:chest", "size", 27, "free", 27, "items", Json.arr(),
                "time", System.currentTimeMillis(), "open", false, "signText", "склад руды")));
        until(() -> m.loop.await(() -> {
            WorldDoc.Container c = m.worlds.get("main").containerAt("minecraft:overworld", new Pos(5, 64, 5));
            return c != null && c.fromSign();
        }), "sign roles");
        assertEquals(List.of("storage", "sorted:ores_ingots"), m.loop.await(() ->
                m.worlds.get("main").containerAt("minecraft:overworld", new Pos(5, 64, 5)).roles()));
        // the storage chest from setUp was set by hand (applySections + markManualRoles as the API does)
        m.loop.awaitRun(() -> {
            WorldDoc doc = m.worlds.get("main");
            var before = doc.rolesById();
            List<JsonObject> list = new java.util.ArrayList<>();
            doc.containers.forEach(c -> list.add(Json.toObject(c)));
            list.getFirst().add("roles", Json.arr("kit"));
            doc.applySections(Json.obj("containers", Json.arrOf(list)));
            doc.markManualRoles(before);
        });
        bot.send(Envelope.of(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld", "pos",
                Json.obj("x", 31, "y", 64, "z", 30), "block", "minecraft:chest", "size", 27, "free", 27, "items", Json.arr(),
                "time", System.currentTimeMillis(), "open", false, "signText", "еда")));
        until(() -> m.loop.await(() -> "еда".equals(m.worlds.get("main").containerAt("minecraft:overworld",
                new Pos(31, 64, 30)).signText())), "label stored");
        assertEquals(List.of("kit"), m.loop.await(() ->
                m.worlds.get("main").containerAt("minecraft:overworld", new Pos(31, 64, 30)).roles()));
    }

    @Test
    void trashStepDropsDuplicatesAndAutoTrashRunsOnFullInventory() throws Exception {
        bot.slots = Json.arr(
                Json.obj("slot", 0, "item", "minecraft:iron_pickaxe", "count", 1, "damage", 200, "maxDamage", 250),
                Json.obj("slot", 1, "item", "minecraft:iron_pickaxe", "count", 1, "damage", 10, "maxDamage", 250),
                Json.obj("slot", 2, "item", "minecraft:stone_pickaxe", "count", 1, "damage", 0, "maxDamage", 131),
                Json.obj("slot", 3, "item", "minecraft:dirt", "count", 64, "damage", 0, "maxDamage", 0));
        queue(Json.obj("type", "trash", "args", Json.obj("to", "drop")));
        until(() -> bot.types().contains("drop"), "drop");
        JsonArray picks = bot.last("drop").getAsJsonArray("slots");
        List<Integer> slots = new java.util.ArrayList<>();
        picks.forEach(p -> slots.add(p.getAsJsonObject().get("slot").getAsInt()));
        assertEquals(List.of(0, 2), slots, "the worn-out iron pickaxe and the stone one go; dirt is the 64 blocks kept");

        // auto-trash: mining fails with inventory_full while two junk stacks are carried
        bot.statusItems = Json.obj("minecraft:gravel", 128, "minecraft:diamond", 3);
        bot.status();
        bot.slots = Json.arr(Json.obj("slot", 0, "item", "minecraft:gravel", "count", 64, "damage", 0, "maxDamage", 0),
                Json.obj("slot", 1, "item", "minecraft:gravel", "count", 64, "damage", 0, "maxDamage", 0),
                Json.obj("slot", 2, "item", "minecraft:diamond", "count", 3, "damage", 0, "maxDamage", 0));
        Thread.sleep(200);
        int before = bot.tasks.size();
        bot.failOnce.put("mine", "inventory_full");
        queue(Json.obj("type", "mine", "args", Json.obj("blocks", Json.arr("minecraft:diamond_ore"), "amount", 4)));
        until(() -> bot.tasks.size() >= before + 3, "mine, drop, mine again");
        assertEquals(List.of("mine", "drop", "mine"), bot.types().subList(before, before + 3));
        assertEquals(2, bot.last("drop").getAsJsonArray("slots").size());
        assertTrue(event("trash_retry"));
    }

    @Test
    void projectAndWorldReferencesResolveWhenSaved() throws Exception {
        JsonObject body = m.loop.await(() -> m.refs.resolveWorld("main", Json.obj("waypoints", Json.arr(
                Json.obj("name", "там", "pos", Json.obj("ref", "owner")))))).get(5, java.util.concurrent.TimeUnit.SECONDS);
        JsonObject wp = body.getAsJsonArray("waypoints").get(0).getAsJsonObject();
        assertEquals(new Pos(100, 70, -20), Pos.fromJson(wp.get("pos")));
        assertEquals("minecraft:overworld", Json.getString(wp, "dim", ""));

        JsonObject project = m.loop.await(() -> m.refs.resolveProject(Json.obj("kind", "farm", "serverId", "main",
                "name", "F", "config", Json.obj("box", Json.obj("ref", "auto"))))).get(5, java.util.concurrent.TimeUnit.SECONDS);
        Box box = Box.fromJson(project.getAsJsonObject("config").get("box"));
        assertEquals(new Box(new Pos(40, 63, 40), new Pos(48, 64, 52)), box, "farmland plus the crop layer");
    }
}
