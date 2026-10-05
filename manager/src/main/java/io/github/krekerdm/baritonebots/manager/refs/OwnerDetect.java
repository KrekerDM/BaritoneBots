package io.github.krekerdm.baritonebots.manager.refs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.http.ApiException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Owner detection (SPEC §5.7e): while {@code general.ownerPlayer} is empty, the bots pass on every player's line that
 * starts with the command prefix, and once a minute one online bot per server lists the players on the server. The
 * first player who is not one of our bots and writes a command — or the only such player online — becomes the
 * candidate: event {@code owner_candidate} (a warning, so the panel shows it), a whisper asking to confirm in the
 * panel, and {@code owner.candidate} in {@code GET /api/state}. Nothing the candidate writes is run before the user
 * confirms ({@code POST /api/owner/confirm} sets {@code general.ownerPlayer}); a dismissed name is not offered again
 * until the manager restarts. Loop-owned.
 */
public final class OwnerDetect {
    static final long ONLINE_CHECK_MS = 60_000;
    static final String NAME_RE = "[A-Za-z0-9_]{1,16}";

    /** {@code via}: {@code command} (wrote a prefixed line) or {@code online} (the only other player online). */
    public record Candidate(String player, String serverId, String via, long time) {
    }

    private final Manager m;
    private final Set<String> dismissed = new HashSet<>();
    private Candidate candidate;
    private long nextCheck;

    public OwnerDetect(Manager m) {
        this.m = m;
    }

    /** An {@code owner_command} while no owner is configured. */
    void onCommand(BotState b, JsonObject d) {
        String player = Json.getString(d, "player", "").trim();
        if (!player.matches(NAME_RE) || isBot(player) || dismissed.contains(player.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (offer(player, b, "command")) {
            m.owner.replyTo(b, player, "confirmOwner", Map.of("player", player));
        }
    }

    /** After every planner tick: the player list check, once a minute while there is neither owner nor candidate. */
    public void tick(long now) {
        if (m.config.get().general().ownerOrNull() != null) {
            candidate = null;
            return;
        }
        if (candidate != null || now < nextCheck) {
            return;
        }
        nextCheck = now + ONLINE_CHECK_MS;
        for (ManagerConfig.ServerProfile s : m.config.get().servers()) {
            BotState asker = m.bots.all().stream()
                    .filter(b -> b.online() && s.id().equalsIgnoreCase(String.valueOf(b.def.serverId())))
                    .findFirst().orElse(null);
            if (asker == null) {
                continue;
            }
            m.planner.queryVia(asker, QueryKinds.PLAYER, new JsonObject(), 5_000, (r, t) -> {
                if (r == null || !r.ok() || r.data() == null || candidate != null
                        || m.config.get().general().ownerOrNull() != null) {
                    return;
                }
                List<String> others = new ArrayList<>();
                JsonArray online = Json.getArr(r.data(), "online");
                for (JsonElement e : online == null ? new JsonArray() : online) {
                    String name = e.getAsString();
                    if (name.matches(NAME_RE) && !isBot(name) && !dismissed.contains(name.toLowerCase(Locale.ROOT))) {
                        others.add(name);
                    }
                }
                if (others.size() == 1) {
                    offer(others.getFirst(), asker, "online");
                }
            });
        }
    }

    private boolean offer(String player, BotState b, String via) {
        if (candidate != null && candidate.player().equalsIgnoreCase(player)) {
            return false;
        }
        candidate = new Candidate(player, b.def.serverId(), via, System.currentTimeMillis());
        m.event("owner_candidate", Levels.WARN, b.id, "event.owner.candidate." + via, Map.of("player", player),
                Json.obj("player", player, "serverId", b.def.serverId(), "via", via));
        return true;
    }

    private boolean isBot(String name) {
        return m.config.get().bots().stream().anyMatch(d -> name.equalsIgnoreCase(d.username()));
    }

    /** {@code {player, candidate}} for {@code GET /api/state}. */
    public JsonObject view() {
        String owner = m.config.get().general().ownerOrNull();
        return Json.obj("player", owner, "candidate", owner != null || candidate == null ? null
                : Json.obj("player", candidate.player(), "serverId", candidate.serverId(), "via", candidate.via(),
                "time", candidate.time()));
    }

    /** The user confirmed {@code player} (the candidate or a name typed in the panel) as the owner. */
    public void confirm(String player) {
        String p = player == null ? "" : player.trim();
        if (!p.matches(NAME_RE)) {
            throw ApiException.badRequest("bad_player", "a Minecraft name: 1-16 letters, digits or _");
        }
        if (isBot(p)) {
            throw ApiException.badRequest("bot_player", p + " is one of the bots");
        }
        m.config.patch(Json.obj("general", Json.obj("ownerPlayer", p)));
        candidate = null;
        m.event("owner_set", Levels.INFO, null, "event.owner.set", Map.of("player", p), Json.obj("player", p));
    }

    /** "Not the owner": the candidate is dropped and not offered again until the manager restarts. */
    public void dismiss() {
        if (candidate != null) {
            dismissed.add(candidate.player().toLowerCase(Locale.ROOT));
            m.event("owner_candidate", Levels.INFO, null, "event.owner.dismissed", Map.of("player", candidate.player()),
                    Json.obj("player", candidate.player(), "dismissed", true));
            candidate = null;
        }
    }
}
