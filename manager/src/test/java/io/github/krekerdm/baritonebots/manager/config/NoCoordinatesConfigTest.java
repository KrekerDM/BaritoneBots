package io.github.krekerdm.baritonebots.manager.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Settings of SPEC §5.7e / §5.7f: defaults, validation, owner fields in BotConfig, deleting map entries. */
class NoCoordinatesConfigTest {
    private static Map<String, String> errors(JsonObject patch) {
        return ConfigValidator.validate(ConfigStore.normalize(Json.deepMerge(SettingsSchema.defaults(), patch)));
    }

    @Test
    void defaultsAreValidAndComplete() {
        ManagerConfig cfg = ManagerConfig.fromJson(ConfigStore.normalize(SettingsSchema.defaults()));
        assertEquals("!b", cfg.general().commandPrefixOrDefault());
        assertEquals("/msg {player} {text}", cfg.general().replyCommandOrDefault());
        assertEquals("storage", cfg.autopilot().signWords().get("склад"));
        assertEquals("sorted:ores_ingots", cfg.autopilot().signWords().get("руда"));
        assertTrue(cfg.autopilot().autoTrash().enabled());
        assertEquals(64, cfg.autopilot().autoTrash().keepCounts().get("minecraft:cobbled_deepslate"));
        JsonObject gear = cfg.keepProfile(null);
        assertNotNull(gear, "the default profile «снаряжение»");
        assertEquals(gear, cfg.keepProfile("СНАРЯЖЕНИЕ"));
        assertEquals(64, Json.getInt(Json.getObj(gear, "food"), "max", 0));
        assertEquals(null, cfg.keepProfile("nope"));
    }

    @Test
    void badValuesAreRejected() {
        assertTrue(errors(Json.obj("general", Json.obj("commandPrefix", "! b"))).containsKey("general.commandPrefix"));
        assertTrue(errors(Json.obj("general", Json.obj("ownerReplyCommand", "msg {player}")))
                .containsKey("general.ownerReplyCommand"));
        assertTrue(errors(Json.obj("general", Json.obj("ownerChatPatterns", List.of("([")))).keySet().stream()
                .anyMatch(k -> k.startsWith("general.ownerChatPatterns")));
        assertTrue(errors(Json.obj("autopilot", Json.obj("signWords", Json.obj("бочка", "Bad Role!"))))
                .containsKey("autopilot.signWords.бочка"));
        assertTrue(errors(Json.obj("autopilot", Json.obj("autoTrash", Json.obj("keepCounts", Json.obj("minecraft:dirt", -1)))))
                .containsKey("autopilot.autoTrash.keepCounts.minecraft:dirt"));
        assertTrue(errors(Json.obj("keepProfiles", Json.obj("x", Json.obj("tools", Json.arr("spoon")))))
                .containsKey("keepProfiles.x.tools"));
        assertTrue(errors(Json.obj("keepProfiles", Json.obj("x", Json.obj("armor", "all"))))
                .containsKey("keepProfiles.x.armor"));
        assertTrue(errors(Json.obj("keepProfiles", Json.obj("шахтёр", Json.obj("tools", Json.arr("pickaxe", "shovel"),
                "food", Json.obj("max", 32))))).isEmpty());
    }

    @Test
    void deletedDefaultEntriesStayDeleted() {
        JsonObject words = ManagerConfig.AutopilotCfg.defaults().signWords().entrySet().stream()
                .filter(e -> !e.getKey().equals("склад"))
                .collect(JsonObject::new, (o, e) -> o.addProperty(e.getKey(), e.getValue()), (a, b) -> { });
        JsonObject in = SettingsSchema.defaults();
        in.getAsJsonObject("autopilot").add("signWords", words);
        ManagerConfig cfg = ManagerConfig.fromJson(ConfigStore.normalize(in));
        assertFalse(cfg.autopilot().signWords().containsKey("склад"));
        assertTrue(cfg.autopilot().signWords().containsKey("приём"));
    }

    @Test
    void ownerGoesToTheBots() {
        JsonObject root = Json.deepMerge(SettingsSchema.defaults(), Json.obj("general", Json.obj("ownerPlayer", "Boss",
                "commandPrefix", "#bot"), "bots", Json.arr(Json.obj("id", "b1", "username", "B1", "serverId", "main"))));
        ManagerConfig cfg = ManagerConfig.fromJson(ConfigStore.normalize(root));
        BotConfig bc = BotConfigFactory.build(cfg, cfg.bots().getFirst(), cfg.server("main").orElseThrow(),
                new Secrets(java.nio.file.Path.of("unused-secrets.json")), List.of());
        assertEquals("Boss", bc.owner().player());
        assertEquals("#bot", bc.owner().prefix());
        assertEquals(BotConfig.Owner.defaults().patterns(), bc.owner().patterns());
    }
}
