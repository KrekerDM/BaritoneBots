package io.github.krekerdm.baritonebots.manager.events;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.msg.Levels;

/**
 * One line of events.jsonl and of the panel's event feed (SPEC §5.8).
 *
 * @param kind    bot event kinds (SPEC §2.6) for {@code source=bot}; manager kinds such as {@code crashed},
 *                {@code task_failed}, {@code runtime} otherwise
 * @param source  bot | manager | plugin | an extension name
 * @param message ready-to-show text in the manager's language (bots send their own text)
 * @param key     i18n key of {@code message} for the panel to re-translate, or null for bot text
 * @param args    placeholder values for {@code key}
 */
public record ManagerEvent(long seq, long time, String kind, String level, String source, String botId,
                           String message, String key, JsonObject args, JsonObject data) {
    public static final String SOURCE_BOT = "bot";
    public static final String SOURCE_MANAGER = "manager";
    public static final String SOURCE_PLUGIN = "plugin";

    /** info=0, warn=1, error=2; unknown levels count as info. */
    public static int rank(String level) {
        return Levels.ERROR.equals(level) ? 2 : Levels.WARN.equals(level) ? 1 : 0;
    }
}
