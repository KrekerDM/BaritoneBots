package io.github.krekerdm.baritonebots.manager.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.DoubleConsumer;

/** HTTPS downloads with digest verification and the Modrinth version lookup (blocking; installer thread only). */
public final class Downloader {
    private HttpClient http;
    private final String userAgent;

    public Downloader(String version) {
        // Modrinth asks API clients for a descriptive user agent.
        this.userAgent = "KrekerDM/BaritoneBots/" + version + " (github.com/KrekerDM/BaritoneBots)";
    }

    /** Created on first use: an install is rare, and the client opens a selector the manager otherwise never needs. */
    private synchronized HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
        }
        return http;
    }

    /** A resolved mod file. */
    public record Resolved(String url, String fileName, String sha512, String version) {
    }

    /**
     * Downloads {@code url} to {@code target} (via a .part file) and checks the digest when given.
     *
     * @param algorithm {@code SHA-256} or {@code SHA-512}
     * @param progress  receives 0..1 when the size is known
     */
    public void download(String url, Path target, String algorithm, String expected, DoubleConsumer progress)
            throws IOException, InterruptedException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent)
                .timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> res = client().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " for " + url);
        }
        long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        Path part = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest md = Hashing.digest(algorithm);
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (total > 0 && progress != null) {
                    progress.accept(Math.min(1.0, done / (double) total));
                }
            }
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!Hashing.matches(expected, actual)) {
            Files.deleteIfExists(part);
            throw new IOException(algorithm + " mismatch for " + target.getFileName() + ": expected " + expected
                    + ", got " + actual);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Finds the Fabric build of a Modrinth project for one Minecraft version. Prefers {@code wantedVersion}
     * (a version number) when the API lists it, else the newest compatible build.
     */
    public Resolved modrinth(String project, String mcVersion, String wantedVersion) throws IOException, InterruptedException {
        String url = "https://api.modrinth.com/v2/project/" + URLEncoder.encode(project, StandardCharsets.UTF_8)
                + "/version?loaders=" + URLEncoder.encode("[\"fabric\"]", StandardCharsets.UTF_8)
                + "&game_versions=" + URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> res = client().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Modrinth HTTP " + res.statusCode() + " for project " + project);
        }
        JsonElement root = Json.parse(res.body());
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) {
            throw new IOException("Modrinth has no Fabric build of " + project + " for " + mcVersion);
        }
        JsonArray versions = root.getAsJsonArray();
        JsonObject chosen = versions.get(0).getAsJsonObject();
        if (wantedVersion != null && !wantedVersion.isBlank()) {
            for (JsonElement v : versions) {
                if (wantedVersion.equals(Json.getString(v.getAsJsonObject(), "version_number", ""))) {
                    chosen = v.getAsJsonObject();
                    break;
                }
            }
        }
        JsonArray files = Json.getArr(chosen, "files");
        if (files == null || files.isEmpty()) {
            throw new IOException("Modrinth version of " + project + " has no files");
        }
        JsonObject file = files.get(0).getAsJsonObject();
        for (JsonElement f : files) {
            if (Json.getBool(f.getAsJsonObject(), "primary", false)) {
                file = f.getAsJsonObject();
                break;
            }
        }
        JsonObject hashes = Json.getObj(file, "hashes");
        return new Resolved(Json.getString(file, "url", null), Json.getString(file, "filename", project + ".jar"),
                Json.getString(hashes, "sha512", null), Json.getString(chosen, "version_number", "?"));
    }
}
