package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BaritoneDefaults;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.krekerdm.baritonebots.manager.config.SchemaField.*;

/**
 * Every field of config.json with its type, default and limits (SPEC §5.3). This class is the single source
 * of the defaults: {@link #defaults()} and {@link #itemDefaults(String)} are built from it, and behaviour /
 * client / status / Baritone defaults come from the common {@link BotConfig} records so the mod and the
 * manager cannot drift apart.
 */
public final class SettingsSchema {
    public static final List<String> ROLES = List.of("builder", "miner", "lumberjack", "farmer", "smelter",
            "crafter", "sorter", "rancher", "hauler", "guard");
    public static final List<String> SECTIONS = List.of("general", "runtime", "servers", "bots", "behaviour",
            "baritone", "client", "status", "planner");
    /** Xmx per bot for {@code runtime.memoryPreset}; {@code custom} uses {@code runtime.memoryMb}. */
    public static final Map<String, Integer> MEMORY_PRESETS = Map.of("eco", 768, "normal", 1024, "performance", 2048);

    public static final String HEADLESSMC_URL =
            "https://github.com/headlesshq/headlessmc/releases/download/{version}/headlessmc-launcher-{version}.jar";
    /** GitHub release digest of headlessmc-launcher-2.10.0.jar (checked 2026-10-02). */
    public static final String HEADLESSMC_SHA256 = "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd";
    public static final String FABRIC_INSTALLER_URL =
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar";

    private static final List<SchemaField> FIELDS = build();
    private static final Map<String, SchemaField> BY_PATH = index();

    private SettingsSchema() {
    }

    public static List<SchemaField> fields() {
        return FIELDS;
    }

    public static SchemaField field(String path) {
        return BY_PATH.get(path);
    }

    /** Schema for the panel: section order plus every field. */
    public static JsonObject toJson() {
        JsonArray fields = new JsonArray();
        FIELDS.forEach(f -> fields.add(f.toJson()));
        JsonArray sections = new JsonArray();
        SECTIONS.forEach(s -> sections.add(Json.obj("id", s, "label", "settings.section." + s)));
        return Json.obj("sections", sections, "fields", fields, "roles", Json.arrOf(ROLES),
                "memoryPresets", Json.toTree(MEMORY_PRESETS));
    }

    /** A complete config.json with every default value. */
    public static JsonObject defaults() {
        JsonObject root = new JsonObject();
        for (SchemaField f : FIELDS) {
            if (!f.isItemField() && f.def() != null) {
                put(root, f.path(), f.def().deepCopy());
            }
        }
        return root;
    }

    /** Defaults for one element of a list field, e.g. {@code itemDefaults("servers")}. */
    public static JsonObject itemDefaults(String listPath) {
        String prefix = listPath + "[].";
        JsonObject item = new JsonObject();
        for (SchemaField f : FIELDS) {
            if (f.path().startsWith(prefix) && !f.path().substring(prefix.length()).contains("[]")
                    && f.def() != null) {
                put(item, f.path().substring(prefix.length()), f.def().deepCopy());
            }
        }
        return item;
    }

