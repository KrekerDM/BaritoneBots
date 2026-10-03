package io.github.krekerdm.baritonebots.manager.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manager diagnostics log: console plus {@code <data>/manager.log} (rotated at 5 MB, one backup).
 * User-facing notifications go through the event log instead; this is for operators and bug reports.
 */
public final class Log {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Path file;
    private static Writer out;
    private static long written;

    private Log() {
    }

    public static synchronized void init(Path logFile) {
        file = logFile;
        try {
            written = Files.exists(logFile) ? Files.size(logFile) : 0;
            out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            out = null;
            System.err.println("Cannot open " + logFile + ": " + e.getMessage());
        }
    }

    public static void info(String fmt, Object... args) {
        write("INFO ", format(fmt, args), null);
    }

    public static void warn(String fmt, Object... args) {
        write("WARN ", format(fmt, args), null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static String format(String fmt, Object... args) {
        return args.length == 0 ? fmt : String.format(fmt, args);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(LocalDateTime.now().format(TIME)).append(' ').append(level).append(' ')
                .append('[').append(Thread.currentThread().getName()).append("] ").append(msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append(System.lineSeparator()).append(sw);
        }
        String line = sb.toString();
        (t != null || level.startsWith("E") ? System.err : System.out).println(line);
        if (out == null) {
            return;
        }
        try {
            out.write(line);
            out.write(System.lineSeparator());
            out.flush();
            written += line.length() + 2L;
            if (written > MAX_BYTES) {
                rotate();
            }
        } catch (IOException e) {
            out = null;
        }
    }

    private static void rotate() throws IOException {
        out.close();
        Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        written = 0;
    }

    public static synchronized void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing on shutdown; nothing left to report to
            }
            out = null;
        }
    }
}
