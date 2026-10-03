package io.github.krekerdm.baritonebots.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.BotEvent;
import io.github.krekerdm.baritonebots.common.msg.BotStatus;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Hello;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.LogLine;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Reject;
import io.github.krekerdm.baritonebots.common.msg.TaskResult;
import io.github.krekerdm.baritonebots.common.msg.Welcome;
import io.github.krekerdm.baritonebots.manager.bots.BotRegistry;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.BotConfigFactory;
import io.github.krekerdm.baritonebots.manager.config.ConfigStore;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.Secrets;
import io.github.krekerdm.baritonebots.manager.events.EventLog;
import io.github.krekerdm.baritonebots.manager.events.I18n;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.events.SseHub;
import io.github.krekerdm.baritonebots.manager.http.HttpApi;
import io.github.krekerdm.baritonebots.manager.link.LinkServer;
import io.github.krekerdm.baritonebots.manager.link.LinkSession;
import io.github.krekerdm.baritonebots.manager.process.ProcessSupervisor;
import io.github.krekerdm.baritonebots.manager.runtime.RuntimeInstaller;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.JsonItemStore;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import io.github.krekerdm.baritonebots.manager.tasks.Validators;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Os;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import io.github.krekerdm.baritonebots.manager.world.WorldStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Application context: owns every component and wires them together. Implements the link callbacks (hello,
 * status, task_done, event, container, log) and turns config changes into bot updates. Everything here runs on
 * the {@link ManagerLoop} unless a method says otherwise.
 */
public final class Manager implements LinkServer.Handler {
    public final String version;
    public final Path dataDir;
    public final boolean noTray;
    public final boolean noBrowser;
    public final ManagerLoop loop = new ManagerLoop();
    public final Secrets secrets;
    public final ConfigStore config;
    public final I18n i18n = new I18n();
    public final SseHub sse = new SseHub();
    public final EventLog events;
    public final TaskCatalog catalog;
    public final BotRegistry bots = new BotRegistry();
    public final WorldStore worlds;
    public final JsonItemStore kits;
    public final JsonItemStore scenarios;
    public final Dispatcher dispatcher;
    public final RuntimeInstaller installer;
    public final ProcessSupervisor supervisor;
    public final LinkServer link;
    public final HttpApi http;
    private Tray tray;
    private volatile boolean shutDown;

    /**
     * Loads secrets.json and config.json (creating them with defaults).
     *
     * @throws io.github.krekerdm.baritonebots.manager.config.ValidationException when config.json breaks the schema
     */
    public Manager(Path dataDir, boolean noTray, boolean noBrowser) throws IOException {
        this.version = versionString();
        this.dataDir = dataDir;
        this.noTray = noTray;
        this.noBrowser = noBrowser;
        Files.createDirectories(dataDir);
        secrets = new Secrets(dataDir.resolve("secrets.json"));
        secrets.load();
        config = new ConfigStore(dataDir.resolve("config.json"), secrets);
        config.load();
        events = new EventLog(dataDir, config.get().general().eventLogLimit(), i18n,
                () -> config.get().general().language());
        catalog = TaskCatalog.load();
        worlds = new WorldStore(dataDir.resolve("world"), loop);
        kits = new JsonItemStore(dataDir.resolve("kits.json"), "kits", "kit", Validators::kit);
        scenarios = new JsonItemStore(dataDir.resolve("scenarios.json"), "scenarios", "sc",
                body -> Validators.scenario(catalog, body));
        dispatcher = new Dispatcher(this, dataDir.resolve("queues.json"));
        installer = new RuntimeInstaller(this, dataDir.resolve("runtime"));
        supervisor = new ProcessSupervisor(this, dataDir.resolve("bots"));
        link = new LinkServer(loop, this);
        http = new HttpApi(this);
    }

