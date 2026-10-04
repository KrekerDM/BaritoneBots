package io.github.krekerdm.baritonebots.manager.config;

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
                            PlannerCfg planner) {

    public ManagerConfig {
        servers = servers == null ? List.of() : List.copyOf(servers);
        bots = bots == null ? List.of() : List.copyOf(bots);
        behaviour = orEmpty(behaviour);
        baritone = orEmpty(baritone);
        client = orEmpty(client);
        status = orEmpty(status);
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

    public record General(String language, String ownerPlayer, Panel panel, LinkCfg link, boolean tray,
                          boolean autoStartBots, int eventLogLimit) {
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
                                String antiXray) {
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
        }
    }

    public record CompanionCfg(boolean enabled) {
    }

    public record BotDef(String id, String username, Account account, String serverId, boolean enabled,
                         boolean autoStart, Integer memoryMb, String jvmArgs, String homeWaypoint, List<String> roles,
                         JsonObject behaviour, JsonObject baritone) {
        public BotDef {
            account = account == null ? new Account(Account.OFFLINE) : account;
            roles = roles == null ? List.of() : List.copyOf(roles);
            behaviour = orEmpty(behaviour);
            baritone = orEmpty(baritone);
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

    /** Effective Xmx of a bot in MB. */
    public int memoryMbFor(BotDef bot) {
        return bot.memoryMb() != null ? bot.memoryMb() : runtime.presetMemoryMb();
    }
}
