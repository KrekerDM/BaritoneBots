package io.github.krekerdm.baritonebots.mod.schematic;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.ModInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads schematics with Baritone's schematic system ({@code getByFile(file)} → {@code format.parse(stream)}) on a
 * background thread and keeps the last {@value #CACHE_SIZE} parsed files (keyed by path, size and modification
 * time), so {@code bom}, {@code progress} and {@code build} for the same project parse once.
 */
public final class SchematicStore {
    private static final int CACHE_SIZE = 2;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BaritoneBots schematic loader");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Key, IStaticSchematic> CACHE = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, IStaticSchematic> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final Map<Key, CompletableFuture<IStaticSchematic>> LOADING = new HashMap<>();

    private SchematicStore() {
    }

    private record Key(String path, long size, long modified) {
    }

    /** Load failure with a SPEC §3 reason ({@code not_found}, {@code unsupported}, {@code error}). */
    public static final class LoadException extends RuntimeException {
        private final String reason;

        LoadException(String reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Parsed schematic (completes on the loader thread); fails with {@link LoadException}. */
    public static synchronized CompletableFuture<IStaticSchematic> load(String path) {
        File file = new File(path).getAbsoluteFile();
        if (!file.isFile()) {
            return CompletableFuture.failedFuture(new LoadException(Reasons.NOT_FOUND,
                    "schematic file not found: " + file, null));
        }
        Key key = new Key(file.getPath(), file.length(), file.lastModified());
        IStaticSchematic cached = CACHE.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<IStaticSchematic> running = LOADING.get(key);
        if (running != null) {
            return running;
        }
        CompletableFuture<IStaticSchematic> f = CompletableFuture.supplyAsync(() -> parse(file), LOADER);
        LOADING.put(key, f);
        f.whenComplete((s, ex) -> {
            synchronized (SchematicStore.class) {
                LOADING.remove(key);
                if (s != null) {
                    CACHE.put(key, s);
                }
            }
        });
        return f;
    }

    /** The {@link LoadException} behind a failed future (other errors become reason {@code error}). */
    public static LoadException unwrap(Throwable t) {
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        return c instanceof LoadException le ? le
                : new LoadException(Reasons.ERROR, "schematic load failed: " + c, c);
    }

    private static IStaticSchematic parse(File file) {
        Optional<ISchematicFormat> format = BaritoneAPI.getProvider().getSchematicSystem().getByFile(file);
        if (format.isEmpty()) {
            throw new LoadException(Reasons.UNSUPPORTED, "unsupported schematic format: " + file.getName()
                    + " (supported: " + BaritoneAPI.getProvider().getSchematicSystem().getFileExtensions() + ")", null);
        }
        long t0 = System.nanoTime();
        try (InputStream in = new FileInputStream(file)) {
            IStaticSchematic s = format.get().parse(in);
            if (s == null || s.widthX() < 1 || s.heightY() < 1 || s.lengthZ() < 1) {
                throw new LoadException(Reasons.BAD_ARGS, "empty schematic: " + file.getName(), null);
            }
            ModInfo.LOG.info("Loaded schematic {} ({}x{}x{}) in {} ms", file.getName(), s.widthX(), s.heightY(),
                    s.lengthZ(), (System.nanoTime() - t0) / 1_000_000);
            return s;
        } catch (LoadException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            throw new LoadException(Reasons.BAD_ARGS, "cannot read schematic " + file.getName() + ": " + e, e);
        }
    }
}
