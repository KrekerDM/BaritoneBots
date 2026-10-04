package io.github.krekerdm.baritonebots.manager.process;

import com.google.gson.JsonElement;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.runtime.HmcFiles;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Os;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Bot client processes (SPEC §5.4): per-bot HeadlessMC config, staggered launch, launcher log, process priority,
 * restart policy with a crash-loop limit, stop = {@code quit} → 15 s → kill tree. Decisions on the manager loop;
 * file preparation, process start and kills on virtual threads.
 */
public final class ProcessSupervisor {
    private static final long QUIT_GRACE_MS = 15_000;
    private static final long CRASH_WINDOW_MS = 10 * 60_000;
    private static final long LOG_ROTATE_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Manager m;
    private final Path botsDir;
    private long lastLaunchAt;

    public ProcessSupervisor(Manager m, Path botsDir) {
        this.m = m;
        this.botsDir = botsDir;
    }

    public Path botDir(String botId) {
        return botsDir.resolve(botId);
    }

    /** Everything a launch needs, computed on the loop and applied on the launch thread. */
    private record LaunchPlan(Path botDir, Path gameDir, Path logFile, List<String> command, Map<String, String> props,
                              List<Path> mods, String optionsSeed, String baritoneSettings) {
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts a bot (installing the runtime first when needed; starts are staggered by {@code startStaggerSec}).
     *
     * @throws ApiException {@code login_required} for Microsoft accounts without a stored login, {@code disabled} for
     *                      disabled bots
     */
    public void start(BotState b) {
        if (b.def.microsoft() && !m.msLogin.hasAccount(b.id)) {
            throw ApiException.conflict("login_required",
                    "log in with the Microsoft account first (POST /api/bots/" + b.id + "/microsoft-login)");
        }
        if (!b.def.enabled()) {
            throw ApiException.conflict("disabled", "the bot is disabled");
        }
        if (b.processAlive() || b.linked() || BotState.STARTING.equals(b.phase) || BotState.INSTALLING.equals(b.phase)
                || BotState.RUNNING.equals(b.phase)) {
            return;
        }
        cancelTimers(b);
        b.stopRequested = false;
        b.restartAfterStop = false;
        b.lastError = null;
        if (!m.installer.isReady()) {
            b.phase = BotState.INSTALLING;
            m.broadcastProcess(b);
            m.installer.whenReady(() -> {
                if (BotState.INSTALLING.equals(b.phase) && !b.stopRequested && m.bots.get(b.id) == b) {
                    schedule(b);
                }
            }, err -> {
                if (BotState.INSTALLING.equals(b.phase)) {
                    b.phase = BotState.STOPPED;
                    b.lastError = err;
                    m.broadcastProcess(b);
                }
            });
            return;
        }
        schedule(b);
    }

    private void schedule(BotState b) {
        long now = System.currentTimeMillis();
        long stagger = Math.max(0, m.config.get().runtime().startStaggerSec()) * 1000L;
        long at = Math.max(now, lastLaunchAt + stagger);
        lastLaunchAt = at;
        b.phase = BotState.STARTING;
        b.startAt = at;
        b.startTimer = m.loop.schedule(() -> launch(b), at - now, TimeUnit.MILLISECONDS);
        m.event("starting", Levels.INFO, b.id, "event.bot.starting",
                Map.of("bot", b.id, "sec", (at - now + 999) / 1000));
        m.broadcastProcess(b);
    }

    public void startAll() {
        for (BotState b : m.bots.all()) {
            if (b.def.enabled() && (!b.def.microsoft() || m.msLogin.hasAccount(b.id))) {
                try {
                    start(b);
                } catch (ApiException e) {
                    Log.warn("start %s: %s", b.id, e.getMessage());
                }
            }
        }
    }

    /** {@code general.autoStartBots}: bots with {@code autoStart}. */
    public void autoStart() {
        for (BotState b : m.bots.all()) {
            if (b.def.enabled() && b.def.autoStart() && (!b.def.microsoft() || m.msLogin.hasAccount(b.id))) {
                start(b);
            }
        }
    }

    private void launch(BotState b) {
        b.startTimer = null;
        b.startAt = 0;
        if (!BotState.STARTING.equals(b.phase) || b.stopRequested || m.bots.get(b.id) != b) {
            return;
        }
        LaunchPlan plan;
        try {
            plan = plan(m.config.get(), b);
        } catch (RuntimeException e) {
            launchFailed(b, e.getMessage());
            return;
        }
        int seq = ++b.launchSeq;
        Thread.ofVirtual().name("launch-" + b.id).start(() -> {
            try {
                prepare(plan);
                Process p = new ProcessBuilder(plan.command()).directory(plan.botDir().toFile())
                        .redirectErrorStream(true).start();
                p.getOutputStream().close();
                m.loop.post(() -> launched(b, seq, p, plan));
            } catch (IOException | RuntimeException e) {
                Log.error("launch of " + b.id + " failed", e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                m.loop.post(() -> {
                    if (seq == b.launchSeq) {
                        launchFailed(b, msg);
                    }
                });
            }
        });
    }

    private void launchFailed(BotState b, String msg) {
        b.phase = BotState.STOPPED;
        b.lastError = msg;
        m.event("launch_failed", Levels.ERROR, b.id, "event.bot.launchFailed", Map.of("bot", b.id, "error", String.valueOf(msg)));
        m.broadcastProcess(b);
    }

    private void launched(BotState b, int seq, Process p, LaunchPlan plan) {
        if (seq != b.launchSeq || b.stopRequested || m.bots.get(b.id) != b) {
            killTree(p.toHandle(), null);
            return;
        }
        b.process = p;
        b.phase = BotState.RUNNING;
        b.startedAt = System.currentTimeMillis();
        b.lastExit = null;
        Thread.ofVirtual().name("launcher-out-" + b.id).start(() -> pumpLog(b, p, plan.logFile()));
        p.onExit().thenAccept(x -> m.loop.post(() -> exited(b, seq, x.exitValue())));
        applyPriority(m.config.get().runtime().priority(), p);
        m.event("launched", Levels.INFO, b.id, "event.bot.launched", Map.of("bot", b.id, "pid", p.pid()));
        m.broadcastProcess(b);
    }

    private void exited(BotState b, int seq, int code) {
        if (seq != b.launchSeq) {
            return;
        }
        b.process = null;
        b.lastExit = code;
        if (b.killTimer != null) {
            b.killTimer.cancel(false);
            b.killTimer = null;
        }
        boolean intentional = b.stopRequested || BotState.STOPPING.equals(b.phase);
        if (intentional) {
            b.phase = BotState.STOPPED;
            m.event("stopped", Levels.INFO, b.id, "event.bot.stopped", Map.of("bot", b.id));
            m.broadcastProcess(b);
            if (b.restartAfterStop && m.bots.get(b.id) == b) {
                b.restartAfterStop = false;
                safeStart(b);
            }
            return;
        }
        b.phase = BotState.CRASHED;
        b.lastError = "exit code " + code;
        m.event("crashed", Levels.ERROR, b.id, "event.bot.crashed", Map.of("bot", b.id, "code", code));
        m.broadcastProcess(b);
        maybeRestart(b);
    }

    private void maybeRestart(BotState b) {
        ManagerConfig.Restart r = m.config.get().runtime().restart();
        if (!r.enabled() || !b.def.enabled() || m.bots.get(b.id) != b) {
            return;
        }
        long now = System.currentTimeMillis();
        while (!b.crashTimes.isEmpty() && now - b.crashTimes.peekFirst() > CRASH_WINDOW_MS) {
            b.crashTimes.removeFirst();
        }
        if (b.crashTimes.size() >= r.maxPer10Min()) {
            m.event("crash_loop", Levels.ERROR, b.id, "event.bot.crashLoop", Map.of("bot", b.id, "max", r.maxPer10Min()));
            return;
        }
        b.crashTimes.addLast(now);
        m.event("restart_scheduled", Levels.INFO, b.id, "event.bot.restartScheduled",
                Map.of("bot", b.id, "sec", r.delaySec()));
        b.startTimer = m.loop.schedule(() -> {
            b.startTimer = null;
            if (BotState.CRASHED.equals(b.phase) && m.bots.get(b.id) == b) {
                b.phase = BotState.STOPPED;
                safeStart(b);
            }
        }, r.delaySec(), TimeUnit.SECONDS);
    }

    private void safeStart(BotState b) {
        try {
            start(b);
        } catch (ApiException e) {
            b.lastError = e.getMessage();
            m.broadcastProcess(b);
        }
    }

    // ------------------------------------------------------------------ stop / kill

    /** {@code quit}, then kill the process tree after 15 s. */
    public void stop(BotState b) {
        cancelTimers(b);
        b.restartAfterStop = false;
        b.stopRequested = true;
        if (b.process == null && !b.linked()) {
            b.launchSeq++; // a launch in flight sees the new sequence and kills its process
            b.phase = BotState.STOPPED;
            b.startAt = 0;
            m.broadcastProcess(b);
            return;
        }
        b.phase = BotState.STOPPING;
        if (b.linked()) {
            b.session.send(MessageTypes.QUIT, Json.obj());
            b.killTimer = m.loop.schedule(() -> {
                b.killTimer = null;
                if (b.processAlive() || b.linked()) {
                    Log.warn("%s did not quit within %d s; killing", b.id, QUIT_GRACE_MS / 1000);
                    killTree(b);
                }
            }, QUIT_GRACE_MS, TimeUnit.MILLISECONDS);
        } else {
            killTree(b);
        }
        m.broadcastProcess(b);
    }

    public void kill(BotState b) {
        cancelTimers(b);
        b.restartAfterStop = false;
        b.stopRequested = true;
        if (b.process == null && !b.linked()) {
            b.launchSeq++;
            b.phase = BotState.STOPPED;
            m.broadcastProcess(b);
            return;
        }
        b.phase = BotState.STOPPING;
        killTree(b);
        m.broadcastProcess(b);
    }

    public void restart(BotState b) {
        if (b.processAlive() || b.linked()) {
            stop(b);
            b.restartAfterStop = true;
        } else {
            b.phase = BotState.STOPPED;
            start(b);
        }
    }

    public void stopAll() {
        for (BotState b : m.bots.all()) {
            if (b.processAlive() || b.linked() || !BotState.STOPPED.equals(b.phase)) {
                stop(b);
            }
        }
    }

    /** A client without a manager-owned process (started by hand or before a manager restart) closed its link. */
    public void onExternalLinkClosed(BotState b) {
        if (b.process == null && BotState.STOPPING.equals(b.phase)) {
            b.phase = BotState.STOPPED;
            if (b.killTimer != null) {
                b.killTimer.cancel(false);
                b.killTimer = null;
            }
            if (b.restartAfterStop) {
                b.restartAfterStop = false;
                safeStart(b);
            }
        }
    }

    private void cancelTimers(BotState b) {
        if (b.startTimer != null) {
            b.startTimer.cancel(false);
            b.startTimer = null;
        }
        if (b.killTimer != null) {
            b.killTimer.cancel(false);
            b.killTimer = null;
        }
        b.startAt = 0;
    }

    private void killTree(BotState b) {
        Long gamePid = b.hello != null && b.hello.pid() > 0 ? b.hello.pid() : null;
        killTree(b.process == null ? null : b.process.toHandle(), gamePid);
    }

    private static void killTree(ProcessHandle root, Long extraPid) {
        Thread.ofVirtual().name("kill-tree").start(() -> {
            if (root != null) {
                root.descendants().forEach(ProcessHandle::destroyForcibly);
                root.destroyForcibly();
            }
            if (extraPid != null) {
                ProcessHandle.of(extraPid).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                });
            }
        });
    }

