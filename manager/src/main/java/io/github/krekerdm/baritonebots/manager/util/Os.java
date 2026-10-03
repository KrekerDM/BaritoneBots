package io.github.krekerdm.baritonebots.manager.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;

/** Platform facts the supervisor and installer need. */
public final class Os {
    private Os() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The {@code java} executable running this manager. */
    public static Path currentJava() {
        String exe = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe);
    }

    public static int currentJavaFeature() {
        return Runtime.version().feature();
    }

    /**
     * Flags for child JVMs (HeadlessMC, the game) so their NIO selectors use the same short AF_UNIX directory as
     * this manager (see ManagerMain#shortenUnixSocketDir); without it HeadlessMC's HTTP client fails on Windows
     * hosts with a redirected temp folder. Skipped when the path has a space, because hmc.jvmargs is
     * space-delimited.
     */
    public static List<String> childJvmFlags() {
        String dir = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (dir == null || dir.isBlank() || dir.indexOf(' ') >= 0) {
            return List.of();
        }
        return List.of("-Djdk.net.unixdomain.tmpdir=" + dir);
    }

    /** Total size of regular files below {@code root}; 0 when it does not exist. */
    public static long directorySize(Path root) {
        if (!Files.exists(root)) {
            return 0;
        }
        long[] total = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // partial sum is good enough for a disk-use display
        }
        return total[0];
    }

    /** Opens a URL in the default browser; false when the platform offers no way. */
    public static boolean openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                return true;
            }
        } catch (Exception | LinkageError ignored) {
            // fall through to the shell commands below
        }
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                    : new ProcessBuilder(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                    ? "open" : "xdg-open", url);
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
