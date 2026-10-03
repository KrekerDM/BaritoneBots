package io.github.krekerdm.baritonebots.manager.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.events.SseHub;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Hashing;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Os;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Prepares the shared bot runtime (SPEC §5.2): HeadlessMC launcher jar, mod jars (Modrinth / direct URLs, sha512
 * checked), our bot mod from the manager jar, and the one-time {@code fabric <mc> --uid <loader>} + {@code launch
 * fabric:<mc> -lwjgl -offline -prepare} into the shared {@code hmc.mcdir}. The install runs on its own thread and
 * never twice at the same time; state lives on the manager loop and is broadcast as SSE {@code runtime}.
 */
public final class RuntimeInstaller {
    public static final String MISSING = "missing";
    public static final String OUTDATED = "outdated";
    public static final String INSTALLING = "installing";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
    public static final String BOTMOD_RESOURCE = "botmod/baritonebots-botmod.jar";
    public static final String BOTMOD_FILE = "baritonebots-botmod.jar";
    private static final long FABRIC_TIMEOUT_MIN = 15;
    private static final long PREPARE_TIMEOUT_MIN = 40;

    private final Manager m;
    private final Path root;
    private final Downloader downloader;
    private final String botmodSha;

    // loop-owned
    private String state = MISSING;
    private String step;
    private double progress;
    private String message;
    private String error;
    private JsonObject manifest;
    private long lastBroadcast;
    private final List<Runnable> readyWaiters = new ArrayList<>();
    private final List<Consumer<String>> failWaiters = new ArrayList<>();

    public RuntimeInstaller(Manager m, Path runtimeDir) {
        this.m = m;
        this.root = runtimeDir;
        this.downloader = new Downloader(m.version);
        this.botmodSha = resourceSha(BOTMOD_RESOURCE);
    }

    public Path root() {
        return root;
    }

    public Path mcDir() {
        return root.resolve("mc");
    }

    public Path modsDir() {
        return root.resolve("mods");
    }

    public Path hmcJar(ManagerConfig cfg) {
        return root.resolve("headlessmc").resolve(cfg.runtime().headlessmcJarName());
    }

    /** {@code runtime.javaPath}, else the manager's own java (Java 25, which 26.2 needs). */
    public Path javaFor(ManagerConfig cfg) {
        String p = cfg.runtime().javaPathOrNull();
        return p != null ? Path.of(p) : Os.currentJava();
    }

    /** Mod jars every bot gets, as file names in {@link #modsDir()} (valid when {@link #isReady()}). */
    public List<String> modFiles() {
        List<String> out = new ArrayList<>();
        JsonArray files = manifest == null ? null : Json.getArr(manifest, "files");
        if (files != null) {
            files.forEach(f -> out.add(f.getAsString()));
        }
        return out;
    }

    public void init() {
        try {
            JsonElement e = AtomicFiles.readJson(root.resolve("installed.json"));
            manifest = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (IOException e) {
            manifest = null;
        }
        state = computeState(m.config.get());
        if (botmodSha == null) {
            Log.warn("the bot mod jar is missing from the manager build (%s); bots cannot start", BOTMOD_RESOURCE);
        }
    }

    public boolean isReady() {
        return READY.equals(state);
    }

    public boolean installing() {
        return INSTALLING.equals(state);
    }

    /** Re-evaluates the state after a config change. */
    public void refresh() {
        if (!installing()) {
            String s = computeState(m.config.get());
            if (!s.equals(state)) {
                state = s;
                broadcast(true);
            }
        }
    }

    private String computeState(ManagerConfig cfg) {
        if (manifest == null) {
            return MISSING;
        }
        if (!coreKey(cfg).equals(Json.getString(manifest, "coreKey", ""))
                || !modsKey(cfg).equals(Json.getString(manifest, "modsKey", ""))) {
            return OUTDATED;
        }
        if (!Files.isRegularFile(hmcJar(cfg)) || !fabricVersionExists(cfg.runtime().minecraftVersion())) {
            return MISSING;
        }
        for (String f : modFiles()) {
            if (!Files.isRegularFile(modsDir().resolve(f))) {
                return MISSING;
            }
        }
        return READY;
    }

    private static String coreKey(ManagerConfig cfg) {
        return cfg.runtime().minecraftVersion() + "|" + cfg.runtime().fabricLoader() + "|" + cfg.runtime().headlessmcVersion();
    }

    private String modsKey(ManagerConfig cfg) {
        StringBuilder sb = new StringBuilder();
        for (ManagerConfig.ModEntry mod : cfg.runtime().mods()) {
            if (mod.enabled()) {
                sb.append(mod.id()).append('|').append(mod.url()).append('|').append(mod.modrinth()).append('|')
                        .append(mod.version()).append('|').append(mod.sha512()).append(';');
            }
        }
        return sb.append("botmod=").append(botmodSha).toString();
    }

    private boolean fabricVersionExists(String mc) {
        Path versions = mcDir().resolve("versions");
        if (!Files.isDirectory(versions)) {
            return false;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(versions)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) && n.contains("fabric") && n.endsWith(mc)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // treated as missing
        }
        return false;
    }

