package io.github.krekerdm.baritonebots.common.msg;

/** Manager's handshake answer carrying the bot's full config (SPEC §2.1). */
public record Welcome(BotConfig config) {
}
