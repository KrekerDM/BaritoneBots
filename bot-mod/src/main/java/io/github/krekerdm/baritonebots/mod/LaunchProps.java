package io.github.krekerdm.baritonebots.mod;

import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.link.HostPort;

/**
 * JVM system properties set by the manager (SPEC §2). {@code link == null} means the mod was started without a
 * manager and stays dormant apart from the low-power options.
 */
public record LaunchProps(HostPort link, String secret, String botId, boolean headless) {

    public static LaunchProps fromSystem() {
        String raw = System.getProperty(Protocol.PROP_LINK);
        HostPort link = null;
        if (raw != null && !raw.isBlank()) {
            try {
                link = HostPort.parse(raw, Protocol.DEFAULT_LINK_PORT);
            } catch (IllegalArgumentException e) {
                ModInfo.LOG.error("Ignoring malformed -D{}={}: {}", Protocol.PROP_LINK, raw, e.getMessage());
            }
        }
        return new LaunchProps(link, System.getProperty(Protocol.PROP_SECRET, ""),
                System.getProperty(Protocol.PROP_BOT_ID, ""), Boolean.getBoolean(Protocol.PROP_HEADLESS));
    }

    public boolean linked() {
        return link != null;
    }
}
