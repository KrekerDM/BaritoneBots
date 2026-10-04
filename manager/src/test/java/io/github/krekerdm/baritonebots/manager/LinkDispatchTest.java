package io.github.krekerdm.baritonebots.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.link.LineCodec;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fake bots over a real TCP link: handshake, dispatch, inventory_full → deposit_storage + retry, death recovery, heavy limit. */
class LinkDispatchTest {
    @TempDir
    Path dir;
    private Manager m;
    private final List<FakeBot> fakes = new ArrayList<>();

    /** One bot client speaking the link protocol. */
    private final class FakeBot implements AutoCloseable {
        final Socket socket;
        final LineCodec in;
        final OutputStream out;

        FakeBot() throws IOException {
            socket = new Socket("127.0.0.1", m.link.port());
            socket.setSoTimeout(5_000);
            in = new LineCodec(socket.getInputStream(), Protocol.MAX_LINE_BYTES);
            out = socket.getOutputStream();
            fakes.add(this);
        }

        void send(String type, Object payload) throws IOException {
            LineCodec.writeLine(out, Envelope.of(type, payload).encode(), Protocol.MAX_LINE_BYTES);
        }

        Envelope expect(String type) throws IOException {
            for (int i = 0; i < 20; i++) {
                Envelope e = Envelope.decode(in.readLine());
                if (e.is(type)) {
                    return e;
                }
            }
            throw new AssertionError("no " + type);
        }

        FakeBot hello(String id) throws IOException {
            send(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", id, "secret", m.secrets.linkSecret(),
                    "username", id, "modVersion", "0.1.0", "mcVersion", "26.2", "baritoneVersion", "1.19.0", "pid", 1));
            Envelope welcome = expect(MessageTypes.WELCOME);
            assertEquals(id, welcome.d().getAsJsonObject("config").get("botId").getAsString());
            send(MessageTypes.STATUS, Json.obj("botId", id, "username", id, "state", "online",
                    "dim", "minecraft:overworld", "pos", Json.obj("x", 0.5, "y", 64, "z", 0.5), "freeSlots", 10,
                    "items", Json.obj(), "armor", Json.arr(), "time", 1));
            return this;
        }

