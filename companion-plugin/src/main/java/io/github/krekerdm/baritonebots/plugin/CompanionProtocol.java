package io.github.krekerdm.baritonebots.plugin;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.plugin.journal.JournalService;
import io.github.krekerdm.baritonebots.plugin.journal.TimeWindow;
import io.github.krekerdm.baritonebots.plugin.rollback.RollbackResult;
import io.github.krekerdm.baritonebots.plugin.rollback.RollbackService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The plugin side of SPEC §7: {@code hello} → {@code welcome} / {@code reject}, {@code rollback} →
 * {@code rollback_result}, {@code journal} → {@code journal_result}. Replies carry {@code re} when the request had
 * an {@code id}. Extra reply fields ({@code error}, {@code target}, {@code minutes}, ...) are additions the bot mod
 * and manager simply pass through. Server thread only.
 */
final class CompanionProtocol {
    static final String FEATURE_DISTANCE = "distance";
    static final String FEATURE_LOGIN = "login";
    static final String FEATURE_JOURNAL = "journal";
    static final String FEATURE_ROLLBACK = "rollback";
    static final String FEATURE_PERMISSIONS = "permissions";
    private static final int MAX_FIELD = 64;

    private final BaritoneBotsPlugin plugin;

    CompanionProtocol(BaritoneBotsPlugin plugin) {
        this.plugin = plugin;
    }

    void handle(Player p, Envelope env) {
        switch (env.t()) {
            case MessageTypes.HELLO -> hello(p, env);
            case MessageTypes.ROLLBACK -> rollback(p, env);
            case MessageTypes.JOURNAL -> journal(p, env);
            default -> plugin.debug("Ignored '" + env.t() + "' from " + p.getName());
        }
    }

    // ------------------------------------------------------------------ handshake

    private void hello(Player p, Envelope env) {
        BotRegistry reg = plugin.registry();
        UUID id = p.getUniqueId();
        if (reg.isVerified(id)) {
            // The bot repeats hello every 2 s until an answer arrives; answer duplicates the same way.
            reply(id, env, MessageTypes.WELCOME, welcome());
            return;
        }
        if (reg.tooManyFailures(id)) {
            plugin.debug("Ignored hello from " + p.getName() + ": too many failed attempts on this connection");
            return;
        }
        PluginSettings s = plugin.settings();
        String code = null;
        if (s.token().isEmpty()) {
            code = "no_token";
        } else if (!Tokens.matches(s.token(), Json.getString(env.d(), "token", ""))) {
            code = "bad_token";
        } else if (s.botName(p.getName()) == null) {
            code = "not_in_bots_list";
        }
        if (code != null) {
            int n = reg.fail(id);
            plugin.getLogger().warning("Rejected bot hello from " + p.getName() + " (" + address(p) + "): " + code
                    + " [" + n + "/" + BotRegistry.MAX_FAILURES + "]");
            reply(id, env, MessageTypes.REJECT,
                    Json.obj("reason", plugin.messages().plain("reject." + code), "code", code));
            return;
        }
        String botId = field(Json.getString(env.d(), "botId", ""));
        String modVersion = field(Json.getString(env.d(), "modVersion", ""));
        plugin.verify(p, botId, modVersion);
        plugin.getLogger().info("Verified bot " + p.getName() + " (bot id '" + botId + "', mod " + modVersion + ")");
        reply(id, env, MessageTypes.WELCOME, welcome());
    }

    private JsonObject welcome() {
        PluginSettings s = plugin.settings();
        List<String> features = new ArrayList<>();
        if (s.distances().enabled()) {
            features.add(FEATURE_DISTANCE);
        }
        if (s.forceLogin().enabled() && plugin.forceLogin().available()) {
            features.add(FEATURE_LOGIN);
        }
        if (plugin.journal() != null) {
            features.add(FEATURE_JOURNAL);
        }
        if (RollbackService.available(s, plugin.journal())) {
            features.add(FEATURE_ROLLBACK);
        }
        if (!s.permissions().isEmpty()) {
            features.add(FEATURE_PERMISSIONS);
        }
        String server = s.serverName().isEmpty() ? Bukkit.getServer().getName() : s.serverName();
        return Json.obj("features", features, "server", server,
                "pluginVersion", plugin.getPluginMeta().getVersion());
    }

    // ------------------------------------------------------------------ requests

