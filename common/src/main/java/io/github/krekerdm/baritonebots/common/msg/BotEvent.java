package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;

/** Something notable happened to a bot (SPEC §2.6): {@code kind} from {@link EventKinds}, {@code level} from {@link Levels}. */
public record BotEvent(String kind, String level, String message, JsonObject data, long time) {
    public BotEvent {
        if (level == null) {
            level = Levels.INFO;
        }
    }

    /** Event stamped with the current time. */
    public static BotEvent of(String kind, String level, String message, JsonObject data) {
        return new BotEvent(kind, level, message, data, System.currentTimeMillis());
    }
}
