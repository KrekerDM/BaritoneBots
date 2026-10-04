package io.github.krekerdm.baritonebots.manager.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules (SPEC §5.7b, config {@code rules[]}): "if → then". Triggers are evaluated on events ({@code event}), bot
 * status ({@code healthBelow}), container snapshots and once a minute ({@code containerFull}, {@code itemBelow}),
 * and once a minute through a {@code player} query plus join / leave chat lines ({@code playerOnline}).
 *
 * <p>Event triggers fire on every matching event; state triggers fire when the condition becomes true (a rising
 * edge; at manager start a true condition counts as one). {@code cooldownSec} applies per rule and scope (the bot
 * for bot triggers and bot events, else the server); an edge suppressed by the cooldown stays armed and fires once
 * the cooldown is over if the condition still holds. Steps of bot events and {@code healthBelow} run on that bot (if
 * {@code botIds} allows it); others on the bots {@code botIds} picks. Events emitted by rules and schedules never
 * trigger rules. Loop-owned.
 */
public final class RuleService {
    static final Set<String> IGNORED = Set.of("rule_fired", "rule_skipped", "rule_failed", "schedule_fired",
            "schedule_skipped", "schedule_failed");
    static final long PLAYER_QUERY_MS = 3_000;

    private final Manager m;
    private final StepRunner runner;
    private final Map<String, Trigger> parsed = new HashMap<>();
    private final Map<String, Long> lastFired = new HashMap<>();
    private final Map<String, Boolean> state = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    /** server/player (lower case) → online according to join / leave chat lines. */
    private final Map<String, Boolean> chatOnline = new HashMap<>();
    private final Map<String, JsonObject> status = new HashMap<>();

    RuleService(Manager m, StepRunner runner) {
        this.m = m;
        this.runner = runner;
    }

    /** The parsed trigger of a rule, null when invalid (the validator rejects those). */
    Trigger trigger(ManagerConfig.RuleDef r) {
        String key = r.id() + "|" + r.condition();
        if (!parsed.containsKey(key)) {
            Trigger t = null;
            try {
                t = Trigger.parse(r.condition());
            } catch (IllegalArgumentException ignored) {
                // invalid: never fires
            }
            parsed.put(key, t);
        }
        return parsed.get(key);
    }

