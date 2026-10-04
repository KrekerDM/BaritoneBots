package io.github.krekerdm.baritonebots.plugin;

import io.github.krekerdm.baritonebots.plugin.journal.JournalRecord;
import io.github.krekerdm.baritonebots.plugin.journal.JournalService;
import io.github.krekerdm.baritonebots.plugin.journal.JournalStore;
import io.github.krekerdm.baritonebots.plugin.journal.JournalSummary;
import io.github.krekerdm.baritonebots.plugin.journal.TimeWindow;
import io.github.krekerdm.baritonebots.plugin.rollback.RollbackService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /baritonebots status | reload | rollback <bot> <minutes> | journal <bot> <minutes> | token}. */
final class AdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "rollback", "journal", "token");
    private static final List<String> MINUTE_HINTS = List.of("10", "30", "60", "180", "1440");
    private static final int JOURNAL_LINES = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final BaritoneBotsPlugin plugin;

    AdminCommand(BaritoneBotsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages m = plugin.messages();
        if (!sender.hasPermission(BaritoneBotsPlugin.ADMIN_PERMISSION)) {
            sender.sendMessage(m.chat("no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "rollback" -> rollback(sender, label, args);
            case "journal" -> journal(sender, label, args);
            case "token" -> token(sender);
            default -> sender.sendMessage(m.chat("usage", Messages.arg("label", label)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(BaritoneBotsPlugin.ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("rollback") && !sub.equals("journal")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(plugin.settings().bots(), args[1]);
        }
        if (args.length == 3) {
            return filter(MINUTE_HINTS, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ status

    private void status(CommandSender sender) {
        Messages m = plugin.messages();
        PluginSettings s = plugin.settings();
        BotRegistry reg = plugin.registry();
        sender.sendMessage(m.chat("status.header", Messages.arg("version", plugin.getPluginMeta().getVersion()),
                Messages.arg("language", s.language())));
        sender.sendMessage(m.component(s.token().isEmpty() ? "status.token-missing" : "status.token-set"));

        if (s.bots().isEmpty()) {
            sender.sendMessage(m.component("status.bots-none"));
        }
        long now = System.currentTimeMillis();
        for (String name : s.bots()) {
            BotRegistry.Session session = reg.byName(name);
            Player online = plugin.getServer().getPlayerExact(name);
            if (session != null) {
                sender.sendMessage(m.component("status.bot-verified", Messages.arg("name", session.name()),
                        Messages.arg("id", session.botId().isEmpty() ? "-" : session.botId()),
                        Messages.arg("mod", session.modVersion().isEmpty() ? "-" : session.modVersion()),
                        Messages.arg("minutes", (now - session.verifiedAt()) / TimeWindow.MINUTE_MS)));
            } else if (online != null) {
                sender.sendMessage(m.component("status.bot-online", Messages.arg("name", online.getName())));
            } else {
                sender.sendMessage(m.component("status.bot-offline", Messages.arg("name", name)));
            }
        }

        sender.sendMessage(s.protectNames()
                ? m.component("status.protect-on", Messages.arg("rules", s.allowedAddresses().toString()))
                : m.component("status.protect-off"));
        sender.sendMessage(s.distances().enabled()
                ? m.component("status.distances-on", Messages.arg("value", s.distances().describe()))
                : m.component("status.distances-off"));
        sender.sendMessage(s.permissions().isEmpty()
                ? m.component("status.permissions-none")
                : m.component("status.permissions", Messages.arg("value", String.join(", ", s.permissions()))));

        if (!s.forceLogin().enabled()) {
            sender.sendMessage(m.component("status.login-off"));
        } else {
            String auth = plugin.forceLogin().describe();
            sender.sendMessage(auth == null ? m.component("status.login-none")
                    : m.component("status.login-on", Messages.arg("value", auth)));
        }

        JournalService journal = plugin.journal();
        if (journal == null) {
            sender.sendMessage(m.component("status.journal-off"));
        } else {
            JournalStore store = journal.store();
            sender.sendMessage(m.component("status.journal-on", Messages.arg("days", s.journal().retentionDays()),
                    Messages.arg("memory", journal.memoryRecords()), Messages.arg("queued", store.queued()),
                    Messages.arg("written", store.written())));
            if (store.lastError() != null) {
                sender.sendMessage(m.component("status.journal-error", Messages.arg("error", store.lastError())));
            }
        }

        RollbackService rollback = plugin.rollback();
        if (!RollbackService.available(s, journal)) {
            sender.sendMessage(m.component("status.rollback-unavailable", Messages.arg("mode", s.rollback().mode())));
        } else if (s.rollback().commandMode()) {
            sender.sendMessage(m.component("status.rollback-command", Messages.arg("command", s.rollback().command())));
        } else {
            sender.sendMessage(m.component("status.rollback-journal",
                    Messages.arg("per_tick", s.rollback().blocksPerTick()),
                    Messages.arg("max", s.rollback().maxBlocks())));
        }
        String running = rollback.describeRunning();
        if (running != null) {
            sender.sendMessage(m.component("status.rollback-running", Messages.arg("value", running)));
        }
    }

    // ------------------------------------------------------------------ reload / token

    private void reload(CommandSender sender) {
        Messages before = plugin.messages();
        try {
            int warnings = plugin.reload();
            sender.sendMessage(plugin.messages().chat("reload.done", Messages.arg("warnings", warnings)));
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Reload failed", e);
            sender.sendMessage(before.chat("reload.failed", Messages.arg("error", String.valueOf(e.getMessage()))));
        }
    }

    private void token(CommandSender sender) {
        Messages m = plugin.messages();
        String token = plugin.settings().token();
        if (token.isEmpty()) {
            sender.sendMessage(m.chat("token.missing"));
            return;
        }
        Component value = Component.text(token, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(token))
                .hoverEvent(HoverEvent.showText(m.component("token.hover")));
        sender.sendMessage(m.chat("token.show", Messages.arg("token", value)));
    }

    // ------------------------------------------------------------------ journal / rollback

    /** @return the canonical bot name and minutes, or null after telling the sender what is wrong */
    private Target target(CommandSender sender, String label, String[] args, int maxMinutes) {
        Messages m = plugin.messages();
        if (args.length != 3) {
            sender.sendMessage(m.chat("usage", Messages.arg("label", label)));
            return null;
        }
        String bot = plugin.settings().botName(args[1]);
        if (bot == null) {
            sender.sendMessage(m.chat("unknown-bot", Messages.arg("bot", args[1])));
            return null;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(args[2].trim());
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        if (!TimeWindow.validMinutes(minutes, maxMinutes)) {
            sender.sendMessage(m.chat("bad-minutes", Messages.arg("max", maxMinutes)));
            return null;
        }
        return new Target(bot, minutes);
    }

    private record Target(String bot, int minutes) {
    }

    private void journal(CommandSender sender, String label, String[] args) {
        Messages m = plugin.messages();
        JournalService journal = plugin.journal();
        if (journal == null) {
            sender.sendMessage(m.chat("journal.disabled"));
            return;
        }
        Target t = target(sender, label, args, TimeWindow.maxJournalMinutes(plugin.settings().journal().retentionDays()));
        if (t == null) {
            return;
        }
        long since = TimeWindow.since(journal.now(), t.minutes());
        journal.summary(t.bot(), since, JOURNAL_LINES).whenComplete((sum, error) -> {
            if (!reachable(sender)) {
                return;
            }
            Messages now = plugin.messages();
            if (error != null) {
                sender.sendMessage(now.chat("journal.error", Messages.arg("error", String.valueOf(error.getMessage()))));
                return;
            }
            sender.sendMessage(now.chat("journal.result", Messages.arg("bot", t.bot()),
                    Messages.arg("minutes", t.minutes()), Messages.arg("breaks", sum.breaks()),
                    Messages.arg("places", sum.places())));
            printLatest(sender, now, sum);
        });
    }

    private static void printLatest(CommandSender sender, Messages m, JournalSummary sum) {
        ZoneId zone = ZoneId.systemDefault();
        for (JournalRecord r : sum.latest()) {
            boolean placed = r.action() == JournalRecord.Action.PLACE;
            sender.sendMessage(m.component("journal.entry",
                    Messages.arg("time", TIME.format(Instant.ofEpochMilli(r.time()).atZone(zone))),
                    Messages.arg("action", m.component(placed ? "journal.action-place" : "journal.action-break")),
                    Messages.arg("block", shortBlock(placed ? r.after() : r.before())),
                    Messages.arg("world", r.world()), Messages.arg("x", r.x()), Messages.arg("y", r.y()),
                    Messages.arg("z", r.z())));
        }
    }

    /** {@code minecraft:oak_stairs[facing=east,...]} → {@code oak_stairs}. */
    static String shortBlock(String data) {
        String s = data;
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            s = s.substring(0, bracket);
        }
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private void rollback(CommandSender sender, String label, String[] args) {
        PluginSettings s = plugin.settings();
        Target t = target(sender, label, args, RollbackService.maxMinutes(s));
        if (t == null) {
            return;
        }
        Messages m = plugin.messages();
        sender.sendMessage(m.chat("rollback.started", Messages.arg("bot", t.bot()), Messages.arg("minutes", t.minutes())));
        plugin.getLogger().info(sender.getName() + " started a rollback of " + t.bot() + ", " + t.minutes() + " min");
        plugin.rollback().request(t.bot(), t.minutes(), s, plugin.journal(), r -> {
            BaritoneBotsPlugin.Line line = plugin.rollbackLine(t.bot(), r);
            if (sender instanceof Player) {
                plugin.getLogger().info(plugin.messages().plain(line.key(), line.args()));
            }
            if (reachable(sender)) {
                sender.sendMessage(plugin.messages().chat(line.key(), line.args()));
            }
        });
    }

    /** A player who left while a request ran gets nothing; the console always does. */
    private static boolean reachable(CommandSender sender) {
        return !(sender instanceof Player p) || p.isOnline();
    }
}
