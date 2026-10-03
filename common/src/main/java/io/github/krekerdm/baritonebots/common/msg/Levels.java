package io.github.krekerdm.baritonebots.common.msg;

/** Severity values used by {@link BotEvent#level()} and {@link LogLine#level()}. */
public final class Levels {
    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String ERROR = "error";

    private Levels() {
    }
}
