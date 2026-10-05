package io.github.krekerdm.baritonebots.manager.ai;

import io.github.krekerdm.baritonebots.manager.http.ApiException;

/** A failed model round (SPEC §5.7c/d): the code goes to the panel and into the supervisor feed. */
public final class AiException extends RuntimeException {
    public static final String DISABLED = "ai_disabled";
    public static final String UNREACHABLE = "ai_unreachable";
    public static final String TIMEOUT = "ai_timeout";
    public static final String MODEL_MISSING = "ai_model_missing";
    public static final String HTTP = "ai_http";
    public static final String INVALID_JSON = "ai_invalid_json";

    private final String code;

    public AiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** For the HTTP API: 409 when switched off, 504 on timeout, 502 for everything the model server did wrong. */
    public ApiException toApi() {
        int status = switch (code) {
            case DISABLED -> 409;
            case TIMEOUT -> 504;
            default -> 502;
        };
        return new ApiException(status, code, getMessage());
    }
}
