package io.github.krekerdm.baritonebots.plugin.journal;

import java.util.Locale;
import java.util.Objects;

/**
 * One block change made by a verified bot.
 *
 * @param time   epoch milliseconds of the event
 * @param action break or place
 * @param actor  bot player name as it was online (matched case-insensitively)
 * @param world  Bukkit world name
 * @param before full block-data string before the change ({@code BlockData#getAsString()})
 * @param after  full block-data string after the change
 */
public record JournalRecord(long time, Action action, String actor, String world, int x, int y, int z,
                            String before, String after) {
    public JournalRecord {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }

    /** Key used to group records per bot. */
    public String actorKey() {
        return key(actor);
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public enum Action {
        BREAK('B'),
        PLACE('P');

        private final char code;

        Action(char code) {
            this.code = code;
        }

        public char code() {
            return code;
        }

        public static Action ofCode(char c) {
            for (Action a : values()) {
                if (a.code == c) {
                    return a;
                }
            }
            throw new IllegalArgumentException("unknown action code '" + c + "'");
        }
    }
}
