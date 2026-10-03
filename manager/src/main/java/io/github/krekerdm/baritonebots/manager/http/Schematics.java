package io.github.krekerdm.baritonebots.manager.http;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.schematic.Schematic;
import io.github.krekerdm.baritonebots.common.schematic.SchematicLoader;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The {@code schematics/} folder (SPEC §5.2, §6): safe names, size-limited uploads that must parse, and a listing
 * with dimensions and block counts (parsed once per file version). Thread-safe; not loop state.
 */
final class Schematics {
    static final long MAX_UPLOAD_BYTES = 64L * 1024 * 1024;
    /** Plain file names only: no separators, no leading dot, limited character set. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.()+\\-]{0,95}");

    private record Cached(long size, long modified, JsonObject info) {
    }

    private final Path dir;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    Schematics(Path dir) {
        this.dir = dir;
    }

    static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches() || n.contains("..") || !SchematicLoader.hasSupportedExtension(n)) {
            throw ApiException.badRequest("bad_name",
                    "use letters, digits, space, _ . ( ) + - and one of " + SchematicLoader.EXTENSIONS);
        }
        return n;
    }

    List<JsonObject> list() throws IOException {
        List<JsonObject> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && NAME.matcher(n).matches() && SchematicLoader.hasSupportedExtension(n)) {
                    files.add(p);
                }
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
        for (Path p : files) {
            out.add(info(p));
        }
        return out;
    }

    private JsonObject info(Path p) throws IOException {
        String n = p.getFileName().toString();
        long size = Files.size(p);
        long modified = Files.getLastModifiedTime(p).toMillis();
        Cached c = cache.get(n);
        if (c != null && c.size() == size && c.modified() == modified) {
            return c.info().deepCopy();
        }
        JsonObject o = Json.obj("name", n, "size", size, "modified", modified);
        try {
            Schematic s = SchematicLoader.load(p);
            o.addProperty("format", s.format().id());
            o.addProperty("width", s.width());
            o.addProperty("height", s.height());
            o.addProperty("length", s.length());
            o.addProperty("blocks", s.nonAirCount());
        } catch (IOException | RuntimeException e) {
            o.addProperty("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        cache.put(n, new Cached(size, modified, o));
        return o.deepCopy();
    }

    /** Stores an upload after checking that it parses. */
    JsonObject save(String rawName, InputStream body, boolean overwrite) throws IOException {
        String name = checkName(rawName);
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        if (Files.exists(target) && !overwrite) {
            throw ApiException.conflict("exists", "a schematic named '" + name + "' exists (add ?overwrite=true)");
        }
        Path tmp = dir.resolve(".upload-" + Tokens.random(12) + ".tmp");
        try {
            long total = 0;
            try (InputStream in = body; OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UPLOAD_BYTES) {
                        throw new ApiException(413, "too_large", "schematics are limited to " + (MAX_UPLOAD_BYTES >> 20) + " MiB");
                    }
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) {
                throw ApiException.badRequest("empty", "empty upload");
            }
            try {
                SchematicLoader.load(tmp);
            } catch (IOException | RuntimeException e) {
                throw ApiException.badRequest("bad_schematic", e.getMessage());
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            cache.remove(name);
            Log.info("schematic uploaded: %s (%d bytes)", name, total);
            return info(target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    void delete(String rawName) throws IOException {
        String name = checkName(rawName);
        if (!Files.deleteIfExists(dir.resolve(name))) {
            throw ApiException.notFound("schematic '" + name + "'");
        }
        cache.remove(name);
    }
}
