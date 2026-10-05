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
            "baritone", "client", "status", "planner", "autopilot", "keepProfiles", "orders", "schedules", "rules", "ai");
    /** {@code priority} of schedules and rules: normal = queued behind planner work, high = replaces it (§5.7b). */
    public static final List<String> STEP_PRIORITIES = List.of("normal", "high");
    /** {@code ai.mode}: supervisor actions wait for a click, or apply at once (SPEC §5.7d). */
    public static final List<String> AI_MODES = List.of("suggest", "auto");
    /** Container roles a standing order may deliver into (besides {@code sorted:<category>} and container ids). */
    public static final List<String> ORDER_ROLES = List.of("storage", "supply", "kit", "fuel", "inbox");
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
        autopilot(l);
        keepProfiles(l);
        orders(l);
        schedules(l);
        rules(l);
        ai(l);
        return List.copyOf(l);
    }

    private static void general(List<SchemaField> l) {
        l.add(choice("general.language", ENUM, "ru", List.of("ru", "en")));
        l.add(f("general.ownerPlayer", STRING, ""));
        BotConfig.Owner owner = BotConfig.Owner.defaults();
        l.add(f("general.commandPrefix", STRING, owner.prefix()));
        l.add(f("general.ownerChatPatterns", STRING_LIST, owner.patterns()));
        l.add(f("general.ownerReplyCommand", STRING, "/msg {player} {text}"));
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
        l.add(f("servers[].dayStart", STRING, "07:00"));
        l.add(f("servers[].nightStart", STRING, "22:00"));
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
        l.add(f("bots[].autopilot", JSON, new JsonObject()));
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

    private static void autopilot(List<SchemaField> l) {
        l.add(f("autopilot.supply", BOOL, true));
        l.add(f("autopilot.sort", BOOL, true));
        l.add(f("autopilot.idleWork", BOOL, true));
        l.add(f("autopilot.discovery", BOOL, true));
        l.add(num("autopilot.discoveryRadius", INT, 32, 4, 64, "blocks"));
        l.add(num("autopilot.discoveryIntervalSec", INT, 60, 10, 3600, "s"));
        l.add(num("autopilot.inspectMaxAgeMin", INT, 120, 0, 10080, "min"));
        l.add(num("autopilot.maxInspectPerTick", INT, 2, 0, 16, null));
        l.add(num("autopilot.inspectMaxDistance", INT, 48, 0, 1024, "blocks"));
        l.add(num("autopilot.homeRadius", INT, 24, 0, 256, "blocks"));
        l.add(f("autopilot.useFound", BOOL, false));
        l.add(num("autopilot.foodMin", INT, 8, 0, 256, "items"));
        l.add(num("autopilot.blocksMin", INT, 32, 0, 1024, "items"));
        l.add(num("autopilot.toolMinDurability", DOUBLE, 0.1, 0, 1, null));
        l.add(num("autopilot.stuckSec", INT, 60, 0, 3600, "s"));
        l.add(num("autopilot.idleHomeSec", INT, 60, 0, 86400, "s"));
        l.add(num("autopilot.holdAfterOwnerSec", INT, 300, 0, 86400, "s"));
        l.add(f("autopilot.throwaway", STRING_LIST, List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
                "minecraft:dirt", "minecraft:netherrack", "minecraft:stone")));
        l.add(f("autopilot.smeltInputs", STRING_LIST, List.of("minecraft:raw_iron", "minecraft:raw_gold",
                "minecraft:raw_copper")));
        l.add(f("autopilot.categories", LIST, defaultCategories()));
        l.add(f("autopilot.categories[].name", STRING, null));
        l.add(f("autopilot.categories[].globs", STRING_LIST, new JsonArray()));
        l.add(f("autopilot.signWords", MAP, defaultSignWords()));
        l.add(f("autopilot.autoTrash.enabled", BOOL, true));
        l.add(f("autopilot.autoTrash.junk", STRING_LIST, List.of("minecraft:dirt", "minecraft:coarse_dirt",
                "minecraft:gravel", "minecraft:sand", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
                "minecraft:tuff", "minecraft:cobbled_deepslate", "minecraft:rotten_flesh",
                "minecraft:poisonous_potato", "minecraft:spider_eye", "minecraft:wheat_seeds",
                "minecraft:beetroot_seeds")));
        JsonObject keep = new JsonObject();
        keep.addProperty("minecraft:sand", 64);
        keep.addProperty("minecraft:cobbled_deepslate", 64);
        keep.addProperty("minecraft:*_seeds", 64);
        l.add(f("autopilot.autoTrash.keepCounts", MAP, keep));
    }

    /** Default keep profile name (SPEC §5.7f). */
    public static final String DEFAULT_KEEP_PROFILE = "снаряжение";

    /** Keep profiles (SPEC §5.7f): name → what a bot keeps when it throws away junk. */
    private static void keepProfiles(List<SchemaField> l) {
        JsonObject gear = Json.obj("armor", "worn", "weapon", "best", "tools", Json.arr("pickaxe", "axe"),
                "food", Json.obj("max", 64), "blocks", Json.obj("globs", new JsonArray(), "count", 64),
                "extra", Json.arr("minecraft:torch", "minecraft:shield", "minecraft:water_bucket"));
        l.add(f("keepProfiles", JSON, Json.obj(DEFAULT_KEEP_PROFILE, gear)));
    }

    /**
     * Sign words (SPEC §5.7e): a word on a sign at a container → role ({@code storage}, {@code inbox}, {@code kit},
     * {@code supply}, {@code fuel}, {@code trash}) or sorting category ({@code sorted:<category>}). A word matches
     * the same word, or a longer one starting with it when it has at least 4 letters (склад → склада).
     */
    static JsonObject defaultSignWords() {
        JsonObject o = new JsonObject();
        String[][] pairs = {
                {"склад", "storage"}, {"storage", "storage"}, {"хранилище", "storage"},
                {"приём", "inbox"}, {"прием", "inbox"}, {"inbox", "inbox"},
                {"кит", "kit"}, {"kit", "kit"}, {"набор", "kit"},
                {"снабжение", "supply"}, {"supply", "supply"},
                {"топливо", "fuel"}, {"fuel", "fuel"},
                {"мусор", "trash"}, {"trash", "trash"},
                {"руда", "sorted:ores_ingots"}, {"руды", "sorted:ores_ingots"}, {"ores", "sorted:ores_ingots"},
                {"ore", "sorted:ores_ingots"},
                {"дерево", "sorted:wood"}, {"wood", "sorted:wood"},
                {"камень", "sorted:stone_building"}, {"stone", "sorted:stone_building"},
                {"еда", "sorted:food"}, {"еды", "sorted:food"}, {"food", "sorted:food"},
                {"инструменты", "sorted:tools_armor"}, {"инструмент", "sorted:tools_armor"},
                {"tools", "sorted:tools_armor"},
                {"редстоун", "sorted:redstone"}, {"redstone", "sorted:redstone"},
                {"ферма", "sorted:farming"}, {"farming", "sorted:farming"}, {"farm", "sorted:farming"},
                {"мобы", "sorted:mob_drops"}, {"моб", "sorted:mob_drops"}, {"mob", "sorted:mob_drops"},
                {"mobs", "sorted:mob_drops"},
                {"разное", "sorted:misc"}, {"misc", "sorted:misc"}};
        for (String[] p : pairs) {
            o.addProperty(p[0], p[1]);
        }
        return o;
    }

    private static void orders(List<SchemaField> l) {
        l.add(f("orders", LIST, new JsonArray()));
        l.add(f("orders[].id", STRING, null));
        l.add(f("orders[].enabled", BOOL, true));
        l.add(f("orders[].serverId", STRING, null));
        l.add(f("orders[].item", STRING, null));
        l.add(num("orders[].min", INT, 64, 0, 100000, "items"));
        l.add(num("orders[].max", INT, null, 0, 100000, "items").asNullable());
        l.add(f("orders[].into", STRING, "storage"));
    }

    /** Schedules (SPEC §5.7b): steps queued on a 5-field cron, or when day / night starts. */
    private static void schedules(List<SchemaField> l) {
        l.add(f("schedules", LIST, new JsonArray()));
        l.add(f("schedules[].id", STRING, null));
        l.add(f("schedules[].name", STRING, ""));
        l.add(f("schedules[].enabled", BOOL, true));
        l.add(f("schedules[].serverId", STRING, null).asNullable());
        l.add(f("schedules[].when", STRING, "0 * * * *"));
        l.add(f("schedules[].botIds", JSON, "any"));
        l.add(f("schedules[].steps", JSON, new JsonArray()));
        l.add(choice("schedules[].priority", ENUM, "normal", STEP_PRIORITIES));
    }

    /** Rules (SPEC §5.7b): if a trigger happens, queue steps (with a cooldown). */
    private static void rules(List<SchemaField> l) {
        l.add(f("rules", LIST, new JsonArray()));
        l.add(f("rules[].id", STRING, null));
        l.add(f("rules[].name", STRING, ""));
        l.add(f("rules[].enabled", BOOL, true));
        l.add(f("rules[].serverId", STRING, null).asNullable());
        l.add(f("rules[].if", JSON, new JsonObject()));
        l.add(f("rules[].then", JSON, new JsonArray()));
        l.add(f("rules[].botIds", JSON, "any"));
        l.add(num("rules[].cooldownSec", INT, 300, 0, 86400, "s"));
        l.add(choice("rules[].priority", ENUM, "normal", STEP_PRIORITIES));
    }

    /** Optional local AI through Ollama (SPEC §5.7c command box, §5.7d supervisor); off by default. */
    private static void ai(List<SchemaField> l) {
        l.add(f("ai.enabled", BOOL, false));
        l.add(f("ai.endpoint", STRING, "http://127.0.0.1:11434"));
        l.add(f("ai.model", STRING, "qwen2.5:7b-instruct"));
        l.add(num("ai.timeoutSec", INT, 30, 1, 600, "s"));
        l.add(num("ai.superviseSec", INT, 90, 15, 3600, "s"));
        l.add(choice("ai.mode", ENUM, "suggest", AI_MODES));
    }

    /** Sorting categories (SPEC §5.7a); the first category whose globs or {@code #tags} match an item wins. */
    static JsonArray defaultCategories() {
        JsonArray a = new JsonArray();
        a.add(cat("ores_ingots", "minecraft:*_ore", "minecraft:raw_*", "minecraft:*_ingot", "minecraft:*_nugget",
                "minecraft:coal", "minecraft:charcoal", "minecraft:diamond", "minecraft:emerald",
                "minecraft:lapis_lazuli", "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:netherite_scrap",
                "minecraft:ancient_debris", "minecraft:coal_block", "minecraft:iron_block", "minecraft:gold_block",
                "minecraft:copper_block", "minecraft:diamond_block", "minecraft:emerald_block",
                "minecraft:lapis_block", "minecraft:netherite_block", "minecraft:flint"));
        a.add(cat("wood", "#minecraft:logs", "#minecraft:planks", "#minecraft:saplings", "#minecraft:leaves",
                "#minecraft:wooden_slabs", "#minecraft:wooden_stairs", "#minecraft:wooden_fences",
                "#minecraft:wooden_doors", "#minecraft:wooden_trapdoors", "minecraft:*_log", "minecraft:*_wood",
                "minecraft:*_planks", "minecraft:*_sapling", "minecraft:*_leaves", "minecraft:stick",
                "minecraft:bamboo_block", "minecraft:*_stem", "minecraft:*_hyphae"));
        a.add(cat("food", "minecraft:cooked_*", "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
                "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon", "minecraft:bread",
                "minecraft:apple", "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
                "minecraft:golden_carrot", "minecraft:baked_potato", "minecraft:carrot", "minecraft:potato",
                "minecraft:beetroot", "minecraft:melon_slice", "minecraft:sweet_berries", "minecraft:glow_berries",
                "minecraft:pumpkin_pie", "minecraft:cookie", "minecraft:*_stew", "minecraft:beetroot_soup",
                "minecraft:dried_kelp", "minecraft:honey_bottle", "minecraft:tropical_fish", "minecraft:cake"));
        a.add(cat("tools_armor", "minecraft:*_pickaxe", "minecraft:*_axe", "minecraft:*_shovel", "minecraft:*_hoe",
                "minecraft:*_sword", "minecraft:*_helmet", "minecraft:*_chestplate", "minecraft:*_leggings",
                "minecraft:*_boots", "minecraft:shears", "minecraft:bow", "minecraft:crossbow", "minecraft:arrow",
                "minecraft:spectral_arrow", "minecraft:shield", "minecraft:flint_and_steel", "minecraft:fishing_rod",
                "minecraft:trident", "minecraft:mace", "minecraft:elytra", "minecraft:spyglass", "minecraft:brush",
                "minecraft:*_bucket", "minecraft:bucket", "minecraft:compass", "minecraft:clock", "minecraft:totem_of_undying"));
        a.add(cat("redstone", "minecraft:redstone", "minecraft:redstone_*", "minecraft:repeater",
                "minecraft:comparator", "minecraft:piston", "minecraft:sticky_piston", "minecraft:observer",
                "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser", "minecraft:lever", "minecraft:*_button",
                "minecraft:*_pressure_plate", "minecraft:daylight_detector", "minecraft:target",
                "minecraft:tripwire_hook", "minecraft:*rail", "minecraft:tnt", "minecraft:note_block",
                "minecraft:crafter", "minecraft:lightning_rod", "minecraft:sculk_sensor"));
        a.add(cat("farming", "minecraft:wheat", "minecraft:*_seeds", "minecraft:sugar_cane", "minecraft:pumpkin",
                "minecraft:melon", "minecraft:cactus", "minecraft:bamboo", "minecraft:bone_meal",
                "minecraft:cocoa_beans", "minecraft:nether_wart", "minecraft:hay_block", "minecraft:kelp",
                "minecraft:egg", "minecraft:sugar", "minecraft:carved_pumpkin", "minecraft:brown_mushroom",
                "minecraft:red_mushroom"));
        a.add(cat("mob_drops", "minecraft:rotten_flesh", "minecraft:bone", "minecraft:string", "minecraft:spider_eye",
                "minecraft:gunpowder", "minecraft:ender_pearl", "minecraft:slime_ball", "minecraft:leather",
                "minecraft:feather", "#minecraft:wool", "minecraft:*_wool", "minecraft:blaze_rod",
                "minecraft:ghast_tear", "minecraft:phantom_membrane", "minecraft:rabbit_hide", "minecraft:ink_sac",
                "minecraft:glow_ink_sac", "minecraft:magma_cream", "minecraft:prismarine_shard",
                "minecraft:prismarine_crystals", "minecraft:breeze_rod", "minecraft:*_scute", "minecraft:honeycomb",
                "minecraft:shulker_shell", "minecraft:nautilus_shell"));
        a.add(cat("stone_building", "#minecraft:stone_bricks", "minecraft:stone", "minecraft:cobblestone",
                "minecraft:*deepslate*", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
                "minecraft:tuff", "minecraft:calcite", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:gravel",
                "minecraft:sand", "minecraft:red_sand", "minecraft:*sandstone", "minecraft:*_bricks",
                "minecraft:bricks", "minecraft:brick", "minecraft:clay", "minecraft:clay_ball", "minecraft:glass",
                "minecraft:*_glass", "minecraft:*glass_pane", "minecraft:*terracotta", "minecraft:*_concrete",
                "minecraft:*_concrete_powder", "minecraft:netherrack", "minecraft:blackstone", "minecraft:basalt",
                "minecraft:obsidian", "minecraft:end_stone", "minecraft:*_slab", "minecraft:*_stairs",
                "minecraft:*_wall", "minecraft:smooth_*", "minecraft:polished_*", "minecraft:mossy_*"));
        a.add(cat("misc"));
        return a;
    }

    private static JsonObject cat(String name, String... globs) {
        return Json.obj("name", name, "globs", Json.arrOf(List.of(globs)));
    }
}
