package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.geom.Vec3d;
import io.github.krekerdm.baritonebots.common.json.Json;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeTest {

    @Test
    void roundTripsThroughOneLine() {
        Envelope q = Envelope.request(MessageTypes.QUERY, "m-12",
                new Query(QueryKinds.BLOCK_AT, Json.obj("pos", new Pos(1, -2, 3))));
        String line = q.encode();
        assertFalse(line.contains("\n"));
        Envelope back = Envelope.decode(line);
        assertEquals(q, back);
        assertEquals("m-12", back.id());
        assertNull(back.re());
        Query query = back.payload(Query.class);
        assertEquals(QueryKinds.BLOCK_AT, query.kind());
        assertEquals(new Pos(1, -2, 3), Pos.fromJson(query.args().get("pos")));
    }

    @Test
    void escapesLineBreaksAndKeepsUnicode() {
        Envelope chat = Envelope.of(MessageTypes.CHAT, Json.obj("text", "привет\nworld <&>"));
        String line = chat.encode();
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(line.contains("привет"));
        assertTrue(line.contains("<&>"), "HTML escaping is off");
        assertEquals("привет\nworld <&>", Envelope.decode(line).d().get("text").getAsString());
    }

    @Test
    void replyAndRecordPayloads() {
        TaskSpec spec = new TaskSpec("t1", TaskTypes.GOTO, Json.obj("x", 10, "z", -5), 60, null, TaskSpec.ORIGIN_PANEL);
        Envelope task = Envelope.of(MessageTypes.TASK, spec);
        String line = task.encode();
        assertFalse(line.contains("\"label\""), "nulls are not serialised");
        assertFalse(line.contains("\"id\":null"));
        assertEquals(spec, Envelope.decode(line).payload(TaskSpec.class));

        TaskResult result = TaskResult.failure(spec, Reasons.PATH_FAILED, "no path", null, 1234);
        Envelope done = Envelope.of(MessageTypes.TASK_DONE, result);
        assertEquals(result, Envelope.decode(done.encode()).payload(TaskResult.class));

        Envelope reply = Envelope.reply(MessageTypes.RESULT, "m-1", QueryResult.failure(Reasons.UNSUPPORTED));
        Envelope back = Envelope.decode(reply.encode());
        assertEquals("m-1", back.re());
        assertFalse(back.payload(QueryResult.class).ok());
    }

    @Test
    void statusAndSnapshotRoundTrip() {
        BotStatus status = new BotStatus("bot1", "Bot1", BotStatus.ONLINE, "play.example.net", "minecraft:overworld",
                new Vec3d(1.5, 64, -3.25), 90f, 10f, 20f, 20f, 18, 5f, 3,
                Arrays.asList("minecraft:iron_helmet", null, null, "minecraft:iron_boots"), "minecraft:stone_pickaxe",
                null, 20, Map.of("minecraft:cobblestone", 64),
                new BotStatus.TaskInfo("t1", TaskTypes.MINE, null, BotStatus.TaskInfo.RUNNING, "mining", -1),
                new BotStatus.BaritoneInfo("MineProcess", true, "GoalBlock{1,2,3}", null),
                new BotStatus.Perf(300, 1024, 0.12, 10, 45), 120, 1700000000000L, 18_000L);
        String line = Envelope.of(MessageTypes.STATUS, status).encode();
        assertTrue(line.contains("[\"minecraft:iron_helmet\",null,null,\"minecraft:iron_boots\"]"),
                "empty armor slots stay as null array elements");
        assertEquals(status, Envelope.decode(line).payload(BotStatus.class));

        ContainerSnapshot snap = new ContainerSnapshot("minecraft:overworld", new Pos(1, 2, 3), "minecraft:chest", 27, 26,
                List.of(new ContainerSnapshot.SlotItem(0, "minecraft:dirt", 12)), 5L, true);
        ContainerSnapshot snapBack = Envelope.decode(Envelope.of(MessageTypes.CONTAINER, snap).encode())
                .payload(ContainerSnapshot.class);
        assertEquals(snap, snapBack);
        assertEquals(Map.of("minecraft:dirt", 12), snapBack.totals());

        Hello hello = new Hello(1, "bot1", "s3cr3t", "Bot1", "0.1.0", "26.2", "1.19.0", 4242);
        assertEquals(hello, Envelope.decode(Envelope.of(MessageTypes.HELLO, hello).encode()).payload(Hello.class));
    }

    @Test
    void decodeRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Envelope.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Envelope.decode("{not json"));
        assertThrows(IllegalArgumentException.class, () -> Envelope.decode("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> Envelope.decode("{\"d\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> Envelope.decode("{\"t\":5}"));
        assertThrows(IllegalArgumentException.class, () -> Envelope.decode("{\"t\":\"x\",\"d\":[1]}"));
        Envelope noPayload = Envelope.decode("{\"t\":\"stop\",\"re\":null}");
        assertEquals(new JsonObject(), noPayload.d());
        assertNull(noPayload.re());
    }
}
