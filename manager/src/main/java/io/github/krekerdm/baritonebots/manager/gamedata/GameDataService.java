package io.github.krekerdm.baritonebots.manager.gamedata;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.ManagerLoop;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.util.Log;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Lazily loads {@link GameData} for the configured Minecraft version. {@link #ensureLoaded()} (loop) starts a worker
 * thread; the parsed data is published back on the loop. Until then (and when no client jar exists) {@link #current()}
 * returns {@code null} and callers use their fallbacks. {@link #current()} may also be read from HTTP threads.
 */
public final class GameDataService {
    public static final String IDLE = "idle";
    public static final String LOADING = "loading";
    public static final String READY = "ready";
    public static final String MISSING = "missing";
    public static final String FAILED = "failed";

    private final ManagerLoop loop;
    private final Supplier<Path> mcDir;
    private final Supplier<ManagerConfig> config;
    private volatile GameData data;
    private String state = IDLE;
    private String key;
    private String sourcePath;
    private String error;
    private long loadedAt;
    private int generation;
    private final List<Runnable> listeners = new ArrayList<>();

    public GameDataService(ManagerLoop loop, Supplier<Path> mcDir, Supplier<ManagerConfig> config) {
        this.loop = loop;
        this.mcDir = mcDir;
        this.config = config;
    }

    /** Loaded data, or {@code null} while loading / without a client jar. */
    public GameData current() {
        return data;
    }

    /** Called on the loop whenever new data is published. */
    public void addListener(Runnable r) {
        listeners.add(r);
    }

    private String wantedKey(ManagerConfig cfg) {
        ManagerConfig.RuntimeCfg rt = cfg.runtime();
        return rt.minecraftVersion() + "|" + rt.fabricLoader() + "|" + Objects.toString(rt.gameDataPathOrNull(), "");
    }

    /** Starts loading when nothing is loaded for the current settings (loop). Missing jars are retried every call. */
    public void ensureLoaded() {
        ManagerConfig cfg = config.get();
        String want = wantedKey(cfg);
        if (want.equals(key) && (READY.equals(state) || LOADING.equals(state) || FAILED.equals(state))) {
            return;
        }
        List<Path> candidates = candidates(mcDir.get(), cfg.runtime().minecraftVersion(), cfg.runtime().fabricLoader(),
                cfg.runtime().gameDataPathOrNull());
        if (candidates.isEmpty()) {
            if (!MISSING.equals(state) || !want.equals(key)) {
                Log.info("game data: no client jar for %s yet (recipes and drops unknown)", cfg.runtime().minecraftVersion());
            }
            key = want;
            state = MISSING;
            data = null;
            return;
        }
        key = want;
        state = LOADING;
        error = null;
        int gen = ++generation;
        Thread t = new Thread(() -> load(gen, candidates), "gamedata-loader");
        t.setDaemon(true);
        t.start();
    }

    /** Forces a reload on the next {@link #ensureLoaded()} (settings changed). */
    public void invalidate(ManagerConfig nu) {
        if (!wantedKey(nu).equals(key)) {
            key = null;
            state = IDLE;
            data = null;
            generation++;
        }
    }

    private void load(int gen, List<Path> candidates) {
        IOException last = null;
        for (Path p : candidates) {
            long t0 = System.currentTimeMillis();
            try {
                GameData d = GameDataParser.parse(p);
                if (d.isEmpty()) {
                    continue; // e.g. a mod jar without data/
                }
                long ms = System.currentTimeMillis() - t0;
                loop.post(() -> publish(gen, d, p, null));
                Log.info("game data: %s loaded in %d ms (%s)", p, ms, d.stats());
                return;
            } catch (IOException | RuntimeException e) {
                last = e instanceof IOException io ? io : new IOException(e);
                Log.warn("game data: cannot read %s: %s", p, e.getMessage());
            }
        }
        String msg = last == null ? "no recipes or loot tables found in " + candidates : last.getMessage();
        loop.post(() -> publish(gen, null, null, msg));
    }

    private void publish(int gen, GameData d, Path source, String err) {
        if (gen != generation) {
            return; // settings changed meanwhile
        }
        data = d;
        sourcePath = source == null ? null : source.toString();
        error = err;
        state = d == null ? FAILED : READY;
        loadedAt = System.currentTimeMillis();
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                Log.error("game data listener failed", e);
            }
        }
    }

    public JsonObject view() {
        JsonObject o = Json.obj("state", state, "source", sourcePath, "error", error,
                "loadedAt", loadedAt == 0 ? null : loadedAt);
        GameData d = data;
        if (d != null) {
            o.add("stats", d.stats());
        }
        return o;
    }

    /**
     * Where the client jar may be, in order: the configured path (jar, zip or folder with {@code data/}),
     * {@code versions/<mc>/<mc>.jar}, {@code versions/fabric-loader-<loader>-<mc>/fabric-loader-<loader>-<mc>.jar}
     * (HeadlessMC's Fabric install keeps the game jar there), then any {@code versions/<dir>/<dir>.jar} whose folder is
     * named {@code <mc>} or ends with {@code -<mc>}. Only existing paths are returned.
     */
    public static List<Path> candidates(Path mcDir, String mc, String loader, String configured) {
        Set<Path> out = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Files.exists(p)) {
                out.add(p);
            }
        }
        if (mcDir != null && mc != null && !mc.isBlank()) {
            Path versions = mcDir.resolve("versions");
            add(out, versions.resolve(mc).resolve(mc + ".jar"));
            if (loader != null && !loader.isBlank()) {
                String fab = "fabric-loader-" + loader + "-" + mc;
                add(out, versions.resolve(fab).resolve(fab + ".jar"));
            }
            if (Files.isDirectory(versions)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(versions)) {
                    List<Path> dirs = new ArrayList<>();
                    ds.forEach(dirs::add);
                    dirs.sort(null);
                    for (Path d : dirs) {
                        String n = d.getFileName().toString();
                        if (n.equals(mc) || n.endsWith("-" + mc)) {
                            add(out, d.resolve(n + ".jar"));
                        }
                    }
                } catch (IOException e) {
                    Log.warn("game data: cannot list %s: %s", versions, e.getMessage());
                }
            }
        }
        return List.copyOf(out);
    }

    private static void add(Set<Path> out, Path p) {
        if (Files.isRegularFile(p)) {
            out.add(p);
        }
    }
}
