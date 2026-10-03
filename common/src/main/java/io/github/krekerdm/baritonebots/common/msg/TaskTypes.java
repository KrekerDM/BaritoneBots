package io.github.krekerdm.baritonebots.common.msg;

import java.util.List;

/** {@link TaskSpec#type()} values executed by the bot mod (SPEC §3). */
public final class TaskTypes {
    public static final String GOTO = "goto";
    public static final String GOTO_PLAYER = "goto_player";
    public static final String FOLLOW = "follow";
    public static final String EXPLORE = "explore";
    public static final String BARITONE = "baritone";
    public static final String MINE = "mine";
    public static final String FARM = "farm";
    public static final String SELECTION = "selection";
    public static final String BUILD = "build";
    public static final String COLLECT_DROPS = "collect_drops";
    public static final String TAKE = "take";
    public static final String DEPOSIT = "deposit";
    public static final String TRANSFER = "transfer";
    public static final String INSPECT = "inspect";
    public static final String EQUIP = "equip";
    public static final String DROP = "drop";
    public static final String CRAFT = "craft";
    public static final String SMELT_LOAD = "smelt_load";
    public static final String SMELT_COLLECT = "smelt_collect";
    public static final String BREED = "breed";
    public static final String SLAUGHTER = "slaughter";
    public static final String SHEAR = "shear";
    public static final String GUARD = "guard";
    public static final String ATTACK = "attack";
    public static final String RECOVER = "recover";
    public static final String EAT = "eat";
    public static final String IDLE = "idle";

    /** Every bot-side task type, in catalog order. */
    public static final List<String> ALL = List.of(
            GOTO, GOTO_PLAYER, FOLLOW, EXPLORE, BARITONE, MINE, FARM, SELECTION, BUILD, COLLECT_DROPS,
            TAKE, DEPOSIT, TRANSFER, INSPECT, EQUIP, DROP, CRAFT, SMELT_LOAD, SMELT_COLLECT,
            BREED, SLAUGHTER, SHEAR, GUARD, ATTACK, RECOVER, EAT, IDLE);

    /** Tasks that run until cancelled or timed out. */
    public static final List<String> CONTINUOUS = List.of(FOLLOW, EXPLORE, GUARD);

    /** Path-heavy tasks counted against {@code runtime.maxHeavyTasks} (SPEC §5.3). */
    public static final List<String> HEAVY = List.of(MINE, EXPLORE, BUILD, SELECTION, FARM);

    /** {@code selection} task {@code op} values. */
    public static final List<String> SELECTION_OPS = List.of("clear", "fill", "walls", "shell", "replace");

    private TaskTypes() {
    }

    public static boolean isKnown(String type) {
        return ALL.contains(type);
    }
}
