package io.github.krekerdm.baritonebots.manager;

import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Os;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * System tray icon (AWT): Open panel / Start all / Stop all / Exit, balloons for warn/error events (SPEC §5.8).
 * The icon is a plain #7FB4D6 square drawn in code.
 */
public final class Tray {
    private static final long BALLOON_MIN_INTERVAL_MS = 5_000;

    private final TrayIcon icon;
    private volatile long lastBalloon;

    private Tray(TrayIcon icon) {
        this.icon = icon;
    }

    /** Adds the icon; null when the platform has no tray (headless, some Linux desktops). */
    public static Tray install(Manager m, String panelUrl) {
        try {
            if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                Log.info("no system tray available; running without one");
                return null;
            }
            String lang = m.config.get().general().language();
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem(m.i18n.t(lang, "tray.open", null));
            open.addActionListener(e -> Os.openBrowser(panelUrl));
            MenuItem startAll = new MenuItem(m.i18n.t(lang, "tray.startAll", null));
            startAll.addActionListener(e -> m.loop.post(m.supervisor::startAll));
            MenuItem stopAll = new MenuItem(m.i18n.t(lang, "tray.stopAll", null));
            stopAll.addActionListener(e -> m.loop.post(m.supervisor::stopAll));
            MenuItem exit = new MenuItem(m.i18n.t(lang, "tray.exit", null));
            exit.addActionListener(e -> new Thread(() -> System.exit(0), "tray-exit").start());
            menu.add(open);
            menu.addSeparator();
            menu.add(startAll);
            menu.add(stopAll);
            menu.addSeparator();
            menu.add(exit);
            TrayIcon icon = new TrayIcon(squareIcon(), "BaritoneBots", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> Os.openBrowser(panelUrl));
            SystemTray.getSystemTray().add(icon);
            return new Tray(icon);
        } catch (AWTException | RuntimeException | LinkageError e) {
            Log.warn("system tray unavailable: %s", e.getMessage());
            return null;
        }
    }

    private static Image squareIcon() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int rgb = new Color(0x7F, 0xB4, 0xD6).getRGB();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    /** Event listener (manager loop): balloon for warn/error, at most one per 5 s. */
    public void onEvent(ManagerEvent e) {
        int rank = ManagerEvent.rank(e.level());
        if (rank < 1) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBalloon < BALLOON_MIN_INTERVAL_MS) {
            return;
        }
        lastBalloon = now;
        String title = e.botId() == null ? "BaritoneBots" : "BaritoneBots: " + e.botId();
        String text = e.message() == null ? e.kind() : e.message();
        TrayIcon.MessageType type = Levels.ERROR.equals(e.level()) ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.WARNING;
        EventQueue.invokeLater(() -> {
            icon.displayMessage(title, text, type);
            icon.setToolTip(("BaritoneBots — " + text).substring(0, Math.min(120, text.length() + 15)));
        });
    }

    public void remove() {
        try {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(icon));
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }
}
