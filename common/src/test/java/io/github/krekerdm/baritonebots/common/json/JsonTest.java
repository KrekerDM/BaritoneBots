package io.github.krekerdm.baritonebots.common.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.msg.LogLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    private static void merge(String target, String patch, String expected) {
        JsonElement t = Json.parse(target);
        JsonElement before = t.deepCopy();
        JsonElement result = Json.deepMerge(t, Json.parse(patch));
        assertEquals(Json.parse(expected), result, target + " + " + patch);
        assertEquals(before, t, "target must not be modified");
    }

    /** Test vectors from RFC 7396, appendix A. */
    @Test
    void mergePatchRfcExamples() {
        merge("{\"a\":\"b\"}", "{\"a\":\"c\"}", "{\"a\":\"c\"}");
        merge("{\"a\":\"b\"}", "{\"b\":\"c\"}", "{\"a\":\"b\",\"b\":\"c\"}");
        merge("{\"a\":\"b\"}", "{\"a\":null}", "{}");
        merge("{\"a\":\"b\",\"b\":\"c\"}", "{\"a\":null}", "{\"b\":\"c\"}");
        merge("{\"a\":[\"b\"]}", "{\"a\":\"c\"}", "{\"a\":\"c\"}");
        merge("{\"a\":\"c\"}", "{\"a\":[\"b\"]}", "{\"a\":[\"b\"]}");
        merge("{\"a\":{\"b\":\"c\"}}", "{\"a\":{\"b\":\"d\",\"c\":null}}", "{\"a\":{\"b\":\"d\"}}");
        merge("{\"a\":[{\"b\":\"c\"}]}", "{\"a\":[1]}", "{\"a\":[1]}");
        merge("[\"a\",\"b\"]", "[\"c\",\"d\"]", "[\"c\",\"d\"]");
        merge("{\"a\":\"b\"}", "[\"c\"]", "[\"c\"]");
        merge("{\"a\":\"foo\"}", "null", "null");
        merge("{\"a\":\"foo\"}", "\"bar\"", "\"bar\"");
        merge("{\"e\":null}", "{\"a\":1}", "{\"e\":null,\"a\":1}");
        merge("[1,2]", "{\"a\":\"b\",\"c\":null}", "{\"a\":\"b\"}");
        merge("{}", "{\"a\":{\"bb\":{\"ccc\":null}}}", "{\"a\":{\"bb\":{}}}");
        assertEquals(JsonNull.INSTANCE, Json.deepMerge(Json.parse("{}"), (JsonElement) null));
    }

    @Test
    void tolerantGetters() {
        JsonObject o = Json.parseObject("{\"s\":\"text\",\"n\":42,\"ns\":\" 7 \",\"f\":2.9,\"b\":true,\"bs\":\"FALSE\","
                + "\"o\":{\"k\":1},\"a\":[1,\"x\",{}],\"nul\":null}");
        assertEquals("text", Json.getString(o, "s", "d"));
        assertEquals("42", Json.getString(o, "n", "d"));
        assertEquals("d", Json.getString(o, "o", "d"));
        assertEquals("d", Json.getString(o, "nul", "d"));
        assertEquals(42, Json.getInt(o, "n", -1));
        assertEquals(7, Json.getInt(o, "ns", -1));
        assertEquals(2, Json.getInt(o, "f", -1));
        assertEquals(-1, Json.getInt(o, "s", -1));
        assertEquals(-1, Json.getInt(o, "b", -1));
        assertEquals(-1, Json.getInt(null, "n", -1));
        assertEquals(42L, Json.getLong(o, "n", 0));
        assertEquals(2.9, Json.getDouble(o, "f", 0));
        assertTrue(Json.getBool(o, "b", false));
        assertFalse(Json.getBool(o, "bs", true));
        assertTrue(Json.getBool(o, "s", true));
        assertEquals(1, Json.getObj(o, "o").get("k").getAsInt());
        assertNull(Json.getObj(o, "a"));
        assertEquals(3, Json.getArr(o, "a").size());
        assertNull(Json.getArr(o, "o"));
        assertEquals(List.of("1", "x"), Json.getStringList(o, "a"));
        assertEquals(List.of("text"), Json.getStringList(o, "s"));
        assertEquals(List.of(), Json.getStringList(o, "missing"));
    }

    @Test
    void builderAndGsonSettings() {
        JsonObject o = Json.obj("a", 1, "skip", null, "list", Json.arr("x", null, 2), "rec", new LogLine("info", "m", "mod"));
        assertEquals("{\"a\":1,\"list\":[\"x\",null,2],\"rec\":{\"level\":\"info\",\"message\":\"m\",\"source\":\"mod\"}}",
                Json.toJson(o));
        assertEquals("{\"level\":\"warn\",\"source\":\"baritone\"}", Json.toJson(new LogLine("warn", null, "baritone")));
        assertEquals("\"<b>&'\"", Json.toJson("<b>&'"));
        assertEquals(new JsonObject(), Json.toObject(null));
    }
}
