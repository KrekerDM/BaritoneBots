package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.krekerdm.baritonebots.common.json.Json;
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
public final class OllamaClient {
    /** Low temperature: the model fills a schema, it does not write prose. */
    static final double TEMPERATURE = 0.1;
    /** Context window asked for; the catalog prompt plus a digest stay well below it. */
    static final int NUM_CTX = 8192;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * One chat round. Completes with the model's answer parsed as a JSON object, or exceptionally with an
     * {@link AiException} ({@code ai_unreachable}, {@code ai_timeout}, {@code ai_model_missing}, {@code ai_http},
     * {@code ai_invalid_json}).
     */
    public CompletableFuture<JsonObject> chat(String base, String model, String system, String user, JsonObject schema,
                                              int timeoutSec) {
        JsonArray messages = Json.arr(Json.obj("role", "system", "content", system),
                Json.obj("role", "user", "content", user));
        JsonObject body = Json.obj("model", model, "stream", false, "format", schema, "messages", messages,
                "options", Json.obj("temperature", TEMPERATURE, "num_ctx", NUM_CTX));
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
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

    /** Names of the installed models ({@code GET /api/tags}). */
    public CompletableFuture<List<String>> tags(String base, int timeoutSec) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + "/api/tags"))
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
                    List<String> names = new ArrayList<>();
                    try {
                        JsonArray models = Json.getArr(Json.parseObject(resp.body()), "models");
                        if (models != null) {
                            for (JsonElement mo : models) {
                                if (mo.isJsonObject()) {
                                    String n = Json.getString(mo.getAsJsonObject(), "name", null);
                                    if (n != null) {
                                        names.add(n);
                                    }
                                }
                            }
                        }
                    } catch (RuntimeException e) {
                        throw new CompletionException(new AiException(AiException.INVALID_JSON,
                                "/api/tags did not answer with JSON"));
                    }
                    return names;
                });
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
            throw new CompletionException(new AiException(AiException.INVALID_JSON, "the answer has no content"));
        }
        return parseContent(content);
    }

    /** The model's text as one JSON object; tolerates a Markdown code fence around it. */
    static JsonObject parseContent(String content) {
        String s = content.trim();
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

    private static AiException httpError(int status, String body, String model) {
        String err = null;
        try {
            err = Json.getString(Json.parseObject(body), "error", null);
        } catch (RuntimeException ignored) {
            // not JSON
        }
        String text = err != null ? err : body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body;
        if (status == 404 && model != null && text.toLowerCase(java.util.Locale.ROOT).contains("not found")) {
            return new AiException(AiException.MODEL_MISSING, text);
        }
        return new AiException(AiException.HTTP, "HTTP " + status + (text.isBlank() ? "" : ": " + text));
    }

    private static AiException mapError(Throwable err, String base, int timeoutSec) {
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
