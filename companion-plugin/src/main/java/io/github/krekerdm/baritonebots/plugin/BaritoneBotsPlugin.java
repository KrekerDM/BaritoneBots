package io.github.krekerdm.baritonebots.plugin;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.plugin.journal.JournalService;
import io.github.krekerdm.baritonebots.plugin.journal.JournalStore;
import io.github.krekerdm.baritonebots.plugin.rollback.RollbackResult;
import io.github.krekerdm.baritonebots.plugin.rollback.RollbackService;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Optional companion plugin (SPEC §7). Bots prove themselves with the shared token over {@code baritonebots:main};
 * verified bots get smaller distances, extra permission nodes and a forced AuthMe / nLogin login, their block
 * changes go into a journal that can be rolled back, and their names can be locked to known addresses.
 */
public final class BaritoneBotsPlugin extends JavaPlugin {
    static final String ADMIN_PERMISSION = "baritonebots.admin";
    private static final long JOURNAL_FLUSH_MS = 1_000;
    private static final long JOURNAL_CLOSE_TIMEOUT_MS = 10_000;
    private static final long PRUNE_PERIOD_TICKS = 20L * 60 * 5;

    private final BotRegistry registry = new BotRegistry();
    /** Read by the async pre-login listener, hence volatile; both are immutable snapshots. */
    private volatile PluginSettings settings;
    private volatile Messages messages;
    private BotPerks perks;
    private ForceLogin forceLogin;
    private CompanionChannel channel;
    private RollbackService rollback;
    private JournalStore journalStore;
    private JournalService journal;
    private BukkitTask pruneTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        perks = new BotPerks(this);
        forceLogin = new ForceLogin(getLogger());
        rollback = new RollbackService(this);
        try {
            loadSettings();
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage() + " - fix the file and restart; the plugin stays disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        applyJournal(null);

        channel = new CompanionChannel(this);
        channel.register(new CompanionProtocol(this)::handle);
        getServer().getPluginManager().registerEvents(new BotListener(this), this);

        AdminCommand admin = new AdminCommand(this);
        PluginCommand cmd = getCommand("baritonebots");
        if (cmd != null) {
            cmd.setExecutor(admin);
            cmd.setTabCompleter(admin);
        } else {
            getLogger().severe("Command 'baritonebots' is missing from plugin.yml");
        }

        PluginSettings s = settings;
        getLogger().info("Enabled: " + s.bots().size() + " bot name(s), journal " + (journal != null ? "on" : "off")
                + ", rollback " + s.rollback().mode() + ", name protection " + (s.protectNames() ? "on" : "off"));
        if (!getServer().getOnlinePlayers().isEmpty()) {
            getLogger().info("Bots that are already online are verified when they reconnect.");
        }
    }

    @Override
    public void onDisable() {
        if (rollback != null) {
            rollback.cancel();
        }
        if (channel != null) {
            channel.unregister();
        }
        for (BotRegistry.Session s : registry.clear()) {
            Player p = getServer().getPlayer(s.player());
            perks.revokePermissions(p, s);
            if (p != null) {
                perks.resetDistances(p);
            }
        }
        closeJournal();
    }

    // ------------------------------------------------------------------ settings

    /**
     * Reads {@code config.yml}, creating the token on first start.
     *
     * @return the warnings, already logged
     * @throws IllegalStateException when the file is not valid YAML; nothing is changed then
     */
    private List<String> loadSettings() {
        // Bukkit's reloadConfig() swallows YAML errors and falls back to the defaults; saving the new token on top
        // of that would overwrite the admin's file, so a broken file is detected first and left alone.
        try {
            new YamlConfiguration().load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("config.yml cannot be read: " + e.getMessage(), e);
        }
        reloadConfig();
        FileConfiguration c = getConfig();
        if (c.getString("token", "").isBlank()) {
            c.set("token", Tokens.generate());
            saveConfig();
            getLogger().info("Generated a new bot token in config.yml (show it with /baritonebots token).");
        }
        List<String> warnings = new ArrayList<>();
        PluginSettings s = PluginSettings.read(c, warnings);
        for (String w : warnings) {
            getLogger().warning("config.yml: " + w);
        }
        messages = Messages.load(s.language());
        settings = s;
        return warnings;
    }