        void done(Envelope task, boolean ok, String reason) throws IOException {
            JsonObject d = task.d();
            send(MessageTypes.TASK_DONE, Json.obj("id", d.get("id").getAsString(), "type", d.get("type").getAsString(),
                    "ok", ok, "reason", reason, "durationMs", 5));
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
            // these tests check exact task sequences; the autopilot has its own tests
            m.config.patch(Json.obj("autopilot", Json.obj("supply", false, "sort", false, "idleWork", false,
                    "discovery", false, "stuckSec", 0)));
            m.config.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main"));
            m.config.addItem("bots", Json.obj("id", "bot2", "username", "Bot2", "serverId", "main"));
            m.worlds.get("main").applySections(Json.obj(
                    "containers", Json.arr(Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", 10, "y", 64, "z", 0),
                            "block", "minecraft:chest", "roles", Json.arr("storage"))),
                    "waypoints", Json.arr(Json.obj("name", "home", "dim", "minecraft:overworld",
                            "pos", Json.obj("x", 1, "y", 65, "z", 2)))));
        });
        m.link.start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() throws IOException {
        for (FakeBot f : fakes) {
            f.close();
        }
        m.link.stop();
        m.loop.shutdown(1_000);
    }

    private void queue(String botId, JsonObject template) {
        m.loop.awaitRun(() -> m.dispatcher.addTemplate(m.bots.require(botId), template, TaskQueue.Mode.APPEND, "panel"));
    }

    @Test
    void rejectsWrongSecretAndUnknownBot() throws IOException {
        FakeBot a = new FakeBot();
        a.send(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", "bot1", "secret", "wrong", "pid", 1));
        assertEquals("bad_secret", a.expect(MessageTypes.REJECT).d().get("reason").getAsString());
        FakeBot b = new FakeBot();
        b.send(MessageTypes.HELLO, Json.obj("protocol", 1, "botId", "ghost", "secret", m.secrets.linkSecret(), "pid", 1));
        assertEquals("unknown_bot", b.expect(MessageTypes.REJECT).d().get("reason").getAsString());
    }

    @Test
    void inventoryFullDepositsAndRetries() throws Exception {
        FakeBot bot = new FakeBot().hello("bot1");
        queue("bot1", Json.obj("type", "mine", "args", Json.obj("blocks", Json.arr("stone"))));
        Envelope mine = bot.expect(MessageTypes.TASK);
        assertEquals("mine", mine.d().get("type").getAsString());
        bot.done(mine, false, "inventory_full");

        Envelope deposit = bot.expect(MessageTypes.TASK);
        assertEquals("deposit", deposit.d().get("type").getAsString());
        JsonObject pos = deposit.d().getAsJsonObject("args").getAsJsonArray("containers").get(0).getAsJsonObject();
        assertEquals(10, pos.get("x").getAsInt());
        assertTrue(deposit.d().getAsJsonObject("args").getAsJsonArray("keep").size() > 0);
        bot.done(deposit, true, null);

        Envelope retry = bot.expect(MessageTypes.TASK);
        assertEquals("mine", retry.d().get("type").getAsString());
        assertFalse(retry.d().get("id").getAsString().equals(mine.d().get("id").getAsString()));
        bot.done(retry, true, null);
        Thread.sleep(200);
        assertTrue(m.loop.await(() -> m.bots.require("bot1").queue.isIdle()));
    }

    @Test
    void scenarioWithHomeStepAndDeathRecovery() throws Exception {
        FakeBot bot = new FakeBot().hello("bot1");
        JsonObject sc = m.loop.await(() -> m.scenarios.create(Json.obj("name", "trip", "steps", Json.arr(
                Json.obj("type", "home"), Json.obj("type", "idle")))));
        m.loop.awaitRun(() -> m.dispatcher.runScenario(sc, m.bots.require("bot1"), false));
        Envelope go = bot.expect(MessageTypes.TASK);
        assertEquals("goto", go.d().get("type").getAsString());
        assertEquals(2, go.d().getAsJsonObject("args").get("z").getAsInt());
        assertTrue(go.d().get("origin").getAsString().startsWith("scenario:"));

        // the bot dies on the way: the task fails with 'died' and is retried after the recovery
        bot.send(MessageTypes.EVENT, Json.obj("kind", "death", "level", "warn", "message", "died",
                "data", Json.obj("pos", Json.obj("x", 3, "y", 64, "z", 3), "dim", "minecraft:overworld"), "time", 2));
        bot.done(go, false, "died");
        bot.send(MessageTypes.EVENT, Json.obj("kind", "respawned", "level", "info", "message", "respawned", "time", 3));
        Envelope recover = bot.expect(MessageTypes.TASK);
        assertEquals("recover", recover.d().get("type").getAsString());
        assertEquals(3, recover.d().getAsJsonObject("args").getAsJsonObject("pos").get("x").getAsInt());
        bot.done(recover, true, null);

        Envelope again = bot.expect(MessageTypes.TASK);
        assertEquals("goto", again.d().get("type").getAsString());
        bot.done(again, true, null);
        Envelope idle = bot.expect(MessageTypes.TASK);
        assertEquals("idle", idle.d().get("type").getAsString());
        bot.done(idle, true, null);
        Thread.sleep(200);
        assertEquals(1, m.loop.await(() -> m.worlds.get("main").deaths.size()));
        assertTrue(m.loop.await(() -> m.dispatcher.queueView(m.bots.require("bot1")).getAsJsonArray("runs").isEmpty()));
    }

    @Test
    void heavyLimitHoldsSecondBotUntilTheFirstFinishes() throws Exception {
        m.loop.awaitRun(() -> m.config.patch(Json.obj("runtime", Json.obj("maxHeavyTasks", 1))));
        FakeBot one = new FakeBot().hello("bot1");
        FakeBot two = new FakeBot().hello("bot2");
        queue("bot1", Json.obj("type", "explore"));
        Envelope explore = one.expect(MessageTypes.TASK);
        assertEquals("explore", explore.d().get("type").getAsString());
        queue("bot2", Json.obj("type", "mine", "args", Json.obj("blocks", Json.arr("stone"))));
        String waiting = null;
        for (int i = 0; i < 40 && !Dispatcher.WAIT_HEAVY.equals(waiting); i++) {
            Thread.sleep(50); // bot2's first status may still be in flight
            waiting = m.loop.await(() -> m.bots.require("bot2").waiting);
        }
        assertEquals(Dispatcher.WAIT_HEAVY, waiting);
        one.done(explore, false, "cancelled");
        Envelope mine = two.expect(MessageTypes.TASK);
        assertEquals("mine", mine.d().get("type").getAsString());
    }

    @Test
    void containerSnapshotsUpdateTheWorld() throws Exception {
        FakeBot bot = new FakeBot().hello("bot1");
        bot.send(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", 10, "y", 64, "z", 0),
                "block", "minecraft:chest", "size", 27, "free", 26,
                "items", Json.arr(Json.obj("slot", 0, "item", "minecraft:cobblestone", "count", 64)), "time", 5, "open", true));
        bot.send(MessageTypes.CONTAINER, Json.obj("dim", "minecraft:overworld", "pos", Json.obj("x", 20, "y", 64, "z", 0),
                "block", "minecraft:barrel", "size", 27, "free", 27, "items", Json.arr(), "time", 6, "open", true));
        Thread.sleep(300);
        int count = m.loop.await(() -> m.worlds.get("main").containers.size());
        assertEquals(2, count);
        int stone = m.loop.await(() -> m.worlds.get("main").containerAt("minecraft:overworld",
                new io.github.krekerdm.baritonebots.common.geom.Pos(10, 64, 0)).snapshot().totals().get("minecraft:cobblestone"));
        assertEquals(64, stone);
    }

    @Test
    void companionPluginMessagesAreRelayed() throws Exception {
        FakeBot bot = new FakeBot().hello("bot1");
        bot.send(MessageTypes.PLUGIN, Json.obj("payload", Json.obj("t", "notice", "d", Json.obj("message", "restart in 5 min"))));
        bot.send(MessageTypes.PLUGIN, Json.obj("payload", Json.obj("t", "rollback_result",
                "d", Json.obj("ok", true, "restored", 12, "via", "journal", "message", ""))));
        bot.send(MessageTypes.PLUGIN, Json.obj("payload", Json.obj("t", "welcome", "d", Json.obj("features", Json.arr("journal")))));
        Thread.sleep(300);
        var events = m.loop.await(() -> m.events.query(50, "bot1", null));
        assertTrue(events.stream().anyMatch(e -> "plugin_notice".equals(e.kind()) && "plugin".equals(e.source())
                && e.message().contains("restart in 5 min")));
        assertTrue(events.stream().anyMatch(e -> "plugin_rollback".equals(e.kind())));
        JsonObject stored = m.loop.await(() -> m.botView(m.bots.require("bot1")).getAsJsonObject("plugin"));
        assertEquals(3, stored.size(), "last payload per type");
        assertEquals(12, stored.getAsJsonObject("rollback_result").getAsJsonObject("d").get("restored").getAsInt());

        // manager → plugin goes out as a 'plugin' message to the bot
        m.loop.awaitRun(() -> m.bots.require("bot1").session.send(MessageTypes.PLUGIN,
                Json.obj("payload", Json.obj("t", "journal", "d", Json.obj("target", "Bot1", "minutes", 5)))));
        Envelope out = bot.expect(MessageTypes.PLUGIN);
        assertEquals("journal", out.d().getAsJsonObject("payload").get("t").getAsString());
    }
}
