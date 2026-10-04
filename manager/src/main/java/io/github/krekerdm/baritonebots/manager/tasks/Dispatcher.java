package io.github.krekerdm.baritonebots.manager.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.BotEvent;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskResult;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Queue engine (SPEC §5.5): dispatches queue heads to bots, expands manager-side steps, runs scenarios, applies the
 * {@code inventory_full → deposit_storage + retry} rule, death recovery and the {@code runtime.maxHeavyTasks} limit.
 * Owned by the manager loop.
 */
public final class Dispatcher {
    public static final int MAX_FULL_RETRIES = 50;
    /** A task lost to death / disconnect is dispatched at most this many times in total. */
    public static final int MAX_ATTEMPTS = 3;
    public static final String WAIT_OFFLINE = "offline";
    public static final String WAIT_HEAVY = "heavy_limit";
    /** A cancel the bot never answers is dropped after this long. */
    private static final long CANCEL_GRACE_MS = 10_000;

    // Manager-side steps (catalog "managerSteps").
    public static final String STEP_KIT = "kit";
    public static final String STEP_DEPOSIT_STORAGE = "deposit_storage";
    public static final String STEP_HOME = "home";
    public static final String STEP_WAIT = "wait";
    public static final String STEP_GOTO_WAYPOINT = "goto_waypoint";

    private final Manager m;
    private final Path queuesFile;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private boolean saveScheduled;
    private Hooks hooks;

    /**
     * Observer for the planner (called on the loop, never re-entrantly dispatching: implementations post work).
     * {@code onFinished} sees every finished entry once; {@code requeued} = the dispatcher queued it again
     * (died / disconnected / inventory_full retry), so the work is not over yet.
     */
    public interface Hooks {
        void onQueued(BotState b, List<QueueEntry> added);

        void onFinished(BotState b, QueueEntry e, boolean ok, String reason, String message, JsonObject data,
                        boolean requeued);
    }

    public void setHooks(Hooks hooks) {
        this.hooks = hooks;
    }

    /** One scenario run on one bot. */
    private static final class Run {
        final String id;
        final String scenarioId;
        final String name;
        final String botId;
        final List<JsonObject> steps;
        final boolean repeat;
        int fullRetries;
        int iteration = 1;

        Run(String id, String scenarioId, String name, String botId, List<JsonObject> steps, boolean repeat) {
            this.id = id;
            this.scenarioId = scenarioId;
            this.name = name;
            this.botId = botId;
            this.steps = steps;
            this.repeat = repeat;
        }

        String origin() {
            return TaskSpec.scenarioOrigin(id);
        }
    }

    public Dispatcher(Manager m, Path queuesFile) {
        this.m = m;
        this.queuesFile = queuesFile;
    }

    // ------------------------------------------------------------------ queue API (HTTP)

    /**
     * Validates a TaskTemplate and queues it.
     *
     * @throws io.github.krekerdm.baritonebots.manager.config.ValidationException for bad types/arguments
     * @throws ApiException {@code unsupported} for task types the bot mod does not implement
     */
    public QueueEntry addTemplate(BotState b, JsonObject template, TaskQueue.Mode mode, String origin) {
        JsonObject t = Validators.template(m.catalog, template == null ? new JsonObject() : template, "task");
        String type = Json.getString(t, "type", "");
        if (!m.catalog.isSupported(type)) {
            throw ApiException.badRequest(Reasons.UNSUPPORTED, "task type '" + type + "' is not supported yet");
        }
        QueueEntry e = QueueEntry.fromTemplate(t, origin);
        add(b, List.of(e), mode);
        return e;
    }

    public void add(BotState b, List<QueueEntry> entries, TaskQueue.Mode mode) {
        if (mode == TaskQueue.Mode.REPLACE) {
            dropRuns(b, Reasons.CANCELLED);
        }
        QueueEntry running = b.queue.add(entries, mode);
        if (running != null) {
            abortRunning(b, running);
        }
        if (hooks != null) {
            hooks.onQueued(b, entries);
        }
        changed(b);
        dispatch(b);
    }

