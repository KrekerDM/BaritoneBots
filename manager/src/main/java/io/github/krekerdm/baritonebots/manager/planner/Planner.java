package io.github.krekerdm.baritonebots.manager.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The automatic planner (SPEC §5.7): every {@code planner.tickSec} it collects work items from the registered
 * {@link WorkSource}s, matches idle bots to them ({@link Matcher}), keeps locks, roles and retries, and turns each
 * assignment into ordinary queue entries the {@link io.github.krekerdm.baritonebots.manager.tasks.Dispatcher} runs.
 * Manual work always wins: queueing a manual task cancels the bot's planner work. Everything runs on the loop.
 */
public final class Planner implements io.github.krekerdm.baritonebots.manager.tasks.Dispatcher.Hooks {
    /** An assignment whose bot stays offline this long is released. */
    static final long OFFLINE_GRACE_MS = 60_000;
    /** A bot whose planner work was cancelled by hand rests this long before it gets new work. */
    static final long CANCEL_REST_MS = 60_000;

    private final Manager m;
    private final Locks locks = new Locks();
    private final RoleTracker roles = new RoleTracker();
    private final RetryBook retries = new RetryBook();
    private final Map<String, WorkSource> sources = new LinkedHashMap<>();
    private final Map<String, Assignment> assignments = new LinkedHashMap<>();
    private final Map<String, Long> restUntil = new HashMap<>();
    private final Map<String, Long> offlineSince = new HashMap<>();
    private ScheduledFuture<?> timer;
    private int timerSec;
    private boolean soon;
    private Runnable afterTick;

