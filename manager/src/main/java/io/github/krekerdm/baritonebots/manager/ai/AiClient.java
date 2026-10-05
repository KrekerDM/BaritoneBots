package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One model server API (SPEC §5.7c): Ollama's own ({@link OllamaClient}) or an OpenAI-compatible one such as LM Studio
 * ({@link OpenAiClient}). Requests run on the HTTP client's threads, never on the manager loop. Failures complete the
 * future exceptionally with an {@link AiException}.
 */
public interface AiClient {
    /** {@code ollama} or {@code openai}. */
    String provider();

    /** One chat round; completes with the model's answer parsed as a JSON object that should match {@code schema}. */
    CompletableFuture<JsonObject> chat(ManagerConfig.AiCfg c, String system, String user, JsonObject schema);

    /** Ids of the models the server offers (installed in Ollama, loaded in LM Studio). */
    CompletableFuture<List<String>> models(ManagerConfig.AiCfg c, int timeoutSec);
}
