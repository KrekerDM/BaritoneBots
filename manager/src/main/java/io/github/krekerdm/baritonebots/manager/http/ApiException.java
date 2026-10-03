package io.github.krekerdm.baritonebots.manager.http;

/**
 * An error the HTTP API reports as {@code {"error":code,"message":...}} with the given status (SPEC §6).
 * Thrown from loop code too; the HTTP layer maps it.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message == null ? code : message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException unsupported(String message) {
        return new ApiException(501, "unsupported", message);
    }
}
