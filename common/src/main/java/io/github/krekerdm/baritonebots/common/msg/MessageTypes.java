package io.github.krekerdm.baritonebots.common.msg;

/** Envelope {@code t} values: link manager⇄bot (SPEC §2.1-2.3) and companion channel (SPEC §7). */
public final class MessageTypes {
    // Handshake (link) and companion handshake share these names.
    /** Bot → manager / bot → plugin: {@link Hello} on the link, {@code {token,botId,modVersion}} to the plugin. */
    public static final String HELLO = "hello";
    /** Manager → bot {@link Welcome}; plugin → bot {@code {features,server}}. */
    public static final String WELCOME = "welcome";
    /** {@link Reject}, sent before the peer closes / ignores the bot. */
    public static final String REJECT = "reject";

    // Manager → bot
    /** {@link BotConfig}: replace and apply. */
    public static final String CONFIG = "config";
    /** {@link TaskSpec}: start, replacing the current task. */
    public static final String TASK = "task";
    /** {@code {"taskId"}}: cancel if current. */
    public static final String CANCEL = "cancel";
    /** {@code {}}: cancel task and everything Baritone does, release keys, close container. */
    public static final String STOP = "stop";
    /** {@code {"text"}}: chat line, or a command when it starts with {@code /}. */
    public static final String CHAT = "chat";
    /** {@code {"address"?}}: connect to a server. */
    public static final String CONNECT = "connect";
    /** {@code {}}: leave the server and disable auto-reconnect. */
    public static final String DISCONNECT = "disconnect";
    /** {@code {}}: stop the client gracefully. */
    public static final String QUIT = "quit";
    /** {@link Query}: answered with {@link #RESULT}. */
    public static final String QUERY = "query";
    /** {@code {"payload":{...}}}: forward to / received from the companion plugin. */
    public static final String PLUGIN = "plugin";

    // Bot → manager
    /** {@link BotStatus}. */
    public static final String STATUS = "status";
    /** {@link TaskResult}, exactly once per started task. */
    public static final String TASK_DONE = "task_done";
    /** {@link BotEvent}. */
    public static final String EVENT = "event";
    /** {@link ContainerSnapshot}. */
    public static final String CONTAINER = "container";
    /** {@link QueryResult} with {@code re} = query id. */
    public static final String RESULT = "result";
    /** {@link LogLine}. */
    public static final String LOG = "log";

    // Companion channel (SPEC §7)
    /** Bot → plugin {@code {"target","minutes"}}. */
    public static final String ROLLBACK = "rollback";
    /** Plugin → bot {@code {"ok","restored","via","message"}}. */
    public static final String ROLLBACK_RESULT = "rollback_result";
    /** Bot → plugin {@code {"target","minutes"}}. */
    public static final String JOURNAL = "journal";
    /** Plugin → bot {@code {"breaks","places","since"}}. */
    public static final String JOURNAL_RESULT = "journal_result";
    /** Plugin → bot {@code {"message"}}. */
    public static final String NOTICE = "notice";

    private MessageTypes() {
    }
}
