package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * Talks to a local Ollama server (SPEC §5.7c): {@code POST /api/chat} with {@code stream:false} and a JSON schema in
 * {@code format}, {@code GET /api/tags} for the model list. Requests run on the HTTP client's own virtual threads and
 * never on the manager loop; callers post the result back. Thread-safe.
 */
public final class OllamaClient implements AiClient {
    /** Low temperature: the model fills a schema, it does not write prose. */
    static final double TEMPERATURE = 0.1;
    /** Context window asked for; the catalog prompt plus a digest stay well below it. */
    static final int NUM_CTX = 8192;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    @Override
    public String provider() {
        return ManagerConfig.AiCfg.OLLAMA;
    }

    @Override
    public CompletableFuture<JsonObject> chat(ManagerConfig.AiCfg c, String system, String user, JsonObject schema) {
        return chat(c.base(), c.apiKey(), c.model(), system, user, schema, c.timeoutSec());
    }

    @Override
    public CompletableFuture<List<String>> models(ManagerConfig.AiCfg c, int timeoutSec) {
        return tags(c.base(), c.apiKey(), timeoutSec);
    }

    /** Adds {@code Authorization: Bearer} when a key is set; the key never reaches a log or an error text. */
    static HttpRequest.Builder auth(HttpRequest.Builder b, String apiKey) {
        return apiKey == null || apiKey.isBlank() ? b : b.header("Authorization", "Bearer " + apiKey);
    }

    /**
     * One chat round. Completes with the model's answer parsed as a JSON object, or exceptionally with an
     * {@link AiException} ({@code ai_unreachable}, {@code ai_timeout}, {@code ai_model_missing}, {@code ai_http},
     * {@code ai_invalid_json}).
     */
    CompletableFuture<JsonObject> chat(String base, String apiKey, String model, String system, String user,
                                       JsonObject schema, int timeoutSec) {
        JsonArray messages = Json.arr(Json.obj("role", "system", "content", system),
                Json.obj("role", "user", "content", user));
        JsonObject body = Json.obj("model", model, "stream", false, "format", schema, "messages", messages,
                "options", Json.obj("temperature", TEMPERATURE, "num_ctx", NUM_CTX));
        HttpRequest req;
        try {
            req = auth(HttpRequest.newBuilder(URI.create(base + "/api/chat")), apiKey)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSec)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new AiException(AiException.UNREACHABLE, "bad endpoint: " + base));
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, err) -> {
                    if (err != null) {
                        throw new CompletionException(mapError(err, base, timeoutSec));
                    }
                    return parseChat(resp.statusCode(), resp.body(), model);
                });
    }

    /**
     * Names of the installed models ({@code GET /api/tags}). An answer without a {@code models} array is an error: an
     * OpenAI-compatible server may answer 200 with an error object to an unknown path.
     */
    CompletableFuture<List<String>> tags(String base, String apiKey, int timeoutSec) {
        HttpRequest req;
        try {
            req = auth(HttpRequest.newBuilder(URI.create(base + "/api/tags")), apiKey)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSec))).GET().build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new AiException(AiException.UNREACHABLE, "bad endpoint: " + base));
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, err) -> {
                    if (err != null) {
                        throw new CompletionException(mapError(err, base, timeoutSec));
                    }
                    if (resp.statusCode() != 200) {
                        throw new CompletionException(httpError(resp.statusCode(), resp.body(), null));
                    }
                    return names(resp.body(), "models", "name", "/api/tags");
                });
    }

    /** {@code root[array][*][field]}; a missing array is reported with the server's own error text. */
    static List<String> names(String body, String array, String field, String path) {
        JsonArray items;
        try {
            items = Json.getArr(Json.parseObject(body), array);
        } catch (RuntimeException e) {
            items = null;
        }
        if (items == null) {
            String text = errorText(body);
            throw new CompletionException(new AiException(AiException.HTTP, path + " did not list models"
                    + (text.isBlank() ? "" : ": " + text)));
        }
        List<String> names = new ArrayList<>();
        for (JsonElement mo : items) {
            if (mo.isJsonObject()) {
                String n = Json.getString(mo.getAsJsonObject(), field, null);
                if (n != null) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    static JsonObject parseChat(int status, String body, String model) {
        if (status != 200) {
            throw new CompletionException(httpError(status, body, model));
        }
        JsonObject root;
        try {
            root = Json.parseObject(body);
        } catch (RuntimeException e) {
            throw new CompletionException(new AiException(AiException.INVALID_JSON, "the answer is not JSON"));
        }
        JsonObject message = Json.getObj(root, "message");
        String content = message == null ? null : Json.getString(message, "content", null);
        if (content == null || content.isBlank()) {
            throw new CompletionException(message == null && root.has("error") ? httpError(status, body, model)
                    : new AiException(AiException.INVALID_JSON, "the answer has no content"));
        }
        return parseContent(content);
    }

    /** The model's text as one JSON object; tolerates a Markdown code fence around it. */
    static JsonObject parseContent(String content) {
        String s = content.trim();
        int think = s.lastIndexOf("</think>");
        if (think >= 0) {
            s = s.substring(think + "</think>".length()).trim();
        }
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            s = nl > 0 && end > nl ? s.substring(nl + 1, end).trim() : s;
        }
        try {
            JsonElement e = Json.parse(s);
            if (e != null && e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (JsonParseException | IllegalStateException e) {
            // reported below
        }
        String shown = s.length() > 120 ? s.substring(0, 117) + "..." : s;
        throw new CompletionException(new AiException(AiException.INVALID_JSON, "not a JSON object: " + shown));
    }

    /** The server's error text: {@code error} as a string or {@code error.message}, else the first 200 chars of the body. */
    static String errorText(String body) {
        try {
            JsonElement err = Json.parseObject(body).get("error");
            if (err != null && err.isJsonPrimitive()) {
                return err.getAsString();
            }
            if (err != null && err.isJsonObject()) {
                String msg = Json.getString(err.getAsJsonObject(), "message", null);
                if (msg != null) {
                    return msg;
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        String b = body == null ? "" : body.strip();
        return b.length() > 200 ? b.substring(0, 200) : b;
    }

    private static AiException httpError(int status, String body, String model) {
        String text = errorText(body);
        if (status == 404 && model != null && text.toLowerCase(java.util.Locale.ROOT).contains("not found")) {
            return new AiException(AiException.MODEL_MISSING, text);
        }
        return new AiException(AiException.HTTP, "HTTP " + status + (text.isBlank() ? "" : ": " + text));
    }

    static AiException mapError(Throwable err, String base, int timeoutSec) {
        Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        if (c instanceof AiException ae) {
            return ae;
        }
        if (c instanceof HttpTimeoutException) {
            return new AiException(AiException.TIMEOUT, "no answer from " + base + " within " + timeoutSec + " s");
        }
        if (c instanceof IOException) {
            return new AiException(AiException.UNREACHABLE, base + ": "
                    + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage()));
        }
        return new AiException(AiException.UNREACHABLE, String.valueOf(c));
    }
}
