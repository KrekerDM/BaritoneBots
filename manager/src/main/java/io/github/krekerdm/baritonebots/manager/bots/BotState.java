package io.github.krekerdm.baritonebots.manager.bots;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.BotStatus;
import io.github.krekerdm.baritonebots.common.msg.Hello;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.link.LinkSession;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.util.LineTail;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledFuture;

/**
 * Configuration plus live state of one bot (SPEC §5.4): process, link, last status, queue. Owned by the manager
 * loop; only {@link #log} is touched from other threads (it is thread-safe).
 */
public final class BotState {
    // Process phases. "linked" / "online" are derived from the link and the last status, see processState().
    public static final String STOPPED = "stopped";
    public static final String INSTALLING = "installing";
    public static final String STARTING = "starting";
    public static final String RUNNING = "running";
    public static final String LINKED = "linked";
    public static final String ONLINE = "online";
    public static final String STOPPING = "stopping";
    public static final String CRASHED = "crashed";

    public final String id;
    public ManagerConfig.BotDef def;

    // ---- process (ProcessSupervisor)
    public String phase = STOPPED;
    public Process process;
    /** Incremented on every launch; stale exit callbacks compare against it. */
    public int launchSeq;
    public long startedAt;
    /** When a staggered start will launch (0 = not waiting). */
    public long startAt;
    public Integer lastExit;
    public String lastError;
    public boolean stopRequested;
    public boolean restartAfterStop;
    public final Deque<Long> crashTimes = new ArrayDeque<>();
    public ScheduledFuture<?> startTimer;
    public ScheduledFuture<?> killTimer;
    public final LineTail log = new LineTail(2000);

    // ---- link
    public LinkSession session;
    public Hello hello;
    public long linkedAt;
    public BotStatus status;
    public BotConfig sentConfig;

    // ---- tasks (Dispatcher)
    public final TaskQueue queue = new TaskQueue();
    /** Why the head of the queue is not dispatched: null, {@code offline} or {@code heavy_limit}. */
    public String waiting;
    public ScheduledFuture<?> waitTimer;
    /** Between a death and the respawn: nothing is dispatched. */
    public boolean dead;

    // ---- companion plugin (SPEC §7): last payload per message type, as relayed by the bot
    public final java.util.Map<String, JsonObject> plugin = new java.util.LinkedHashMap<>();

    public BotState(ManagerConfig.BotDef def) {
        this.id = def.id();
        this.def = def;
    }

    public boolean linked() {
        return session != null && !session.closed() && session.accepted();
    }

    public boolean online() {
        return linked() && status != null && BotStatus.ONLINE.equals(status.state());
    }

    public boolean processAlive() {
        return process != null && process.isAlive();
    }

    /** Lifecycle state for the panel: stopped → installing → starting → linked → online; stopping; crashed. */
    public String processState() {
        if (STOPPING.equals(phase) || INSTALLING.equals(phase) || CRASHED.equals(phase)) {
            return phase;
        }
        if (online()) {
            return ONLINE;
        }
        if (linked()) {
            return LINKED;
        }
        if (STARTING.equals(phase) || RUNNING.equals(phase)) {
            return STARTING;
        }
        return STOPPED;
    }

    public JsonObject processView() {
        JsonObject o = Json.obj("state", processState(), "phase", phase);
        if (process != null) {
            o.addProperty("pid", process.pid());
        }
        if (hello != null && hello.pid() > 0) {
            o.addProperty("gamePid", hello.pid());
        }
        if (startedAt > 0) {
            o.addProperty("startedAt", startedAt);
        }
        if (startAt > 0) {
            o.addProperty("startAt", startAt);
        }
        if (lastExit != null) {
            o.addProperty("lastExit", lastExit);
        }
        if (lastError != null) {
            o.addProperty("error", lastError);
        }
        o.addProperty("crashes", crashTimes.size());
        return o;
    }
}
