package io.github.krekerdm.baritonebots.manager.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import io.github.krekerdm.baritonebots.common.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** One API request: path parameters, query parameters and a size-limited body. */
public final class Req {
    /** JSON request bodies are small; schematics use {@link #stream()} with their own limit. */
    public static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    final HttpExchange ex;
    private final Map<String, String> params;
    private final Map<String, String> query;

    Req(HttpExchange ex, Map<String, String> params) {
        this.ex = ex;
        this.params = params;
        this.query = parseQuery(ex.getRequestURI().getRawQuery());
    }

    public String param(String name) {
        return params.get(name);
    }

    public String query(String name, String def) {
        String v = query.get(name);
        return v == null || v.isBlank() ? def : v;
    }

    public int queryInt(String name, int def, int min, int max) {
        String v = query.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_request", "query parameter '" + name + "' is not a number");
        }
    }

    public InputStream stream() {
        return ex.getRequestBody();
    }

    public byte[] body(int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new ApiException(413, "too_large", "request body exceeds " + max + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** The body as a JSON object; an empty body is {@code {}}. */
    public JsonObject json() throws IOException {
        String text = new String(body(MAX_JSON_BYTES), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement e = Json.parse(text);
            if (!e.isJsonObject()) {
                throw ApiException.badRequest("bad_request", "body must be a JSON object");
            }
            return e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw ApiException.badRequest("bad_json", e.getMessage());
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // malformed escape; skip the pair
            }
        }
        return out;
    }
}