    public Planner(Manager m) {
        this.m = m;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts (or re-times) the tick; call on the loop. */
    public void start() {
        int sec = Math.max(1, m.config.get().planner().tickSec());
        if (timer != null && sec == timerSec) {
            return;
        }
        if (timer != null) {
            timer.cancel(false);
        }
        timerSec = sec;
        timer = m.loop.every(this::tick, sec, sec, TimeUnit.SECONDS);
    }

    /** Applies a changed {@code planner.tickSec} when the tick is running. */
    public void retime() {
        if (timer != null) {
            start();
        }
    }

    public void stop() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    /** Runs after every tick (projects broadcast their views). */
    public void setAfterTick(Runnable r) {
        afterTick = r;
    }

    /** A tick in ~300 ms (debounced), e.g. after an assignment ended so the bot gets new work quickly. */
    public void tickSoon() {
        if (!soon) {
            soon = true;
            m.loop.schedule(() -> {
                soon = false;
                tick();
            }, 300, TimeUnit.MILLISECONDS);
        }
    }

    public void addSource(WorkSource s) {
        sources.put(s.id(), s);
        retries.clear(s.id() + "/");
        tickSoon();
    }

    /** Unregisters a source and cancels its assignments (pause / stop / delete). */
    public void removeSource(String id, String why) {
        WorkSource s = sources.remove(id);
        if (s == null) {
            return;
        }
        for (Assignment a : List.copyOf(assignments.values())) {
            if (a.source == s) {
                release(a, why, true);
            }
        }
        retries.clear(id + "/");
    }

    public boolean hasSource(String id) {
        return sources.containsKey(id);
    }

    /** Queue origin prefix of the autopilot's work sources (sorting, idle work, discovery inspections, refills). */
    public static final String ORIGIN_AUTOPILOT_PREFIX = "auto:";
    /** Queue origin prefix of standing-order work. */
    public static final String ORIGIN_ORDER_PREFIX = "order:";

    /**
     * Schedule and rule entries ({@code schedule:<id>}, {@code rule:<id>}): user intent, but they do not cancel planner
     * work when queued (they run when the current batch ends); priority {@code high} releases the assignment itself.
     */
    public static boolean isSoftOrigin(String origin) {
        return origin != null && (origin.startsWith("schedule:") || origin.startsWith("rule:"));
    }

    /** Planner work: projects, the autopilot and standing orders. Manual (panel, scenario) entries replace it. */
    public static boolean isPlannerOrigin(String origin) {
        return origin != null && (origin.startsWith(TaskSpec.ORIGIN_PROJECT_PREFIX)
                || origin.startsWith(ORIGIN_AUTOPILOT_PREFIX) || origin.startsWith(ORIGIN_ORDER_PREFIX));
    }

    // ------------------------------------------------------------------ the tick

    public void tick() {
        long now = System.currentTimeMillis();
        m.gameData.ensureLoaded();
        housekeeping(now);
        if (!sources.isEmpty()) {
            assign(now);
        }
        if (afterTick != null) {
            afterTick.run();
        }
    }

    private void housekeeping(long now) {
        for (Assignment a : List.copyOf(assignments.values())) {
            BotState b = m.bots.get(a.botId);
            if (b == null) {
                release(a, "deleted", false);
                continue;
            }
            if (!b.online() || b.dead) {
                long since = offlineSince.computeIfAbsent(a.botId, k -> now);
                if (now - since >= OFFLINE_GRACE_MS) {
                    release(a, "offline", true);
                }
                continue;
            }
            offlineSince.remove(a.botId);
            if (a.batchOpen && a.query == null && !hasEntries(b, a.source.origin())) {
                completeBatch(a, b); // entries vanished without an outcome (queue edited by hand)
            }
        }
    }

    private void assign(long now) {
        ManagerConfig cfg = m.config.get();
        long cooldownMs = cfg.planner().roleSwitchCooldownSec() * 1000L;
        List<Matcher.Offer> offers = new ArrayList<>();
        for (WorkSource s : List.copyOf(sources.values())) {
            List<WorkItem> items;
            try {
                items = s.workItems(now);
            } catch (RuntimeException e) {
                Log.error("work source " + s.id() + " failed", e);
                continue;
            }
            for (WorkItem w : items) {
                if (!retries.ready(w.key(), now)) {
                    continue;
                }
                int busy = (int) assignments.values().stream().filter(a -> a.item.key().equals(w.key())).count();
                int slots = w.capacity() - busy;
                if (w.lock() != null) {
                    slots = Math.min(slots, w.capacity() - locks.count(w.lock()));
                }
                if (slots > 0) {
                    offers.add(new Matcher.Offer(w, slots));
                }
            }
        }
        if (offers.isEmpty()) {
            return;
        }
        List<Matcher.Bot> idle = new ArrayList<>();
        for (BotState b : m.bots.all()) {
            if (isIdle(b, now)) {
                idle.add(new Matcher.Bot(b.id, b.status.dim() == null ? Dims.OVERWORLD : Dims.normalize(b.status.dim()),
                        b.status.pos() == null ? null : b.status.pos().toPos(), b.def.roles()));
            }
        }
        if (idle.isEmpty()) {
            return;
        }
        List<Matcher.Match> matches = Matcher.match(idle, offers, roles, now, cooldownMs, (bot, w) -> {
            WorkSource s = sources.get(w.source());
            BotState b = m.bots.get(bot.id());
            return s != null && b != null && b.def.serverId() != null && b.def.serverId().equalsIgnoreCase(s.serverId())
                    && s.allows(b) && s.eligible(b, w);
        });
        for (Matcher.Match mt : matches) {
            BotState b = m.bots.get(mt.botId());
            WorkSource s = sources.get(mt.item().source());
            if (b == null || s == null || assignments.containsKey(b.id)) {
                continue;
            }
            Assignment a = new Assignment(b.id, s, mt.item(), now);
            if (mt.item().lock() != null && !locks.acquire(mt.item().lock(), b.id, mt.item().capacity())) {
                continue;
            }
            if (mt.item().lock() != null) {
                a.locks.add(mt.item().lock());
            }
            assignments.put(b.id, a);
            if (mt.item().role() != null) {
                roles.assign(b.id, mt.item().role(), now); // role-neutral items (autopilot idle work) keep the role
            }
            try {
                s.begin(a, b);
            } catch (RuntimeException e) {
                Log.error("work source " + s.id() + " could not begin " + mt.item().id(), e);
                fail(a, "error", String.valueOf(e.getMessage()));
            }
        }
    }

    /** Online, alive, enabled, nothing queued, no assignment, not resting. */
    public boolean isIdle(BotState b, long now) {
        return b.def.enabled() && b.online() && !b.dead && b.status != null && b.queue.current() == null
                && b.queue.size() == 0 && !assignments.containsKey(b.id) && restUntil.getOrDefault(b.id, 0L) <= now;
    }

    // ------------------------------------------------------------------ API for work sources

    public Assignment assignmentOf(String botId) {
        return assignments.get(botId);
    }

    public List<Assignment> assignmentsOf(String sourceId) {
        return assignments.values().stream().filter(a -> a.source.id().equals(sourceId)).toList();
    }

    public Locks locks() {
        return locks;
    }

    public RetryBook retries() {
        return retries;
    }

    public RoleTracker roles() {
        return roles;
    }

    /** Queues a batch for the assignment's bot (origin = the source's origin). */
    public void push(Assignment a, List<QueueEntry> entries) {
        BotState b = m.bots.get(a.botId);
        if (a.ended || b == null) {
            return;
        }
        a.batch.clear();
        a.batchOpen = true;
        m.dispatcher.add(b, entries, TaskQueue.Mode.APPEND);
        if (!hasEntries(b, a.source.origin()) && a.batchOpen) {
            m.loop.post(() -> checkBatch(a));
        }
    }

    /** A queue entry for {@link #push}: bot task or manager step, with the source's origin. */
    public static QueueEntry entry(Assignment a, String type, JsonObject args, int timeoutSec, String label) {
        return QueueEntry.of(type, args, timeoutSec, label, a.source.origin());
    }

    /** Sends a query through the assignment's bot; the handler runs on the loop unless the assignment ended. */
    public void query(Assignment a, String kind, JsonObject args, long timeoutMs, BiConsumer<QueryResult, Throwable> handler) {
        BotState b = m.bots.get(a.botId);
        if (b == null || !b.linked()) {
            m.loop.post(() -> {
                if (!a.ended) {
                    handler.accept(null, new IOException("bot not linked"));
                }
            });
            return;
        }
        CompletableFuture<QueryResult> f = b.session.query(kind, args, timeoutMs);
        a.query = f;
        f.whenComplete((r, t) -> m.loop.post(() -> {
            if (a.ended || a.query != f) {
                return;
            }
            a.query = null;
            handler.accept(r, t);
        }));
    }

    /** A query through any bot (BOM, surveys); the handler always runs on the loop. */
    public void queryVia(BotState b, String kind, JsonObject args, long timeoutMs, BiConsumer<QueryResult, Throwable> handler) {
        if (b == null || !b.linked()) {
            m.loop.post(() -> handler.accept(null, new IOException("bot not linked")));
            return;
        }
        b.session.query(kind, args, timeoutMs).whenComplete((r, t) -> m.loop.post(() -> handler.accept(r, t)));
    }

    /** Runs {@code r} after {@code delayMs} if the assignment is still active (waiting for a free container, ...). */
    public void later(Assignment a, long delayMs, Runnable r) {
        m.loop.schedule(() -> {
            if (!a.ended) {
                r.run();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Takes a lock for the rest of the assignment (or until {@link #releaseLock}). */
    public boolean holdLock(Assignment a, String key, int capacity) {
        if (a.ended || !locks.acquire(key, a.botId, capacity)) {
            return false;
        }
        a.locks.add(key);
        return true;
    }

    public void releaseLock(Assignment a, String key) {
        if (a.locks.remove(key)) {
            locks.release(key, a.botId);
        }
        a.reserved.remove(key);
    }

    /** Frees every container lock of the assignment except its work item's own lock (e.g. its furnace). */
    public void releaseContainers(Assignment a) {
        for (String key : List.copyOf(a.locks)) {
            if (key.startsWith("container:") && !key.equals(a.item.lock())) {
                releaseLock(a, key);
            }
        }
    }

    /** Success: clears the item's failure count and frees the bot. */
    public void finish(Assignment a) {
        if (a.ended) {
            return;
        }
        retries.success(a.item.key());
        release(a, "done", false);
    }

    /** Failure: backoff, after {@link RetryBook#MAX_FAILURES} the item is failed (the source is told). */
    public void fail(Assignment a, String reason, String message) {
        if (a.ended) {
            return;
        }
        RetryBook.State st = retries.fail(a.item.key(), reason, System.currentTimeMillis());
        Log.info("planner: %s failed %s (%s %s), attempt %d", a.botId, a.item.key(), reason,
                message == null ? "" : message, st.failures());
        release(a, "failed:" + reason, true);
        if (st.failed()) {
            a.source.onItemFailed(a.item, reason, message);
        }
    }

    /** Ends an assignment. {@code cancelEntries}: remove its queued entries and cancel the running one. */
    public void release(Assignment a, String why, boolean cancelEntries) {
        if (a.ended) {
            return;
        }
        a.ended = true;
        a.batchOpen = false;
        assignments.remove(a.botId, a);
        offlineSince.remove(a.botId);
        for (String key : a.locks) {
            locks.release(key, a.botId);
        }
        a.locks.clear();
        a.reserved.clear();
        BotState b = m.bots.get(a.botId);
        if (cancelEntries && b != null) {
            String origin = a.source.origin();
            if (!b.queue.removeIf(e -> origin.equals(e.origin())).isEmpty()) {
                m.broadcastQueue(b);
            }
            QueueEntry cur = b.queue.current();
            if (cur != null && origin.equals(cur.origin())) {
                m.dispatcher.cancelCurrent(b);
            }
        }
        try {
            a.source.onReleased(a, why);
        } catch (RuntimeException e) {
            Log.error("work source " + a.source.id() + " onReleased failed", e);
        }
        tickSoon();
    }

    private static boolean hasEntries(BotState b, String origin) {
        QueueEntry cur = b.queue.current();
        return cur != null && origin.equals(cur.origin()) || b.queue.anyMatch(e -> origin.equals(e.origin()));
    }

    private void checkBatch(Assignment a) {
        BotState b = m.bots.get(a.botId);
        if (a.ended || !a.batchOpen || b == null || hasEntries(b, a.source.origin())) {
            return;
        }
        completeBatch(a, b);
    }

    private void completeBatch(Assignment a, BotState b) {
        a.batchOpen = false;
        List<Assignment.Result> results = List.copyOf(a.batch);
        a.batch.clear();
        try {
            a.source.onBatchDone(a, b, results);
        } catch (RuntimeException e) {
            Log.error("work source " + a.source.id() + " onBatchDone failed", e);
            fail(a, "error", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ dispatcher hooks

    @Override
    public void onQueued(BotState b, List<QueueEntry> added) {
        boolean manual = added.stream().anyMatch(e -> !isPlannerOrigin(e.origin()) && !isSoftOrigin(e.origin())
                && !QueueEntry.ORIGIN_RECOVERY.equals(e.origin()));
        if (manual && assignments.containsKey(b.id)) {
            m.loop.post(() -> {
                Assignment a = assignments.get(b.id);
                if (a != null) {
                    release(a, "manual", true); // manual queues always win
                }
            });
        }
    }

    @Override
    public void onFinished(BotState b, QueueEntry e, boolean ok, String reason, String message, JsonObject data,
                           boolean requeued) {
        Assignment a = assignments.get(b.id);
        if (a == null || !a.source.origin().equals(e.origin())) {
            return; // not planner work, or the result of a cancel the planner sent itself
        }
        if (requeued) {
            return; // same work comes again
        }
        if (!ok && "cancelled".equals(reason) && !a.ended) {
            // cancelled by hand (panel cancel / clear): give the bot a rest so the user can take over
            restUntil.put(b.id, System.currentTimeMillis() + CANCEL_REST_MS);
            m.loop.post(() -> release(a, "cancelled", true));
            return;
        }
        a.batch.add(new Assignment.Result(e, ok, reason, message, data));
        m.loop.post(() -> checkBatch(a));
    }

    // ------------------------------------------------------------------ world helpers for sources

    public WorldDoc world(String serverId) {
        return serverId == null ? null : m.worlds.get(serverId);
    }

    /** Containers with {@code role} in {@code dim}. */
    public List<WorldDoc.Container> containers(String serverId, String dim, String role) {
        WorldDoc doc = world(serverId);
        if (doc == null) {
            return List.of();
        }
        String d = Dims.normalize(dim);
        return doc.containers.stream().filter(c -> c.hasRole(role) && Dims.normalize(c.dim()).equals(d)).toList();
    }

    /** Items other assignments are about to take from a container. */
    public Map<String, Integer> reservedBy(String containerKey, Assignment except) {
        Map<String, Integer> out = new HashMap<>();
        for (Assignment a : assignments.values()) {
            if (a != except) {
                Map<String, Integer> r = a.reserved.get(containerKey);
                if (r != null) {
                    r.forEach((k, v) -> out.merge(k, v, Integer::sum));
                }
            }
        }
        return out;
    }

    /** Snapshot totals minus other assignments' reservations ({@code null} = never inspected). */
    public Map<String, Integer> available(WorldDoc.Container c, Assignment except) {
        if (c.snapshot() == null) {
            return null;
        }
        Map<String, Integer> totals = new HashMap<>(c.snapshot().totals());
        reservedBy(Locks.container(c.dim(), c.pos()), except).forEach((k, v) -> totals.merge(k, -v, Integer::sum));
        totals.values().removeIf(v -> v <= 0);
        return totals;
    }

    public static String containerKey(WorldDoc.Container c) {
        return Locks.container(c.dim(), c.pos());
    }

    public static Pos posOf(BotState b) {
        return b.status != null && b.status.pos() != null ? b.status.pos().toPos() : null;
    }

    // ------------------------------------------------------------------ views

    public JsonArray assignmentsView(String sourceId) {
        JsonArray out = new JsonArray();
        assignmentsOf(sourceId).forEach(a -> out.add(a.view()));
        return out;
    }

    public JsonObject view() {
        JsonArray as = new JsonArray();
        assignments.values().forEach(a -> {
            JsonObject o = a.view();
            o.addProperty("source", a.source.id());
            as.add(o);
        });
        return io.github.krekerdm.baritonebots.common.json.Json.obj("tickSec", timerSec, "sources",
                io.github.krekerdm.baritonebots.common.json.Json.arrOf(sources.keySet()), "assignments", as);
    }
}
