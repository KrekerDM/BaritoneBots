package io.github.krekerdm.baritonebots.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
    @TempDir
    Path dir;

    private ConfigStore store() throws IOException {
        Secrets secrets = new Secrets(dir.resolve("secrets.json"));
        secrets.load();
        ConfigStore s = new ConfigStore(dir.resolve("config.json"), secrets);
        s.load();
        return s;
    }

    @Test
    void mergePatchSemantics() {
        JsonObject target = Json.obj("a", 1, "nested", Json.obj("x", 1, "y", 2), "list", Json.arr(1, 2, 3));
        JsonObject patch = Json.obj("a", 5, "nested", Json.obj("y", JsonNull.INSTANCE, "z", 3), "list", Json.arr(9));
        JsonObject merged = Json.deepMerge(target, patch);
        assertEquals(5, merged.get("a").getAsInt());
        assertEquals(Json.obj("x", 1, "z", 3), merged.get("nested"));
        assertEquals(Json.arr(9), merged.get("list"), "arrays are replaced, not merged");
        assertEquals(1, target.get("a").getAsInt(), "inputs are not modified");
    }

    @Test
    void defaultsAreValidAndComplete() {
        JsonObject defaults = SettingsSchema.defaults();
        assertTrue(ConfigValidator.validate(ConfigStore.normalize(defaults)).isEmpty());
        ManagerConfig cfg = ManagerConfig.fromJson(ConfigStore.normalize(defaults));
        assertEquals(8765, cfg.general().panel().port());
        assertEquals("127.0.0.1", cfg.general().panel().bind());
        assertEquals(25590, cfg.general().link().port());
        assertEquals("26.2", cfg.runtime().minecraftVersion());
        assertEquals("2.10.0", cfg.runtime().headlessmcVersion());
        assertEquals(3, cfg.runtime().mods().size());
        assertEquals(1, cfg.servers().size());
        assertTrue(cfg.bots().isEmpty());
        assertNull(cfg.runtime().javaPathOrNull());
    }

    @Test
    void memoryPresets() throws IOException {
        ConfigStore s = store();
        assertEquals(1024, s.get().runtime().presetMemoryMb());
        s.patch(Json.obj("runtime", Json.obj("memoryPreset", "eco")));
        assertEquals(768, s.get().runtime().presetMemoryMb());
        s.patch(Json.obj("runtime", Json.obj("memoryPreset", "performance")));
        assertEquals(2048, s.get().runtime().presetMemoryMb());
        s.patch(Json.obj("runtime", Json.obj("memoryPreset", "custom", "memoryMb", 1536)));
        assertEquals(1536, s.get().runtime().presetMemoryMb());
        s.addItem("bots", Json.obj("id", "b1", "username", "Bot_1", "serverId", "main", "memoryMb", 900));
        assertEquals(900, s.get().memoryMbFor(s.get().bot("b1").orElseThrow()));
    }

    @Test
    void createsFileAndBotItemsGetDefaults() throws IOException {
        ConfigStore s = store();
        assertTrue(Files.exists(dir.resolve("config.json")));
        JsonObject bot = s.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main"));
        assertTrue(Json.getBool(bot, "enabled", false));
        assertEquals("offline", Json.getString(Json.getObj(bot, "account"), "type", null));
        ManagerConfig.BotDef def = s.get().bot("BOT1").orElseThrow();
        assertEquals("Bot1", def.username());
        assertFalse(def.microsoft());
        assertEquals("home", def.homeWaypointName());
        // reload from disk
        ConfigStore again = store();
        assertEquals(1, again.get().bots().size());
    }

    @Test
    void validationRejectsBadValues() throws IOException {
        ConfigStore s = store();
        ValidationException port = assertThrows(ValidationException.class,
                () -> s.patch(Json.obj("general", Json.obj("panel", Json.obj("port", 70000)))));
        assertEquals("range", port.fields().get("general.panel.port"));
        ValidationException type = assertThrows(ValidationException.class,
                () -> s.patch(Json.obj("general", Json.obj("tray", "yes"))));
        assertEquals("type", type.fields().get("general.tray"));
        ValidationException en = assertThrows(ValidationException.class,
                () -> s.patch(Json.obj("runtime", Json.obj("priority", "realtime"))));
        assertEquals("enum", en.fields().get("runtime.priority"));
        ValidationException user = assertThrows(ValidationException.class,
                () -> s.addItem("bots", Json.obj("id", "x", "username", "a b", "serverId", "main")));
        assertEquals("pattern", user.fields().get("bots[0].username"));
        ValidationException server = assertThrows(ValidationException.class,
                () -> s.addItem("bots", Json.obj("id", "x", "username", "Valid", "serverId", "nope")));
        assertEquals("unknown_server", server.fields().get("bots[0].serverId"));
        s.addItem("bots", Json.obj("id", "x", "username", "Valid", "serverId", "main"));
        ValidationException dup = assertThrows(ValidationException.class,
                () -> s.addItem("bots", Json.obj("id", "X", "username", "Other", "serverId", "main")));
        assertEquals("duplicate", dup.fields().get("bots[1].id"));
        ValidationException regex = assertThrows(ValidationException.class, () -> s.updateItem("servers", "main",
                Json.obj("login", Json.obj("loginPatterns", Json.arr("(unclosed")))));
        assertEquals("regex", regex.fields().get("servers[0].login.loginPatterns"));
        // a rejected patch leaves the stored config untouched
        assertEquals(8765, s.get().general().panel().port());
        assertEquals(1, s.get().bots().size());
    }

    @Test
    void idsAreImmutableAndMissingItemsReported() throws IOException {
        ConfigStore s = store();
        assertThrows(ValidationException.class, () -> s.updateItem("servers", "main", Json.obj("id", "other")));
        assertThrows(ConfigStore.NoSuchItemException.class, () -> s.updateItem("servers", "ghost", new JsonObject()));
    }

    @Test
    void companionTokensLiveInSecrets() throws IOException {
        ConfigStore s = store();
        s.updateItem("servers", "main", Json.obj("companion", Json.obj("enabled", true, "token", "tok123")));
        String onDisk = Files.readString(dir.resolve("config.json"));
        assertFalse(onDisk.contains("tok123"));
        assertTrue(Files.readString(dir.resolve("secrets.json")).contains("tok123"));
        JsonArray servers = s.viewForPanel().getAsJsonArray("servers");
        assertEquals("tok123", servers.get(0).getAsJsonObject().getAsJsonObject("companion").get("token").getAsString());
    }

    @Test
    void deletedBaritoneDefaultStaysDeleted() throws IOException {
        ConfigStore s = store();
        String someSetting = s.get().baritone().keySet().iterator().next();
        JsonObject patch = new JsonObject();
        patch.add(someSetting, JsonNull.INSTANCE); // RFC 7396: null deletes
        s.patch(Json.obj("baritone", patch));
        assertFalse(s.get().baritone().has(someSetting));
        s.patch(Json.obj("general", Json.obj("tray", false)));
        assertFalse(s.get().baritone().has(someSetting), "defaults are not merged back on later patches");
    }

    @Test
    void botConfigLayering() throws IOException {
        ConfigStore s = store();
        s.patch(Json.obj("behaviour", Json.obj("pickupRadius", 12)));
        s.updateItem("servers", "main", Json.obj("address", "mc.example.org:25570", "baritone", Json.obj("allowSprint", false)));
        s.addItem("bots", Json.obj("id", "b", "username", "Bee", "serverId", "main",
                "behaviour", Json.obj("defense", Json.obj("mode", "flee")), "baritone", Json.obj("allowParkour", true)));
        Secrets secrets = new Secrets(dir.resolve("secrets.json"));
        secrets.load();
        secrets.ensureBotPassword("b");
        ManagerConfig cfg = s.get();
        BotConfig bc = BotConfigFactory.build(cfg, cfg.bot("b").orElseThrow(), cfg.server("main").orElseThrow(), secrets,
                List.of(Json.obj("dim", "minecraft:overworld", "name", "spawn",
                        "box", Json.obj("a", Json.obj("x", 0, "y", 0, "z", 0), "b", Json.obj("x", 5, "y", 5, "z", 5)))));
        assertEquals("mc.example.org:25570", bc.server().address());
        assertEquals(12, bc.behaviour().pickupRadius());
        assertEquals("flee", bc.behaviour().defense().mode());
        assertEquals(false, bc.baritone().get("allowSprint").getAsBoolean());
        assertEquals(true, bc.baritone().get("allowParkour").getAsBoolean());
        assertEquals(16, bc.login().password().length());
        assertEquals(1, bc.protection().zones().size());
        assertFalse(Json.toTree(BotConfigFactory.redacted(bc)).toString().contains(bc.login().password()));
    }

    @Test
    void schemaHasMetadataForThePanel() {
        JsonObject schema = SettingsSchema.toJson();
        assertTrue(schema.getAsJsonArray("fields").size() > 80);
        assertEquals(Map.of("eco", 768, "normal", 1024, "performance", 2048), SettingsSchema.MEMORY_PRESETS);
        SchemaField port = SettingsSchema.field("general.panel.port");
        assertEquals(1, port.min().intValue());
        assertEquals(65535, port.max().intValue());
        assertEquals("settings.general.panel.port", port.labelKey());
        assertEquals("s", SettingsSchema.field("runtime.startStaggerSec").unit());
    }
}