    /** Runs {@code ok} once the runtime is ready (installing first when needed), or {@code failed} with the error. */
    public void whenReady(Runnable ok, Consumer<String> failed) {
        if (isReady()) {
            ok.run();
            return;
        }
        readyWaiters.add(ok);
        failWaiters.add(failed);
        install(false);
    }

    /** Starts an install unless one is running. {@code force} re-runs the Fabric install and prepare step. */
    public void install(boolean force) {
        if (installing()) {
            return;
        }
        ManagerConfig cfg = m.config.get();
        state = INSTALLING;
        step = "start";
        progress = 0;
        message = null;
        error = null;
        broadcast(true);
        m.event("runtime", Levels.INFO, null, "event.runtime.started", Map.of());
        JsonObject oldManifest = manifest;
        Thread.ofVirtual().name("runtime-install").start(() -> run(cfg, force, oldManifest));
    }

    private void run(ManagerConfig cfg, boolean force, JsonObject oldManifest) {
        try {
            JsonObject result = doInstall(cfg, force, oldManifest);
            m.loop.post(() -> finished(result, null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.error("runtime install failed", e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            m.loop.post(() -> finished(null, msg));
        }
    }

    private void finished(JsonObject newManifest, String err) {
        List<Runnable> ok = List.copyOf(readyWaiters);
        List<Consumer<String>> fail = List.copyOf(failWaiters);
        readyWaiters.clear();
        failWaiters.clear();
        if (err == null) {
            manifest = newManifest;
            state = computeState(m.config.get());
            if (!READY.equals(state)) {
                // config changed while installing; the next start installs again
                Log.warn("runtime installed but config changed meanwhile (state %s)", state);
            }
            step = "done";
            progress = 1;
            message = null;
            m.event("runtime", Levels.INFO, null, "event.runtime.done", Map.of());
            broadcast(true);
            ok.forEach(r -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Log.error("runtime waiter failed", e);
                }
            });
        } else {
            state = FAILED;
            error = err;
            m.event("runtime", Levels.ERROR, null, "event.runtime.failed", Map.of("error", err));
            broadcast(true);
            fail.forEach(f -> f.accept(err));
        }
    }

    /** Progress from the install thread; broadcasts at most 4 times per second. */
    private void report(String newStep, double p, String msg) {
        m.loop.post(() -> {
            boolean stepChanged = !newStep.equals(step);
            step = newStep;
            progress = p;
            if (msg != null) {
                message = msg.length() > 300 ? msg.substring(0, 300) : msg;
            }
            broadcast(stepChanged);
        });
    }

    private void broadcast(boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - lastBroadcast >= 250) {
            lastBroadcast = now;
            m.sse.broadcast(SseHub.RUNTIME, view());
        }
    }

    // ------------------------------------------------------------------ the install itself (install thread)

