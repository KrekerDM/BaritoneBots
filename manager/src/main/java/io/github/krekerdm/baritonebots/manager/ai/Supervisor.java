package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.projects.Project;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The AI supervisor (SPEC §5.7d). Every {@code ai.superviseSec} per running project, and soon after a
 * {@code project_blocked}, a repeated {@code stuck} or a {@code death} of one of its bots (debounced), it sends a
 * compact digest to the model and gets {@code {summary, actions[]}} back. Actions pass {@link PlanValidator#action}
 * (whitelist + live checks); in mode {@code suggest} they wait in the feed for Apply / Dismiss, in mode {@code auto}
 * they are applied at once, each logged with its reason. A failed round becomes a feed entry and nothing else
 * changes: the planner keeps working on its own. Loop-owned; the model round runs on the HTTP client's threads.
 */
final class Supervisor {
    static final long TICK_MS = 5_000;
    static final long URGENT_DEBOUNCE_MS = 30_000;
    static final long STUCK_WINDOW_MS = 10 * 60_000;
    static final int FEED_KEPT = 60;
    static final int EVENTS_IN_DIGEST = 30;
    static final String PENDING = "pending";
    static final String APPLIED = "applied";
    static final String DISMISSED = "dismissed";
    static final String REJECTED = "rejected";
    static final String FAILED = "failed";
    static final String EXPIRED = "expired";

    private static final class State {
        final long seenAt;
        long lastRunAt;
        boolean inFlight;
        String urgent;
        final List<CompletableFuture<JsonObject>> waiting = new ArrayList<>();

        State(long seenAt) {
            this.seenAt = seenAt;
        }
    }

    private final Manager m;
    private final AiService ai;
    private final Map<String, State> states = new HashMap<>();
    private final Map<String, Deque<JsonObject>> feeds = new HashMap<>();
    private final Map<String, Deque<Long>> stuckAt = new HashMap<>();
    private final Map<String, String> lastProjectOf = new HashMap<>();
    private ScheduledFuture<?> timer;

    Supervisor(Manager m, AiService ai) {
        this.m = m;
        this.ai = ai;
    }

    void start() {
        if (timer == null) {
            timer = m.loop.every(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    void stop() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private ManagerConfig.AiCfg cfg() {
        return m.config.get().ai();
    }

    // ------------------------------------------------------------------ scheduling

    void tick() {
        long now = System.currentTimeMillis();
        Set<String> running = new HashSet<>();
        for (Project p : m.projects.all()) {
            if (!Project.RUNNING.equals(p.status)) {
                continue;
            }
            running.add(p.id);
            for (Assignment a : m.planner.assignmentsOf(p.id)) {
                lastProjectOf.put(a.botId, p.id);
            }
            if (!cfg().enabled()) {
                continue;
            }
            State s = states.computeIfAbsent(p.id, k -> new State(now));
            if (s.inFlight) {
                continue;
            }
            boolean due = now - Math.max(s.lastRunAt, s.seenAt) >= cfg().superviseSec() * 1000L;
            boolean urgent = s.urgent != null && now - s.lastRunAt >= URGENT_DEBOUNCE_MS;
            if (due || urgent) {
                run(p, urgent ? s.urgent : "timer");
            }
        }
        for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, State> e = it.next();
            if (!running.contains(e.getKey()) && !e.getValue().inFlight) {
                it.remove();
                expirePending(e.getKey());
            }
        }
        lastProjectOf.values().removeIf(pid -> !running.contains(pid));
    }

    /** Event log listener (loop): blocked / repeated stuck / death → an early round for the bot's project. */
    void onEvent(ManagerEvent e) {
        if (!cfg().enabled() || e.kind() == null || e.kind().startsWith("ai_")) {
            return;
        }
        String pid = null;
        String why = null;
        switch (e.kind()) {
            case "project_blocked" -> {
                pid = e.data() == null ? null : Json.getString(e.data(), "projectId", null);
                why = "blocked";
            }
            case EventKinds.DEATH -> {
                pid = projectOfBot(e.botId());
                why = "death";
            }
            case "stuck", "stuck_retry" -> {
                if (e.botId() != null) {
                    long now = System.currentTimeMillis();
                    Deque<Long> times = stuckAt.computeIfAbsent(e.botId(), k -> new ArrayDeque<>());
                    times.addLast(now);
                    while (!times.isEmpty() && now - times.peekFirst() > STUCK_WINDOW_MS) {
                        times.pollFirst();
                    }
                    if (times.size() >= 2) {
                        pid = projectOfBot(e.botId());
                        why = "stuck";
                    }
                }
            }
            default -> {
                // other events only show up in the digest
            }
        }
        if (pid != null) {
            urgent(pid, why);
        }
    }

    private String projectOfBot(String botId) {
        if (botId == null) {
            return null;
        }
        Assignment a = m.planner.assignmentOf(botId);
        if (a != null && m.projects.get(a.source.id()) != null) {
            return a.source.id();
        }
        return lastProjectOf.get(botId);
    }

    private void urgent(String pid, String why) {
        Project p = m.projects.get(pid);
        if (p == null || !Project.RUNNING.equals(p.status)) {
            return;
        }
        long now = System.currentTimeMillis();
        State s = states.computeIfAbsent(pid, k -> new State(now));
        s.urgent = why;
        if (!s.inFlight && now - s.lastRunAt >= URGENT_DEBOUNCE_MS) {
            m.loop.post(() -> { // never inside the event listener
                State st = states.get(pid);
                Project pr = m.projects.get(pid);
                if (st != null && st.urgent != null && !st.inFlight && pr != null && Project.RUNNING.equals(pr.status)) {
                    run(pr, st.urgent);
                }
            });
        }
    }

    /** A round now (POST /api/ai/supervise); completes with the new feed entry. */
    CompletableFuture<JsonObject> runNow(Project p) {
        if (!Project.RUNNING.equals(p.status)) {
            throw ApiException.conflict("bad_status", "the project is not running");
        }
        State s = states.computeIfAbsent(p.id, k -> new State(System.currentTimeMillis()));
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        s.waiting.add(f);
        if (!s.inFlight) {
            run(p, "manual");
        }
        return f;
    }

    // ------------------------------------------------------------------ one round

    private void run(Project p, String trigger) {
        long now = System.currentTimeMillis();
        State s = states.computeIfAbsent(p.id, k -> new State(now));
        s.inFlight = true;
        s.lastRunAt = now;
        s.urgent = null;
        ManagerConfig.AiCfg c = cfg();
        AiContext ctx = AiContext.capture(m, null, p.bots);
        String user = "Trigger: " + trigger + "\nDigest:\n" + Json.toJson(digest(p, now));
        String pid = p.id;
        ai.chat(c, AiPrompts.superviseSystem(ctx), user, AiPrompts.superviseSchema(ctx))
                .whenComplete((out, err) -> m.loop.post(() -> finish(pid, trigger, out, err)));
    }

    private void finish(String pid, String trigger, JsonObject out, Throwable err) {
        State s = states.get(pid);
        List<CompletableFuture<JsonObject>> waiting = new ArrayList<>();
        if (s != null) {
            s.inFlight = false;
            waiting.addAll(s.waiting);
            s.waiting.clear();
        }
        Project p = m.projects.get(pid);
        JsonObject entry = null;
        if (p != null) {
            entry = err != null ? errorEntry(pid, trigger, AiService.unwrap(err)) : summaryEntry(p, trigger, out);
        }
        for (CompletableFuture<JsonObject> f : waiting) {
            f.complete(entry);
        }
    }

    private JsonObject summaryEntry(Project p, String trigger, JsonObject out) {
        long now = System.currentTimeMillis();
        expirePending(p.id);
        AiContext ctx = AiContext.capture(m, null, p.bots);
        JsonArray actions = new JsonArray();
        JsonArray raw = Json.getArr(out, "actions");
        for (int i = 0; raw != null && i < raw.size(); i++) {
            JsonElement el = raw.get(i);
            JsonObject a = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
            JsonObject act = Json.obj("id", Tokens.id("act"), "reason",
                    PlanValidator.cut(Json.getString(a, "reason", "").trim(), 300));
            try {
                if (i >= PlanValidator.MAX_ACTIONS) {
                    throw new PlanValidator.Reject("too_many", String.valueOf(PlanValidator.MAX_ACTIONS));
                }
                JsonObject v = PlanValidator.action(a, ctx, p.serverId, p.bots);
                act.add("type", v.get("type"));
                act.add("args", v.get("args"));
                act.addProperty("status", PENDING);
            } catch (PlanValidator.Reject r) {
                act.addProperty("type", PlanValidator.cut(Json.getString(a, "type", ""), 40));
                act.addProperty("raw", PlanValidator.cut(Json.toJson(el), 200));
                act.addProperty("status", REJECTED);
                act.addProperty("why", r.reason());
                act.addProperty("detail", r.detail());
            }
            actions.add(act);
        }
        String summary = PlanValidator.cut(Json.getString(out, "summary", "").trim(), 600);
        JsonObject entry = Json.obj("id", Tokens.id("feed"), "time", now, "projectId", p.id, "kind", "summary",
                "trigger", trigger, "summary", summary, "actions", actions, "model", cfg().model());
        add(p.id, entry);
        if (cfg().auto()) {
            for (JsonElement e : actions) {
                JsonObject act = e.getAsJsonObject();
                if (PENDING.equals(Json.getString(act, "status", ""))) {
                    apply(p, act, ManagerConfig.AiCfg.AUTO);
                }
            }
        }
        ai.broadcast(p.id, entry);
        return entry;
    }

    /** Unreachable model / invalid answer: one feed entry, repeated failures of the same kind are counted on it. */
    private JsonObject errorEntry(String pid, String trigger, AiException e) {
        Deque<JsonObject> feed = feeds.computeIfAbsent(pid, k -> new ArrayDeque<>());
        JsonObject last = feed.peekLast();
        long now = System.currentTimeMillis();
        if (last != null && "error".equals(Json.getString(last, "kind", ""))
                && e.code().equals(Json.getString(last, "code", ""))) {
            last.addProperty("count", Json.getInt(last, "count", 1) + 1);
            last.addProperty("time", now);
            last.addProperty("trigger", trigger);
            last.addProperty("message", PlanValidator.cut(String.valueOf(e.getMessage()), 300));
            ai.broadcast(pid, last);
            return last;
        }
        JsonObject entry = Json.obj("id", Tokens.id("feed"), "time", now, "projectId", pid, "kind", "error",
                "trigger", trigger, "code", e.code(), "message", PlanValidator.cut(String.valueOf(e.getMessage()), 300),
                "count", 1);
        add(pid, entry);
        ai.broadcast(pid, entry);
        return entry;
    }

    private void add(String pid, JsonObject entry) {
        Deque<JsonObject> feed = feeds.computeIfAbsent(pid, k -> new ArrayDeque<>());
        feed.addLast(entry);
        while (feed.size() > FEED_KEPT) {
            feed.pollFirst();
        }
    }

    /** A new round supersedes earlier suggestions: the model sees fresh data and repeats what still applies. */
    private void expirePending(String pid) {
        Deque<JsonObject> feed = feeds.get(pid);
        if (feed == null) {
            return;
        }
        for (JsonObject entry : feed) {
            boolean changed = false;
            JsonArray actions = Json.getArr(entry, "actions");
            for (int i = 0; actions != null && i < actions.size(); i++) {
                JsonObject act = actions.get(i).getAsJsonObject();
                if (PENDING.equals(Json.getString(act, "status", ""))) {
                    act.addProperty("status", EXPIRED);
                    changed = true;
                }
            }
            if (changed) {
                ai.broadcast(pid, entry);
            }
        }
    }

    // ------------------------------------------------------------------ applying

    /** Apply / Dismiss from the panel. */
    JsonObject decide(String actionId, boolean apply) {
        for (Map.Entry<String, Deque<JsonObject>> f : feeds.entrySet()) {
            for (JsonObject entry : f.getValue()) {
                JsonArray actions = Json.getArr(entry, "actions");
                for (int i = 0; actions != null && i < actions.size(); i++) {
                    JsonObject act = actions.get(i).getAsJsonObject();
                    if (!actionId.equals(Json.getString(act, "id", ""))) {
                        continue;
                    }
                    if (!PENDING.equals(Json.getString(act, "status", ""))) {
                        throw ApiException.conflict("not_pending", "the suggestion is " + Json.getString(act, "status", ""));
                    }
                    Project p = m.projects.require(f.getKey());
                    if (apply) {
                        apply(p, act, "owner");
                    } else {
                        act.addProperty("status", DISMISSED);
                        act.addProperty("decidedAt", System.currentTimeMillis());
                    }
                    ai.broadcast(p.id, entry);
                    return entry;
                }
            }
        }
        throw ApiException.notFound("suggestion '" + actionId + "'");
    }

    /** Runs one validated action; {@code by} = auto | owner. Every applied action is logged with its reason. */
    void apply(Project p, JsonObject act, String by) {
        String type = Json.getString(act, "type", "");
        JsonObject args = Json.getObj(act, "args") == null ? new JsonObject() : Json.getObj(act, "args");
        String bot = Json.getString(args, "bot", null);
        try {
            // checked again on the live state: a suggestion may wait a while for its click
            AiContext ctx = AiContext.capture(m, null, p.bots);
            JsonObject flat = args.deepCopy();
            flat.addProperty("type", type);
            JsonObject v = PlanValidator.action(flat, ctx, p.serverId, p.bots);
            args = v.getAsJsonObject("args");
            switch (type) {
                case "set_priority" -> m.projects.replace(p.id, Json.obj("priority", args.get("priority")));
                case "reassign" -> {
                    BotState b = m.bots.require(bot);
                    Assignment a = m.planner.assignmentOf(b.id);
                    if (a != null) {
                        m.planner.release(a, "ai", true);
                    }
                    m.planner.roles().assign(b.id, Json.getString(args, "role", null), System.currentTimeMillis());
                    m.planner.tickSoon();
                }
                case "add_standing_order" -> act.addProperty("orderId", ai.addOrder(args));
                case "pause_project" -> m.projects.action(p.id, "pause");
                case "resume_project" -> m.projects.action(p.id, "resume");
                case "add_task" -> {
                    BotState b = m.bots.require(bot);
                    QueueEntry e = m.dispatcher.addTemplate(b, args.getAsJsonObject("step").deepCopy(),
                            TaskQueue.Mode.APPEND, AiService.ORIGIN_PREFIX + Json.getString(act, "id", ""));
                    act.addProperty("taskId", e.id());
                }
                case "notify" -> m.event("ai_notify", Levels.WARN, null, "event.ai.notify",
                        Map.of("name", p.name, "text", Json.getString(args, "text", "")), Json.obj("projectId", p.id));
                default -> throw new PlanValidator.Reject("unknown_action", type);
            }
            act.addProperty("status", APPLIED);
            act.addProperty("by", by);
            act.addProperty("decidedAt", System.currentTimeMillis());
            m.event("ai_action", Levels.INFO, bot, "event.ai.action",
                    Map.of("name", p.name, "action", ai.describe(type, args), "reason", Json.getString(act, "reason", ""),
                            "by", ai.label("aiBy." + by)), Json.obj("projectId", p.id, "action", act.deepCopy()));
        } catch (PlanValidator.Reject r) {
            failed(act, r.reason(), r.detail());
        } catch (ApiException e) {
            failed(act, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            failed(act, "error", String.valueOf(e.getMessage()));
        }
    }

    private static void failed(JsonObject act, String why, String detail) {
        act.addProperty("status", FAILED);
        act.addProperty("why", why);
        act.addProperty("detail", PlanValidator.cut(detail, 300));
        act.addProperty("decidedAt", System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ views

    JsonObject feed(String pid) {
        Deque<JsonObject> feed = feeds.getOrDefault(pid, new ArrayDeque<>());
        JsonArray entries = new JsonArray();
        int pending = 0;
        for (Iterator<JsonObject> it = feed.descendingIterator(); it.hasNext(); ) {
            JsonObject e = it.next();
            entries.add(e.deepCopy());
            JsonArray actions = Json.getArr(e, "actions");
            for (int i = 0; actions != null && i < actions.size(); i++) {
                if (PENDING.equals(Json.getString(actions.get(i).getAsJsonObject(), "status", ""))) {
                    pending++;
                }
            }
        }
        State s = states.get(pid);
        Long next = null;
        if (s != null && cfg().enabled()) {
            next = Math.max(s.lastRunAt, s.seenAt) + cfg().superviseSec() * 1000L;
        }
        return Json.obj("projectId", pid, "enabled", cfg().enabled(), "mode", cfg().mode(),
                "inFlight", s != null && s.inFlight, "nextAt", next, "pending", pending, "entries", entries);
    }

    // ------------------------------------------------------------------ digest

    /** Compact project state for the model: progress, rate, ETA, deficits with sources, assignments, last events. */
    JsonObject digest(Project p, long now) {
        JsonObject v = m.projects.view(p, true);
        JsonObject d = new JsonObject();
        d.add("project", Json.obj("id", p.id, "name", p.name, "kind", p.kind, "status", p.status,
                "priority", p.priority, "bots", p.botsJson(),
                "runningMin", p.startedAt > 0 ? (now - p.startedAt) / 60_000 : null));
        JsonObject progress = Json.getObj(v, "progress");
        if (progress != null) {
            d.add("progress", progress);
        }
        JsonArray bom = Json.getArr(v, "bom");
        if (bom != null) {
            List<JsonObject> rows = new ArrayList<>();
            bom.forEach(r -> {
                if (r.isJsonObject() && Json.getInt(r.getAsJsonObject(), "deficit", 0) > 0) {
                    rows.add(r.getAsJsonObject());
                }
            });
            rows.sort(Comparator.comparingInt((JsonObject r) -> -Json.getInt(r, "deficit", 0)));
            JsonArray deficits = new JsonArray();
            rows.stream().limit(15).forEach(r -> deficits.add(Json.obj("item", Json.getString(r, "item", ""),
                    "deficit", Json.getInt(r, "deficit", 0), "source", Json.getString(r, "source", null))));
            d.add("deficits", deficits);
        }
        d.add("manual", limit(Json.getArr(v, "manual"), 10));
        d.add("blocked", limit(Json.getArr(v, "blocked"), 10));
        JsonArray assignments = new JsonArray();
        Set<String> involved = new HashSet<>();
        for (Assignment a : m.planner.assignmentsOf(p.id)) {
            involved.add(a.botId);
            assignments.add(Json.obj("bot", a.botId, "role", a.item.role(),
                    "work", a.item.label() != null ? a.item.label() : a.item.kind(), "phase", a.phase,
                    "forSec", (now - a.since) / 1000));
        }
        d.add("assignments", assignments);
        JsonArray bots = new JsonArray();
        for (BotState b : m.bots.all()) {
            if (!b.def.enabled() || !p.allows(b) || bots.size() >= 16) {
                continue;
            }
            involved.add(b.id);
            JsonObject o = Json.obj("id", b.id, "online", b.online() && !b.dead, "roles", b.def.roles());
            if (b.status != null) {
                o.addProperty("hp", Math.round(b.status.health()));
                o.addProperty("food", b.status.food());
                o.addProperty("freeSlots", b.status.freeSlots());
                if (b.status.task() != null) {
                    o.addProperty("task", b.status.task().type());
                }
            }
            bots.add(o);
        }
        d.add("bots", bots);
        JsonArray orders = new JsonArray();
        for (ManagerConfig.OrderDef o : m.config.get().orders()) {
            if (o.serverId() != null && o.serverId().equalsIgnoreCase(p.serverId) && orders.size() < 10) {
                orders.add(Json.obj("item", o.item(), "min", o.min(), "into", o.into(), "enabled", o.enabled()));
            }
        }
        d.add("orders", orders);
        List<ManagerEvent> recent = new ArrayList<>();
        for (ManagerEvent e : m.events.query(500, null, null)) {
            boolean mine = e.data() != null && p.id.equals(Json.getString(e.data(), "projectId", null))
                    || e.botId() != null && involved.contains(e.botId());
            if (mine && (e.kind() == null || !e.kind().startsWith("ai_"))) {
                recent.add(e);
            }
        }
        JsonArray events = new JsonArray();
        for (ManagerEvent e : recent.subList(Math.max(0, recent.size() - EVENTS_IN_DIGEST), recent.size())) {
            events.add(Json.obj("agoSec", (now - e.time()) / 1000, "kind", e.kind(), "bot", e.botId(),
                    "level", e.level(), "text", PlanValidator.cut(e.message(), 140)));
        }
        d.add("events", events);
        return d;
    }

    private static JsonArray limit(JsonArray a, int n) {
        JsonArray out = new JsonArray();
        for (int i = 0; a != null && i < a.size() && i < n; i++) {
            out.add(a.get(i));
        }
        return out;
    }
}
