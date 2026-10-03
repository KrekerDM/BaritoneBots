package io.github.krekerdm.baritonebots.common.msg;

/** Handshake refusal (SPEC §2.1 link, §7 companion); the sender closes / ignores the peer afterwards. */
public record Reject(String reason) {
    /** Link: wrong {@code secret}. */
    public static final String BAD_SECRET = "bad_secret";
    /** Link: no bot with that id is configured. */
    public static final String UNKNOWN_BOT = "unknown_bot";
    /** Link: protocol version mismatch. */
    public static final String PROTOCOL = "protocol";
}
