package io.github.krekerdm.baritonebots.mod;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.BotEvent;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.Hello;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.mod.baritone.BaritoneSettingsApplier;
import io.github.krekerdm.baritonebots.mod.baritone.DefenseProcess;
import io.github.krekerdm.baritonebots.mod.baritone.LogForwarder;
import io.github.krekerdm.baritonebots.mod.baritone.PauseProcess;
import io.github.krekerdm.baritonebots.mod.behaviour.ConnectionBehaviour;
import io.github.krekerdm.baritonebots.mod.behaviour.ContainerSensor;
import io.github.krekerdm.baritonebots.mod.behaviour.DefenseBehaviour;
import io.github.krekerdm.baritonebots.mod.behaviour.EatBehaviour;
import io.github.krekerdm.baritonebots.mod.behaviour.Eater;
import io.github.krekerdm.baritonebots.mod.behaviour.LifeBehaviour;
import io.github.krekerdm.baritonebots.mod.behaviour.LoginBehaviour;
import io.github.krekerdm.baritonebots.mod.behaviour.StatusReporter;
import io.github.krekerdm.baritonebots.mod.link.LinkClient;
import io.github.krekerdm.baritonebots.mod.lowpower.LowPower;
import io.github.krekerdm.baritonebots.mod.query.QueryHandler;
import io.github.krekerdm.baritonebots.mod.task.TaskManager;
import io.github.krekerdm.baritonebots.mod.util.Interact;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The linked bot: owns the link, the current config, Baritone hooks, behaviours, the task manager and query
 * handler. Created once by {@link BaritoneBotsMod} when {@code -Dbaritonebots.link} is set.
 * <p>
 * Threading (SPEC §4.1): link threads only enqueue; everything here runs on the client thread from
 * {@code END_CLIENT_TICK} or Fabric's client events, except {@link #send}/{@link #event} which are thread-safe.
 */
public final class BotRuntime {
    private static BotRuntime instance;

    public final Minecraft mc;
    public final LaunchProps props;
    public final LinkClient link;
    public final LogForwarder log;
    public final PauseProcess pause = new PauseProcess();
    public final DefenseProcess defenseProcess = new DefenseProcess();
    public final Eater eater;
    public final ConnectionBehaviour connection;
    public final LoginBehaviour login;
    public final LifeBehaviour life;
    public final EatBehaviour eat;
    public final DefenseBehaviour defense;
    public final ContainerSensor containers;
    public final StatusReporter status;
    public final TaskManager tasks;
    public final QueryHandler queries;

    private final BaritoneSettingsApplier settingsApplier = new BaritoneSettingsApplier();
    private final RateLimiter warnLimiter = new RateLimiter(5, 1000);
    private final AtomicInteger calcFailures = new AtomicInteger();
    private final long startedAtMs = System.currentTimeMillis();
    private volatile BotConfig config;
    private IBaritone baritone;
    private long nextBaritoneAttempt;
    private long ticks;

    private BotRuntime(Minecraft mc, LaunchProps props) {
        this.mc = mc;
        this.props = props;
        // Fabric runs client entrypoints early in Minecraft's constructor: the session user is not set yet.
        String id = props.botId().isEmpty() ? "bot" : props.botId();
        this.config = BotConfig.defaults(id, id);
        this.link = new LinkClient(props.link(), this::hello);
        this.log = new LogForwarder(link);
        this.eater = new Eater(this);
        this.connection = new ConnectionBehaviour(this);
        this.login = new LoginBehaviour(this);
        this.life = new LifeBehaviour(this);
        this.eat = new EatBehaviour(this);
        this.defense = new DefenseBehaviour(this);
        this.containers = new ContainerSensor(this);
        this.status = new StatusReporter(this);
        this.tasks = new TaskManager(this);
        this.queries = new QueryHandler(this);
    }

