package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.Sectors;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code clear} projects (SPEC §5.7): empty an area. Config {@code {box | area, dim, deposit?:[pos], verify:true}}.
 * On the first tick the box is split into vertical slabs ({@link Box#split}, at least {@link Sectors#MIN_WIDTH} wide)
 * — one per bot allowed to work on it — and each slab is a work item (role miner) running {@code selection clear}.
 * {@code inventory_full} is handled by the dispatcher (deposit_storage + retry); after a slab the drops go into
 * {@code deposit} (or deposit_storage). With {@code verify} a finished slab is checked by sampling {@code block_at}
 * at its centre and corners; anything but air / fluids (in loaded chunks) puts it back (3 tries, then blocked).
 * Done when every slab is done.
 */
public final class ClearKind implements ProjectKind {
    public static final String KIND = "clear";
    static final int MAX_TRIES = 3;
    static final int TASK_TIMEOUT_SEC = 7200;
    private final Manager m;

    public ClearKind(Manager m) {
        this.m = m;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject normalizeConfig(JsonObject config, String serverId) {
        KindConfig c = new KindConfig(config);
        Object[] area = c.area(m.worlds.get(serverId), true);
        JsonArray deposit = c.positions("deposit");
        boolean verify = c.bool("verify", true);
        String areaName = Json.getString(config, "area", "").trim();
        JsonObject out = Json.obj("box", area == null ? null : Json.toTree(area[0]),
                "dim", area == null ? c.dim() : area[1], "deposit", deposit, "verify", verify);
        if (!areaName.isEmpty() && !config.has("box")) {
            out.addProperty("area", areaName);
        }
        return c.done(out);
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService svc) {
        return new Runtime(p, svc);
    }

    /** Slabs for {@code bots} workers. Pure. */
    static List<Box> slabs(Box box, int bots) {
        return box.split(Math.max(1, bots), Sectors.MIN_WIDTH);
    }

    /** Positions to sample after a slab: centre and four corners (distinct). Pure. */
    static List<Pos> samples(Box b) {
        Pos min = b.min();
        Pos max = b.max();
        Set<Pos> out = new LinkedHashSet<>();
        out.add(new Pos((min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2));
        out.add(min);
        out.add(max);
        out.add(new Pos(min.x(), max.y(), max.z()));
        out.add(new Pos(max.x(), min.y(), min.z()));
        return List.copyOf(out);
    }

    /** Air, fluids and bubble columns count as cleared (Baritone's clear does not drain fluids). Pure. */
    static boolean cleared(String block) {
        String id = Ids.stripState(Ids.normalize(block == null ? "minecraft:air" : block));
        return Ids.isAir(id) || id.equals("minecraft:water") || id.equals("minecraft:lava")
                || id.equals("minecraft:bubble_column") || id.equals("minecraft:light");
    }

    static final class Slab {
        final Box box;
        String state = "pending"; // pending | active | done | blocked
        int tries;

        Slab(Box box) {
            this.box = box;
        }
    }

    private static final class Ctx {
        String step = "clear";
        int sample;
        final List<Pos> leftovers = new ArrayList<>();
    }

    static final class Runtime extends KindRuntime {
        private final Box box;
        private final JsonArray depositPos;
        private final boolean verify;
        final List<Slab> slabs = new ArrayList<>();

        Runtime(Project p, ProjectService svc) {
            super(p, svc);
            box = Box.fromJson(p.config.get("box"));
            depositPos = Json.getArr(p.config, "deposit");
            verify = Json.getBool(p.config, "verify", true);
            JsonArray saved = Json.getArr(p.state, "slabs");
            if (saved != null) {
                for (JsonElement e : saved) {
                    Box b = Box.fromJson(e.getAsJsonObject().get("box"));
                    if (b != null) {
                        Slab s = new Slab(b);
                        s.state = Json.getString(e.getAsJsonObject(), "state", "pending");
                        s.tries = Json.getInt(e.getAsJsonObject(), "tries", 0);
                        slabs.add(s);
                    }
                }
            }
        }

        @Override
        public void start(boolean restart) {
            super.start(restart);
            for (Slab s : slabs) {
                if (!"done".equals(s.state)) {
                    s.state = "pending"; // blocked slabs get another chance on start / resume
                    s.tries = restart ? 0 : s.tries;
                }
            }
            if (restart && !slabs.isEmpty() && slabs.stream().allMatch(s -> "done".equals(s.state))) {
                slabs.forEach(s -> s.state = "pending"); // started again after done: clear (and check) once more
            }
        }

        @Override
        public void stop() {
            super.stop();
            slabs.stream().filter(s -> "active".equals(s.state)).forEach(s -> s.state = "pending");
        }

        private int workers() {
            int n = 0;
            for (BotState b : m.bots.all()) {
                if (b.def.enabled() && p.allows(b) && roleOk(b, "miner")) {
                    n++;
                }
            }
            return Math.max(1, n);
        }

        @Override
        public List<WorkItem> workItems(long now) {
            List<WorkItem> out = new ArrayList<>();
            if (box == null) {
                svc.failed(p, "the project has no box");
                return out;
            }
            if (slabs.isEmpty()) {
                slabs(box, workers()).forEach(b -> slabs.add(new Slab(b)));
                svc.changed(p);
            }
            if (slabs.stream().allMatch(s -> "done".equals(s.state)) && planner.assignmentsOf(id()).isEmpty()) {
                svc.done(p);
                return out;
            }
            for (int i = 0; i < slabs.size(); i++) {
                Slab s = slabs.get(i);
                if ("pending".equals(s.state)) {
                    Pos c = new Pos((s.box.min().x() + s.box.max().x()) / 2, s.box.max().y(),
                            (s.box.min().z() + s.box.max().z()) / 2);
                    out.add(new WorkItem("slab:" + i, id(), "clear_slab", "miner", p.priority, null, dim, c,
                            "slab:" + p.id + ":" + i, 1, "clear slab " + (i + 1) + "/" + slabs.size(),
                            Json.obj("slab", i)));
                }
            }
            return out;
        }

        @Override
        public void begin(Assignment a, BotState b) {
            Slab s = slabs.get(Json.getInt(a.item.data(), "slab", 0));
            s.state = "active";
            a.ctx = new Ctx();
            a.phase("clear");
            planner.push(a, List.of(Planner.entry(a, TaskTypes.SELECTION, Json.obj("op", "clear",
                    "box", Json.toTree(s.box)), TASK_TIMEOUT_SEC, a.item.label())));
            svc.changed(p);
        }

        @Override
        public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
            Slab s = slabs.get(Json.getInt(a.item.data(), "slab", 0));
            Ctx c = (Ctx) a.ctx;
            switch (c.step) {
                case "clear" -> {
                    Assignment.Result r = last(results, TaskTypes.SELECTION);
                    if (r == null || !r.ok()) {
                        planner.fail(a, r == null ? Reasons.CANCELLED : r.reason(), r == null ? null : r.message());
                        return;
                    }
                    if (verify) {
                        c.step = "verify";
                        a.phase("verify");
                        sample(a, s, c);
                    } else {
                        slabDone(a, s, c);
                    }
                }
                case "deposit" -> planner.finish(a);
                default -> planner.fail(a, Reasons.ERROR, "unexpected step " + c.step);
            }
        }

        /** Queries the sample positions one after another; then judges the slab. */
        private void sample(Assignment a, Slab s, Ctx c) {
            List<Pos> pts = samples(s.box);
            if (c.sample >= pts.size()) {
                if (c.leftovers.isEmpty()) {
                    slabDone(a, s, c);
                } else if (++s.tries >= MAX_TRIES) {
                    s.state = "blocked";
                    String why = "blocks left at " + c.leftovers + " after " + s.tries + " tries";
                    notes.put(a.item.id(), a.item.label() + ": " + why);
                    svc.blocked(p, a.item.id(), a.item.label() + ": " + why, null);
                    planner.release(a, "blocked", false);
                    svc.changed(p);
                } else {
                    planner.fail(a, Reasons.NOT_FOUND, "blocks left at " + c.leftovers);
                }
                return;
            }
            Pos pos = pts.get(c.sample++);
            planner.query(a, QueryKinds.BLOCK_AT, Json.obj("pos", Json.toTree(pos)), 3_000, (res, err) -> {
                if (err == null && res != null && res.ok() && Json.getBool(res.data(), "loaded", false)
                        && !cleared(Json.getString(res.data(), "block", null))) {
                    c.leftovers.add(pos);
                }
                sample(a, s, c);
            });
        }

        private void slabDone(Assignment a, Slab s, Ctx c) {
            s.state = "done";
            notes.remove(a.item.id());
            svc.unblocked(p, a.item.id());
            svc.changed(p);
            c.step = "deposit";
            a.phase("deposit");
            planner.push(a, List.of(deposit(a, resolve(depositPos, null))));
        }

        @Override
        public void onReleased(Assignment a, String why) {
            int i = Json.getInt(a.item.data(), "slab", -1);
            if (i >= 0 && i < slabs.size() && "active".equals(slabs.get(i).state)) {
                slabs.get(i).state = "pending";
            }
        }

        @Override
        public JsonObject view(boolean full) {
            long total = box == null ? 0 : box.volume();
            long done = slabs.stream().filter(s -> "done".equals(s.state)).mapToLong(s -> s.box.volume()).sum();
            JsonObject progress = Json.obj("done", done, "total", total,
                    "slabsDone", slabs.stream().filter(s -> "done".equals(s.state)).count(), "slabs", slabs.size());
            JsonObject o = view(progress);
            o.add("slabs", slabsJson());
            if (box != null) {
                o.add("box", Json.toTree(box));
            }
            return o;
        }

        private JsonArray slabsJson() {
            JsonArray a = new JsonArray();
            slabs.forEach(s -> a.add(Json.obj("box", Json.toTree(s.box), "state", s.state, "tries", s.tries)));
            return a;
        }

        @Override
        public JsonObject persist() {
            return Json.obj("slabs", slabsJson());
        }
    }
}
