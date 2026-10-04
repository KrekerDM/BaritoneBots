package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.refs.Refs;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Attention hold (SPEC §5.7a): after the owner called a bot ({@code come}, {@code follow}, a {@code goto} to an
 * {@code owner} / {@code owner_look} reference) the autopilot leaves it alone (no idle work, no inspection walks) for
 * {@code autopilot.holdAfterOwnerSec}, or until the next manual task / owner command; {@code follow} holds while the
 * bot follows, then the normal hold starts. Pure, unit-tested; loop-owned.
 */
final class AttentionHold {
    static final long WHILE_FOLLOWING = Long.MAX_VALUE;

    private final Map<String, Long> until = new HashMap<>();

    /**
     * Hold end a newly queued manual entry asks for: {@link #WHILE_FOLLOWING}, a time, or 0 = none (the entry
     * releases an existing hold).
     */
    static long untilFor(String type, String origin, JsonObject args, Set<String> refKinds, String owner,
                         String ownerOrigin, long now, int holdSec) {
        if (holdSec <= 0) {
            return 0;
        }
        boolean fromOwner = ownerOrigin.equals(origin);
        if (TaskTypes.FOLLOW.equals(type)) {
            String player = args == null ? "" : Json.getString(args, "player", "");
            return fromOwner || owner != null && owner.equalsIgnoreCase(player) ? WHILE_FOLLOWING : 0;
        }
        if (TaskTypes.GOTO.equals(type)
                && (fromOwner || refKinds.contains(Refs.OWNER) || refKinds.contains(Refs.OWNER_LOOK))) {
            return now + holdSec * 1000L;
        }
        return 0;
    }

    /** A manual queue addition: hold until {@code end}, or release when {@code end <= 0}. */
    void set(String botId, long end) {
        if (end > 0) {
            until.put(botId, end);
        } else {
            until.remove(botId);
        }
    }

    void release(String botId) {
        until.remove(botId);
    }

    /** Is the bot held now? A follow hold whose follow ended turns into the normal hold from now. */
    boolean held(String botId, boolean following, long now, int holdSec) {
        Long end = until.get(botId);
        if (end == null) {
            return false;
        }
        if (end == WHILE_FOLLOWING) {
            if (following) {
                return true;
            }
            end = holdSec <= 0 ? now : now + holdSec * 1000L;
            until.put(botId, end);
        }
        if (now >= end) {
            until.remove(botId);
            return false;
        }
        return true;
    }

    /** Seconds left per held bot (-1 = while following), for the autopilot view. */
    Map<String, Long> view(long now) {
        Map<String, Long> out = new HashMap<>();
        until.forEach((k, v) -> out.put(k, v == WHILE_FOLLOWING ? -1 : Math.max(0, (v - now) / 1000)));
        return out;
    }
}