    /**
     * {@code /baritonebots reload}: new settings, journal started or stopped, perks re-applied. Bots removed from
     * the list, and every bot when the token changed, lose their verification and get a {@code reject}.
     *
     * @return the number of config warnings
     */
    int reload() {
        PluginSettings old = settings;
        int warnings = loadSettings().size();
        applyJournal(old);
        PluginSettings now = settings;
        boolean tokenChanged = !old.token().equals(now.token());
        for (BotRegistry.Session s : registry.sessions()) {
            Player p = getServer().getPlayer(s.player());
            if (p == null) {
                registry.remove(s.player());
            } else if (tokenChanged) {
                unverify(p, s, "token_changed");
            } else if (now.botName(s.name()) == null) {
                unverify(p, s, "removed");
            } else {
                applyPerks(p, s);
            }
        }
        return warnings;
    }

    private void applyJournal(PluginSettings old) {
        PluginSettings.Journal j = settings.journal();
        if (!j.enabled()) {
            closeJournal();
            return;
        }
        if (journalStore == null) {
            try {
                JournalStore store = new JournalStore(getDataFolder().toPath().resolve("journal"), ZoneId.systemDefault(),
                        j.retentionDays(), JOURNAL_FLUSH_MS, getLogger());
                store.start();
                journalStore = store;
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Journal could not start; block journal and journal rollback are off", e);
                return;
            }
            journal = new JournalService(journalStore, j.memoryLimit(), mainThread(), System::currentTimeMillis);
            pruneTask = getServer().getScheduler().runTaskTimer(this, () -> {
                if (journal != null) {
                    journal.prune();
                }
            }, PRUNE_PERIOD_TICKS, PRUNE_PERIOD_TICKS);
            return;
        }
        journalStore.setRetentionDays(j.retentionDays());
        if (old != null && old.journal().memoryLimit() != j.memoryLimit()) {
            // A fresh index starts empty and answers from the files until it covers a window again.
            journal = new JournalService(journalStore, j.memoryLimit(), mainThread(), System::currentTimeMillis);
        }
    }

    /** Writes everything still queued, then stops the IO thread. */
    private void closeJournal() {
        if (pruneTask != null) {
            pruneTask.cancel();
            pruneTask = null;
        }
        journal = null;
        if (journalStore != null) {
            JournalStore store = journalStore;
            journalStore = null;
            store.close(JOURNAL_CLOSE_TIMEOUT_MS);
            if (store.queued() > 0) {
                getLogger().warning("Journal closed with " + store.queued() + " record(s) not written");
            }
        }
    }

    private Executor mainThread() {
        // Throws once the plugin is disabled; JournalService then completes on the IO thread instead.
        return task -> getServer().getScheduler().runTask(this, task);
    }

    // ------------------------------------------------------------------ verified bots

    /** Server thread: the hello passed every check. */
    void verify(Player p, String botId, String modVersion) {
        BotRegistry.Session s = new BotRegistry.Session(p.getUniqueId(), p.getName(), botId, modVersion,
                System.currentTimeMillis());
        registry.put(s);
        applyPerks(p, s);
        scheduleForceLogin(p);
    }

    /** Distances and permission nodes from the current settings; also used after a reload. */
    void applyPerks(Player p, BotRegistry.Session s) {
        PluginSettings cur = settings;
        if (cur.distances().enabled()) {
            perks.applyDistances(p, cur.distances());
        } else {
            perks.resetDistances(p);
        }
        perks.grantPermissions(p, s, cur.permissions());
    }

    /** Respawn / world change: other plugins may reset per-player distances there. */
    void reapplyDistances(Player p) {
        if (registry.isVerified(p.getUniqueId()) && settings.distances().enabled()) {
            perks.applyDistances(p, settings.distances());
        }
    }

