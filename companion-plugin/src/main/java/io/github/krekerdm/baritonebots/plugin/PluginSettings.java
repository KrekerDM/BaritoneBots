package io.github.krekerdm.baritonebots.plugin;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of {@code config.yml}. A new snapshot replaces the old one on {@code /baritonebots reload};
 * the async pre-login listener reads it through a volatile field, so it must stay immutable.
 */
public record PluginSettings(
        String language,
        String token,
        String serverName,
        List<String> bots,
        boolean protectNames,
        AddressRules allowedAddresses,
        Distances distances,
        List<String> permissions,
        ForceLogin forceLogin,
        Journal journal,
        Rollback rollback,
        boolean debug) {

    public static final List<String> LANGUAGES = List.of("en", "ru");
    /** Vanilla player-name rule; also keeps {@code {player}} in rollback commands free of injection. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern NODE = Pattern.compile("-?[A-Za-z0-9_.*\\-]+");

    public PluginSettings {
        bots = List.copyOf(bots);
        permissions = List.copyOf(permissions);
    }

    /**
     * @param view       client view distance (2..32) or -1 to keep the server value
     * @param simulation simulation distance (2..32) or -1
     * @param send       send distance (2..32) or -1
     */
    public record Distances(boolean enabled, int view, int simulation, int send, boolean affectsSpawning) {
        static int sanitize(String key, int value, List<String> warnings) {
            if (value == -1) {
                return -1;
            }
            int clamped = Math.max(2, Math.min(32, value));
            if (clamped != value) {
                warnings.add("distances." + key + " = " + value + " is outside 2..32 (or -1); using " + clamped);
            }
            return clamped;
        }

        public String describe() {
            return "view " + show(view) + ", simulation " + show(simulation) + ", send " + show(send)
                    + ", affects-spawning " + affectsSpawning;
        }

        private static String show(int v) {
            return v == -1 ? "server" : Integer.toString(v);
        }
    }

    public record ForceLogin(boolean enabled, int delayTicks) {
    }

    public record Journal(boolean enabled, int retentionDays, int memoryLimit) {
    }

    public record Rollback(String mode, String command, int blocksPerTick, int maxBlocks) {
        public static final String MODE_JOURNAL = "journal";
        public static final String MODE_COMMAND = "command";

        public boolean commandMode() {
            return MODE_COMMAND.equals(mode);
        }
    }

    /** Canonical spelling of a configured bot name, or null. */
    public String botName(String name) {
        if (name == null) {
            return null;
        }
        for (String b : bots) {
            if (b.equalsIgnoreCase(name)) {
                return b;
            }
        }
        return null;
    }

    /** Reads and validates; every problem becomes a line in {@code warnings} and a safe default. */
    public static PluginSettings read(ConfigurationSection c, List<String> warnings) {
        String language = c.getString("language", "en").trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(language)) {
            warnings.add("language '" + language + "' is not one of " + LANGUAGES + "; using en");
            language = "en";
        }

        Map<String, String> bots = new LinkedHashMap<>();
        for (String raw : c.getStringList("bots")) {
            String name = raw == null ? "" : raw.trim();
            if (!NAME.matcher(name).matches()) {
                warnings.add("bots entry '" + raw + "' is not a valid player name (A-Z, a-z, 0-9, _; 1-16); ignored");
                continue;
            }
            bots.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }

        boolean protect = c.getBoolean("protect-names.enabled", false);
        AddressRules rules = AddressRules.parse(c.getStringList("protect-names.allowed-ips"), warnings);
        if (protect && rules.isEmpty()) {
            warnings.add("protect-names is enabled but allowed-ips is empty; name protection is OFF until an address is added");
            protect = false;
        }

        boolean distEnabled = c.getBoolean("distances.enabled", true);
        Distances distances = new Distances(distEnabled,
                Distances.sanitize("view", c.getInt("distances.view", 4), warnings),
                Distances.sanitize("simulation", c.getInt("distances.simulation", 3), warnings),
                Distances.sanitize("send", c.getInt("distances.send", 4), warnings),
                c.getBoolean("distances.affects-spawning", false));

        List<String> permissions = new ArrayList<>();
        for (String raw : c.getStringList("permissions")) {
            String node = raw == null ? "" : raw.trim();
            if (node.isEmpty()) {
                continue;
            }
            if (!NODE.matcher(node).matches()) {
                warnings.add("permissions entry '" + raw + "' is not a permission node; ignored");
                continue;
            }
            permissions.add(node);
        }

        ForceLogin forceLogin = new ForceLogin(c.getBoolean("force-login.enabled", true),
                clamp("force-login.delay-ticks", c.getInt("force-login.delay-ticks", 10), 0, 1200, warnings));

        Journal journal = new Journal(c.getBoolean("journal.enabled", true),
                clamp("journal.retention-days", c.getInt("journal.retention-days", 14), 1, 3650, warnings),
                clamp("journal.memory-limit", c.getInt("journal.memory-limit", 100_000), 1000, 5_000_000, warnings));

        String mode = c.getString("rollback.mode", Rollback.MODE_JOURNAL).trim().toLowerCase(Locale.ROOT);
        if (!mode.equals(Rollback.MODE_JOURNAL) && !mode.equals(Rollback.MODE_COMMAND)) {
            warnings.add("rollback.mode '" + mode + "' is not journal or command; using journal");
            mode = Rollback.MODE_JOURNAL;
        }
        String command = c.getString("rollback.command", "").trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (mode.equals(Rollback.MODE_COMMAND) && command.isEmpty()) {
            warnings.add("rollback.mode is command but rollback.command is empty; rollback is unavailable");
        }
        if (mode.equals(Rollback.MODE_JOURNAL) && !journal.enabled()) {
            warnings.add("rollback.mode is journal but journal.enabled is false; rollback is unavailable");
        }
        Rollback rollback = new Rollback(mode, command,
                clamp("rollback.blocks-per-tick", c.getInt("rollback.blocks-per-tick", 500), 1, 20_000, warnings),
                clamp("rollback.max-blocks", c.getInt("rollback.max-blocks", 200_000), 1, 5_000_000, warnings));

        return new PluginSettings(language,
                c.getString("token", "").trim(),
                c.getString("server-name", "").trim(),
                new ArrayList<>(bots.values()),
                protect, rules, distances, permissions, forceLogin, journal, rollback,
                c.getBoolean("debug", false));
    }

    private static int clamp(String key, int value, int min, int max, List<String> warnings) {
        int v = Math.max(min, Math.min(max, value));
        if (v != value) {
            warnings.add(key + " = " + value + " is outside " + min + ".." + max + "; using " + v);
        }
        return v;
    }
}
