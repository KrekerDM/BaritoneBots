package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.List;
import java.util.Map;

/**
 * Everything a bot client needs to behave (SPEC §2.5), sent in {@code welcome} and {@code config}.
 * Null nested sections and collections are replaced by defaults / empty values on construction;
 * use {@link #parse(JsonElement)} to fill missing scalar fields from {@link #defaults(String, String)} too.
 */
public record BotConfig(String botId, String username, Server server, Login login, Companion companion,
                        Behaviour behaviour, Protection protection, Map<String, JsonElement> baritone,
                        ClientOpts client, StatusOpts status, Owner owner) {

    public BotConfig {
        server = server == null ? Server.defaults() : server;
        login = login == null ? Login.defaults() : login;
        companion = companion == null ? Companion.defaults() : companion;
        behaviour = behaviour == null ? Behaviour.defaults() : behaviour;
        protection = protection == null ? Protection.defaults() : protection;
        baritone = Copies.map(baritone);
        client = client == null ? ClientOpts.defaults() : client;
        status = status == null ? StatusOpts.defaults() : status;
        owner = owner == null ? Owner.defaults() : owner;
    }

    /** Shipped defaults for a bot (low-load client, auto login, defense on, Baritone low-load set). */
    public static BotConfig defaults(String botId, String username) {
        return new BotConfig(botId, username, Server.defaults(), Login.defaults(), Companion.defaults(),
                Behaviour.defaults(), Protection.defaults(), BaritoneDefaults.map(), ClientOpts.defaults(),
                StatusOpts.defaults(), Owner.defaults());
    }

    /**
     * Reads a possibly partial config: the JSON is merge-patched (RFC 7396) onto the defaults for its
     * {@code botId}/{@code username}, so absent fields keep their default values.
     */
    public static BotConfig parse(JsonElement json) {
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        String botId = Json.getString(o, "botId", "bot");
        String username = Json.getString(o, "username", botId);
        JsonObject merged = Json.deepMerge(Json.toObject(defaults(botId, username)), o);
        return Json.fromJson(merged, BotConfig.class);
    }

    /** Server address and reconnect policy; {@code address} is {@code host[:port]}. */
    public record Server(String address, boolean autoConnect, Reconnect reconnect) {
        public Server {
            reconnect = reconnect == null ? Reconnect.defaults() : reconnect;
        }

        public static Server defaults() {
            return new Server("localhost:25565", true, Reconnect.defaults());
        }
    }

    /** Reconnect backoff after a kick/disconnect: {@code delaySec} doubling up to {@code maxDelaySec}. */
    public record Reconnect(boolean enabled, int delaySec, int maxDelaySec) {
        public static Reconnect defaults() {
            return new Reconnect(true, 15, 300);
        }
    }

    /**
     * Chat-driven login for auth plugins (AuthMe, nLogin, ...). Patterns are Java regexes matched with
     * {@code find()} against the plain text (no formatting codes) of system chat lines; {@code {password}}
     * in the commands is replaced by {@link #password()}. Register patterns should be tested before login
     * patterns, since some register prompts also mention {@code /login}.
     */
    public record Login(String mode, String password, String loginCommand, String registerCommand,
                        List<String> loginPatterns, List<String> registerPatterns, List<String> successPatterns,
                        List<String> failurePatterns, int delayMs, List<String> joinCommands) {
        public static final String MODE_NONE = "none";
        public static final String MODE_AUTO = "auto";

        public Login {
            loginPatterns = Copies.list(loginPatterns);
            registerPatterns = Copies.list(registerPatterns);
            successPatterns = Copies.list(successPatterns);
            failurePatterns = Copies.list(failurePatterns);
            joinCommands = Copies.list(joinCommands);
        }

        public static Login defaults() {
            return new Login(MODE_AUTO, "", "/login {password}", "/register {password} {password}",
                    List.of(
                            "(?i)/(?:login|log|l)(?:\\s|$)",
                            "(?i)please,?\\s+(?:log\\s?in|login|authenticate)",
                            "(?iu)авторизуйтесь",
                            "(?iu)войдите\\s+(?:в\\s+(?:аккаунт|игру)|с\\s+помощью|командой)",
                            "(?iu)(?:введите|используйте)\\s+/(?:login|l)\\b"),
                    List.of(
                            "(?i)/(?:register|reg)(?:\\s|$)",
                            "(?i)please,?\\s+register",
                            "(?iu)зарегистрируйтесь",
                            "(?iu)(?:введите|используйте)\\s+/(?:register|reg)\\b"),
                    List.of(
                            "(?i)success(?:ful(?:ly)?)?\\s+(?:logged\\s+in|login|registered|registration|authenticated)",
                            "(?i)logged\\s+in\\s+successfully",
                            "(?i)(?:login|authentication|registration)\\s+successful",
                            "(?iu)успешн\\p{L}*\\s+(?:вош|вход|авториз|зарегистр|регистрац)",
                            "(?iu)(?:вошли|авторизовались|зарегистрировались)\\s+успешно"),
                    List.of(
                            "(?i)(?:wrong|incorrect|invalid)\\s+password",
                            "(?i)password\\s+(?:is\\s+)?(?:wrong|incorrect|invalid)",
                            "(?iu)(?:неверный|неправильный)\\s+пароль",
                            "(?iu)пароль\\s+(?:неверный|неправильный|не\\s+подходит)"),
                    1500, List.of());
        }
    }

    /** Companion plugin handshake; {@code token} must equal the plugin's configured token. */
    public record Companion(boolean enabled, String token) {
        public static Companion defaults() {
            return new Companion(false, "");
        }
    }

    /** Always-on behaviours (SPEC §4.3). {@code lowToolDurability} is a fraction 0..1 of max durability. */
    public record Behaviour(boolean autoRespawn, AutoEat autoEat, Defense defense, DeathRecovery deathRecovery,
                            int inventoryFullFreeSlots, float lowToolDurability, int pickupRadius) {
        public Behaviour {
            autoEat = autoEat == null ? AutoEat.defaults() : autoEat;
            defense = defense == null ? Defense.defaults() : defense;
            deathRecovery = deathRecovery == null ? DeathRecovery.defaults() : deathRecovery;
        }

        public static Behaviour defaults() {
            return new Behaviour(true, AutoEat.defaults(), Defense.defaults(), DeathRecovery.defaults(), 1, 0.08f, 8);
        }
    }

    /** Eat when food is below {@code belowFood} or health below {@code belowHealth}; {@code avoid} = item globs. */
    public record AutoEat(boolean enabled, int belowFood, float belowHealth, List<String> avoid) {
        public AutoEat {
            avoid = Copies.list(avoid);
        }

        public static AutoEat defaults() {
            return new AutoEat(true, 14, 10.0f, List.of(
                    "minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:poisonous_potato",
                    "minecraft:pufferfish", "minecraft:chorus_fruit", "minecraft:suspicious_stew"));
        }
    }

    /**
     * Reaction to hostiles within {@code radius}: {@code fight}, {@code flee} or {@code ignore}. The bot flees
     * below {@code fleeBelowHealth} or from {@code avoidEntities} / names matching {@code avoidNamePatterns}.
     */
    public record Defense(String mode, int radius, float fleeBelowHealth, List<String> avoidEntities,
                          List<String> avoidNamePatterns, boolean retaliatePlayers) {
        public static final String MODE_FIGHT = "fight";
        public static final String MODE_FLEE = "flee";
        public static final String MODE_IGNORE = "ignore";

        public Defense {
            avoidEntities = Copies.list(avoidEntities);
            avoidNamePatterns = Copies.list(avoidNamePatterns);
        }

        public static Defense defaults() {
            return new Defense(MODE_FIGHT, 6, 6.0f, List.of("minecraft:creeper", "minecraft:warden"), List.of(), false);
        }
    }

    /** Walk back to the death point (if within {@code maxDistance}) and pick items up within {@code timeoutSec}. */
    public record DeathRecovery(boolean enabled, int maxDistance, int timeoutSec) {
        public static DeathRecovery defaults() {
            return new DeathRecovery(true, 2000, 240);
        }
    }

    /**
     * Blocks (globs) the bot must never break, zones where it must not break or place anything, and {@code built}:
     * globs of player-built blocks (planks, glass, doors, ...) that are never broken either, except inside the box of
     * the running area task ({@code selection}, {@code build}, {@code farm}) — which may also work inside zones.
     */
    public record Protection(boolean enabled, List<String> noBreak, List<Zone> zones, List<String> built) {
        public Protection {
            noBreak = Copies.list(noBreak);
            zones = Copies.list(zones);
            built = Copies.list(built);
        }

        public static Protection defaults() {
            return new Protection(true, List.of(
                    "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:ender_chest",
                    "minecraft:*shulker_box", "minecraft:*_bed", "minecraft:spawner",
                    "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
                    "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper"), List.of(), List.of());
        }
    }

    /** Protected box in a dimension. */
    public record Zone(String dim, Box box) {
    }

    /** Client-side load reduction (SPEC §4.4). */
    public record ClientOpts(boolean lowPower, boolean skipRender, boolean muteSounds, int maxFps, int renderDistance) {
        public static ClientOpts defaults() {
            return new ClientOpts(true, true, true, 10, 4);
        }
    }

    /** {@code status} message cadence in client ticks. */
    public record StatusOpts(int activeIntervalTicks, int idleIntervalTicks) {
        public static StatusOpts defaults() {
            return new StatusOpts(20, 100);
        }
    }

    /**
     * In-game commands from the owner (SPEC §5.7e): chat lines from {@code player} whose message starts with
     * {@code prefix} become {@code owner_command} events. Signed player chat carries its sender; system lines
     * (chat plugins, other whisper formats) are matched with {@code patterns}, Java regexes with the named groups
     * {@code name} and {@code msg}, tried in order with {@code find()} on the plain text; the first matching
     * pattern decides who spoke, and the empty group {@code (?<dm>)} marks a private-message format. An empty
     * {@code player} disables the feature.
     */
    public record Owner(String player, String prefix, List<String> patterns) {
        public Owner {
            player = player == null ? "" : player.trim();
            prefix = prefix == null || prefix.isBlank() ? "!b" : prefix.trim();
            patterns = Copies.list(patterns);
        }

        public static Owner defaults() {
            return new Owner("", "!b", List.of(
                    // vanilla / Paper public chat rendered as a system line: <Name> text
                    "^<(?<name>[A-Za-z0-9_]{1,16})> (?<msg>.*)$",
                    // vanilla whispers (English and Russian client language)
                    "^(?<dm>)(?<name>[A-Za-z0-9_]{1,16}) whispers to you: (?<msg>.*)$",
                    "^(?<dm>)(?<name>[A-Za-z0-9_]{1,16}) шепчет вам: (?<msg>.*)$",
                    // Essentials / CMI style private messages: [Name -> me] text
                    "^(?<dm>)\\[(?<name>[A-Za-z0-9_]{1,16}) (?:->|→|»|>>) (?:me|you|я|вам|мне)\\] (?<msg>.*)$",
                    // chat plugins: optional [rank] / [G] tags, then Name: text or Name » text
                    "^(?:\\[[^\\]]{0,24}\\]\\s*){0,3}(?<name>[A-Za-z0-9_]{1,16})\\s*(?::|»|>>|->|→)\\s*(?<msg>.*)$"));
        }

        public boolean enabled() {
            return !player.isEmpty();
        }
    }
}