    private void rollback(Player p, Envelope env) {
        if (!verified(p, env, MessageTypes.ROLLBACK_RESULT)) {
            return;
        }
        UUID id = p.getUniqueId();
        PluginSettings s = plugin.settings();
        String requested = Json.getString(env.d(), "target", null);
        String target = s.botName(requested);
        int minutes = Json.getInt(env.d(), "minutes", 0);
        String via = s.rollback().commandMode() ? RollbackResult.VIA_COMMAND : RollbackResult.VIA_JOURNAL;
        if (target == null) {
            sendRollback(id, env, String.valueOf(requested), minutes,
                    RollbackResult.failure(via, RollbackResult.NOT_A_BOT));
            return;
        }
        plugin.getLogger().info("Bot " + p.getName() + " requested a rollback of " + target + ", " + minutes + " min");
        plugin.rollback().request(target, minutes, s, plugin.journal(),
                r -> sendRollback(id, env, target, minutes, r));
    }

    private void sendRollback(UUID id, Envelope env, String target, int minutes, RollbackResult r) {
        BaritoneBotsPlugin.Line line = plugin.rollbackLine(target, r);
        reply(id, env, MessageTypes.ROLLBACK_RESULT, Json.obj(
                "ok", r.ok(),
                "restored", Math.max(0, r.restored()),
                "via", r.via(),
                "message", plugin.messages().plain(line.key(), line.args()),
                "error", r.error(),
                "target", target,
                "minutes", minutes,
                "total", r.total(),
                "skipped", r.skipped(),
                "failed", r.failed(),
                "command", r.command()));
    }

    private void journal(Player p, Envelope env) {
        if (!verified(p, env, MessageTypes.JOURNAL_RESULT)) {
            return;
        }
        UUID id = p.getUniqueId();
        PluginSettings s = plugin.settings();
        String requested = Json.getString(env.d(), "target", null);
        String target = s.botName(requested);
        int minutes = Json.getInt(env.d(), "minutes", 0);
        JournalService journal = plugin.journal();
        String error = null;
        if (target == null) {
            error = RollbackResult.NOT_A_BOT;
        } else if (journal == null) {
            error = RollbackResult.UNAVAILABLE;
        } else if (!TimeWindow.validMinutes(minutes, TimeWindow.maxJournalMinutes(s.journal().retentionDays()))) {
            error = RollbackResult.BAD_MINUTES;
        }
        if (error != null) {
            reply(id, env, MessageTypes.JOURNAL_RESULT, journalFailure(error, String.valueOf(requested), minutes));
            return;
        }
        long since = TimeWindow.since(journal.now(), minutes);
        journal.summary(target, since, 0).whenComplete((sum, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("Journal query for " + target + " failed: " + failure);
                reply(id, env, MessageTypes.JOURNAL_RESULT, journalFailure(RollbackResult.ERROR, target, minutes));
                return;
            }
            reply(id, env, MessageTypes.JOURNAL_RESULT, Json.obj("ok", true, "breaks", sum.breaks(),
                    "places", sum.places(), "since", sum.since(), "target", target, "minutes", minutes));
        });
    }

    private JsonObject journalFailure(String error, String target, int minutes) {
        return Json.obj("ok", false, "breaks", 0, "places", 0, "since", 0L, "error", error, "target", target,
                "minutes", minutes, "message", plugin.messages().plain("reason." + error,
                        Messages.arg("max", TimeWindow.maxJournalMinutes(plugin.settings().journal().retentionDays()))));
    }

    /**
     * Requests from unverified connections get one {@code not_verified} answer (so a misconfigured bot learns why),
     * then are ignored without a reply.
     */
    private boolean verified(Player p, Envelope env, String replyType) {
        BotRegistry reg = plugin.registry();
        if (reg.isVerified(p.getUniqueId())) {
            return true;
        }
        if (reg.silence(p.getUniqueId())) {
            reply(p.getUniqueId(), env, replyType, Json.obj("ok", false, "error", RollbackResult.NOT_VERIFIED,
                    "message", plugin.messages().plain("reason." + RollbackResult.NOT_VERIFIED)));
        }
        plugin.debug("Ignored '" + env.t() + "' from unverified " + p.getName());
        return false;
    }

    /** Sends to the player currently online with {@code id} (the request may finish after a reconnect). */
    private void reply(UUID id, Envelope request, String type, JsonObject payload) {
        Player now = Bukkit.getPlayer(id);
        if (now == null) {
            return;
        }
        Envelope out = request.id() != null ? Envelope.reply(type, request.id(), payload) : Envelope.of(type, payload);
        plugin.channel().send(now, out);
    }

    private static String field(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > MAX_FIELD ? t.substring(0, MAX_FIELD) : t;
    }

    private static String address(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? "?" : a.getAddress().getHostAddress();
    }
}
