package io.github.krekerdm.baritonebots.common;

/** Wire-level constants shared by the manager, the bot mod and the companion plugin (SPEC §2, §7). */
public final class Protocol {
    /** Link protocol version sent in {@code hello}; the manager rejects other values with {@code protocol}. */
    public static final int PROTOCOL_VERSION = 1;

    /** Plugin messaging channel between the bot mod and the companion plugin. */
    public static final String PLUGIN_CHANNEL = "baritonebots:main";
    public static final String PLUGIN_CHANNEL_NAMESPACE = "baritonebots";
    public static final String PLUGIN_CHANNEL_PATH = "main";

    public static final int DEFAULT_LINK_PORT = 25590;
    public static final int DEFAULT_PANEL_PORT = 8765;
    public static final String DEFAULT_BIND = "127.0.0.1";
    public static final int DEFAULT_MINECRAFT_PORT = 25565;

    /** JVM system property: {@code host:port} of the manager's link listener. Absent = mod stays dormant. */
    public static final String PROP_LINK = "baritonebots.link";
    /** JVM system property: shared link secret sent in {@code hello}. */
    public static final String PROP_SECRET = "baritonebots.secret";
    /** JVM system property: bot id as configured in the manager. */
    public static final String PROP_BOT_ID = "baritonebots.botId";
    /** JVM system property: {@code true} when the client runs without a real window (HeadlessMC). */
    public static final String PROP_HEADLESS = "baritonebots.headless";

    /** Maximum size of one link line in bytes, excluding the terminating {@code \n}. */
    public static final int MAX_LINE_BYTES = 4 * 1024 * 1024;
    /** Vanilla limit for serverbound custom payloads. */
    public static final int MAX_C2S_PLUGIN_BYTES = 32767;
    /** Limit for clientbound companion payloads (SPEC §7). */
    public static final int MAX_S2C_PLUGIN_BYTES = 1024 * 1024;

    /** Bot-side link reconnect backoff: starts here and doubles. */
    public static final long LINK_RECONNECT_MIN_MS = 1_000;
    /** Bot-side link reconnect backoff ceiling. */
    public static final long LINK_RECONNECT_MAX_MS = 15_000;

    /** Rate limit for {@code log} messages from one bot. */
    public static final int MAX_LOG_LINES_PER_SECOND = 10;
    /** Delay between a container menu opening and its {@code container} snapshot. */
    public static final int CONTAINER_SNAPSHOT_DELAY_TICKS = 3;

    private Protocol() {
    }
}