    /** Drops the verification of an online bot and tells it why ({@code reject}). */
    void unverify(Player p, BotRegistry.Session s, String code) {
        registry.remove(s.player());
        perks.revokePermissions(p, s);
        perks.resetDistances(p);
        channel.send(p, Envelope.of(MessageTypes.REJECT,
                Json.obj("reason", messages.plain("reject." + code), "code", code)));
        getLogger().info("Bot " + s.name() + " is no longer verified (" + code + ")");
    }

    void onQuit(Player p) {
        BotRegistry.Session s = registry.disconnect(p.getUniqueId());
        if (s != null) {
            perks.revokePermissions(p, s);
            debug("Bot " + s.name() + " left");
        }
    }

    private void scheduleForceLogin(Player p) {
        PluginSettings.ForceLogin cfg = settings.forceLogin();
        if (!cfg.enabled()) {
            return;
        }
        UUID id = p.getUniqueId();
        getServer().getScheduler().runTaskLater(this, () -> {
            Player now = getServer().getPlayer(id);
            if (now == null || !registry.isVerified(id)) {
                return;
            }
            ForceLogin.Result r = forceLogin.attempt(now);
            switch (r.outcome()) {
                case LOGGED_IN -> {
                    getLogger().info("Logged in bot " + now.getName() + " through " + r.plugin());
                    notice(now, messages.plain("notice.login-ok", Messages.arg("plugin", r.plugin())));
                }
                case ALREADY, NO_AUTH_PLUGIN -> debug("Force login of " + now.getName() + ": " + r.outcome().code);
                case NOT_REGISTERED -> {
                    getLogger().info("Bot " + now.getName() + " is not registered in " + r.plugin() + "; it has to /register once");
                    notice(now, messages.plain("notice.login-not-registered", Messages.arg("plugin", r.plugin())));
                }
                default -> {
                    getLogger().warning("Force login of " + now.getName() + " through " + r.plugin() + " failed: "
                            + r.outcome().code + (r.detail() == null ? "" : " (" + r.detail() + ")"));
                    notice(now, messages.plain("notice.login-failed", Messages.arg("plugin", String.valueOf(r.plugin())),
                            Messages.arg("reason", r.outcome().code)));
                }
            }
        }, cfg.delayTicks());
    }

    void notice(Player p, String text) {
        channel.send(p, Envelope.of(MessageTypes.NOTICE, Json.obj("message", text)));
    }

    // ------------------------------------------------------------------ shared texts

    /** A message key with its placeholders, rendered for chat or as plain text for bots. */
    record Line(String key, TagResolver... args) {
    }

    /** Human-readable outcome of a rollback, for the admin command and for {@code rollback_result.message}. */
    Line rollbackLine(String target, RollbackResult r) {
        if (r.ok() && RollbackResult.VIA_COMMAND.equals(r.via())) {
            return new Line("rollback.done-command", Messages.arg("bot", target),
                    Messages.arg("command", String.valueOf(r.command())));
        }
        if (r.ok()) {
            return new Line("rollback.done-journal", Messages.arg("bot", target), Messages.arg("total", r.total()),
                    Messages.arg("restored", r.restored()), Messages.arg("skipped", r.skipped()),
                    Messages.arg("failed", r.failed()));
        }
        return new Line("rollback.failed", Messages.arg("bot", target),
                Messages.arg("reason", messages.component("reason." + r.error(),
                        Messages.arg("max", RollbackService.maxMinutes(settings)))));
    }

    void debug(String message) {
        PluginSettings s = settings;
        if (s != null && s.debug()) {
            getLogger().info("[debug] " + message);
        }
    }

    // ------------------------------------------------------------------ accessors (server thread unless noted)

    /** Any thread. */
    PluginSettings settings() {
        return settings;
    }

    /** Any thread. */
    Messages messages() {
        return messages;
    }

    BotRegistry registry() {
        return registry;
    }

    ForceLogin forceLogin() {
        return forceLogin;
    }

    CompanionChannel channel() {
        return channel;
    }

    /** Null while {@code journal.enabled} is false. */
    JournalService journal() {
        return journal;
    }

    RollbackService rollback() {
        return rollback;
    }
}
