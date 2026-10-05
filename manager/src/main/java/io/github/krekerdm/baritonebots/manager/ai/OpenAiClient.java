package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * Talks to an OpenAI-compatible server (LM Studio, llama.cpp, vLLM): {@code POST /v1/chat/completions} with the schema
 * in {@code response_format}, {@code GET /v1/models} for the model list. A server that answers 400 to
 * {@code json_schema} gets {@code json_object}, then no format, with the schema in the system prompt. Thread-safe.
 */
public final class OpenAiClient implements AiClient {
    /** Upper bound of one answer; plans and supervisor actions stay far below it. */
    static final int MAX_TOKENS = 2048;
    private static final int FORMAT_SCHEMA = 0;
    private static final int FORMAT_OBJECT = 1;
    private static final int FORMAT_NONE = 2;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    @Override
    public String provider() {
        return ManagerConfig.AiCfg.OPENAI;
    }

    @Override
    public CompletableFuture<JsonObject> chat(ManagerConfig.AiCfg c, String system, String user, JsonObject schema) {
        return round(c, system, user, schema, FORMAT_SCHEMA);
    }

    private CompletableFuture<JsonObject> round(ManagerConfig.AiCfg c, String system, String user, JsonObject schema,
                                                int format) {
        String sys = format == FORMAT_SCHEMA ? system : system
                + "\n\nAnswer with one JSON object and nothing else. It must match this JSON schema:\n" + Json.toJson(schema);
        JsonArray messages = Json.arr(Json.obj("role", "system", "content", sys),
                Json.obj("role", "user", "content", user));
        JsonObject body = Json.obj("model", c.model(), "stream", false, "temperature", OllamaClient.TEMPERATURE,
                "max_tokens", MAX_TOKENS, "messages", messages);
        if (format == FORMAT_SCHEMA) {
            body.add("response_format", Json.obj("type", "json_schema",
                    "json_schema", Json.obj("name", "answer", "strict", true, "schema", schema)));
        } else if (format == FORMAT_OBJECT) {
            body.add("response_format", Json.obj("type", "json_object"));
        }
        HttpRequest req;
        try {
            req = OllamaClient.auth(HttpRequest.newBuilder(URI.create(c.base() + "/v1/chat/completions")), c.apiKey())
                    .timeout(Duration.ofSeconds(Math.max(1, c.timeoutSec())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new AiException(AiException.UNREACHABLE, "bad endpoint: " + c.base()));
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, err) -> {
                    if (err != null) {
                        throw new CompletionException(OllamaClient.mapError(err, c.base(), c.timeoutSec()));
                    }
                    return resp;
                })
                .thenCompose(resp -> {
                    if (resp.statusCode() == 400 && format < FORMAT_NONE
                            && !AiException.MODEL_MISSING.equals(httpError(400, resp.body(), c.model()).code())) {
                        return round(c, system, user, schema, format + 1);
                    }
                    return CompletableFuture.completedFuture(parseChat(resp.statusCode(), resp.body(), c.model()));
                });
    }

    @Override
    public CompletableFuture<List<String>> models(ManagerConfig.AiCfg c, int timeoutSec) {
        HttpRequest req;
        try {
            req = OllamaClient.auth(HttpRequest.newBuilder(URI.create(c.base() + "/v1/models")), c.apiKey())
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSec))).GET().build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new AiException(AiException.UNREACHABLE, "bad endpoint: " + c.base()));
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((resp, err) -> {
                    if (err != null) {
                        throw new CompletionException(OllamaClient.mapError(err, c.base(), timeoutSec));
                    }
                    if (resp.statusCode() != 200) {
                        throw new CompletionException(httpError(resp.statusCode(), resp.body(), null));
                    }
                    return OllamaClient.names(resp.body(), "data", "id", "/v1/models");
                });
    }

    /** {@code choices[0].message.content} as one JSON object. */
    static JsonObject parseChat(int status, String body, String model) {
        if (status != 200) {
            throw new CompletionException(httpError(status, body, model));
        }
        String content;
        try {
            JsonObject root = Json.parseObject(body);
            JsonArray choices = Json.getArr(root, "choices");
            if (choices == null || choices.isEmpty()) {
                throw new CompletionException(root.has("error") ? httpError(status, body, model)
                        : new AiException(AiException.INVALID_JSON, "the answer has no choices"));
            }
            JsonObject message = Json.getObj(choices.get(0).getAsJsonObject(), "message");
            content = message == null || !message.has("content") || message.get("content").isJsonNull() ? null
                    : message.get("content").getAsString();
        } catch (CompletionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CompletionException(new AiException(AiException.INVALID_JSON, "the answer is not JSON"));
        }
        if (content == null || content.isBlank()) {
            throw new CompletionException(new AiException(AiException.INVALID_JSON, "the answer has no content"));
        }
        return OllamaClient.parseContent(content);
    }

    private static AiException httpError(int status, String body, String model) {
        String text = OllamaClient.errorText(body);
        String low = text.toLowerCase(Locale.ROOT);
        if (model != null && (status == 400 || status == 404) && low.contains("model")
                && (low.contains("not found") || low.contains("not loaded") || low.contains("no models loaded")
                || low.contains("does not exist"))) {
            return new AiException(AiException.MODEL_MISSING, text);
        }
        return new AiException(AiException.HTTP, "HTTP " + status + (text.isBlank() ? "" : ": " + text));
    }
}
