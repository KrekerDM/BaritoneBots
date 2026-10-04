package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code ranch} projects (SPEC §5.7): an animal pen. Config {@code {box | area, dim, animal, max:20, keep:4,
 * food?, shear:false, intervalSec:300, deposit?:[pos]}}. Every {@code intervalSec} a rancher runs one round:
 * {@code shear} (sheep with {@code shear}), {@code breed} up to {@code max}; when the population reported by the
 * breed task is at {@code max} or more, {@code slaughter} down to {@code keep} (the task collects the drops); then
 * the loot goes into {@code deposit} (or deposit_storage). Runs until stopped.
 */
public final class RanchKind implements ProjectKind {
    public static final String KIND = "ranch";
    private final Manager m;

    public RanchKind(Manager m) {
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
        String animal = c.id("animal", true);
        String food = c.id("food", false);
        int max = c.integer("max", 20, 2, 500);
        int keep = c.integer("keep", 4, 0, 500);
        if (keep >= max && !c.errors.containsKey("max") && !c.errors.containsKey("keep")) {
            c.errors.put("keep", "range");
        }
        boolean shear = c.bool("shear", false);
        if (shear && animal != null && !animal.equals("minecraft:sheep")) {
            c.errors.put("shear", "sheep_only");
        }
        int interval = c.integer("intervalSec", 300, 30, 86_400);
        JsonArray deposit = c.positions("deposit");
        JsonObject out = Json.obj("box", area == null ? null : Json.toTree(area[0]), "dim", area == null ? c.dim() : area[1],
                "animal", animal, "food", food, "max", max, "keep", keep, "shear", shear, "intervalSec", interval,
                "deposit", deposit);
        String areaName = Json.getString(config, "area", "").trim();
        if (!areaName.isEmpty() && !config.has("box")) {
            out.addProperty("area", areaName);
        }
        return c.done(out);
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService svc) {
        return new Runtime(p, svc);
    }

    /** Slaughter after breeding? Only once the pen is full. Pure. */
    static boolean slaughter(int population, int max) {
        return population >= max;
    }

    private static final class Ctx {
        String step = "breed";
    }

    static final class Runtime extends KindRuntime {
        private final Box box;
        private final String animal;
        private final String food;
        private final int max;
        private final int keep;
        private final boolean shear;
        private final int interval;
        private final JsonArray depositPos;
        long nextAt;
        int rounds;
        int population = -1;
        int fed;
        int killed;
        int sheared;

        Runtime(Project p, ProjectService svc) {
            super(p, svc);
            box = Box.fromJson(p.config.get("box"));
            animal = Json.getString(p.config, "animal", "minecraft:cow");
            food = Json.getString(p.config, "food", null);
            max = Json.getInt(p.config, "max", 20);
            keep = Json.getInt(p.config, "keep", 4);
            shear = Json.getBool(p.config, "shear", false);
            interval = Json.getInt(p.config, "intervalSec", 300);
            depositPos = Json.getArr(p.config, "deposit");
            rounds = Json.getInt(p.state, "rounds", 0);
            population = Json.getInt(p.state, "population", -1);
            fed = Json.getInt(p.state, "fed", 0);
            killed = Json.getInt(p.state, "killed", 0);
            sheared = Json.getInt(p.state, "sheared", 0);
        }

        @Override
        public void start(boolean restart) {
            super.start(restart);
            nextAt = 0;
        }

        @Override
        public List<WorkItem> workItems(long now) {
            List<WorkItem> out = new ArrayList<>();
            if (box == null) {
                svc.failed(p, "the pen has no box");
                return out;
            }
            if (now < nextAt) {
                return out;
            }
            Pos c = new Pos((box.min().x() + box.max().x()) / 2, box.min().y(), (box.min().z() + box.max().z()) / 2);
            out.add(new WorkItem("pen", id(), "ranch", "rancher", p.priority, null, dim, c, "pen:" + p.id, 1,
                    "ranch " + io.github.krekerdm.baritonebots.common.ids.Ids.path(animal), new JsonObject()));
            return out;
        }

        private JsonObject boxArgs() {
            return Json.obj("box", Json.toTree(box), "animal", animal);
        }

        @Override
        public void begin(Assignment a, BotState b) {
            a.ctx = new Ctx();
            a.phase("breed");
            List<QueueEntry> batch = new ArrayList<>();
            if (shear) {
                batch.add(Planner.entry(a, TaskTypes.SHEAR, Json.obj("box", Json.toTree(box)), 600, a.item.label()));
            }
            JsonObject breed = boxArgs();
            breed.addProperty("max", max);
            if (food != null) {
                breed.addProperty("food", food);
            }
            batch.add(Planner.entry(a, TaskTypes.BREED, breed, 600, a.item.label()));
            planner.push(a, batch);
        }

        @Override
        public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
            Ctx c = (Ctx) a.ctx;
            switch (c.step) {
                case "breed" -> {
                    Assignment.Result sh = last(results, TaskTypes.SHEAR);
                    if (sh != null && sh.ok() && sh.data() != null) {
                        sheared += Json.getInt(sh.data(), "sheared", 0);
                    }
                    Assignment.Result br = last(results, TaskTypes.BREED);
                    if (br == null || !br.ok() && !Reasons.NOT_FOUND.equals(br.reason())) {
                        planner.fail(a, br == null ? Reasons.CANCELLED : br.reason(), br == null ? null : br.message());
                        return;
                    }
                    if (br.ok() && br.data() != null) {
                        fed += Json.getInt(br.data(), "fed", 0);
                        population = Json.getInt(br.data(), "population", population);
                    }
                    List<QueueEntry> next = new ArrayList<>();
                    if (population >= 0 && slaughter(population, max)) {
                        JsonObject args = boxArgs();
                        args.addProperty("keep", keep);
                        next.add(Planner.entry(a, TaskTypes.SLAUGHTER, args, 900, a.item.label()));
                        a.phase("slaughter");
                    }
                    boolean loot = !next.isEmpty() || sh != null && sh.ok();
                    if (loot) {
                        next.add(deposit(a, resolve(depositPos, null)));
                    }
                    if (next.isEmpty()) {
                        roundDone(a);
                        return;
                    }
                    c.step = "harvest";
                    planner.push(a, next);
                }
                case "harvest" -> {
                    Assignment.Result sl = last(results, TaskTypes.SLAUGHTER);
                    if (sl != null && sl.ok() && sl.data() != null) {
                        int k = Json.getInt(sl.data(), "killed", 0);
                        killed += k;
                        population = Math.max(0, population - k);
                    }
                    roundDone(a);
                }
                default -> planner.fail(a, Reasons.ERROR, "unexpected step " + c.step);
            }
        }

        private void roundDone(Assignment a) {
            rounds++;
            nextAt = System.currentTimeMillis() + interval * 1000L;
            planner.finish(a);
            svc.changed(p);
        }

        @Override
        public JsonObject view(boolean full) {
            JsonObject progress = Json.obj("rounds", rounds, "population", population < 0 ? null : population,
                    "max", max, "keep", keep, "fed", fed, "killed", killed, "sheared", sheared,
                    "nextAt", nextAt == 0 ? null : nextAt);
            return view(progress);
        }

        @Override
        public JsonObject persist() {
            return Json.obj("rounds", rounds, "population", population, "fed", fed, "killed", killed, "sheared", sheared);
        }
    }
}
