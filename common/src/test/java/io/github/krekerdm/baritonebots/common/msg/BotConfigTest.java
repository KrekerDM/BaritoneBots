package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotConfigTest {

    @Test
    void defaultsSerialiseAndParseBack() {
        BotConfig d = BotConfig.defaults("bot1", "Bot1");
        String json = Json.toJson(d);
        BotConfig back = Json.fromJson(json, BotConfig.class);
        assertEquals(d, back);
        assertEquals(d, BotConfig.parse(Json.parse(json)));
        assertEquals(d, Envelope.decode(Envelope.of(MessageTypes.WELCOME, new Welcome(d)).encode())
                .payload(Welcome.class).config());
    }

    @Test
    void jsonUsesSpecFieldNames() {
        JsonObject o = Json.toObject(BotConfig.defaults("bot1", "Bot1"));
        for (String key : List.of("botId", "username", "server", "login", "companion", "behaviour", "protection",
                "baritone", "client", "status")) {
            assertTrue(o.has(key), key);
        }
        assertTrue(Json.getObj(o, "server").getAsJsonObject("reconnect").has("maxDelaySec"));
        JsonObject behaviour = Json.getObj(o, "behaviour");
        for (String key : List.of("autoRespawn", "autoEat", "defense", "deathRecovery", "inventoryFullFreeSlots",
                "lowToolDurability", "pickupRadius")) {
            assertTrue(behaviour.has(key), key);
        }
        assertTrue(behaviour.getAsJsonObject("defense").has("fleeBelowHealth"));
        assertTrue(Json.getObj(o, "login").has("registerCommand"));
        assertTrue(Json.getObj(o, "status").has("activeIntervalTicks"));
        assertTrue(Json.getObj(o, "client").has("renderDistance"));
        assertFalse(Json.getObj(o, "companion").has("unknown"));
    }

    @Test
    void defaultValues() {
        BotConfig d = BotConfig.defaults("bot1", "Bot1");
        BotConfig.Behaviour b = d.behaviour();
        assertEquals(BotConfig.Defense.MODE_FIGHT, b.defense().mode());
        assertEquals(6, b.defense().radius());
        assertEquals(6.0f, b.defense().fleeBelowHealth());
        assertEquals(14, b.autoEat().belowFood());
        assertEquals(10.0f, b.autoEat().belowHealth());
        assertTrue(b.autoEat().avoid().contains("minecraft:suspicious_stew"));
        assertEquals(6, b.autoEat().avoid().size());
        assertTrue(b.deathRecovery().enabled());
        assertEquals(2000, b.deathRecovery().maxDistance());
        assertEquals(240, b.deathRecovery().timeoutSec());
        assertEquals(1, b.inventoryFullFreeSlots());
        assertEquals(0.08f, b.lowToolDurability());
        assertEquals(8, b.pickupRadius());
        assertEquals(BotConfig.Login.MODE_AUTO, d.login().mode());
        assertEquals("/login {password}", d.login().loginCommand());
        assertEquals("/register {password} {password}", d.login().registerCommand());
        assertEquals(new BotConfig.ClientOpts(true, true, true, 10, 4), d.client());
        assertEquals(new BotConfig.StatusOpts(20, 100), d.status());
        assertEquals(new BotConfig.Reconnect(true, 15, 300), d.server().reconnect());
        assertEquals(new JsonPrimitive(false), d.baritone().get("chatControl"));
        assertEquals(20, d.baritone().get("mineGoalUpdateInterval").getAsInt());
        assertEquals(17, d.baritone().size());
    }

    @Test
    void parseFillsMissingFieldsFromDefaults() {
        JsonObject partial = Json.parseObject("""
                {"botId":"b2","username":"Miner",
                 "behaviour":{"autoEat":{"belowFood":10}},
                 "baritone":{"allowParkour":true,"chatControl":null},
                 "protection":{"zones":[{"dim":"minecraft:the_nether","box":{"a":{"x":5,"y":0,"z":5},"b":{"x":0,"y":10,"z":0}}}]}}
                """);
        BotConfig c = BotConfig.parse(partial);
        assertEquals("b2", c.botId());
        assertEquals("Miner", c.username());
        assertEquals(10, c.behaviour().autoEat().belowFood());
        assertTrue(c.behaviour().autoEat().enabled());
        assertEquals(10.0f, c.behaviour().autoEat().belowHealth());
        assertEquals(new JsonPrimitive(true), c.baritone().get("allowParkour"));
        assertFalse(c.baritone().containsKey("chatControl"), "merge-patch null removes a default");
        assertEquals(new JsonPrimitive(true), c.baritone().get("pruneRegionsFromRAM"));
        assertEquals(List.of(new BotConfig.Zone(Dims.NETHER, new Box(Pos.ZERO, new Pos(5, 10, 5)))), c.protection().zones());
        assertTrue(c.protection().noBreak().contains("minecraft:chest"));
        assertEquals(BotConfig.defaults("b2", "Miner").login(), c.login());
    }

    @Test
    void missingSectionsBecomeDefaultsEvenWithoutParse() {
        BotConfig c = Json.fromJson("{\"botId\":\"x\"}", BotConfig.class);
        assertEquals(BotConfig.Behaviour.defaults(), c.behaviour());
        assertTrue(c.baritone().isEmpty());
        assertEquals(List.of(), c.login().joinCommands());
    }

    private static boolean any(List<String> patterns, String line) {
        return patterns.stream().anyMatch(p -> Pattern.compile(p).matcher(line).find());
    }

    @Test
    void defaultLoginPatternsRecogniseCommonPrompts() {
        BotConfig.Login l = BotConfig.Login.defaults();
        assertTrue(any(l.loginPatterns(), "Please, login with the command: /login <password>"));
        assertTrue(any(l.loginPatterns(), "Please log in using /login <password>."));
        assertTrue(any(l.loginPatterns(), "Авторизуйтесь командой /login <пароль>"));
        assertTrue(any(l.loginPatterns(), "Войдите в аккаунт: /l пароль"));
        assertTrue(any(l.registerPatterns(), "Please, register to the server with the command: /register <password> <ConfirmPassword>"));
        assertTrue(any(l.registerPatterns(), "Зарегистрируйтесь: /reg <пароль> <повтор пароля>"));
        assertFalse(any(l.loginPatterns(), "Please, register to the server with the command: /register <password> <ConfirmPassword>"));
        assertTrue(any(l.successPatterns(), "Successful login!"));
        assertTrue(any(l.successPatterns(), "Successfully registered!"));
        assertTrue(any(l.successPatterns(), "Вы успешно вошли!"));
        assertTrue(any(l.successPatterns(), "Успешная авторизация!"));
        assertTrue(any(l.failurePatterns(), "Wrong password!"));
        assertTrue(any(l.failurePatterns(), "Неверный пароль!"));
        assertFalse(any(l.successPatterns(), "Use /logout to log out"));
        assertFalse(any(l.loginPatterns(), "Use /logout to log out"));
    }
}