    private JsonObject doInstall(ManagerConfig cfg, boolean force, JsonObject oldManifest) throws Exception {
        ManagerConfig.RuntimeCfg rt = cfg.runtime();
        Files.createDirectories(modsDir());
        Files.createDirectories(mcDir());
        if (botmodSha == null) {
            throw new IOException("the bot mod jar is missing from the manager build; rebuild with :manager:build");
        }

        // 1. HeadlessMC launcher
        Path jar = hmcJar(cfg);
        String sha256 = rt.headlessmcSha256();
        if (!"2.10.0".equals(rt.headlessmcVersion()) && SettingsSchema.HEADLESSMC_SHA256.equals(sha256)) {
            sha256 = null; // the shipped digest belongs to 2.10.0 only
        }
        if (!Files.isRegularFile(jar) || !Hashing.matches(sha256, Hashing.file(jar, "SHA-256"))) {
            report("headlessmc", 0, rt.headlessmcDownloadUrl());
            downloader.download(rt.headlessmcDownloadUrl(), jar, "SHA-256", sha256, p -> report("headlessmc", p, null));
        }

        // 2. mods
        List<String> files = new ArrayList<>();
        JsonArray modsInfo = new JsonArray();
        List<ManagerConfig.ModEntry> mods = rt.mods().stream().filter(ManagerConfig.ModEntry::enabled).toList();
        for (int i = 0; i < mods.size(); i++) {
            ManagerConfig.ModEntry mod = mods.get(i);
            double base = i / (double) Math.max(1, mods.size());
            report("mods", base, mod.name() == null || mod.name().isBlank() ? mod.id() : mod.name());
            String url;
            String fileName;
            String sha512;
            String version = mod.version();
            if (mod.modrinth() != null && !mod.modrinth().isBlank()) {
                Downloader.Resolved r = downloader.modrinth(mod.modrinth(), rt.minecraftVersion(), mod.version());
                if (mod.version() != null && !mod.version().isBlank() && !mod.version().equals(r.version())) {
                    Log.warn("mod %s: version %s not listed for %s, using %s", mod.id(), mod.version(),
                            rt.minecraftVersion(), r.version());
                }
                url = r.url();
                fileName = r.fileName();
                sha512 = mod.sha512() != null && !mod.sha512().isBlank() ? mod.sha512() : r.sha512();
                version = r.version();
            } else {
                url = mod.url();
                fileName = fileNameOf(url, mod.id());
                sha512 = mod.sha512();
            }
            fileName = safeJarName(fileName, mod.id());
            Path target = modsDir().resolve(fileName);
            if (!Files.isRegularFile(target) || !Hashing.matches(sha512, Hashing.file(target, "SHA-512"))) {
                int idx = i;
                downloader.download(url, target, "SHA-512", sha512,
                        p -> report("mods", (idx + p) / Math.max(1, mods.size()), null));
            }
            files.add(fileName);
            modsInfo.add(Json.obj("id", mod.id(), "file", fileName, "version", version));
        }

        // 3. our bot mod from the manager jar
        report("botmod", 0, BOTMOD_FILE);
        Path botmod = modsDir().resolve(BOTMOD_FILE);
        if (!Files.isRegularFile(botmod) || !botmodSha.equalsIgnoreCase(Hashing.file(botmod, "SHA-256"))) {
            try (InputStream in = RuntimeInstaller.class.getClassLoader().getResourceAsStream(BOTMOD_RESOURCE)) {
                if (in == null) {
                    throw new IOException("bot mod jar missing from the manager build");
                }
                AtomicFiles.write(botmod, in.readAllBytes());
            }
        }
        files.add(BOTMOD_FILE);
        modsInfo.add(Json.obj("id", "baritonebots", "file", BOTMOD_FILE, "version", m.version));

        // 4. Fabric install + prepare (shared mcdir, never in parallel: this thread is the only installer)
        String mc = rt.minecraftVersion();
        boolean coreChanged = oldManifest == null || !coreKey(cfg).equals(Json.getString(oldManifest, "coreKey", ""));
        if (force || coreChanged || !fabricVersionExists(mc)) {
            Path setup = root.resolve("setup");
            Files.createDirectories(setup.resolve("game"));
            Map<String, String> props = new LinkedHashMap<>();
            props.put("hmc.mcdir", HmcFiles.slashes(mcDir()));
            props.put("hmc.gamedir", HmcFiles.slashes(setup.resolve("game")));
            props.put("hmc.offline", "true");
            props.put("hmc.offline.username", "BaritoneBots");
            props.put("hmc.always.lwjgl.flag", "true");
            props.put("hmc.assets.dummy", "true");
            props.put("hmc.java.versions", HmcFiles.slashes(javaFor(cfg)));
            if (rt.fabricInstallerUrl() != null && !rt.fabricInstallerUrl().isBlank()) {
                props.put("hmc.fabric.url", rt.fabricInstallerUrl());
            }
            HmcFiles.writeProperties(setup.resolve("HeadlessMC").resolve("config.properties"), props);
            report("fabric", 0, "fabric " + mc + " --uid " + rt.fabricLoader());
            runHmc(cfg, setup, List.of("fabric", mc, "--uid", rt.fabricLoader()), FABRIC_TIMEOUT_MIN, "fabric");
            report("prepare", 0, "launch fabric:" + mc + " -lwjgl -offline -prepare");
            runHmc(cfg, setup, List.of("launch", "fabric:" + mc, "-lwjgl", "-offline", "-prepare"), PREPARE_TIMEOUT_MIN,
                    "prepare");
            if (!fabricVersionExists(mc)) {
                throw new IOException("no Fabric " + mc + " version in runtime/mc/versions after the install; see runtime/install.log");
            }
        }

        JsonObject man = Json.obj("coreKey", coreKey(cfg), "modsKey", modsKey(cfg), "files", Json.arrOf(files),
                "mods", modsInfo, "headlessmc", jar.getFileName().toString(), "installedAt", System.currentTimeMillis());
        AtomicFiles.writeJson(root.resolve("installed.json"), man);
        return man;
    }

