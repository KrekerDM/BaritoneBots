package io.github.krekerdm.baritonebots.manager.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Crash-safe file writes (tmp file + move) and JSON helpers for the data directory (SPEC §5.2). */
public final class AtomicFiles {
    /** Pretty printer for files people may open by hand; same null/HTML settings as {@link Json#GSON}. */
    private static final com.google.gson.Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
            .create();

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void writeString(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes any JSON-able value pretty-printed. */
    public static void writeJson(Path target, Object value) throws IOException {
        writeString(target, PRETTY.toJson(Json.toTree(value)) + "\n");
    }

    /** Reads a JSON file; {@code null} when the file does not exist. */
    public static JsonElement readJson(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Json.parse(text);
        } catch (JsonParseException e) {
            throw new IOException(file.getFileName() + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Copies a broken file aside so a fresh one can be written without losing the user's data. */
    public static Path backupCorrupt(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }
}
