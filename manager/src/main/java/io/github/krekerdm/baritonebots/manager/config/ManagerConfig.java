package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.List;
import java.util.Optional;

/**
 * Typed, immutable view of a validated config.json (SPEC §5.3). Rebuilt by {@link ConfigStore} on every change;
 * pass it around freely. Free-form sections ({@code behaviour}, {@code baritone}, {@code client}, {@code status},
 * per-server/per-bot overrides) stay JSON because they are merged into {@code BotConfig} as JSON.
 */
public record ManagerConfig(General general, RuntimeCfg runtime, List<ServerProfile> servers, List<BotDef> bots,
                            JsonObject behaviour, JsonObject baritone, JsonObject client, JsonObject status,
                            PlannerCfg planner, AutopilotCfg autopilot, List<OrderDef> orders,
                            List<ScheduleDef> schedules, List<RuleDef> rules, JsonObject keepProfiles) {

    public ManagerConfig {
        servers = servers == null ? List.of() : List.copyOf(servers);
        bots = bots == null ? List.of() : List.copyOf(bots);
        autopilot = autopilot == null ? AutopilotCfg.defaults() : autopilot;
        orders = orders == null ? List.of() : List.copyOf(orders);
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
        rules = rules == null ? List.of() : List.copyOf(rules);
        behaviour = orEmpty(behaviour);
        baritone = orEmpty(baritone);
        client = orEmpty(client);
        status = orEmpty(status);
        keepProfiles = orEmpty(keepProfiles);
    }

    /**
     * A keep profile by name (case-insensitive); null / blank = the default «снаряжение», else the first one.
     * Null when there is no profile at all or the name is unknown.
     */
    public JsonObject keepProfile(String name) {
        String want = name == null || name.isBlank() ? SettingsSchema.DEFAULT_KEEP_PROFILE : name.trim();
        for (var e : keepProfiles.entrySet()) {
            if (e.getKey().equalsIgnoreCase(want) && e.getValue().isJsonObject()) {
                return e.getValue().getAsJsonObject();
            }
        }
        if (name == null || name.isBlank()) {
            for (var e : keepProfiles.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    return e.getValue().getAsJsonObject();
                }
            }
        }
        return null;
    }

    static JsonObject orEmpty(JsonObject o) {
        return o == null ? new JsonObject() : o;
    }

    public static ManagerConfig fromJson(JsonObject validated) {
        return Json.fromJson(validated, ManagerConfig.class);
    }

    public Optional<ServerProfile> server(String id) {
        return servers.stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst();
    }

    public Optional<BotDef> bot(String id) {
        return bots.stream().filter(b -> b.id().equalsIgnoreCase(id)).findFirst();
    }

    /**
     * {@code ownerPlayer} + {@code commandPrefix} + {@code ownerChatPatterns} go to the bots as {@code BotConfig.owner};
     * {@code ownerReplyCommand} is the whisper template for replies ({@code {player}}, {@code {text}}).
     */
    public record General(String language, String ownerPlayer, String commandPrefix, List<String> ownerChatPatterns,
                          String ownerReplyCommand, Panel panel, LinkCfg link, boolean tray, boolean autoStartBots,
                          int eventLogLimit) {
        public General {
            ownerChatPatterns = ownerChatPatterns == null ? List.of() : List.copyOf(ownerChatPatterns);
        }

        public String commandPrefixOrDefault() {
            return commandPrefix == null || commandPrefix.isBlank() ? "!b" : commandPrefix.trim();
        }

        public String replyCommandOrDefault() {
            return ownerReplyCommand == null || ownerReplyCommand.isBlank() ? "/msg {player} {text}" : ownerReplyCommand;
        }

        /** The owner's name, or null when none is configured. */
        public String ownerOrNull() {
            return ownerPlayer == null || ownerPlayer.isBlank() ? null : ownerPlayer.trim();
        }
    }

    public record Panel(String bind, int port, boolean openBrowser) {
    }

    public record LinkCfg(String bind, int port) {
    }

    public record RuntimeCfg(String minecraftVersion, String fabricLoader, String headlessmcVersion,
                             String headlessmcUrl, String headlessmcSha256, String fabricInstallerUrl,
                             String javaPath, List<ModEntry> mods, int startStaggerSec, String priority,
                             Restart restart, String memoryPreset, int memoryMb, String jvmArgs, int gcThreads,
                             int maxHeavyTasks, String gameDataPath) {
        public RuntimeCfg {
            mods = mods == null ? List.of() : List.copyOf(mods);
            jvmArgs = jvmArgs == null ? "" : jvmArgs;
        }

        /** Xmx for bots without their own {@code memoryMb}. */
        public int presetMemoryMb() {
            Integer preset = SettingsSchema.MEMORY_PRESETS.get(memoryPreset);
            return preset != null ? preset : memoryMb;
        }

        public String headlessmcDownloadUrl() {
            return headlessmcUrl.replace("{version}", headlessmcVersion);
        }

        public String headlessmcJarName() {
            return "headlessmc-launcher-" + headlessmcVersion + ".jar";
        }

        /** Explicit client jar / data folder for game data, or null to look under runtime/mc/versions. */
        public String gameDataPathOrNull() {
            return gameDataPath == null || gameDataPath.isBlank() ? null : gameDataPath.trim();
        }

        public String javaPathOrNull() {
            return javaPath == null || javaPath.isBlank() ? null : javaPath.trim();
        }
    }

    /**
     * A mod jar put into every bot's mods folder. Either {@code url} (direct download, optional {@code sha512}) or
     * {@code modrinth} slug + {@code version} number (resolved through the Modrinth API, sha512 from the API).
     */
    public record ModEntry(String id, String name, String url, String modrinth, String version, String sha512,
                           boolean enabled) {
    }

    public record Restart(boolean enabled, int delaySec, int maxPer10Min) {
    }

    /** Server profile; the companion token lives in secrets.json, not here. */
    public record ServerProfile(String id, String name, String address, boolean autoConnect, JsonObject reconnect,
                                JsonObject login, CompanionCfg companion, JsonObject protection, JsonObject baritone,
                                String antiXray, String dayStart, String nightStart) {
        /** {@code antiXray} values (SPEC §5.7b3): Paper anti-xray off, engine-mode 1 (hide), engine-mode 2 (fake ores). */
        public static final String ANTI_XRAY_NONE = "none";
        public static final String ANTI_XRAY_HIDE = "hide";
        public static final String ANTI_XRAY_FAKE = "fake";

        public ServerProfile {
            reconnect = orEmpty(reconnect);
            login = orEmpty(login);
            companion = companion == null ? new CompanionCfg(false) : companion;
            protection = orEmpty(protection);
            baritone = orEmpty(baritone);
            antiXray = antiXray == null || antiXray.isBlank() ? ANTI_XRAY_NONE : antiXray;
            dayStart = dayStart == null || dayStart.isBlank() ? "07:00" : dayStart;
            nightStart = nightStart == null || nightStart.isBlank() ? "22:00" : nightStart;
        }
    }

    public record CompanionCfg(boolean enabled) {
    }

    public record BotDef(String id, String username, Account account, String serverId, boolean enabled,
                         boolean autoStart, Integer memoryMb, String jvmArgs, String homeWaypoint, List<String> roles,
                         JsonObject behaviour, JsonObject baritone, JsonObject autopilot) {
        public BotDef {
            account = account == null ? new Account(Account.OFFLINE) : account;
            roles = roles == null ? List.of() : List.copyOf(roles);
            behaviour = orEmpty(behaviour);
            baritone = orEmpty(baritone);
            autopilot = orEmpty(autopilot);
        }

        public boolean microsoft() {
            return Account.MICROSOFT.equals(account.type());
        }

        /** Name of this bot's home waypoint ({@code home} when unset). */
        public String homeWaypointName() {
            return homeWaypoint == null || homeWaypoint.isBlank() ? "home" : homeWaypoint;
        }
    }

    public record Account(String type) {
        public static final String OFFLINE = "offline";
        public static final String MICROSOFT = "microsoft";
    }

    public record PlannerCfg(int tickSec, int roleSwitchCooldownSec, int maxBuildersPerSector,
                             int restockFreeSlotsTarget, boolean autoDepositWhenFull, List<String> depositKeep) {
        public PlannerCfg {
            depositKeep = depositKeep == null ? List.of() : List.copyOf(depositKeep);
        }
    }

    /**
     * Autopilot settings (SPEC §5.7a): auto-supply, auto-sort, idle work, container discovery, stuck recovery.
     * {@link #forBot} applies a bot's {@code bots[].autopilot} override.
     */
    public record AutopilotCfg(boolean supply, boolean sort, boolean idleWork, boolean discovery, int discoveryRadius,
                               int discoveryIntervalSec, int inspectMaxAgeMin, int maxInspectPerTick, int homeRadius,
                               boolean useFound, int foodMin, int blocksMin, double toolMinDurability, int stuckSec,
                               int idleHomeSec, List<String> throwaway, List<String> smeltInputs,
                               List<Category> categories, java.util.Map<String, String> signWords, AutoTrash autoTrash) {
        /** Keys a bot may override in {@code bots[].autopilot}. */
        public static final List<String> BOT_KEYS = List.of("supply", "sort", "idleWork", "discovery", "foodMin",
                "blocksMin", "toolMinDurability", "stuckSec");
        private static AutopilotCfg defaults;

        public AutopilotCfg {
            throwaway = throwaway == null ? List.of() : List.copyOf(throwaway);
            smeltInputs = smeltInputs == null ? List.of() : List.copyOf(smeltInputs);
            categories = categories == null ? List.of() : List.copyOf(categories);
            signWords = signWords == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(signWords));
            autoTrash = autoTrash == null ? new AutoTrash(false, List.of(), java.util.Map.of()) : autoTrash;
        }

        /** The schema defaults. */
        public static synchronized AutopilotCfg defaults() {
            if (defaults == null) {
                defaults = Json.fromJson(Json.getObj(SettingsSchema.defaults(), "autopilot"), AutopilotCfg.class);
            }
            return defaults;
        }

        /** This config with the bot's override applied (unknown keys and wrong types are ignored). */
        public AutopilotCfg forBot(BotDef bot) {
            JsonObject o = bot == null ? null : bot.autopilot();
            if (o == null || o.isEmpty()) {
                return this;
            }
            return new AutopilotCfg(Json.getBool(o, "supply", supply), Json.getBool(o, "sort", sort),
                    Json.getBool(o, "idleWork", idleWork), Json.getBool(o, "discovery", discovery), discoveryRadius,
                    discoveryIntervalSec, inspectMaxAgeMin, maxInspectPerTick, homeRadius, useFound,
                    Math.max(0, Json.getInt(o, "foodMin", foodMin)), Math.max(0, Json.getInt(o, "blocksMin", blocksMin)),
                    Math.max(0, Math.min(1, Json.getDouble(o, "toolMinDurability", toolMinDurability))),
                    Math.max(0, Json.getInt(o, "stuckSec", stuckSec)), idleHomeSec, throwaway, smeltInputs, categories,
                    signWords, autoTrash);
        }
    }

    /**
     * {@code autopilot.autoTrash} (SPEC §5.7f): when the inventory fills up during mining / clearing, items matching
     * {@code junk} are thrown on the spot, except {@code keepCounts} of them ({@code glob → count}).
     */
    public record AutoTrash(boolean enabled, List<String> junk, java.util.Map<String, Integer> keepCounts) {
        public AutoTrash {
            junk = junk == null ? List.of() : List.copyOf(junk);
            keepCounts = keepCounts == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(keepCounts));
        }
    }

    /** A sorting category: item globs and {@code #tag} entries (item tags from game data). */
    public record Category(String name, List<String> globs) {
        public Category {
            globs = globs == null ? List.of() : List.copyOf(globs);
        }
    }

    /**
     * A standing order (SPEC §5.7b): keep {@code item} between {@code min} and {@code max} in the {@code into}
     * containers (a container role such as {@code storage}, {@code supply}, {@code sorted:food}, or a container id).
     */
    public record OrderDef(String id, boolean enabled, String serverId, String item, int min, Integer max, String into) {
        /** Stock the order fills up to once it dropped below {@code min}. */
        public int target() {
            return max != null && max > min ? max : min;
        }
    }

    /**
     * A schedule (SPEC §5.7b): {@code when} = 5-field cron, {@code day} or {@code night}; {@code botIds} = list of bot
     * ids, {@code "any"} (one free bot) or {@code "all"}; {@code steps} = TaskTemplates / manager steps.
     */
    public record ScheduleDef(String id, String name, boolean enabled, String serverId, String when, JsonElement botIds,
                              JsonElement steps, String priority) {
        public boolean high() {
            return "high".equals(priority);
        }
    }

    /**
     * A rule (SPEC §5.7b): {@code if} = one trigger object, {@code then} = steps, {@code cooldownSec} between firings
     * (per rule and target bot).
     */
    public record RuleDef(String id, String name, boolean enabled, String serverId,
                          @com.google.gson.annotations.SerializedName("if") JsonObject condition, JsonElement then,
                          JsonElement botIds, int cooldownSec, String priority) {
        public RuleDef {
            condition = orEmpty(condition);
        }

        public boolean high() {
            return "high".equals(priority);
        }
    }

    /** Effective Xmx of a bot in MB. */
    public int memoryMbFor(BotDef bot) {
        return bot.memoryMb() != null ? bot.memoryMb() : runtime.presetMemoryMb();
    }
}