    /** Removes a queued entry, or cancels it when it is the running one. */
    public void remove(BotState b, String taskId) {
        QueueEntry cur = b.queue.current();
        if (cur != null && cur.id().equals(taskId)) {
            cancelCurrent(b);
            return;
        }
        if (b.queue.removeQueued(taskId) == null) {
            throw ApiException.notFound("task '" + taskId + "'");
        }
        changed(b);
        dispatch(b);
    }

    public void reorder(BotState b, List<String> ids) {
        b.queue.reorder(ids);
        changed(b);
        dispatch(b);
    }

    /** Cancels the running entry; the bot answers with {@code task_done cancelled}. */
    public void cancelCurrent(BotState b) {
        QueueEntry cur = b.queue.current();
        if (cur == null) {
            return;
        }
        if (m.catalog.isStep(cur.type()) || !b.linked()) {
            cancelWaitTimer(b);
            b.queue.finish(cur.id());
            outcome(b, cur, false, Reasons.CANCELLED, null, null);
            afterFinish(b, cur);
            return;
        }
        b.session.send(MessageTypes.CANCEL, Json.obj("taskId", cur.id()));
        String id = cur.id();
        m.loop.schedule(() -> {
            QueueEntry still = b.queue.current();
            if (still != null && still.id().equals(id)) {
                Log.warn("%s: no task_done after cancel of %s; dropping it", b.id, id);
                b.queue.finish(id);
                outcome(b, still, false, Reasons.CANCELLED, null, null);
                afterFinish(b, still);
            }
        }, CANCEL_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops scenario runs, clears the queue and cancels the running task. */
    public void clear(BotState b) {
        dropRuns(b, Reasons.CANCELLED);
        b.queue.clearQueued();
        changed(b);
        cancelCurrent(b);
    }

    /** Forgets everything about a bot that was deleted. */
    public void forget(BotState b) {
        dropRuns(b, Reasons.CANCELLED);
        b.queue.clearQueued();
        b.queue.abortCurrent();
        cancelWaitTimer(b);
        saveSoon();
    }

    private void abortRunning(BotState b, QueueEntry cur) {
        b.queue.abortCurrent();
        cancelWaitTimer(b);
        if (!m.catalog.isStep(cur.type()) && b.linked()) {
            b.session.send(MessageTypes.CANCEL, Json.obj("taskId", cur.id()));
        }
        if (m.catalog.isHeavy(cur.type())) {
            m.loop.post(this::dispatchAll);
        }
    }

    private void cancelWaitTimer(BotState b) {
        if (b.waitTimer != null) {
            b.waitTimer.cancel(false);
            b.waitTimer = null;
        }
    }

    // ------------------------------------------------------------------ scenarios

    /** Starts a run of {@code scenario} on {@code b}; returns the run id. */
    public String runScenario(JsonObject scenario, BotState b, boolean repeat) {
        List<JsonObject> steps = new ArrayList<>();
        JsonArray a = Json.getArr(scenario, "steps");
        if (a != null) {
            a.forEach(e -> steps.add(e.getAsJsonObject().deepCopy()));
        }
        if (steps.isEmpty()) {
            throw ApiException.badRequest("empty_scenario", "the scenario has no steps");
        }
        String name = Json.getString(scenario, "name", Json.getString(scenario, "id", "?"));
        Run run = new Run(Tokens.id("r"), Json.getString(scenario, "id", ""), name, b.id, steps, repeat);
        runs.put(run.id, run);
        m.event("scenario_started", Levels.INFO, b.id, "event.scenario.started", Map.of("bot", b.id, "name", name));
        add(b, entriesOf(run), TaskQueue.Mode.APPEND);
        return run.id;
    }

    private List<QueueEntry> entriesOf(Run run) {
        List<QueueEntry> out = new ArrayList<>();
        for (JsonObject step : run.steps) {
            out.add(QueueEntry.fromTemplate(step, run.origin()));
        }
        return out;
    }

    private Run runOf(QueueEntry e) {
        String o = e.origin();
        return o != null && o.startsWith(TaskSpec.ORIGIN_SCENARIO_PREFIX)
                ? runs.get(o.substring(TaskSpec.ORIGIN_SCENARIO_PREFIX.length())) : null;
    }

    private void stopRun(Run run, String reason) {
        if (runs.remove(run.id) == null) {
            return;
        }
        BotState b = m.bots.get(run.botId);
        if (b != null) {
            b.queue.removeIf(x -> x.hasOrigin(run.origin()));
        }
        m.event("scenario_stopped", Reasons.CANCELLED.equals(reason) ? Levels.INFO : Levels.WARN, run.botId,
                "event.scenario.stopped", Map.of("bot", run.botId, "name", run.name, "reason", reason));
    }

    private void dropRuns(BotState b, String reason) {
        for (Run r : List.copyOf(runs.values())) {
            if (r.botId.equalsIgnoreCase(b.id)) {
                stopRun(r, reason);
            }
        }
    }

    private void iterationDone(BotState b, Run run) {
        if (run.repeat) {
            run.iteration++;
            b.queue.add(entriesOf(run), TaskQueue.Mode.APPEND);
            return;
        }
        runs.remove(run.id);
        m.event("scenario_done", Levels.INFO, b.id, "event.scenario.done", Map.of("bot", b.id, "name", run.name));
    }

    // ------------------------------------------------------------------ dispatch

    public void dispatchAll() {
        for (BotState b : m.bots.all()) {
            dispatch(b);
        }
    }

    /** Starts the queue head when the bot is free and online; expands manager steps on the way. */
    public void dispatch(BotState b) {
        for (int guard = 0; guard < 64; guard++) {
            if (b.queue.current() != null) {
                setWaiting(b, null);
                return;
            }
            QueueEntry next = b.queue.peek();
            if (next == null) {
                setWaiting(b, null);
                return;
            }
            if (!b.online() || b.dead) {
                setWaiting(b, WAIT_OFFLINE);
                return;
            }
            if (m.catalog.isStep(next.type())) {
                b.queue.poll();
                runStep(b, next);
                continue;
            }
            if (m.catalog.isHeavy(next.type()) && heavyLimitReached(b)) {
                setWaiting(b, WAIT_HEAVY);
                return;
            }
            b.queue.poll();
            if (TaskTypes.RECOVER.equals(next.type()) && next.hasOrigin(QueueEntry.ORIGIN_RECOVERY) && tooFar(b, next)) {
                changed(b);
                continue;
            }
            b.queue.start(next, System.currentTimeMillis());
            b.waiting = null;
            b.session.send(MessageTypes.TASK, next.toSpec());
            changed(b);
            return;
        }
        Log.warn("%s: dispatch stopped after 64 expansions (step loop?)", b.id);
    }

    private void setWaiting(BotState b, String why) {
        if (!java.util.Objects.equals(b.waiting, why)) {
            b.waiting = why;
            changed(b);
        }
    }

    private boolean heavyLimitReached(BotState self) {
        int max = m.config.get().runtime().maxHeavyTasks();
        if (max <= 0) {
            return false;
        }
        int running = 0;
        for (BotState b : m.bots.all()) {
            QueueEntry c = b.queue.current();
            if (b != self && c != null && m.catalog.isHeavy(c.type())) {
                running++;
            }
        }
        return running >= max;
    }

    private boolean tooFar(BotState b, QueueEntry rec) {
        Pos target = Pos.fromJson(rec.args().get("pos"));
        if (target == null || b.status == null || b.status.pos() == null) {
            return false;
        }
        String dim = Json.getString(rec.args(), "dim", Dims.OVERWORLD);
        if (b.status.dim() != null && !Dims.normalize(b.status.dim()).equals(Dims.normalize(dim))) {
            return false;
        }
        int max = m.effectiveConfig(b).behaviour().deathRecovery().maxDistance();
        double d = b.status.pos().toPos().distance(target);
        if (max > 0 && d > max) {
            m.event("recovery_too_far", Levels.WARN, b.id, "event.recovery.tooFar",
                    Map.of("bot", b.id, "dist", (int) d, "max", max));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ manager-side steps

    private void runStep(BotState b, QueueEntry e) {
        switch (e.type()) {
            case STEP_WAIT -> {
                int sec = Math.max(1, Json.getInt(e.args(), "sec", 10));
                b.queue.start(e, System.currentTimeMillis());
                b.waitTimer = m.loop.schedule(() -> finishWait(b, e.id()), sec, TimeUnit.SECONDS);
                changed(b);
            }
            case STEP_HOME -> gotoWaypoint(b, e, b.def.homeWaypointName());
            case STEP_GOTO_WAYPOINT -> gotoWaypoint(b, e, Json.getString(e.args(), "name", ""));
            case STEP_DEPOSIT_STORAGE -> depositStorage(b, e);
            case STEP_KIT -> kit(b, e);
            default -> stepFailed(b, e, Reasons.UNSUPPORTED, "manager step '" + e.type() + "' is not implemented yet");
        }
    }

    private void finishWait(BotState b, String id) {
        b.waitTimer = null;
        QueueEntry e = b.queue.finish(id);
        if (e != null) {
            outcome(b, e, true, null, null, null);
            dispatch(b);
        }
    }

    private void stepFailed(BotState b, QueueEntry e, String reason, String message) {
        outcome(b, e, false, reason, message, null);
    }

    private WorldDoc world(BotState b) {
        return b.def.serverId() == null ? null : m.worlds.get(b.def.serverId());
    }

    private static String botDim(BotState b) {
        return b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
    }

    private static Pos botPos(BotState b) {
        return b.status != null && b.status.pos() != null ? b.status.pos().toPos() : null;
    }

    private QueueEntry child(QueueEntry step, String type, JsonObject args) {
        return new QueueEntry(Tokens.id("t"), type, args, 0, step.label(), step.origin(), System.currentTimeMillis(),
                0, step.fullRetries());
    }

    private void expand(BotState b, List<QueueEntry> children) {
        b.queue.pushFront(children);
        changed(b);
    }

    private void gotoWaypoint(BotState b, QueueEntry e, String name) {
        WorldDoc doc = world(b);
        WorldDoc.Waypoint wp = doc == null ? null : doc.waypoint(name);
        if (wp == null) {
            stepFailed(b, e, Reasons.NOT_FOUND, "waypoint '" + name + "' not found");
            return;
        }
        if (!Dims.normalize(wp.dim()).equals(botDim(b))) {
            stepFailed(b, e, Reasons.PATH_FAILED, "waypoint '" + name + "' is in " + wp.dim());
            return;
        }
        JsonObject args = Json.obj("x", wp.pos().x(), "y", wp.pos().y(), "z", wp.pos().z(), "range", 1);
        expand(b, List.of(child(e, TaskTypes.GOTO, args)));
    }

    /** Storage containers in the bot's dimension, nearest-neighbour order from the bot. */
    private List<Pos> storageFor(BotState b) {
        WorldDoc doc = world(b);
        if (doc == null) {
            return List.of();
        }
        String dim = botDim(b);
        List<Pos> list = doc.containers.stream()
                .filter(c -> c.hasRole("storage") && Dims.normalize(c.dim()).equals(dim))
                .map(WorldDoc.Container::pos).toList();
        return KitPlanner.nearestNeighbour(list, botPos(b), p -> p);
    }

    private void depositStorage(BotState b, QueueEntry e) {
        List<Pos> storage = storageFor(b);
        if (storage.isEmpty()) {
            stepFailed(b, e, Reasons.NOT_FOUND, "no containers with role 'storage' in this dimension");
            return;
        }
        JsonArray keep = new JsonArray();
        m.config.get().planner().depositKeep().forEach(keep::add);
        Json.getStringList(e.args(), "keep").forEach(keep::add);
        JsonObject args = Json.obj("containers", Json.arrOf(storage), "keep", keep);
        expand(b, List.of(child(e, TaskTypes.DEPOSIT, args)));
    }

    private void kit(BotState b, QueueEntry e) {
        String kitId = Json.getString(e.args(), "kitId", "");
        JsonObject kit = m.kits.get(kitId);
        if (kit == null) {
            stepFailed(b, e, Reasons.NOT_FOUND, "kit '" + kitId + "' not found");
            return;
        }
        Map<String, Integer> possessed = new LinkedHashMap<>();
        if (b.status != null) {
            possessed.putAll(b.status.items());
            for (String armor : b.status.armor()) {
                if (armor != null) {
                    possessed.merge(armor, 1, Integer::sum);
                }
            }
        }
        List<KitPlanner.Source> sources = new ArrayList<>();
        WorldDoc doc = world(b);
        String dim = botDim(b);
        if (doc != null) {
            for (String role : List.of("kit", "storage")) {
                for (WorldDoc.Container c : doc.containers) {
                    if (c.hasRole(role) && Dims.normalize(c.dim()).equals(dim)
                            && sources.stream().noneMatch(s -> s.pos().equals(c.pos()))) {
                        sources.add(new KitPlanner.Source(c.pos(), role, c.snapshot() == null ? null : c.snapshot().totals()));
                    }
                }
            }
        }
        boolean inspected = Json.getBool(e.args(), "_inspected", false);
        KitPlanner.Plan plan = KitPlanner.plan(KitPlanner.slotsOf(kit), possessed, sources, botPos(b), inspected);
        String kitName = Json.getString(kit, "name", kitId);
        if (plan.needsInspect()) {
            JsonObject again = e.args().deepCopy();
            again.addProperty("_inspected", true);
            expand(b, List.of(child(e, TaskTypes.INSPECT, Json.obj("containers", Json.arrOf(plan.inspect()))),
                    child(e, STEP_KIT, again)));
            return;
        }
        List<QueueEntry> children = new ArrayList<>();
        for (KitPlanner.Take t : plan.takes()) {
            children.add(child(e, TaskTypes.TAKE, KitPlanner.takeArgs(t)));
        }
        JsonObject equip = Json.obj("armor", true);
        if (plan.offhand() != null) {
            equip.addProperty("offhand", plan.offhand());
        }
        children.add(child(e, TaskTypes.EQUIP, equip));
        if (!plan.missing().isEmpty()) {
            m.event("kit_missing", Levels.WARN, b.id, "event.kit.missing",
                    Map.of("bot", b.id, "kit", kitName, "items", plan.missing().toString()), Json.toObject(plan.missing()));
        }
        expand(b, children);
    }

    // ------------------------------------------------------------------ results and bot events

    public void onTaskDone(BotState b, TaskResult r) {
        QueueEntry e = b.queue.finish(r.id());
        if (e == null) {
            return;
        }
        outcome(b, e, r.ok(), r.reason(), r.message(), r.data());
        afterFinish(b, e);
    }

    private void afterFinish(BotState b, QueueEntry e) {
        if (m.catalog.isHeavy(e.type())) {
            dispatchAll();
        } else {
            dispatch(b);
        }
    }

    /** Bookkeeping for a finished entry; never dispatches (callers do). */
    private void outcome(BotState b, QueueEntry e, boolean ok, String reason, String message, JsonObject data) {
        boolean requeued = outcomeInner(b, e, ok, reason, message, data);
        if (hooks != null) {
            hooks.onFinished(b, e, ok, reason, message, data, requeued);
        }
    }

    /** Returns true when the entry was queued again (retry). */
    private boolean outcomeInner(BotState b, QueueEntry e, boolean ok, String reason, String message, JsonObject data) {
        Run run = runOf(e);
        boolean requeued = false;
        if (ok) {
            m.event("task_done", Levels.INFO, b.id, "event.task.done", Map.of("bot", b.id, "task", e.type(),
                    "message", message == null ? "" : message), data);
        } else {
            String r = reason == null ? Reasons.ERROR : reason;
            boolean handled = switch (r) {
                case Reasons.CANCELLED -> {
                    if (run != null) {
                        stopRun(run, Reasons.CANCELLED);
                    }
                    yield true;
                }
                case Reasons.INVENTORY_FULL -> depositAndRetry(b, e, run);
                case Reasons.DIED -> {
                    b.dead = true;
                    yield retry(b, e);
                }
                case Reasons.DISCONNECTED -> retry(b, e);
                default -> false;
            };
            requeued = handled && !Reasons.CANCELLED.equals(r);
            if (!handled) {
                m.event("task_failed", Levels.WARN, b.id, "event.task.failed", Map.of("bot", b.id, "task", e.type(),
                        "reason", r, "message", message == null ? "" : message), data);
                if (run != null) {
                    stopRun(run, r);
                }
            }
        }
        if (run != null && runs.containsKey(run.id) && !b.queue.anyMatch(x -> x.hasOrigin(run.origin()))) {
            iterationDone(b, run);
        }
        changed(b);
        return requeued;
    }

    private boolean depositAndRetry(BotState b, QueueEntry e, Run run) {
        boolean allowed = run != null ? run.fullRetries < MAX_FULL_RETRIES
                : m.config.get().planner().autoDepositWhenFull() && e.fullRetries() < MAX_FULL_RETRIES;
        if (!allowed || storageFor(b).isEmpty()) {
            return false;
        }
        if (run != null) {
            run.fullRetries++;
        }
        int n = e.fullRetries() + 1;
        QueueEntry again = new QueueEntry(Tokens.id("t"), e.type(), e.args(), e.timeoutSec(), e.label(), e.origin(),
                System.currentTimeMillis(), e.attempts(), n);
        QueueEntry deposit = new QueueEntry(Tokens.id("t"), STEP_DEPOSIT_STORAGE, new JsonObject(), 0, null, e.origin(),
                System.currentTimeMillis(), 0, n);
        b.queue.pushFront(List.of(deposit, again));
        m.event("deposit_retry", Levels.INFO, b.id, "event.task.depositRetry",
                Map.of("bot", b.id, "task", e.type(), "n", run != null ? run.fullRetries : n));
        return true;
    }

    private boolean retry(BotState b, QueueEntry e) {
        if (e.attempts() + 1 >= MAX_ATTEMPTS) {
            return false;
        }
        // after a pending death recovery, never before it
        b.queue.insertAfterLeading(x -> x.hasOrigin(QueueEntry.ORIGIN_RECOVERY), e.retry());
        return true;
    }

    /** The bot came online (or linked while online): resume its queue. */
    public void onOnline(BotState b) {
        dispatch(b);
    }

    /** The link dropped: the running task's result will never arrive, so it goes back to the queue head. */
    public void onLinkLost(BotState b) {
        QueueEntry cur = b.queue.current();
        if (cur == null || m.catalog.isStep(cur.type())) {
            return;
        }
        b.queue.abortCurrent();
        if (!retry(b, cur)) {
            outcome(b, cur, false, Reasons.DISCONNECTED, "link lost", null);
        }
        changed(b);
        if (m.catalog.isHeavy(cur.type())) {
            dispatchAll();
        }
    }

    /**
     * {@code death} event: logs the death in the world document and, when death recovery is enabled, queues
     * {@code recover} at the front. Nothing is dispatched until the respawn.
     */
    public void onDeath(BotState b, BotEvent ev) {
        b.dead = true;
        JsonObject d = ev.data() == null ? new JsonObject() : ev.data();
        Pos pos = Pos.fromJson(d.get("pos"));
        if (pos == null && b.status != null && b.status.pos() != null) {
            pos = b.status.pos().toPos();
        }
        if (pos == null) {
            return;
        }
        String dim = Dims.normalize(Json.getString(d, "dim", botDim(b)));
        if (b.def.serverId() != null) {
            m.worlds.addDeath(b.def.serverId(), new WorldDoc.Death(b.id, dim, pos, Json.getString(d, "cause", ""), ev.time()));
            m.broadcastWorld(b.def.serverId());
        }
        BotConfig.DeathRecovery dr = m.effectiveConfig(b).behaviour().deathRecovery();
        if (!dr.enabled()) {
            return;
        }
        b.queue.removeIf(x -> TaskTypes.RECOVER.equals(x.type()) && x.hasOrigin(QueueEntry.ORIGIN_RECOVERY));
        JsonObject args = Json.obj("pos", pos, "dim", dim, "radius", 6);
        b.queue.pushFront(List.of(QueueEntry.of(TaskTypes.RECOVER, args, dr.timeoutSec(), null, QueueEntry.ORIGIN_RECOVERY)));
        m.event("recovery_queued", Levels.INFO, b.id, "event.recovery.queued",
                Map.of("bot", b.id, "x", pos.x(), "y", pos.y(), "z", pos.z()));
        changed(b);
    }

    /** After a respawn the queue (recovery first) resumes. */
    public void onRespawned(BotState b) {
        b.dead = false;
        dispatch(b);
    }

    // ------------------------------------------------------------------ views and persistence

    public JsonObject queueView(BotState b) {
        JsonObject o = new JsonObject();
        QueueEntry cur = b.queue.current();
        if (cur != null) {
            JsonObject c = cur.toJson();
            c.addProperty("startedAt", b.queue.currentStartedAt());
            o.add("current", c);
        } else {
            o.add("current", com.google.gson.JsonNull.INSTANCE);
        }
        JsonArray queued = new JsonArray();
        b.queue.items().forEach(e -> queued.add(e.toJson()));
        o.add("queued", queued);
        if (b.waiting != null) {
            o.addProperty("waiting", b.waiting);
        }
        JsonArray rs = new JsonArray();
        for (Run r : runs.values()) {
            if (r.botId.equalsIgnoreCase(b.id)) {
                rs.add(Json.obj("id", r.id, "scenarioId", r.scenarioId, "name", r.name, "iteration", r.iteration,
                        "repeat", r.repeat, "fullRetries", r.fullRetries));
            }
        }
        o.add("runs", rs);
        return o;
    }

    private void changed(BotState b) {
        m.broadcastQueue(b);
        saveSoon();
    }

    private void saveSoon() {
        if (!saveScheduled) {
            saveScheduled = true;
            m.loop.schedule(this::saveQueues, 1, TimeUnit.SECONDS);
        }
    }

    /** queues.json: per bot the running bot task (re-queued first after a restart) and the queued entries. */
    public void saveQueues() {
        saveScheduled = false;
        JsonObject root = new JsonObject();
        for (BotState b : m.bots.all()) {
            JsonArray a = new JsonArray();
            QueueEntry cur = b.queue.current();
            if (cur != null && !m.catalog.isStep(cur.type())) {
                a.add(cur.toJson());
            }
            b.queue.items().forEach(e -> a.add(e.toJson()));
            if (!a.isEmpty()) {
                root.add(b.id, a);
            }
        }
        try {
            AtomicFiles.writeJson(queuesFile, root);
        } catch (IOException e) {
            Log.error("cannot write " + queuesFile, e);
        }
    }

    /**
     * Restores queued entries; scenario and project entries are dropped because runs and planner assignments are
     * not persisted (running projects re-plan after the restart).
     */
    public void loadQueues() {
        try {
            JsonElement root = AtomicFiles.readJson(queuesFile);
            if (root == null || !root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> en : root.getAsJsonObject().entrySet()) {
                BotState b = m.bots.get(en.getKey());
                if (b == null || !en.getValue().isJsonArray()) {
                    continue;
                }
                List<QueueEntry> entries = new ArrayList<>();
                for (JsonElement el : en.getValue().getAsJsonArray()) {
                    try {
                        QueueEntry q = Json.fromJson(el, QueueEntry.class);
                        if (q != null && q.type() != null && m.catalog.isKnown(q.type()) && (q.origin() == null
                                || !q.origin().startsWith(TaskSpec.ORIGIN_SCENARIO_PREFIX)
                                && !q.origin().startsWith(TaskSpec.ORIGIN_PROJECT_PREFIX))) {
                            entries.add(q);
                        }
                    } catch (RuntimeException ignored) {
                        // skip a broken entry
                    }
                }
                b.queue.add(entries, TaskQueue.Mode.APPEND);
            }
        } catch (IOException e) {
            Log.warn("queues.json unreadable: %s", e.getMessage());
        }
    }
}
