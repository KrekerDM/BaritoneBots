package io.github.krekerdm.baritonebots.manager;

import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar baritonebots-manager.jar [--data <dir>] [--no-tray] [--no-browser]} (SPEC §5.1).
 * Data directory default: {@code ./BaritoneBots-data}.
 */
public final class ManagerMain {
    private ManagerMain() {
    }

    public static void main(String[] args) {
        Path data = Path.of("BaritoneBots-data");
        boolean noTray = false;
        boolean noBrowser = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data" -> {
                    if (i + 1 >= args.length) {
                        usage("--data needs a directory");
                        return;
                    }
                    data = Path.of(args[++i]);
                }
                case "--no-tray" -> noTray = true;
                case "--no-browser" -> noBrowser = true;
                case "--help", "-h" -> {
                    usage(null);
                    return;
                }
                default -> {
                    usage("unknown option " + args[i]);
                    return;
                }
            }
        }
        if (Runtime.version().feature() < 25) {
            System.err.println("BaritoneBots needs Java 25 or newer (running " + Runtime.version() + ").");
            System.exit(1);
        }
        shortenUnixSocketDir();
        Manager manager;
        try {
            Files.createDirectories(data);
            Log.init(data.resolve("manager.log"));
            manager = new Manager(data.toAbsolutePath(), noTray, noBrowser);
        } catch (ValidationException e) {
            System.err.println("config.json is invalid, fix or delete it: " + e.fields());
            System.exit(2);
            return;
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot start: " + e.getMessage());
            Log.error("startup failed", e);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(manager::shutdown, "shutdown"));
        try {
            manager.start();
        } catch (IOException | RuntimeException e) {
            System.err.println("Cannot start: " + e.getMessage()
                    + " (is another manager running, or is the port taken? see general.panel.port / general.link.port)");
            Log.error("startup failed", e);
            System.exit(1);
        }
    }

    /**
     * On Windows every NIO selector (HTTP server, HTTP client) opens a loopback pipe through an AF_UNIX socket in
     * the temp directory, whose real path is limited to ~100 characters. Redirected temp folders (app containers,
     * roaming profiles) break it even when %TEMP% looks short, so a short folder in the user's home is used unless
     * the property is already set. It must happen before the first selector is opened.
     */
    private static void shortenUnixSocketDir() {
        if (!Os.isWindows() || System.getProperty("jdk.net.unixdomain.tmpdir") != null) {
            return;
        }
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".baritonebots-tmp");
            Files.createDirectories(dir);
            System.setProperty("jdk.net.unixdomain.tmpdir", dir.toString());
        } catch (IOException | RuntimeException ignored) {
            // keep the JDK default
        }
    }

    private static void usage(String error) {
        if (error != null) {
            System.err.println(error);
        }
        System.err.println("usage: java -jar baritonebots-manager.jar [--data <dir>] [--no-tray] [--no-browser]");
        if (error != null) {
            System.exit(64);
        }
    }
}