    private void runHmc(ManagerConfig cfg, Path dir, List<String> args, long timeoutMin, String stepName)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(javaFor(cfg).toString(), "-Xmx256m"));
        cmd.addAll(Os.childJvmFlags());
        cmd.addAll(List.of("-jar", hmcJar(cfg).toAbsolutePath().toString(), "--command"));
        cmd.addAll(args);
        Path logFile = root.resolve("install.log");
        try (Writer log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            log.write("\n$ " + String.join(" ", cmd) + "\n");
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            Thread pump = Thread.ofVirtual().start(() -> HmcFiles.pump(p.getInputStream(), line -> {
                synchronized (log) {
                    try {
                        log.write(line);
                        log.write('\n');
                    } catch (IOException ignored) {
                        // log is best effort
                    }
                }
                if (!line.isBlank()) {
                    report(stepName, -1, line.strip());
                }
            }));
            if (!p.waitFor(timeoutMin, TimeUnit.MINUTES)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new IOException("HeadlessMC '" + args.getFirst() + "' timed out after " + timeoutMin + " min");
            }
            pump.join(5_000);
            synchronized (log) {
                log.write("exit " + p.exitValue() + "\n");
            }
            if (p.exitValue() != 0) {
                throw new IOException("HeadlessMC '" + args.getFirst() + "' exited with " + p.exitValue()
                        + "; see runtime/install.log");
            }
        }
    }

    static String fileNameOf(String url, String fallback) {
        try {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return fallback + ".jar";
        }
    }

    /** Keeps file names inside mods/: no separators, a .jar suffix. */
    static String safeJarName(String name, String fallback) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty() || n.startsWith(".")) {
            n = fallback;
        }
        return n.endsWith(".jar") ? n : n + ".jar";
    }

    private static String resourceSha(String resource) {
        try (InputStream in = RuntimeInstaller.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : Hashing.bytes(in.readAllBytes(), "SHA-256");
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ view

    public JsonObject view() {
        ManagerConfig.RuntimeCfg rt = m.config.get().runtime();
        JsonObject o = Json.obj("state", state, "ready", isReady(), "installing", installing(),
                "versions", Json.obj("minecraft", rt.minecraftVersion(), "fabricLoader", rt.fabricLoader(),
                        "headlessmc", rt.headlessmcVersion(), "manager", m.version),
                "javaPath", javaFor(m.config.get()).toString(), "botModBundled", botmodSha != null);
        if (step != null) {
            o.addProperty("step", step);
        }
        if (progress >= 0) {
            o.addProperty("progress", progress);
        }
        if (message != null) {
            o.addProperty("message", message);
        }
        if (error != null) {
            o.addProperty("error", error);
        }
        if (manifest != null) {
            o.add("mods", Json.getArr(manifest, "mods"));
            o.addProperty("installedAt", Json.getLong(manifest, "installedAt", 0));
        }
        return o;
    }
}
