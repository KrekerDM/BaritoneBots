package io.github.krekerdm.baritonebots.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** A live bot stopped reporting status because Baritone's ETA was Infinity; such values must serialise as null. */
class NonFiniteNumbersTest {
    record Sample(Double eta, double progress, float health, String name) {
    }

    @Test
    void infinityAndNanBecomeNull() {
        String json = Json.toJson(new Sample(Double.POSITIVE_INFINITY, Double.NaN, Float.NEGATIVE_INFINITY, "bot1"));
        JsonObject o = Json.parseObject(json);
        assertTrue(!o.has("progress") || o.get("progress").isJsonNull());
        assertTrue(!o.has("eta") || o.get("eta").isJsonNull());
        assertEquals("bot1", o.get("name").getAsString());
    }

    @Test
    void finiteValuesRoundTrip() {
        Sample s = Json.fromJson(Json.toJson(new Sample(12.5, 0.25, 18f, "bot1")), Sample.class);
        assertEquals(12.5, s.eta());
        assertEquals(0.25, s.progress());
        assertEquals(18f, s.health());
    }

    @Test
    void nonFiniteSurvivesRoundTripAsDefault() {
        // nulls are not serialised, so the receiving side sees an absent field: boxed → null, primitive → 0
        Sample s = Json.fromJson(Json.toJson(new Sample(Double.NaN, Double.POSITIVE_INFINITY, 20f, "x")), Sample.class);
        assertNull(s.eta());
        assertEquals(0.0, s.progress());
        assertEquals(20f, s.health());
    }
}
