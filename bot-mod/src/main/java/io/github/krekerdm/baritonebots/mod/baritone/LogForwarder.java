package io.github.krekerdm.baritonebots.mod.baritone;

import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.LogLine;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import io.github.krekerdm.baritonebots.mod.link.LinkClient;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;

/**
 * Sends {@code log} lines to the manager, at most {@link Protocol#MAX_LOG_LINES_PER_SECOND} per second
 * (SPEC §2.3). Thread-safe: Baritone logs from its path-finding threads too.
 */
public final class LogForwarder {
    private final LinkClient link;
    private final RateLimiter limiter = new RateLimiter(Protocol.MAX_LOG_LINES_PER_SECOND, 1000);

    public LogForwarder(LinkClient link) {
        this.link = link;
    }

    /** A line Baritone would have printed to the chat HUD. */
    public void baritone(String text) {
        send(Levels.INFO, text, LogLine.SOURCE_BARITONE);
    }

    public void info(String text) {
        ModInfo.LOG.info(text);
        send(Levels.INFO, text, LogLine.SOURCE_MOD);
    }

    public void warn(String text) {
        ModInfo.LOG.warn(text);
        send(Levels.WARN, text, LogLine.SOURCE_MOD);
    }

    public void error(String text) {
        ModInfo.LOG.error(text);
        send(Levels.ERROR, text, LogLine.SOURCE_MOD);
    }

    private void send(String level, String text, String source) {
        if (text == null || text.isBlank() || !limiter.tryAcquire()) {
            return;
        }
        int skipped = limiter.takeSuppressed();
        String msg = skipped > 0 ? text + " [" + skipped + " earlier line(s) dropped by the rate limit]" : text;
        link.send(Envelope.of(MessageTypes.LOG, new LogLine(level, msg, source)));
    }
}
