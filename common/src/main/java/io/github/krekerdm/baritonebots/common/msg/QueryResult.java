package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;

/** {@code result} payload answering a {@link Query}; {@code error} is a reason code when {@code ok} is false. */
public record QueryResult(boolean ok, String error, JsonObject data) {
    public static QueryResult success(JsonObject data) {
        return new QueryResult(true, null, data == null ? new JsonObject() : data);
    }

    public static QueryResult failure(String error) {
        return new QueryResult(false, error, null);
    }
}
