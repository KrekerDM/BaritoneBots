package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;

/** {@code query} payload (SPEC §2.4): {@code kind} from {@link QueryKinds} plus its {@code args}. */
public record Query(String kind, JsonObject args) {
    public Query {
        if (args == null) {
            args = new JsonObject();
        }
    }
}
