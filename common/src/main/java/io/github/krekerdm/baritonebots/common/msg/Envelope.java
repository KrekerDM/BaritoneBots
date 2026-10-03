package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.Objects;

/**
 * One protocol message (SPEC §2): type {@code t}, optional sender id {@code id}, optional reply-to {@code re},
 * payload {@code d}. Encodes to a single line of JSON for the link and the plugin channel.
 */
public record Envelope(String t, String id, String re, JsonObject d) {
    public Envelope {
        Objects.requireNonNull(t, "t");
        if (t.isBlank()) {
            throw new IllegalArgumentException("message type is blank");
        }
        if (d == null) {
            d = new JsonObject();
        }
    }

    /** Message without id; {@code payload} may be a JsonObject, a record or {@code null}. */
    public static Envelope of(String t, Object payload) {
        return new Envelope(t, null, null, Json.toObject(payload));
    }

    /** Message with a sender id (required for {@code query}). */
    public static Envelope request(String t, String id, Object payload) {
        return new Envelope(t, id, null, Json.toObject(payload));
    }

    /** Reply ({@code result} / {@code reject}) to the message with id {@code re}. */
    public static Envelope reply(String t, String re, Object payload) {
        return new Envelope(t, null, re, Json.toObject(payload));
    }

    public Envelope withId(String newId) {
        return new Envelope(t, newId, re, d);
    }

    /** True when the type equals {@code type}. */
    public boolean is(String type) {
        return t.equals(type);
    }

    /** Deserialises the payload into a record / class. */
    public <T> T payload(Class<T> type) {
        return Json.fromJson(d, type);
    }

    /** One-line JSON without a trailing newline; Gson escapes every control character inside strings. */
    public String encode() {
        JsonObject o = new JsonObject();
        o.addProperty("t", t);
        if (id != null) {
            o.addProperty("id", id);
        }
        if (re != null) {
            o.addProperty("re", re);
        }
        o.add("d", d);
        return Json.GSON.toJson(o);
    }

    /**
     * Parses one line produced by {@link #encode()} (or a compatible peer).
     *
     * @throws IllegalArgumentException when the text is not a JSON object with a string {@code t}
     *                                  or {@code d} is present but not an object
     */
    public static Envelope decode(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("empty message");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(line);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("malformed JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("message is not a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        JsonElement te = o.get("t");
        if (te == null || !te.isJsonPrimitive() || !te.getAsJsonPrimitive().isString() || te.getAsString().isBlank()) {
            throw new IllegalArgumentException("message has no type");
        }
        JsonElement de = o.get("d");
        JsonObject d;
        if (de == null || de.isJsonNull()) {
            d = new JsonObject();
        } else if (de.isJsonObject()) {
            d = de.getAsJsonObject();
        } else {
            throw new IllegalArgumentException("payload is not an object");
        }
        return new Envelope(te.getAsString(), optString(o.get("id")), optString(o.get("re")), d);
    }

    private static String optString(JsonElement e) {
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
