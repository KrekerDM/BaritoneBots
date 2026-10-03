package io.github.krekerdm.baritonebots.plugin.journal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Journal files {@code <yyyy-MM-dd>.<segment>.tsv.gz}: one file per day and writer segment. Each flush appends one
 * complete gzip member, so files are append-only and never rewritten; {@link GZIPInputStream} reads concatenated
 * members as one stream. A crash or a failed write can only damage the tail of the current segment, because the
 * writer starts a new segment (a new file) on every start and after every write error; {@link #read} keeps every
 * line before such damage.
 */
public final class JournalFiles {
    public static final String SUFFIX = ".tsv.gz";

    private JournalFiles() {
    }

    public static String fileName(LocalDate date, long segment) {
        return date + "." + segment + SUFFIX;
    }

    /** Date and segment of a journal file name; empty for any other file. */
    public static Optional<Dated> parse(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        String stem = name.substring(0, name.length() - SUFFIX.length());
        int dot = stem.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        try {
            LocalDate date = LocalDate.parse(stem.substring(0, dot));
            long segment = Long.parseLong(stem.substring(dot + 1));
            return Optional.of(new Dated(date, segment, path));
        } catch (DateTimeParseException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** True when a file of {@code date} is older than {@code retentionDays} full days before {@code today}. */
    public static boolean isExpired(LocalDate date, LocalDate today, int retentionDays) {
        return date.isBefore(today.minusDays(Math.max(1, retentionDays)));
    }

    /** Appends {@code lines} to {@code file} as one gzip member; creates the file and its directory if needed. */
    public static void append(Path file, List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream raw = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
             GZIPOutputStream gz = new GZIPOutputStream(raw, 8192);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(gz, StandardCharsets.UTF_8))) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        }
    }

    /**
     * Feeds every complete line of {@code file} to {@code sink}. {@link BufferedReader#readLine} only returns a line
     * once its terminator was decoded, so a line cut by damage is never delivered.
     *
     * @return false when the file ended in a damaged member (lines before it were still delivered)
     */
    public static boolean read(Path file, Consumer<String> sink) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file), 8192), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sink.accept(line);
            }
            return true;
        } catch (EOFException | ZipException e) {
            return false;
        }
    }

    /** Journal files in {@code dir}, ordered by date, then segment (= write order). */
    public static List<Dated> list(Path dir) throws IOException {
        List<Dated> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> parse(p).ifPresent(out::add));
        }
        out.sort(Comparator.comparing(Dated::date).thenComparingLong(Dated::segment));
        return out;
    }

    public record Dated(LocalDate date, long segment, Path path) {
    }
}