    static void put(JsonObject root, String dotted, JsonElement value) {
        String[] parts = dotted.split("\\.");
        JsonObject cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonObject next = Json.getObj(cur, parts[i]);
            if (next == null) {
                next = new JsonObject();
                cur.add(parts[i], next);
            }
            cur = next;
        }
        cur.add(parts[parts.length - 1], value);
    }

    private static Map<String, SchemaField> index() {
        Map<String, SchemaField> m = new LinkedHashMap<>();
        FIELDS.forEach(f -> m.put(f.path(), f));
        return m;
    }

    // ---------------------------------------------------------------- definitions

    private static SchemaField f(String path, String type, Object def) {
        return new SchemaField(path, type, def == null ? null : Json.toTree(def), null, null, null, null, false, null);
    }

    private static SchemaField num(String path, String type, Number def, Number min, Number max, String unit) {
        return new SchemaField(path, type, def == null ? null : new JsonPrimitive(def), min, max, unit, null, false,
                null);
    }

    private static SchemaField choice(String path, String type, Object def, List<String> values) {
        return new SchemaField(path, type, def == null ? null : Json.toTree(def), null, null, null, values, false, null);
    }

    private static JsonElement at(JsonObject o, String dotted) {
        JsonElement cur = o;
        for (String part : dotted.split("\\.")) {
            cur = cur != null && cur.isJsonObject() ? cur.getAsJsonObject().get(part) : null;
        }
        return cur == null ? JsonNull.INSTANCE : cur.deepCopy();
    }

    private static List<SchemaField> build() {
        List<SchemaField> l = new ArrayList<>();
        general(l);
        runtime(l);
        servers(l);
        bots(l);
        behaviour(l);
        l.add(f("baritone", MAP, BaritoneDefaults.map()));
        JsonObject client = Json.toObject(BotConfig.ClientOpts.defaults());
        l.add(f("client.lowPower", BOOL, at(client, "lowPower")));
        l.add(f("client.skipRender", BOOL, at(client, "skipRender")));
        l.add(f("client.muteSounds", BOOL, at(client, "muteSounds")));
        l.add(num("client.maxFps", INT, client.get("maxFps").getAsInt(), 1, 260, "fps"));
        l.add(num("client.renderDistance", INT, client.get("renderDistance").getAsInt(), 2, 32, "chunks"));
        JsonObject status = Json.toObject(BotConfig.StatusOpts.defaults());
        l.add(num("status.activeIntervalTicks", INT, status.get("activeIntervalTicks").getAsInt(), 1, 1200, "ticks"));
        l.add(num("status.idleIntervalTicks", INT, status.get("idleIntervalTicks").getAsInt(), 1, 6000, "ticks"));
        planner(l);
        return List.copyOf(l);
    }

    private static void general(List<SchemaField> l) {
        l.add(choice("general.language", ENUM, "ru", List.of("ru", "en")));
        l.add(f("general.ownerPlayer", STRING, ""));
        l.add(f("general.panel.bind", STRING, "127.0.0.1").applies(MANAGER_RESTART));
        l.add(num("general.panel.port", INT, 8765, 1, 65535, null).applies(MANAGER_RESTART));
        l.add(f("general.panel.openBrowser", BOOL, true));
        l.add(f("general.link.bind", STRING, "127.0.0.1").applies(MANAGER_RESTART));
        l.add(num("general.link.port", INT, 25590, 1, 65535, null).applies(MANAGER_RESTART));
        l.add(f("general.tray", BOOL, true).applies(MANAGER_RESTART));
        l.add(f("general.autoStartBots", BOOL, false));
        l.add(num("general.eventLogLimit", INT, 500, 50, 10000, "events"));
    }

    private static void runtime(List<SchemaField> l) {
        l.add(f("runtime.minecraftVersion", STRING, "26.2").applies(REINSTALL));
        l.add(f("runtime.fabricLoader", STRING, "0.19.5").applies(REINSTALL));
        l.add(f("runtime.headlessmcVersion", STRING, "2.10.0").applies(REINSTALL));
        l.add(f("runtime.headlessmcUrl", STRING, HEADLESSMC_URL).applies(REINSTALL));
        l.add(f("runtime.headlessmcSha256", STRING, HEADLESSMC_SHA256).applies(REINSTALL));
        l.add(f("runtime.fabricInstallerUrl", STRING, FABRIC_INSTALLER_URL).applies(REINSTALL));
        l.add(f("runtime.javaPath", STRING, null).asNullable().applies(BOT_RESTART));
        JsonArray mods = new JsonArray();
        mods.add(Json.obj("id", "fabric-api", "name", "Fabric API", "modrinth", "fabric-api",
                "version", "0.161.0+26.2", "enabled", true));
        mods.add(Json.obj("id", "baritone-api", "name", "Baritone API",
                "url", "https://github.com/cabaletta/baritone/releases/download/v1.19.0/baritone-api-fabric-1.19.0.jar",
                // Matches GitHub's release digest sha256:eca6e2fd... for this asset.
                "sha512", "dfcfe2dfd3ddeae569aa1d090b3af1263c4ed2b1fcbdfeaa5ce9f821f78956326516412e38e2ca0b0daf213d50d0fa48ea89fd3a37a432cfb8b3794f1d6c5e71",
                "enabled", true));
        mods.add(Json.obj("id", "ferritecore", "name", "FerriteCore", "modrinth", "ferrite-core",
                "version", "9.0.0-fabric", "enabled", true));
        l.add(f("runtime.mods", LIST, mods).applies(REINSTALL));
        l.add(f("runtime.mods[].id", STRING, null));
        l.add(f("runtime.mods[].name", STRING, ""));
        l.add(f("runtime.mods[].url", STRING, null).asNullable());
        l.add(f("runtime.mods[].modrinth", STRING, null).asNullable());
        l.add(f("runtime.mods[].version", STRING, null).asNullable());
        l.add(f("runtime.mods[].sha512", STRING, null).asNullable());
        l.add(f("runtime.mods[].enabled", BOOL, true));
        l.add(num("runtime.startStaggerSec", INT, 20, 0, 600, "s"));
        l.add(choice("runtime.priority", ENUM, "below_normal", List.of("normal", "below_normal", "idle")));
        l.add(f("runtime.restart.enabled", BOOL, true));
        l.add(num("runtime.restart.delaySec", INT, 30, 5, 3600, "s"));
        l.add(num("runtime.restart.maxPer10Min", INT, 3, 0, 20, null));
        l.add(choice("runtime.memoryPreset", ENUM, "normal", List.of("eco", "normal", "performance", "custom"))
                .applies(BOT_RESTART));
        l.add(num("runtime.memoryMb", INT, 1024, 512, 32768, "MB").applies(BOT_RESTART));
        l.add(f("runtime.jvmArgs", STRING, "").applies(BOT_RESTART));
        l.add(num("runtime.gcThreads", INT, 2, 1, 16, null).applies(BOT_RESTART));
        l.add(f("runtime.gameDataPath", STRING, null).asNullable());
        l.add(num("runtime.maxHeavyTasks", INT, 2, 0, 64, null));
    }

    private static void servers(List<SchemaField> l) {
        JsonArray servers = new JsonArray();
        servers.add(Json.obj("id", "main", "name", "Main server", "address", "localhost:25565"));
        l.add(f("servers", LIST, servers));
        l.add(f("servers[].id", STRING, null));
        l.add(f("servers[].name", STRING, ""));
        l.add(f("servers[].address", STRING, "localhost:25565").applies(BOT_RESTART));
        BotConfig.Server server = BotConfig.Server.defaults();
        l.add(f("servers[].autoConnect", BOOL, server.autoConnect()));
        l.add(f("servers[].reconnect.enabled", BOOL, server.reconnect().enabled()));
        l.add(num("servers[].reconnect.delaySec", INT, server.reconnect().delaySec(), 1, 3600, "s"));
        l.add(num("servers[].reconnect.maxDelaySec", INT, server.reconnect().maxDelaySec(), 1, 86400, "s"));
        JsonObject login = Json.toObject(BotConfig.Login.defaults());
        l.add(choice("servers[].login.mode", ENUM, at(login, "mode"), List.of("none", "auto")));
        l.add(f("servers[].login.loginCommand", STRING, at(login, "loginCommand")));
        l.add(f("servers[].login.registerCommand", STRING, at(login, "registerCommand")));
        l.add(f("servers[].login.loginPatterns", STRING_LIST, at(login, "loginPatterns")));
        l.add(f("servers[].login.registerPatterns", STRING_LIST, at(login, "registerPatterns")));
        l.add(f("servers[].login.successPatterns", STRING_LIST, at(login, "successPatterns")));
        l.add(f("servers[].login.failurePatterns", STRING_LIST, at(login, "failurePatterns")));
        l.add(num("servers[].login.delayMs", INT, login.get("delayMs").getAsInt(), 0, 60000, "ms"));
        l.add(f("servers[].login.joinCommands", STRING_LIST, new JsonArray()));
        l.add(f("servers[].companion.enabled", BOOL, false));
        l.add(f("servers[].companion.token", SECRET, ""));
        JsonObject protection = Json.toObject(BotConfig.Protection.defaults());
        l.add(f("servers[].protection.enabled", BOOL, at(protection, "enabled")));
        l.add(f("servers[].protection.noBreak", STRING_LIST, at(protection, "noBreak")));
        l.add(f("servers[].protection.zones", JSON, new JsonArray()));
        l.add(f("servers[].baritone", MAP, new JsonObject()));
        l.add(choice("servers[].antiXray", ENUM, "none", List.of("none", "hide", "fake")));
    }

    private static void bots(List<SchemaField> l) {
        l.add(f("bots", LIST, new JsonArray()));
        l.add(f("bots[].id", STRING, null));
        l.add(f("bots[].username", STRING, null));
        l.add(choice("bots[].account.type", ENUM, "offline", List.of("offline", "microsoft")).applies(BOT_RESTART));
        l.add(f("bots[].serverId", STRING, null));
        l.add(f("bots[].enabled", BOOL, true));
        l.add(f("bots[].autoStart", BOOL, true));
        l.add(num("bots[].memoryMb", INT, null, 512, 32768, "MB").asNullable().applies(BOT_RESTART));
        l.add(f("bots[].jvmArgs", STRING, null).asNullable().applies(BOT_RESTART));
        l.add(f("bots[].homeWaypoint", STRING, null).asNullable());
        l.add(choice("bots[].roles", ENUM_LIST, new JsonArray(), ROLES));
        l.add(f("bots[].behaviour", JSON, new JsonObject()));
        l.add(f("bots[].baritone", MAP, new JsonObject()));
    }

    private static void behaviour(List<SchemaField> l) {
        JsonObject b = Json.toObject(BotConfig.Behaviour.defaults());
        l.add(f("behaviour.autoRespawn", BOOL, at(b, "autoRespawn")));
        l.add(f("behaviour.autoEat.enabled", BOOL, at(b, "autoEat.enabled")));
        l.add(num("behaviour.autoEat.belowFood", INT, at(b, "autoEat.belowFood").getAsInt(), 0, 20, "food"));
        l.add(num("behaviour.autoEat.belowHealth", DOUBLE, at(b, "autoEat.belowHealth").getAsDouble(), 0, 20, "hp"));
        l.add(f("behaviour.autoEat.avoid", STRING_LIST, at(b, "autoEat.avoid")));
        l.add(choice("behaviour.defense.mode", ENUM, at(b, "defense.mode"), List.of("fight", "flee", "ignore")));
        l.add(num("behaviour.defense.radius", INT, at(b, "defense.radius").getAsInt(), 1, 32, "blocks"));
        l.add(num("behaviour.defense.fleeBelowHealth", DOUBLE, at(b, "defense.fleeBelowHealth").getAsDouble(), 0, 20,
                "hp"));
        l.add(f("behaviour.defense.avoidEntities", STRING_LIST, at(b, "defense.avoidEntities")));
        l.add(f("behaviour.defense.avoidNamePatterns", STRING_LIST, at(b, "defense.avoidNamePatterns")));
        l.add(f("behaviour.defense.retaliatePlayers", BOOL, at(b, "defense.retaliatePlayers")));
        l.add(f("behaviour.deathRecovery.enabled", BOOL, at(b, "deathRecovery.enabled")));
        l.add(num("behaviour.deathRecovery.maxDistance", INT, at(b, "deathRecovery.maxDistance").getAsInt(), 0,
                100000, "blocks"));
        l.add(num("behaviour.deathRecovery.timeoutSec", INT, at(b, "deathRecovery.timeoutSec").getAsInt(), 10, 3600,
                "s"));
        l.add(num("behaviour.inventoryFullFreeSlots", INT, at(b, "inventoryFullFreeSlots").getAsInt(), 0, 36, "slots"));
        l.add(num("behaviour.lowToolDurability", DOUBLE, at(b, "lowToolDurability").getAsDouble(), 0, 1, null));
        l.add(num("behaviour.pickupRadius", INT, at(b, "pickupRadius").getAsInt(), 0, 32, "blocks"));
    }

    private static void planner(List<SchemaField> l) {
        l.add(num("planner.tickSec", INT, 5, 1, 300, "s"));
        l.add(num("planner.roleSwitchCooldownSec", INT, 120, 0, 3600, "s"));
        l.add(num("planner.maxBuildersPerSector", INT, 1, 1, 16, null));
        l.add(num("planner.restockFreeSlotsTarget", INT, 4, 0, 36, "slots"));
        l.add(f("planner.autoDepositWhenFull", BOOL, true));
        l.add(f("planner.depositKeep", STRING_LIST, List.of(
                "minecraft:*_pickaxe", "minecraft:*_axe", "minecraft:*_shovel", "minecraft:*_hoe",
                "minecraft:*_sword", "minecraft:shears", "minecraft:bow", "minecraft:crossbow", "minecraft:arrow",
                "minecraft:shield", "minecraft:*_helmet", "minecraft:*_chestplate", "minecraft:*_leggings",
                "minecraft:*_boots", "minecraft:bread", "minecraft:cooked_*", "minecraft:baked_potato",
                "minecraft:golden_carrot", "minecraft:carrot", "minecraft:apple", "minecraft:torch",
                "minecraft:water_bucket")));
    }
}