    private List<ManagerConfig.RuleDef> rules(String type) {
        List<ManagerConfig.RuleDef> out = new ArrayList<>();
        for (ManagerConfig.RuleDef r : m.config.get().rules()) {
            Trigger t = r.enabled() ? trigger(r) : null;
            if (t != null && t.type().equals(type)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean serverOk(ManagerConfig.RuleDef r, String serverId) {
        return r.serverId() == null || serverId == null || r.serverId().equalsIgnoreCase(serverId);
    }

    // ------------------------------------------------------------------ inputs

    /** Every event of the log (listener; firing is posted so the emitter is never re-entered). */
    public void onEvent(ManagerEvent e) {
        if (e.kind() == null || IGNORED.contains(e.kind())) {
            return;
        }
        BotState b = e.botId() == null ? null : m.bots.get(e.botId());
        if ("chat".equals(e.kind()) && b != null) {
            chatLine(b, e.data() != null && e.data().has("text") ? Json.getString(e.data(), "text", "") : e.message());
        }
        for (ManagerConfig.RuleDef r : rules(Trigger.EVENT)) {
            if (trigger(r).event().equals(e.kind())
                    && (b == null || serverOk(r, String.valueOf(b.def.serverId())))) {
                String scope = r.id() + "/" + (b != null ? b.id : "*");
                m.loop.post(() -> {
                    if (cooldownOk(r, scope)) {
                        fire(r, scope, b, b == null ? r.serverId() : b.def.serverId(), "event " + e.kind());
                    }
                });
            }
        }
    }

    private void chatLine(BotState b, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (ManagerConfig.RuleDef r : rules(Trigger.PLAYER_ONLINE)) {
            String name = trigger(r).player();
            Boolean online = joinLeave(text, name);
            if (online != null) {
                chatOnline.put(String.valueOf(b.def.serverId()).toLowerCase(Locale.ROOT) + "/" + name.toLowerCase(Locale.ROOT),
                        online);
            }
        }
    }

    /** True for a join line of {@code name}, false for a leave line, null otherwise (vanilla EN + common RU texts). */
    static Boolean joinLeave(String text, String name) {
        String w = "[\\p{L}\\p{N}_]";
        String who = "(?iu)(?<!" + w + ")" + Pattern.quote(name) + "(?!" + w + ").*?(?<!" + w + ")";
        if (Pattern.compile(who + "(joined|зашёл|зашел|вошёл|вошел|присоединил)").matcher(text).find()) {
            return true;
        }
        if (Pattern.compile(who + "(left|вышел|покинул)").matcher(text).find()) {
            return false;
        }
        return null;
    }

    /** Bot status: {@code healthBelow}. */
    public void onStatus(BotState b) {
        if (b.status == null || !b.online()) {
            return;
        }
        for (ManagerConfig.RuleDef r : rules(Trigger.HEALTH_BELOW)) {
            if (!serverOk(r, String.valueOf(b.def.serverId())) || !StepRunner.Target.of(r.botIds()).includes(b.id)) {
                continue;
            }
            float h = b.status.health();
            level(r, r.id() + "/" + b.id, h > 0 && h < trigger(r).health(), b, b.def.serverId(),
                    "health " + Math.round(h));
        }
    }

    /** A container snapshot arrived on {@code serverId}: {@code containerFull} and {@code itemBelow}. */
    public void onSnapshot(String serverId) {
        WorldDoc doc = serverId == null ? null : m.worlds.get(serverId);
        if (doc == null) {
            return;
        }
        for (ManagerConfig.RuleDef r : rules(Trigger.CONTAINER_FULL)) {
            if (!serverOk(r, serverId)) {
                continue;
            }
            WorldDoc.Container c = container(doc, trigger(r));
            if (c != null && c.snapshot() != null) {
                level(r, r.id() + "/" + serverId, c.snapshot().free() <= 0, null, serverId,
                        "container " + c.pos() + " full");
            }
        }
        for (ManagerConfig.RuleDef r : rules(Trigger.ITEM_BELOW)) {
            if (!serverOk(r, serverId)) {
                continue;
            }
            Integer stock = stock(doc, trigger(r).item());
            if (stock != null) {
                level(r, r.id() + "/" + serverId, stock < trigger(r).count(), null, serverId,
                        trigger(r).describe() + " (" + stock + ")");
            }
        }
    }

    /** The watched container: by id, or by position (the overworld one when several dimensions have one there). */
    static WorldDoc.Container container(WorldDoc doc, Trigger t) {
        WorldDoc.Container found = null;
        for (WorldDoc.Container c : doc.containers) {
            if (t.container() != null) {
                if (t.container().equals(c.id())) {
                    return c;
                }
            } else if (c.pos().equals(t.pos()) && (found == null || Dims.OVERWORLD.equals(Dims.normalize(c.dim())))) {
                found = c;
            }
        }
        return found;
    }

    /** The item in source containers (storage, kit, supply, inbox, fuel, sorted:*); null when none was inspected. */
    static Integer stock(WorldDoc doc, String item) {
        int n = 0;
        boolean any = false;
        for (WorldDoc.Container c : doc.containers) {
            boolean source = c.sortedCategory() != null || List.of("storage", "kit", "supply", "inbox", "fuel")
                    .stream().anyMatch(c::hasRole);
            if (source && c.snapshot() != null) {
                any = true;
                n += c.snapshot().totals().getOrDefault(item, 0);
            }
        }
        return any ? n : null;
    }

    /** Once a minute: container / item conditions again (armed edges after a cooldown) and player polling. */
    void minute() {
        for (ManagerConfig.ServerProfile sp : m.config.get().servers()) {
            onSnapshot(sp.id());
        }
        for (ManagerConfig.RuleDef r : rules(Trigger.PLAYER_ONLINE)) {
            for (ManagerConfig.ServerProfile sp : m.config.get().servers()) {
                if (serverOk(r, sp.id())) {
                    pollPlayer(r, sp.id());
                }
            }
        }
    }

    private void pollPlayer(ManagerConfig.RuleDef r, String serverId) {
        String name = trigger(r).player();
        String chatKey = serverId.toLowerCase(Locale.ROOT) + "/" + name.toLowerCase(Locale.ROOT);
        BotState asker = m.bots.all().stream().filter(b -> b.online() && serverId.equalsIgnoreCase(String.valueOf(b.def.serverId())))
                .findFirst().orElse(null);
        if (asker == null) {
            return;
        }
        m.planner.queryVia(asker, QueryKinds.PLAYER, Json.obj("name", name), PLAYER_QUERY_MS, (res, err) -> {
            boolean seen = err == null && res != null && res.ok() && Json.getBool(res.data(), "found", false);
            playerState(r, serverId, seen || chatOnline.getOrDefault(chatKey, false));
        });
    }

    void playerState(ManagerConfig.RuleDef r, String serverId, boolean online) {
        level(r, r.id() + "/" + serverId, online, null, serverId, trigger(r).player() + " online");
    }

    // ------------------------------------------------------------------ firing

    private void level(ManagerConfig.RuleDef r, String scope, boolean cond, BotState bot, String serverId, String why) {
        Boolean before = state.put(scope, cond);
        if (!cond) {
            pending.remove(scope);
            return;
        }
        if (before == null || !before) {
            pending.add(scope);
        }
        if (pending.contains(scope) && cooldownOk(r, scope)) {
            pending.remove(scope);
            fire(r, scope, bot, serverId, why);
        }
    }

    private boolean cooldownOk(ManagerConfig.RuleDef r, String scope) {
        Long at = lastFired.get(scope);
        return at == null || System.currentTimeMillis() - at >= Math.max(0, r.cooldownSec()) * 1000L;
    }

    /** {@code POST /api/rules/{id}/run}: fire now (no trigger bot, no cooldown); returns the bots. */
    public List<String> runNow(ManagerConfig.RuleDef r) {
        return fire(r, r.id() + "/manual", null, r.serverId(), "manual");
    }

    /** Queues the rule's steps; returns the bots they went to. */
    List<String> fire(ManagerConfig.RuleDef r, String scope, BotState bot, String serverId, String why) {
        lastFired.put(scope, System.currentTimeMillis());
        String name = r.name() == null || r.name().isBlank() ? r.id() : r.name();
        String server = r.serverId() != null ? r.serverId() : serverId;
        List<BotState> bots = runner.pick(StepRunner.Target.of(r.botIds()), server, bot);
        List<String> queued = new ArrayList<>();
        String error = null;
        for (BotState b : bots) {
            try {
                if (runner.run(b, r.then(), StepRunner.ORIGIN_RULE + r.id(), r.high(), name) > 0) {
                    queued.add(b.id);
                }
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
                break;
            }
        }
        JsonObject st = Json.obj("lastFiredAt", System.currentTimeMillis(), "why", why, "bots", Json.arrOf(queued));
        JsonObject data = Json.obj("ruleId", r.id(), "bots", Json.arrOf(queued), "why", why);
        if (error != null) {
            st.addProperty("error", error);
            m.event("rule_failed", Levels.WARN, bot == null ? null : bot.id, "event.rule.failed",
                    Map.of("name", name, "error", error), data);
        } else if (queued.isEmpty()) {
            String reason = bots.isEmpty() ? "no bot available" : "still busy with the previous run";
            st.addProperty("error", reason);
            m.event("rule_skipped", Levels.INFO, bot == null ? null : bot.id, "event.rule.skipped",
                    Map.of("name", name, "why", why, "reason", reason), data);
        } else {
            m.event("rule_fired", Levels.INFO, queued.size() == 1 ? queued.getFirst() : null, "event.rule.fired",
                    Map.of("name", name, "why", why, "bots", String.join(", ", queued)), data);
        }
        status.put(r.id(), st);
        return queued;
    }

    /** {@code GET /api/rules}: config entries with the parsed trigger, current condition states and last firing. */
    public JsonArray view() {
        JsonArray out = new JsonArray();
        for (ManagerConfig.RuleDef r : m.config.get().rules()) {
            JsonObject o = Json.toObject(r);
            Trigger t = trigger(r);
            o.addProperty("trigger", t == null ? null : t.describe());
            JsonObject states = new JsonObject();
            state.forEach((k, v) -> {
                if (k.startsWith(r.id() + "/")) {
                    states.addProperty(k.substring(r.id().length() + 1), v);
                }
            });
            o.add("state", states);
            JsonObject st = status.get(r.id());
            if (st != null) {
                o.add("last", st.deepCopy());
            }
            out.add(o);
        }
        return out;
    }
}
