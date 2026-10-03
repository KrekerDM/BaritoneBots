package io.github.krekerdm.baritonebots.manager.runtime;

import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Helpers for HeadlessMC's {@code config.properties} and for running HeadlessMC processes. */
public final class HmcFiles {
    private HmcFiles() {
    }

    /** Writes {@code key=value} lines readable by {@link java.util.Properties#load}; paths use forward slashes. */
    public static void writeProperties(Path file, Map<String, String> props) throws IOException {
        StringBuilder sb = new StringBuilder("# Written by the BaritoneBots manager; changes are overwritten on start.\n");
        props.forEach((k, v) -> {
            if (v != null) {
                sb.append(k).append('=').append(escape(v)).append('\n');
            }
        });
        AtomicFiles.writeString(file, sb.toString());
    }

    static String escape(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append(i == 0 ? "\\ " : " ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String slashes(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** Charset child processes print with. */
    public static Charset nativeCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", System.getProperty("sun.jnu.encoding", "UTF-8")));
        } catch (RuntimeException e) {
            return Charset.defaultCharset();
        }
    }

    /** Reads lines until EOF on the calling thread. */
    public static void pump(InputStream in, Consumer<String> sink) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, nativeCharset()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sink.accept(line);
            }
        } catch (IOException ignored) {
            // process ended
        }
    }
}
