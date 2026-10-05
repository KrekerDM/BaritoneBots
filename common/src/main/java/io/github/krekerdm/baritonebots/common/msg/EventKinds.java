package io.github.krekerdm.baritonebots.common.msg;

import java.util.List;

/** {@link BotEvent#kind()} values (SPEC §2.6); the data keys each kind carries are noted per constant. */
public final class EventKinds {
    public static final String JOINED = "joined";
    /** data.reason */
    public static final String DISCONNECTED = "disconnected";
    /** data.reason */
    public static final String KICKED = "kicked";
    public static final String LOGIN_OK = "login_ok";
    public static final String LOGIN_FAILED = "login_failed";
    /** data.pos, data.dim, data.cause */
    public static final String DEATH = "death";
    public static final String RESPAWNED = "respawned";
    /** Health dropped by 4 or more within one second; data.source */
    public static final String DAMAGED = "damaged";
    /** Hostile or boss nearby; data.type, data.pos */
    public static final String THREAT = "threat";
    public static final String INVENTORY_FULL = "inventory_full";
    /** data.item, data.durabilityLeft */
    public static final String TOOL_LOW = "tool_low";
    /** No allowed food in the inventory. */
    public static final String FOOD_LOW = "food_low";
    /** System or whisper line that matched nothing else; data.text */
    public static final String CHAT = "chat";
    /** data.state = verified | rejected | absent */
    public static final String COMPANION = "companion";
    public static final String ERROR = "error";
    /**
     * A chat line from the owner starting with the command prefix (SPEC §5.7e); data.text (without the prefix),
     * player, via (chat | whisper | system), pos, dim, yaw, pitch, lookBlock, lookBlockId (null when unknown).
     */
    public static final String OWNER_COMMAND = "owner_command";
    /**
     * The protection guard refused to break or place (first refusal of a streak, at most one per 30 s);
     * data.action (break | place), pos, block, why (zone | noBreak | built).
     */
    public static final String PROTECTED = "protected";

    public static final List<String> ALL = List.of(JOINED, DISCONNECTED, KICKED, LOGIN_OK, LOGIN_FAILED, DEATH,
            RESPAWNED, DAMAGED, THREAT, INVENTORY_FULL, TOOL_LOW, FOOD_LOW, CHAT, COMPANION, ERROR, OWNER_COMMAND, PROTECTED);

    /** {@code companion} event states. */
    public static final String COMPANION_VERIFIED = "verified";
    public static final String COMPANION_REJECTED = "rejected";
    public static final String COMPANION_ABSENT = "absent";

    private EventKinds() {
    }
}
