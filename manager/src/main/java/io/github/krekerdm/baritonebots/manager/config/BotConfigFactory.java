package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link BotConfig} a bot receives in {@code welcome}/{@code config}: global defaults, then the server
 * profile, then the bot's own overrides, plus secrets (password, companion token) and the protected zones of the
 * world knowledge.
 */
public final class BotConfigFactory {
    private BotConfigFactory() {
    }

    /**
     * @param worldZones zones from world/&lt;serverId&gt;.json as {@code {dim, box}} objects (may be empty)
     */
    public static BotConfig build(ManagerConfig cfg, ManagerConfig.BotDef bot, ManagerConfig.ServerProfile server,
                                  Secrets secrets, List<JsonObject> worldZones) {
        JsonObject o = new JsonObject();
        o.addProperty("botId", bot.id());
        o.addProperty("username", bot.username());
        if (server != null) {
            o.add("server", Json.obj("address", server.address(), "autoConnect", server.autoConnect(),
                    "reconnect", server.reconnect().deepCopy()));
            JsonObject login = server.login().deepCopy();
            login.addProperty("password", secrets.botPassword(bot.id()));
            o.add("login", login);
            o.add("companion", Json.obj("enabled", server.companion().enabled(),
                    "token", secrets.companionToken(server.id())));
            JsonObject protection = server.protection().deepCopy();
            JsonArray zones = new JsonArray();
            JsonArray own = Json.getArr(protection, "zones");
            if (own != null) {
                own.forEach(z -> zones.add(zoneOnly(z)));
            }
            worldZones.forEach(z -> zones.add(zoneOnly(z)));
            protection.add("zones", zones);
            o.add("protection", protection);
        }
        o.add("behaviour", Json.deepMerge(cfg.behaviour(), bot.behaviour()));
        JsonObject baritone = cfg.baritone().deepCopy();
        if (server != null) {
            baritone = Json.deepMerge(baritone, server.baritone());
        }
        baritone = Json.deepMerge(baritone, bot.baritone());
        o.add("baritone", baritone);
        o.add("client", cfg.client().deepCopy());
        o.add("status", cfg.status().deepCopy());
        BotConfig p = BotConfig.parse(o);
        // parse() merges onto BotConfig.defaults(), which would bring back Baritone settings the user deleted.
        Map<String, JsonElement> exact = new LinkedHashMap<>();
        baritone.entrySet().forEach(e -> exact.put(e.getKey(), e.getValue()));
        return new BotConfig(p.botId(), p.username(), p.server(), p.login(), p.companion(), p.behaviour(),
                p.protection(), exact, p.client(), p.status());
    }

    /** BotConfig.Zone has only dim + box; extra keys such as a zone name are dropped. */
    private static JsonElement zoneOnly(JsonElement z) {
        if (!z.isJsonObject()) {
            return z;
        }
        JsonObject zo = z.getAsJsonObject();
        return Json.obj("dim", zo.get("dim"), "box", zo.get("box"));
    }

    /** The config as JSON without the login password, for the panel. */
    public static JsonObject redacted(BotConfig config) {
        JsonObject o = Json.toObject(config);
        JsonObject login = Json.getObj(o, "login");
        if (login != null) {
            login.remove("password");
        }
        JsonObject companion = Json.getObj(o, "companion");
        if (companion != null) {
            companion.remove("token");
        }
        return o;
    }
}