    static String versionString() {
        String v = Manager.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    /** Loads the stores and builds the bot registry (on the loop). Part of {@link #start()}; tests call it alone. */
    public void initState() {
        loop.awaitRun(() -> {
            events.open();
            events.addListener(e -> sse.broadcast(SseHub.EVENT, e));
            kits.load();
            scenarios.load();
            for (ManagerConfig.BotDef def : config.get().bots()) {
                secrets.ensureBotPassword(def.id());
            }
            bots.sync(config.get());
            dispatcher.loadQueues();
            installer.init();
            config.addListener(this::onConfigChanged);
        });
    }

    /** Starts listeners, the tray and (optionally) the bots. Called once from {@link ManagerMain}. */
    public void start() throws IOException {
        initState();
        ManagerConfig cfg = config.get();
        link.start(cfg.general().link().bind(), cfg.general().link().port());
        http.start(cfg.general().panel().bind(), cfg.general().panel().port());
        String url = panelUrl();
        if (!noTray && cfg.general().tray()) {
            tray = Tray.install(this, url);
            if (tray != null) {
                loop.awaitRun(() -> events.addListener(tray::onEvent));
            }
        }
        loop.post(() -> {
            event("manager_started", Levels.INFO, null, "event.manager.started", Map.of("version", version));
            if (config.get().general().autoStartBots()) {
                supervisor.autoStart();
            }
        });
        System.out.println();
        System.out.println("BaritoneBots manager " + version + " — panel: " + url);
        System.out.println("Data directory: " + dataDir.toAbsolutePath());
        System.out.println();
        if (!noBrowser && cfg.general().panel().openBrowser()) {
            if (!Os.openBrowser(url)) {
                Log.warn("could not open a browser; open the URL above by hand");
            }
        }
    }

    /** {@code http://host:port/#token=...}; a wildcard bind is shown as 127.0.0.1. */
    public String panelUrl() {
        ManagerConfig.Panel p = config.get().general().panel();
        String host = p.bind() == null || p.bind().isBlank() || "0.0.0.0".equals(p.bind()) || "::".equals(p.bind())
                ? "127.0.0.1" : p.bind();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + p.port() + "/#token=" + secrets.panelToken();
    }

    /** Shutdown hook: quit bots (kill after 15 s), save state. Safe to call more than once; not on the loop. */
    public void shutdown() {
        if (shutDown) {
            return;
        }
        shutDown = true;
        Log.info("shutting down");
        try {
            http.stop();
        } catch (RuntimeException e) {
            Log.error("http stop failed", e);
        }
        supervisor.shutdownAll();
        link.stop();
        try {
            loop.awaitRun(() -> {
                dispatcher.saveQueues();
                worlds.flush();
                events.close();
            });
        } catch (RuntimeException e) {
            Log.error("saving state on shutdown failed", e);
        }
        sse.closeAll();
        if (tray != null) {
            tray.remove();
        }
        loop.shutdown(2_000);
        Log.close();
    }

    // ------------------------------------------------------------------ helpers used by the components

    public ManagerEvent event(String kind, String level, String botId, String key, Map<String, ?> args) {
        return events.manager(kind, level, botId, key, args, null);
    }

    public ManagerEvent event(String kind, String level, String botId, String key, Map<String, ?> args, JsonObject data) {
        return events.manager(kind, level, botId, key, args, data);
    }

    /** The BotConfig for a bot right now (global defaults → server → bot, plus secrets and world zones). */
    public BotConfig buildConfig(BotState b) {
        ManagerConfig cfg = config.get();
        ManagerConfig.ServerProfile server = b.def.serverId() == null ? null : cfg.server(b.def.serverId()).orElse(null);
        secrets.ensureBotPassword(b.id);
        List<JsonObject> zones = server == null ? List.of() : worlds.zonesFor(server.id());
        return BotConfigFactory.build(cfg, b.def, server, secrets, zones);
    }

    /** What the bot runs with: the config it was sent, else what it would be sent. */
    public BotConfig effectiveConfig(BotState b) {
        return b.sentConfig != null ? b.sentConfig : buildConfig(b);
    }

    /** Sends {@code config} when the effective config of a linked bot changed. */
    public void pushConfig(BotState b) {
        if (!b.linked()) {
            return;
        }
        BotConfig c = buildConfig(b);
        if (b.sentConfig == null || !Json.toTree(c).equals(Json.toTree(b.sentConfig))) {
            b.sentConfig = c;
            b.session.send(MessageTypes.CONFIG, c);
        }
    }

    /** Re-sends configs to the bots of a server (protected zones live in the world document). */
    public void pushConfigForServer(String serverId) {
        for (BotState b : bots.all()) {
            if (serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))) {
                pushConfig(b);
            }
        }
    }

    public JsonObject botView(BotState b) {
        JsonObject o = Json.toObject(b.def);
        o.addProperty("password", secrets.botPassword(b.id));
        o.addProperty("memoryMbEffective", config.get().memoryMbFor(b.def));
        o.add("process", b.processView());
        o.addProperty("linked", b.linked());
        if (b.hello != null) {
            o.add("client", Json.obj("modVersion", b.hello.modVersion(), "mcVersion", b.hello.mcVersion(),
                    "baritoneVersion", b.hello.baritoneVersion(), "pid", b.hello.pid(), "linkedAt", b.linkedAt));
        }
        o.add("status", b.status == null ? JsonNull.INSTANCE : Json.toTree(b.status));
        o.add("queue", dispatcher.queueView(b));
        if (b.sentConfig != null) {
            o.add("config", BotConfigFactory.redacted(b.sentConfig));
        }
        return o;
    }

    private boolean registered(BotState b) {
        return bots.get(b.id) == b;
    }

    public void broadcastBot(BotState b) {
        if (registered(b)) {
            sse.broadcast(SseHub.BOT, Json.obj("botId", b.id, "bot", botView(b)));
        }
    }

    public void broadcastProcess(BotState b) {
        if (registered(b)) {
            JsonObject o = b.processView();
            o.addProperty("botId", b.id);
            o.addProperty("linked", b.linked());
            sse.broadcast(SseHub.PROCESS, o);
        }
    }

    public void broadcastQueue(BotState b) {
        if (registered(b)) {
            sse.broadcast(SseHub.QUEUE, Json.obj("botId", b.id, "queue", dispatcher.queueView(b)));
        }
    }

    public void broadcastWorld(String serverId) {
        sse.broadcast(SseHub.WORLD, Json.obj("serverId", serverId));
    }

    /** GET /api/state. */
    public JsonObject stateSnapshot() {
        JsonArray botsArr = new JsonArray();
        bots.all().forEach(b -> botsArr.add(botView(b)));
        JsonArray servers = Json.getArr(config.viewForPanel(), "servers");
        return Json.obj("version", version, "bots", botsArr, "projects", new JsonArray(),
                "servers", servers == null ? new JsonArray() : servers, "runtime", installer.view(),
                "eventsTail", Json.arrOf(events.query(200, null, null)),
                "language", config.get().general().language(), "time", System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ config changes

    private void onConfigChanged(ManagerConfig old, ManagerConfig nu) {
        for (ManagerConfig.BotDef def : nu.bots()) {
            secrets.ensureBotPassword(def.id());
        }
        List<BotState> removed = bots.sync(nu);
        for (BotState b : removed) {
            if (b.processAlive() || b.linked()) {
                supervisor.stop(b);
            }
            dispatcher.forget(b);
            sse.broadcast(SseHub.BOT, Json.obj("botId", b.id, "deleted", true));
        }
        events.setLimit(nu.general().eventLogLimit());
        installer.refresh();
        for (BotState b : bots.all()) {
            pushConfig(b);
            broadcastBot(b);
        }
        if (old == null || old.runtime().maxHeavyTasks() != nu.runtime().maxHeavyTasks()) {
            dispatcher.dispatchAll();
        }
    }

    // ------------------------------------------------------------------ link callbacks (loop)

    @Override
    public void onHello(LinkSession s, Hello h) {
        String reject = null;
        BotState b = null;
        if (h.protocol() != Protocol.PROTOCOL_VERSION) {
            reject = Reject.PROTOCOL;
        } else if (!Tokens.constantTimeEquals(secrets.linkSecret(), h.secret())) {
            reject = Reject.BAD_SECRET;
        } else {
            b = bots.get(h.botId());
            if (b == null) {
                reject = Reject.UNKNOWN_BOT;
            }
        }
        if (reject != null) {
            s.send(MessageTypes.REJECT, new Reject(reject));
            loop.schedule(s::close, 500, TimeUnit.MILLISECONDS);
            event("link_rejected", Levels.WARN, h.botId(), "event.link.rejected",
                    Map.of("bot", String.valueOf(h.botId()), "reason", reject, "remote", s.remote()));
            return;
        }
        if (b.session != null && b.session != s) {
            LinkSession old = b.session;
            b.session = null;
            old.close();
        }
        b.session = s;
        b.hello = h;
        b.linkedAt = System.currentTimeMillis();
        b.status = null;
        b.dead = false;
        BotConfig cfg = buildConfig(b);
        b.sentConfig = cfg;
        s.accept();
        s.send(MessageTypes.WELCOME, new Welcome(cfg));
        if (b.process == null && BotState.STOPPED.equals(b.phase)) {
            Log.info("%s linked without a manager-started process (pid %d)", b.id, h.pid());
        }
        event("linked", Levels.INFO, b.id, "event.bot.linked", Map.of("bot", b.id, "version", String.valueOf(h.modVersion())));
        broadcastProcess(b);
        broadcastBot(b);
    }

    @Override
    public void onMessage(LinkSession s, Envelope e) {
        BotState b = s.botId() == null ? null : bots.get(s.botId());
        if (b == null || b.session != s || !s.accepted()) {
            return;
        }
        try {
            switch (e.t()) {
                case MessageTypes.STATUS -> onStatus(b, e.payload(BotStatus.class));
                case MessageTypes.TASK_DONE -> dispatcher.onTaskDone(b, e.payload(TaskResult.class));
                case MessageTypes.EVENT -> onBotEvent(b, e.payload(BotEvent.class));
                case MessageTypes.CONTAINER -> onContainer(b, e.payload(ContainerSnapshot.class));
                case MessageTypes.LOG -> onLog(b, e.payload(LogLine.class));
                case MessageTypes.PLUGIN -> {
                    // companion plugin bridge is phase 2
                }
                default -> Log.warn("%s sent unknown message type '%s'", b.id, e.t());
            }
        } catch (RuntimeException ex) {
            Log.error("handling '" + e.t() + "' from " + b.id + " failed", ex);
        }
    }

    private void onStatus(BotState b, BotStatus st) {
        boolean wasOnline = b.online();
        boolean wasDead = b.status != null && BotStatus.DEAD.equals(b.status.state());
        b.status = st;
        if (BotStatus.DEAD.equals(st.state())) {
            b.dead = true;
        } else if (wasDead && BotStatus.ONLINE.equals(st.state()) && b.dead) {
            dispatcher.onRespawned(b); // the respawned event may come after the status
        }
        boolean online = b.online();
        sse.broadcast(SseHub.BOT, Json.obj("botId", b.id, "status", st));
        if (online != wasOnline) {
            broadcastProcess(b);
            if (online) {
                dispatcher.onOnline(b);
            } else {
                dispatcher.dispatch(b);
            }
        }
    }

    private void onBotEvent(BotState b, BotEvent ev) {
        String level = ev.level() == null ? Levels.INFO : ev.level();
        events.add(ev.kind() == null ? "bot" : ev.kind(), level, ManagerEvent.SOURCE_BOT, b.id,
                ev.message() == null ? "" : ev.message(), null, null, ev.data());
        if (EventKinds.DEATH.equals(ev.kind())) {
            dispatcher.onDeath(b, ev);
        } else if (EventKinds.RESPAWNED.equals(ev.kind())) {
            dispatcher.onRespawned(b);
        }
    }

    private void onContainer(BotState b, ContainerSnapshot snap) {
        if (b.def.serverId() == null || snap.pos() == null) {
            return;
        }
        worlds.onSnapshot(b.def.serverId(), snap);
        broadcastWorld(b.def.serverId());
    }

    private void onLog(BotState b, LogLine line) {
        String level = Objects.requireNonNullElse(line.level(), Levels.INFO);
        String source = Objects.requireNonNullElse(line.source(), LogLine.SOURCE_MOD);
        String msg = Objects.requireNonNullElse(line.message(), "");
        b.log.add("[" + source + "/" + level + "] " + msg);
        sse.broadcast(SseHub.LOG, Json.obj("botId", b.id, "level", level, "source", source, "message", msg,
                "time", System.currentTimeMillis()));
    }

    @Override
    public void onClosed(LinkSession s) {
        BotState b = s.botId() == null ? null : bots.get(s.botId());
        if (b == null || b.session != s) {
            return;
        }
        b.session = null;
        b.status = null;
        dispatcher.onLinkLost(b);
        if (b.process == null) {
            supervisor.onExternalLinkClosed(b);
        } else if (b.processAlive() && !BotState.STOPPING.equals(b.phase)) {
            event("link_lost", Levels.WARN, b.id, "event.bot.linkLost", Map.of("bot", b.id));
        }
        broadcastProcess(b);
        broadcastBot(b);
    }
}
