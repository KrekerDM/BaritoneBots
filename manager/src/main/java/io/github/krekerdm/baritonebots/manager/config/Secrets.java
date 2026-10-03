package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * secrets.json (SPEC §5.2): panel token, link secret, companion tokens per server, bot passwords. Generated with
 * {@link java.security.SecureRandom} on first use and kept out of config.json so the config can be shared.
 * Owned by the manager loop after startup.
 */
public final class Secrets {
    private static final int TOKEN_LENGTH = 32;
    /** 16 alphanumerics: accepted by AuthMe / nLogin default password rules. */
    private static final int PASSWORD_LENGTH = 16;

    private final Path file;
    private String panelToken;
    private String linkSecret;
    private final Map<String, String> companionTokens = new LinkedHashMap<>();
    private final Map<String, String> botPasswords = new LinkedHashMap<>();

    public Secrets(Path file) {
        this.file = file;
    }

    public void load() throws IOException {
        JsonElement e = AtomicFiles.readJson(file);
        JsonObject o = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        panelToken = Json.getString(o, "panelToken", null);
        linkSecret = Json.getString(o, "linkSecret", null);
        readMap(Json.getObj(o, "companionTokens"), companionTokens);
        readMap(Json.getObj(o, "botPasswords"), botPasswords);
        boolean changed = false;
        if (panelToken == null || panelToken.isBlank()) {
            panelToken = Tokens.random(TOKEN_LENGTH);
            changed = true;
        }
        if (linkSecret == null || linkSecret.isBlank()) {
            linkSecret = Tokens.random(TOKEN_LENGTH);
            changed = true;
        }
        if (changed || e == null) {
            save();
        }
    }

    private static void readMap(JsonObject src, Map<String, String> dst) {
        dst.clear();
        if (src != null) {
            src.entrySet().forEach(en -> {
                if (en.getValue().isJsonPrimitive()) {
                    dst.put(en.getKey().toLowerCase(Locale.ROOT), en.getValue().getAsString());
                }
            });
        }
    }

    public void save() {
        JsonObject o = Json.obj("panelToken", panelToken, "linkSecret", linkSecret,
                "companionTokens", Json.toTree(companionTokens), "botPasswords", Json.toTree(botPasswords));
        try {
            AtomicFiles.writeJson(file, o);
            restrictPermissions();
        } catch (IOException ex) {
            Log.error("cannot write " + file, ex);
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the file inherits the user's profile ACL, which is already private
        }
    }

    public String panelToken() {
        return panelToken;
    }

    public String linkSecret() {
        return linkSecret;
    }

    public String companionToken(String serverId) {
        return companionTokens.getOrDefault(serverId.toLowerCase(Locale.ROOT), "");
    }

    /** Stores a token; a blank value generates a new one. Returns the stored token. */
    public String setCompanionToken(String serverId, String token) {
        String t = token == null || token.isBlank() ? Tokens.random(TOKEN_LENGTH) : token.trim();
        companionTokens.put(serverId.toLowerCase(Locale.ROOT), t);
        save();
        return t;
    }

    public String ensureCompanionToken(String serverId) {
        String t = companionToken(serverId);
        return t.isBlank() ? setCompanionToken(serverId, null) : t;
    }

    public String botPassword(String botId) {
        return botPasswords.getOrDefault(botId.toLowerCase(Locale.ROOT), "");
    }

    public String ensureBotPassword(String botId) {
        String p = botPassword(botId);
        if (p.isBlank()) {
            p = Tokens.random(PASSWORD_LENGTH);
            botPasswords.put(botId.toLowerCase(Locale.ROOT), p);
            save();
        }
        return p;
    }

    public void setBotPassword(String botId, String password) {
        botPasswords.put(botId.toLowerCase(Locale.ROOT), password);
        save();
    }

    public void forgetServer(String serverId) {
        if (companionTokens.remove(serverId.toLowerCase(Locale.ROOT)) != null) {
            save();
        }
    }

    public void forgetBot(String botId) {
        if (botPasswords.remove(botId.toLowerCase(Locale.ROOT)) != null) {
            save();
        }
    }
}
