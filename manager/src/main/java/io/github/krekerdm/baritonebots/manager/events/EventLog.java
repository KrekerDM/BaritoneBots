package io.github.krekerdm.baritonebots.manager.events;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Ring;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * events.jsonl (rotated at 5 MB, 3 files kept: events.jsonl, events.1.jsonl, events.2.jsonl), an in-memory tail of
 * {@code general.eventLogLimit} events, and listeners (SSE, tray, extensions). Owned by the manager loop.
 */
public final class EventLog {
    public static final long ROTATE_BYTES = 5L * 1024 * 1024;
    public static final int FILES_KEPT = 3;

    private final Path file;
    private final I18n i18n;
    private final Supplier<String> language;
    private final Ring<ManagerEvent> tail;
    private final List<Consumer<ManagerEvent>> listeners = new ArrayList<>();
    private BufferedWriter out;
    private long size;
    private long seq;

    public EventLog(Path dataDir, int limit, I18n i18n, Supplier<String> language) {
        this.file = dataDir.resolve("events.jsonl");
        this.i18n = i18n;
        this.language = language;
        this.tail = new Ring<>(limit);
    }

    /** Opens the file and reloads the newest events into the tail. */
    public void open() {
        try {
            if (Files.isRegularFile(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines.subList(Math.max(0, lines.size() - 2000), lines.size())) {
                    try {
                        ManagerEvent e = Json.fromJson(line, ManagerEvent.class);
                        if (e != null) {
                            tail.add(e);
                            seq = Math.max(seq, e.seq());
                        }
                    } catch (RuntimeException ignored) {
                        // a torn last line after a crash; skip it
                    }
                }
                size = Files.size(file);
            }
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.error("cannot open " + file, e);
        }
    }

    public void addListener(Consumer<ManagerEvent> l) {
        listeners.add(l);
    }

    public void setLimit(int limit) {
        tail.resize(limit);
    }

    /** Manager event with a translatable message. */
    public ManagerEvent manager(String kind, String level, String botId, String key, Map<String, ?> args,
                                JsonObject data) {
        String message = i18n.t(language.get(), key, args);
        return add(kind, level, ManagerEvent.SOURCE_MANAGER, botId, message, key,
                args == null ? null : Json.toObject(args), data);
    }

    /** Event whose text was produced elsewhere (a bot, the companion plugin, an extension). */
    public ManagerEvent add(String kind, String level, String source, String botId, String message, String key,
                            JsonObject args, JsonObject data) {
        ManagerEvent e = new ManagerEvent(++seq, System.currentTimeMillis(), kind, level, source, botId, message, key,
                args, data);
        tail.add(e);
        write(e);
        for (Consumer<ManagerEvent> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                Log.error("event listener failed", ex);
            }
        }
        return e;
    }

    /**
     * Newest-last slice of the tail.
     *
     * @param botId    only this bot when not null
     * @param minLevel only events at or above this level when not null
     */
    public List<ManagerEvent> query(int limit, String botId, String minLevel) {
        return query(limit, botId, minLevel, null);
    }

    /** As above; {@code projectId} keeps only events whose {@code data.projectId} matches. */
    public List<ManagerEvent> query(int limit, String botId, String minLevel, String projectId) {
        int min = minLevel == null ? 0 : ManagerEvent.rank(minLevel);
        return tail.tail(Math.max(0, limit), e -> (botId == null || botId.equalsIgnoreCase(e.botId()))
                && ManagerEvent.rank(e.level()) >= min
                && (projectId == null || e.data() != null && projectId.equals(Json.getString(e.data(), "projectId", null))));
    }

    private void write(ManagerEvent e) {
        if (out == null) {
            return;
        }
        try {
            String line = Json.toJson(e);
            out.write(line);
            out.write('\n');
            out.flush();
            size += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (size >= ROTATE_BYTES) {
                rotate();
            }
        } catch (IOException ex) {
            Log.error("cannot write " + file, ex);
            out = null;
        }
    }

    private void rotate() throws IOException {
        out.close();
        for (int i = FILES_KEPT - 1; i >= 1; i--) {
            Path from = i == 1 ? file : rotated(i - 1);
            if (Files.exists(from)) {
                Files.move(from, rotated(i), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        size = 0;
    }

    private Path rotated(int i) {
        return file.resolveSibling("events." + i + ".jsonl");
    }

    public void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // shutting down
            }
            out = null;
        }
    }
}
