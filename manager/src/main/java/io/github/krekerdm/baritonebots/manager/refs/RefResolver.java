package io.github.krekerdm.baritonebots.manager.refs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Resolves position references (SPEC §5.7e) on the manager loop: bot tasks at dispatch time (the dispatcher runs the
 * internal {@code resolve} step in front of a task whose arguments hold references), project configs and world
 * edits when they are saved. The owner is located through an {@code owner} query to every online bot of the server
 * (first bot that tracks the owner wins, cached {@value #OWNER_CACHE_MS} ms); {@code auto} goes to {@link AutoDetect}.
 * Unresolvable references fail the task with a clear reason (event {@code ref_failed}) or the request with 400.
 */
public final class RefResolver {
    static final long OWNER_CACHE_MS = 1_500;
    static final long OWNER_QUERY_MS = 3_000;
    /** HTTP handlers wait this long for references that need bot queries. */
    public static final long HTTP_WAIT_MS = 30_000;

    private final Manager m;
    private final AutoDetect auto;
    private final Map<String, CachedOwner> ownerCache = new HashMap<>();

    private record CachedOwner(Refs.Owner owner, long at) {
    }

    public RefResolver(Manager m) {
        this.m = m;
        this.auto = new AutoDetect(m);
    }

    /** The values one resolution may use (one per task / project / world edit). */
    final class Ctx implements Refs.Context {
        final String serverId;
        final BotState bot;
        final String dim;
        Refs.Owner owner;
        final Map<String, Object> autos = new HashMap<>();
        final Map<String, Object> extras = new LinkedHashMap<>();

        Ctx(String serverId, BotState bot, String dim) {
            this.serverId = serverId;
            this.bot = bot;
            this.dim = dim == null ? null : Dims.normalize(dim);
        }

        WorldDoc doc() {
            return serverId == null ? null : m.worlds.get(serverId);
        }

        String dimOr(String def) {
            return dim == null ? def : dim;
        }

        @Override
        public Refs.Located home() {
            if (bot != null) {
                Refs.Located own = waypoint(bot.def.homeWaypointName());
                if (own != null) {
                    return own;
                }
            }
            Refs.Located home = waypoint("home");
            if (home != null) {
                return home;
            }
            for (BotState b : m.bots.all()) {
                if (serverId != null && serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))) {
                    Refs.Located l = waypoint(b.def.homeWaypointName());
                    if (l != null) {
                        return l;
                    }
                }
            }
            return null;
        }

        @Override
        public Refs.Located waypoint(String name) {
            WorldDoc d = doc();
            WorldDoc.Waypoint w = d == null ? null : d.waypoint(name);
            return w == null ? null : new Refs.Located(w.pos(), w.dim());
        }

        @Override
        public Refs.Located bot(String id) {
            BotState b = id == null ? null : m.bots.get(id);
            Pos p = b == null ? null : Planner.posOf(b);
            return p == null ? null : new Refs.Located(p, b.status.dim());
        }

        @Override
        public Refs.AreaBox area(String name) {
            WorldDoc d = doc();
            if (d == null || name == null) {
                return null;
            }
            for (WorldDoc.Area a : d.areas) {
                if (a.name().equalsIgnoreCase(name.trim())) {
                    return new Refs.AreaBox(a.box(), a.dim());
                }
            }
            return null;
        }

        @Override
        public Refs.Owner owner() {
            return owner;
        }

        @Override
        public Object auto(String arg, String type) {
            Object v = autos.get(arg);
            if (v instanceof Box b && (Refs.T_POS.equals(type) || Refs.T_CONTAINER.equals(type))) {
                return new Pos((b.min().x() + b.max().x()) / 2, b.min().y(), (b.min().z() + b.max().z()) / 2);
            }
            return v;
        }

        @Override
        public String dim() {
            return dim;
        }
    }

    // ------------------------------------------------------------------ bot tasks (dispatch time)

    /** True when the task's reference-capable arguments hold references. */
    public boolean hasRefs(QueueEntry e) {
        Map<String, String> types = m.catalog.refArgs(e.type());
        return !types.isEmpty() && !Refs.kinds(e.args(), types).isEmpty();
    }

    /**
     * The internal {@code resolve} step ({@code args.entry} = the task): resolves, then puts the task with plain
     * coordinates in front of the queue; failures end the step with the reference's reason.
     */
    public void runStep(BotState b, QueueEntry step) {
        QueueEntry task;
        try {
            task = Json.fromJson(step.args().get("entry"), QueueEntry.class);
        } catch (RuntimeException e) {
            task = null;
        }
        if (task == null || task.type() == null) {
            m.dispatcher.failStep(b, step, Reasons.BAD_ARGS, "resolve step without a task");
            return;
        }
        QueueEntry original = task;
        m.dispatcher.startStep(b, step);
        resolveTask(b, original, (resolved, err) -> m.loop.post(() -> { // never finish inside runStep
            if (!m.dispatcher.isRunning(b, step)) {
                return;
            }
            if (err != null) {
                m.event("ref_failed", Levels.WARN, b.id, "event.ref.failed", Map.of("bot", b.id,
                        "task", original.type(), "message", err.getMessage()), Json.obj("reason", err.reason()));
                m.dispatcher.finishStep(b, step, false, err.reason(), err.getMessage(), null, null, false);
            } else {
                m.dispatcher.finishStep(b, step, true, null, null, null, List.of(resolved), true);
            }
        }));
    }

    /** Resolves a task's references; {@code cb(resolved, null)} or {@code cb(null, error)}. */
    public void resolveTask(BotState b, QueueEntry task, BiConsumer<QueueEntry, Refs.RefException> cb) {
        Map<String, String> types = m.catalog.refArgs(task.type());
        Ctx ctx = new Ctx(b.def.serverId(), b, b.status != null && b.status.dim() != null ? b.status.dim() : null);
        resolveArgs(ctx, task.type(), task.args(), types, (args, err) -> {
            if (err != null) {
                cb.accept(null, err);
                return;
            }
            JsonObject a = args;
            if (TaskTypes.GOTO.equals(task.type())) {
                a = Refs.gotoCoordinates(a);
            }
            if (TaskTypes.FARM.equals(task.type()) && !a.has("range") && ctx.extras.get("range") instanceof Integer r) {
                a.addProperty("range", r);
            }
            cb.accept(task.withArgs(a), null);
        });
    }

    /** Fetches the owner / automatic values the references need, then substitutes. */
    void resolveArgs(Ctx ctx, String what, JsonObject args, Map<String, String> types,
                     BiConsumer<JsonObject, Refs.RefException> cb) {
        Set<String> kinds = Refs.kinds(args, types);
        Runnable finish = () -> {
            try {
                cb.accept(Refs.resolve(args, types, ctx), null);
            } catch (Refs.RefException ex) {
                cb.accept(null, ex);
            }
        };
        ArrayDeque<String> autoArgs = new ArrayDeque<>(Refs.autoArgs(args, types));
        Runnable autos = () -> detectAll(ctx, what, args, types, autoArgs, finish, cb);
        if (kinds.contains(Refs.OWNER) || kinds.contains(Refs.OWNER_LOOK)) {
            if (m.config.get().general().ownerOrNull() == null) {
                cb.accept(null, new Refs.RefException("ref_no_owner", "set general.ownerPlayer to use owner references"));
                return;
            }
            owner(ctx.serverId, o -> {
                ctx.owner = o;
                autos.run();
            });
        } else {
            autos.run();
        }
    }

    private void detectAll(Ctx ctx, String what, JsonObject args, Map<String, String> types, ArrayDeque<String> left,
                           Runnable finish, BiConsumer<JsonObject, Refs.RefException> cb) {
        String arg = left.poll();
        if (arg == null) {
            finish.run();
            return;
        }
        auto.detect(ctx, what, arg, types.get(arg), args, (found, why) -> {
            if (found == null) {
                cb.accept(null, new Refs.RefException("ref_auto_none", why));
                return;
            }
            ctx.autos.put(arg, found.value());
            ctx.extras.putAll(found.extras());
            detectAll(ctx, what, args, types, left, finish, cb);
        });
    }

    // ------------------------------------------------------------------ owner

    /** Where the owner is, through the server's online bots (null = nobody sees the owner). Calls back on the loop. */
    public void owner(String serverId, Consumer<Refs.Owner> cb) {
        String key = serverId == null ? "" : serverId.toLowerCase(Locale.ROOT);
        CachedOwner c = ownerCache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < OWNER_CACHE_MS) {
            cb.accept(c.owner());
            return;
        }
        List<BotState> bots = new ArrayList<>();
        for (BotState b : m.bots.all()) {
            if (b.online() && b.linked() && serverId != null && serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))) {
                bots.add(b);
            }
        }
        if (bots.isEmpty()) {
            cb.accept(null);
            return;
        }
        int[] pending = {bots.size()};
        boolean[] done = {false};
        for (BotState b : bots) {
            m.planner.queryVia(b, QueryKinds.OWNER, new JsonObject(), OWNER_QUERY_MS, (r, t) -> {
                pending[0]--;
                if (done[0]) {
                    return;
                }
                Refs.Owner o = r != null && r.ok() && r.data() != null ? ownerOf(r.data()) : null;
                if (o != null || pending[0] == 0) {
                    done[0] = true;
                    ownerCache.put(key, new CachedOwner(o, System.currentTimeMillis()));
                    cb.accept(o);
                }
            });
        }
    }

    /** An {@code owner} query answer or {@code owner_command} event data → Owner (null when not seen). */
    public static Refs.Owner ownerOf(JsonObject d) {
        Pos pos = Pos.fromJson(d.get("pos"));
        if (pos == null || d.has("found") && !Json.getBool(d, "found", false)) {
            return null;
        }
        return new Refs.Owner(pos, Pos.fromJson(d.get("lookBlock")), Json.getString(d, "lookBlockId", null),
                Json.getString(d, "dim", null), Json.getDouble(d, "yaw", 0));
    }

    // ------------------------------------------------------------------ projects and world edits (HTTP)

    /**
     * A project body with its config's references resolved ({@code placement} is merged into {@code config} first,
     * as the project service does). Completes exceptionally with {@link ApiException} (400, the reference's reason).
     */
    public CompletableFuture<JsonObject> resolveProject(JsonObject body) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        String kind = Json.getString(body, "kind", "");
        Map<String, String> types = m.catalog.projectRefArgs(kind);
        JsonObject config = Json.getObj(body, "config") == null ? new JsonObject() : Json.getObj(body, "config").deepCopy();
        JsonObject placement = Json.getObj(body, "placement");
        if (placement != null) {
            placement.entrySet().forEach(e -> config.add(e.getKey(), e.getValue()));
        }
        if (types.isEmpty() || Refs.kinds(config, types).isEmpty()) {
            f.complete(body);
            return f;
        }
        String serverId = Json.getString(body, "serverId", "");
        Ctx ctx = new Ctx(serverId, null, Json.getString(config, "dim", Dims.OVERWORLD));
        resolveArgs(ctx, "project:" + kind, config, types, (args, err) -> {
            if (err != null) {
                f.completeExceptionally(ApiException.badRequest(err.reason(), err.getMessage()));
                return;
            }
            if ("ranch".equals(kind) && !args.has("animal") && ctx.extras.get("animal") instanceof String a) {
                args.addProperty("animal", a);
            }
            JsonObject out = body.deepCopy();
            out.remove("placement");
            out.add("config", args);
            f.complete(out);
        });
        return f;
    }

    /**
     * A world edit ({@code PUT /api/world}) with references in {@code waypoints[].pos}, {@code areas[].box} and
     * {@code zones[].box} resolved; a waypoint / area without {@code dim} takes the reference's dimension.
     */
    public CompletableFuture<JsonObject> resolveWorld(String serverId, JsonObject body) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        if (!Refs.contains(body.get("waypoints")) && !Refs.contains(body.get("areas")) && !Refs.contains(body.get("zones"))) {
            f.complete(body);
            return f;
        }
        Ctx ctx = new Ctx(serverId, null, null);
        Runnable apply = () -> {
            try {
                JsonObject out = body.deepCopy();
                resolveList(out, "waypoints", "pos", Refs.T_POS, ctx);
                resolveList(out, "areas", "box", Refs.T_BOX, ctx);
                resolveList(out, "zones", "box", Refs.T_BOX, ctx);
                f.complete(out);
            } catch (Refs.RefException ex) {
                f.completeExceptionally(ApiException.badRequest(ex.reason(), ex.getMessage()));
            }
        };
        boolean needsOwner = false;
        for (String list : List.of("waypoints", "areas", "zones")) {
            Set<String> k = new java.util.HashSet<>();
            JsonArray a = Json.getArr(body, list);
            for (JsonElement e : a == null ? new JsonArray() : a) {
                if (e.isJsonObject()) {
                    k.addAll(Refs.kinds(e.getAsJsonObject(), Map.of("pos", Refs.T_POS, "box", Refs.T_BOX)));
                }
            }
            needsOwner |= k.contains(Refs.OWNER) || k.contains(Refs.OWNER_LOOK);
        }
        if (needsOwner) {
            if (m.config.get().general().ownerOrNull() == null) {
                f.completeExceptionally(ApiException.badRequest("ref_no_owner",
                        "set general.ownerPlayer to use owner references"));
                return f;
            }
            owner(serverId, o -> {
                ctx.owner = o;
                apply.run();
            });
        } else {
            apply.run();
        }
        return f;
    }

    private static void resolveList(JsonObject body, String list, String key, String type, Ctx ctx) {
        JsonArray a = Json.getArr(body, list);
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).isJsonObject()) {
                continue;
            }
            JsonObject o = a.get(i).getAsJsonObject();
            JsonElement v = o.get(key);
            if (v == null || !Refs.contains(v)) {
                continue;
            }
            String path = list + "[" + i + "]." + key;
            String dim = firstRefDim(v, path, ctx);
            if (Refs.T_POS.equals(type)) {
                o.add(key, Json.toTree(Refs.pos(v, path, type, ctx)));
            } else {
                o.add(key, Json.toTree(Refs.box(v, path, ctx)));
            }
            if (!o.has("dim") && dim != null) {
                o.addProperty("dim", Dims.normalize(dim));
            }
        }
    }

    /** Dimension of the first reference inside {@code v} (areas, waypoints, owner), or null. */
    private static String firstRefDim(JsonElement v, String path, Ctx ctx) {
        if (Refs.isRef(v)) {
            JsonObject r = v.getAsJsonObject();
            if (Refs.AREA.equals(Json.getString(r, "ref", ""))) {
                Refs.AreaBox a = ctx.area(Json.getString(r, "name", ""));
                return a == null ? null : a.dim();
            }
            if (Refs.AUTO.equals(Json.getString(r, "ref", ""))) {
                return null;
            }
            return Refs.locate(v, path, Refs.T_POS, ctx).dim();
        }
        if (v.isJsonArray()) {
            for (JsonElement e : v.getAsJsonArray()) {
                if (Refs.isRef(e)) {
                    return firstRefDim(e, path, ctx);
                }
            }
        } else if (v.isJsonObject()) {
            for (String k : List.of("a", "b")) {
                JsonElement e = v.getAsJsonObject().get(k);
                if (Refs.isRef(e)) {
                    return firstRefDim(e, path, ctx);
                }
            }
        }
        return null;
    }
}