    /**
     * Manager shutdown (shutdown hook thread, not the loop): {@code quit} to every linked bot, kill unlinked
     * processes, wait up to 15 s, then kill whatever is left.
     */
    public void shutdownAll() {
        List<Process> procs = new ArrayList<>();
        try {
            procs = m.loop.await(() -> {
                List<Process> l = new ArrayList<>();
                for (BotState b : m.bots.all()) {
                    cancelTimers(b);
                    b.stopRequested = true;
                    b.restartAfterStop = false;
                    if (b.linked()) {
                        b.phase = BotState.STOPPING;
                        b.session.send(MessageTypes.QUIT, Json.obj());
                    } else if (b.process != null) {
                        killTree(b.process.toHandle(), null);
                    }
                    if (b.process != null) {
                        l.add(b.process);
                    }
                }
                return l;
            });
        } catch (RuntimeException e) {
            Log.warn("shutdown: loop did not answer (%s); killing bot processes", e.getMessage());
        }
        long deadline = System.currentTimeMillis() + QUIT_GRACE_MS;
        while (System.currentTimeMillis() < deadline && procs.stream().anyMatch(Process::isAlive)) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Process p : procs) {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------ launch preparation

    private LaunchPlan plan(ManagerConfig cfg, BotState b) {
        ManagerConfig.BotDef def = b.def;
        ManagerConfig.ServerProfile server = cfg.server(def.serverId())
                .orElseThrow(() -> new IllegalStateException("server profile '" + def.serverId() + "' not found"));
        Path botDir = botDir(def.id());
        Path gameDir = botDir.resolve("game");
        Path java = m.installer.javaFor(cfg);
        String bind = cfg.general().link().bind();
        String host = bind == null || bind.isBlank() || "0.0.0.0".equals(bind) || "::".equals(bind) ? "127.0.0.1" : bind;
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int mem = cfg.memoryMbFor(def);
        List<String> jvm = new ArrayList<>(List.of(
                "-Xms" + Math.min(256, mem) + "m", "-Xmx" + mem + "m",
                "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100",
                "-XX:ParallelGCThreads=" + cfg.runtime().gcThreads(), "-XX:ConcGCThreads=1",
                "-XX:G1PeriodicGCInterval=60000", "-XX:+UseCompactObjectHeaders", "-XX:+UseStringDeduplication",
                "-Djava.awt.headless=true",
                "-D" + Protocol.PROP_LINK + "=" + host + ":" + cfg.general().link().port(),
                "-D" + Protocol.PROP_SECRET + "=" + m.secrets.linkSecret(),
                "-D" + Protocol.PROP_BOT_ID + "=" + def.id(),
                "-D" + Protocol.PROP_HEADLESS + "=true"));
        jvm.addAll(Os.childJvmFlags());
        jvm.addAll(splitArgs(cfg.runtime().jvmArgs()));
        jvm.addAll(splitArgs(def.jvmArgs()));
        Map<String, String> props = new LinkedHashMap<>();
        props.put("hmc.mcdir", HmcFiles.slashes(m.installer.mcDir()));
        props.put("hmc.gamedir", HmcFiles.slashes(gameDir));
        if (def.microsoft()) {
            // the account HeadlessMC stored at login (bots/<id>/HeadlessMC/auth/.accounts.json); refreshed on launch
            props.put("hmc.offline", "false");
        } else {
            props.put("hmc.offline", "true");
            props.put("hmc.offline.username", def.username());
        }
        props.put("hmc.always.lwjgl.flag", "true");
        props.put("hmc.assets.dummy", "true");
        props.put("hmc.jvmargs", String.join(" ", jvm));
        if (server.autoConnect()) {
            props.put("hmc.gameargs", "--quickPlayMultiplayer " + server.address().trim());
        }
        props.put("hmc.java.versions", HmcFiles.slashes(java));
        String fabricUrl = cfg.runtime().fabricInstallerUrl();
        if (fabricUrl != null && !fabricUrl.isBlank()) {
            props.put("hmc.fabric.url", fabricUrl);
        }
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx64m", "-XX:+UseSerialGC"));
        command.addAll(Os.childJvmFlags());
        // No -noout: HeadlessMC 2.10.0 then stops reading the game's stdout, the pipe fills up and the game
        // blocks inside log4j during startup. The game output is forwarded and drained into launcher.log instead.
        command.addAll(List.of("-jar", m.installer.hmcJar(cfg).toAbsolutePath().toString(), "--command", "launch",
                "fabric:" + cfg.runtime().minecraftVersion(), "-lwjgl"));
        if (!def.microsoft()) {
            command.add("-offline");
        }
        List<Path> mods = new ArrayList<>();
        for (String f : m.installer.modFiles()) {
            mods.add(m.installer.modsDir().resolve(f));
        }
        BotConfig bc = m.effectiveConfig(b);
        return new LaunchPlan(botDir, gameDir, botDir.resolve("logs").resolve("launcher.log"), command, props, mods,
                optionsSeed(bc.client()), baritoneSettings(bc.baritone()));
    }

    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String part : s.trim().split("\\s+")) {
                if (!part.isBlank()) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    /** options.txt for a fresh game dir; {@code version:4903} (26.2) or the game resets the file. */
    static String optionsSeed(BotConfig.ClientOpts c) {
        return String.join("\n",
                "version:4903",
                "renderDistance:" + Math.max(2, c.renderDistance()),
                "simulationDistance:5",
                "maxFps:" + Math.max(1, c.maxFps()),
                "inactivityFpsLimit:\"minimized\"",
                "enableVsync:false",
                "graphicsPreset:\"fast\"",
                "preferredGraphicsBackend:\"opengl\"",
                "particles:2",
                "entityDistanceScaling:0.5",
                "entityShadows:false",
                "renderClouds:\"false\"",
                "biomeBlendRadius:0",
                "mipmapLevels:0",
                "ao:false",
                "chunkSectionFadeInTime:0.0",
                "soundCategory_master:0.0",
                "soundCategory_music:0.0",
                "pauseOnLostFocus:false",
                "onboardAccessibility:false",
                "skipMultiplayerWarning:true",
                "joinedFirstServer:true",
                "tutorialStep:none",
                "narrator:0",
                "autoJump:false",
                "sharePresence:\"none\"",
                "inGameNotification:false") + "\n";
    }

    /** baritone/settings.txt in Baritone's {@code name value} syntax (the mod re-applies the config on link). */
    static String baritoneSettings(Map<String, JsonElement> settings) {
        StringBuilder sb = new StringBuilder("# Written by the BaritoneBots manager from config.json on every start.\n");
        settings.forEach((k, v) -> {
            String value = renderBaritone(v);
            if (value != null && k.matches("[A-Za-z0-9_]+")) {
                sb.append(k).append(' ').append(value).append('\n');
            }
        });
        return sb.toString();
    }

    private static String renderBaritone(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (v.isJsonPrimitive()) {
            if (v.getAsJsonPrimitive().isNumber()) {
                double d = v.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
            }
            String s = v.getAsString();
            return s.contains("\n") ? null : s;
        }
        if (v.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            v.getAsJsonArray().forEach(e -> parts.add(renderBaritone(e)));
            return parts.stream().filter(p -> p != null).collect(Collectors.joining(","));
        }
        return null;
    }

    /** Launch thread: directories, mods/, options.txt seed, baritone/settings.txt, HeadlessMC config. */
    private static void prepare(LaunchPlan plan) throws IOException {
        Path mods = plan.gameDir().resolve("mods");
        Files.createDirectories(mods);
        Files.createDirectories(plan.gameDir().resolve("baritone"));
        Files.createDirectories(plan.logFile().getParent());
        syncMods(mods, plan.mods());
        Path options = plan.gameDir().resolve("options.txt");
        if (!Files.exists(options)) {
            AtomicFiles.writeString(options, plan.optionsSeed());
        }
        AtomicFiles.writeString(plan.gameDir().resolve("baritone").resolve("settings.txt"), plan.baritoneSettings());
        HmcFiles.writeProperties(plan.botDir().resolve("HeadlessMC").resolve("config.properties"), plan.props());
        if (Files.exists(plan.logFile()) && Files.size(plan.logFile()) > LOG_ROTATE_BYTES) {
            Files.move(plan.logFile(), plan.logFile().resolveSibling("launcher.log.1"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Copies the runtime's mod jars; removes jars this manager put there earlier but no longer wants. */
    private static void syncMods(Path modsDir, List<Path> sources) throws IOException {
        Path managed = modsDir.resolve(".baritonebots-managed");
        Set<String> previous = new HashSet<>();
        if (Files.exists(managed)) {
            previous.addAll(Files.readAllLines(managed, StandardCharsets.UTF_8));
        }
        Set<String> now = new LinkedHashSet<>();
        for (Path src : sources) {
            String name = src.getFileName().toString();
            now.add(name);
            Path dst = modsDir.resolve(name);
            if (!Files.exists(dst) || Files.mismatch(src, dst) != -1L) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String old : previous) {
            if (!old.isBlank() && !now.contains(old) && !old.contains("/") && !old.contains("\\")) {
                Files.deleteIfExists(modsDir.resolve(old));
            }
        }
        AtomicFiles.writeString(managed, String.join("\n", now) + "\n");
    }

    private static void pumpLog(BotState b, Process p, Path logFile) {
        Writer w = null;
        try {
            w = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            w.write("---- launch " + java.time.LocalDateTime.now() + " pid " + p.pid() + "\n");
        } catch (IOException e) {
            Log.warn("cannot open %s: %s", logFile, e.getMessage());
        }
        Writer out = w;
        HmcFiles.pump(p.getInputStream(), line -> {
            b.log.add(LocalTime.now().format(TIME) + " " + line);
            if (out != null) {
                try {
                    out.write(line);
                    out.write('\n');
                    out.flush();
                } catch (IOException ignored) {
                    // disk full or file gone; the in-memory tail still works
                }
            }
        });
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // done
            }
        }
    }

    // ------------------------------------------------------------------ priority

    /** below_normal / idle on the launcher and every descendant (the game JVM) as they appear, for 2 minutes. */
    private static void applyPriority(String priority, Process p) {
        if (priority == null || "normal".equals(priority)) {
            return;
        }
        Thread.ofVirtual().name("priority-" + p.pid()).start(() -> {
            Set<Long> done = new HashSet<>();
            for (int i = 0; i < 40 && p.isAlive(); i++) {
                List<Long> fresh = new ArrayList<>();
                if (done.add(p.pid())) {
                    fresh.add(p.pid());
                }
                p.descendants().forEach(h -> {
                    if (done.add(h.pid())) {
                        fresh.add(h.pid());
                    }
                });
                if (!fresh.isEmpty()) {
                    setPriority(fresh, priority);
                }
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    static void setPriority(List<Long> pids, String priority) {
        boolean idle = "idle".equals(priority);
        List<String> cmd;
        if (Os.isWindows()) {
            String ids = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
            cmd = List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-Process -Id " + ids + " -ErrorAction SilentlyContinue | ForEach-Object { $_.PriorityClass = '"
                            + (idle ? "Idle" : "BelowNormal") + "' }");
        } else {
            cmd = new ArrayList<>(List.of("renice", "-n", idle ? "19" : "10", "-p"));
            pids.forEach(pid -> cmd.add(String.valueOf(pid)));
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (IOException e) {
            Log.warn("cannot set process priority: %s", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Last launcher log lines from memory, else from the file. */
    public List<String> logTail(BotState b, int lines) {
        if (!b.log.isEmpty()) {
            return b.log.tail(lines);
        }
        Path f = botDir(b.id).resolve("logs").resolve("launcher.log");
        try {
            if (Files.isRegularFile(f)) {
                List<String> all = Files.readAllLines(f, StandardCharsets.UTF_8);
                return new ArrayList<>(all.subList(Math.max(0, all.size() - lines), all.size()));
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            Log.warn("cannot read %s: %s", f, e.getMessage());
        }
        return List.of();
    }

    /** Disk use per bot directory (for /api/runtime). */
    public Optional<Long> botDirSize(String botId) {
        Path d = botDir(botId);
        return Files.isDirectory(d) ? Optional.of(Os.directorySize(d)) : Optional.empty();
    }
}
