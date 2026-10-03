package io.github.krekerdm.baritonebots.common.msg;

/** First link message from a bot (SPEC §2.1). */
public record Hello(int protocol, String botId, String secret, String username, String modVersion, String mcVersion,
                    String baritoneVersion, long pid) {
}