    /** Creates the runtime, registers Fabric events and starts the link. */
    public static synchronized void start(LaunchProps props) {
        if (instance != null) {
            return;
        }
        BotRuntime rt = new BotRuntime(Minecraft.getInstance(), props);
        instance = rt;
        ClientTickEvents.END_CLIENT_TICK.register(rt::onEndTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> rt.login.onSystemMessage(message.getString(),
                overlay));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> rt.connection.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> rt.connection.onLeave());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> rt.link.close(1500));
        ModInfo.LOG.info("BaritoneBots {} will link bot '{}' to {} once the client ticks", ModInfo.modVersion(),
                props.botId(), props.link());
    }

    /** The runtime, or {@code null} when the mod is dormant. */
    public static BotRuntime get() {
        return instance;
    }

    private Hello hello() {
        long pid;
        try {
            pid = ProcessHandle.current().pid();
        } catch (UnsupportedOperationException e) {
            pid = -1;
        }
        return new Hello(Protocol.PROTOCOL_VERSION, props.botId(), props.secret(), username(),
                ModInfo.modVersion(), ModInfo.mcVersion(), ModInfo.baritoneVersion(), pid);
    }

    // ---------------------------------------------------------------- accessors

    public BotConfig config() {
        return config;
    }

    /** Primary Baritone, or {@code null} until Baritone finished initialising. */
    public IBaritone baritone() {
        return baritone;
    }

    public LocalPlayer player() {
        return mc.player;
    }

    public ClientLevel level() {
        return mc.level;
    }

    public boolean inGame() {
        return mc.player != null && mc.level != null && mc.gameMode != null;
    }

    /** Client ticks since the runtime started. */
    public long ticks() {
        return ticks;
    }

    public long uptimeSec() {
        return (System.currentTimeMillis() - startedAtMs) / 1000;
    }

    /** Number of Baritone {@code CALC_FAILED} path events so far (tasks compare before/after). */
    public int calcFailures() {
        return calcFailures.get();
    }

    // ---------------------------------------------------------------- outbound

    /** Sends a message that is dropped while the link is down. Thread-safe. */
    public void send(String type, Object payload) {
        link.send(Envelope.of(type, payload));
    }

    /** Sends a message that waits for the link to come back. Thread-safe. */
    public void sendReliable(String type, Object payload) {
        link.send(Envelope.of(type, payload), true);
    }

    /** Sends a {@code BotEvent}. Thread-safe. */
    public void event(String kind, String level, String message, JsonObject data) {
        sendReliable(MessageTypes.EVENT, BotEvent.of(kind, level, message, data));
    }

    public void sendContainer(ContainerSnapshot snapshot) {
        sendReliable(MessageTypes.CONTAINER, snapshot);
    }

    /** Rate-limited mod warning, also written to the log file. */
    public void warn(String message) {
        if (warnLimiter.tryAcquire()) {
            log.warn(message);
        } else {
            ModInfo.LOG.warn(message);
        }
    }

    // ---------------------------------------------------------------- tick

    /** Minecraft session name (the account the bot plays as). */
    public String username() {
        return mc.getUser() != null ? mc.getUser().getName() : config.username();
    }

    private void onEndTick(Minecraft client) {
        if (ticks++ == 0) {
            link.start(); // first tick: the session user exists now, so hello carries the real name
        }
        ensureBaritone();
        Envelope e;
        int budget = 200;
        while (budget-- > 0 && (e = link.poll()) != null) {
            try {
                dispatch(e);
            } catch (RuntimeException ex) {
                ModInfo.LOG.error("Failed to handle '{}' message", e.t(), ex);
                warn("failed to handle '" + e.t() + "': " + ex);
            }
        }
        step("connection", connection::tick);
        step("login", login::tick);
        step("life", life::tick);
        step("eat", eat::tick);
        step("defense", defense::tick);
        step("tasks", tasks::tick);
        step("containers", containers::tick);
        step("status", status::tick);
    }

    private void step(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            if (warnLimiter.once("tick:" + name, 10_000)) {
                ModInfo.LOG.error("BaritoneBots {} tick failed", name, ex);
                log.error(name + " tick failed: " + ex);
            }
        }
    }

    private void ensureBaritone() {
        if (baritone != null || ticks < nextBaritoneAttempt) {
            return;
        }
        nextBaritoneAttempt = ticks + 100;
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b == null) {
                return;
            }
            b.getPathingControlManager().registerProcess(pause);
            b.getPathingControlManager().registerProcess(defenseProcess);
            b.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED) {
                        calcFailures.incrementAndGet();
                    }
                }
            });
            BaritoneSettingsApplier.redirectLogger(c -> log.baritone(c.getString()));
            baritone = b;
            applyBaritoneSettings();
            ModInfo.LOG.info("Baritone hooks registered");
        } catch (RuntimeException | LinkageError ex) {
            if (warnLimiter.once("baritone-init", 60_000)) {
                ModInfo.LOG.error("Baritone not ready", ex);
            }
        }
    }

    // ---------------------------------------------------------------- inbound

    private void dispatch(Envelope e) {
        switch (e.t()) {
            case MessageTypes.WELCOME -> {
                JsonElement cfg = e.d().get("config");
                applyConfig(BotConfig.parse(cfg));
                status.markDirty();
            }
            case MessageTypes.CONFIG -> applyConfig(BotConfig.parse(e.d()));
            case MessageTypes.TASK -> tasks.start(e.payload(TaskSpec.class));
            case MessageTypes.CANCEL -> tasks.cancel(Json.getString(e.d(), "taskId", null));
            case MessageTypes.STOP -> stopEverything();
            case MessageTypes.CHAT -> connection.chat(Json.getString(e.d(), "text", ""));
            case MessageTypes.CONNECT -> connection.connect(Json.getString(e.d(), "address", null));
            case MessageTypes.DISCONNECT -> connection.disconnect();
            case MessageTypes.QUIT -> quit();
            case MessageTypes.QUERY -> queries.handle(e);
            case MessageTypes.PLUGIN -> {
                if (warnLimiter.once("plugin-bridge", 60_000)) {
                    log.warn("companion plugin bridge is not implemented in this version; 'plugin' message dropped");
                }
            }
            default -> {
                if (warnLimiter.once("unknown:" + e.t(), 60_000)) {
                    log.warn("ignoring unknown link message type '" + e.t() + "'");
                }
            }
        }
    }

    private void applyConfig(BotConfig cfg) {
        this.config = cfg;
        applyBaritoneSettings();
        LowPower.apply(mc, cfg.client());
        login.onConfig(cfg);
        connection.onConfig(cfg);
        status.markDirty();
    }

    private void applyBaritoneSettings() {
        BotConfig cfg = config;
        List<Block> protectedBlocks = cfg.protection().enabled()
                ? McIds.blocksMatching(cfg.protection().noBreak()) : List.of();
        for (String problem : settingsApplier.apply(cfg.baritone(), protectedBlocks)) {
            warn(problem);
        }
    }

    /** {@code stop}: cancel the task, everything Baritone does, keys, containers and behaviour claims. */
    public void stopEverything() {
        tasks.cancelCurrent("stopped by manager");
        eater.stop();
        defense.reset();
        pause.releaseAll();
        defenseProcess.clear();
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        Interact.releaseKeys(mc);
        Interact.closeContainer(mc.player);
        status.markDirty();
    }

    private void quit() {
        log.info("quit requested by manager");
        tasks.cancelCurrent("client quitting");
        connection.suppressReconnect();
        mc.stop();
    }
}
