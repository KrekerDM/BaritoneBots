package io.github.krekerdm.baritonebots.common.msg;

/** {@code log} payload: a Baritone or mod log line ({@code source} = {@code baritone} | {@code mod}). */
public record LogLine(String level, String message, String source) {
    public static final String SOURCE_BARITONE = "baritone";
    public static final String SOURCE_MOD = "mod";
}
