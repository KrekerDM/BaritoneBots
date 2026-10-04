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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code farm} projects (SPEC §5.7): keep a field harvested. Config {@code {box | area | center, range?, dim,
 * durationSec:600, farmers:1, cycles:0, deposit?:[pos]}}; without {@code center} the field's centre and the smallest
 * range covering it are used. A farmer runs {@code farm} for {@code durationSec} (Baritone harvests and replants),
 * then deposits into {@code deposit} (or deposit_storage), and the item is offered again while the project runs.
 * {@code cycles > 0} ends the project after that many farming rounds. Progress: rounds, harvested items (from the
 * deposits) and items per hour.
 */
public final class FarmKind implements ProjectKind {
    public static final String KIND = "farm";
    private final Manager m;

    public FarmKind(Manager m) {
        this.m = m;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject normalizeConfig(JsonObject config, String serverId) {
        KindConfig c = new KindConfig(config);
        Pos center = Pos.fromJson(config.get("center"));
        if (center == null && config.has("center") && !config.get("center").isJsonNull()) {
            c.errors.put("center", "type");
        }
        Object[] area = c.area(m.worlds.get(serverId), center == null);
        Box box = area == null ? null : (Box) area[0];
        int range = c.integer("range", box == null ? 20 : rangeFor(box), 1, 128);
        if (center == null && box != null) {
            center = centerOf(box);
        }
        int duration = c.integer("durationSec", 600, 30, 86_400);
        int farmers = c.integer("farmers", 1, 1, 16);
        int cycles = c.integer("cycles", 0, 0, 100_000);
        JsonArray deposit = c.positions("deposit");
        JsonObject out = Json.obj("center", center == null ? null : Json.toTree(center), "range", range,
                "dim", area == null ? c.dim() : area[1], "durationSec", duration, "farmers", farmers, "cycles", cycles,
                "deposit", deposit);
        if (box != null) {
            out.add("box", Json.toTree(box));
        }
        String areaName = Json.getString(config, "area", "").trim();
        if (!areaName.isEmpty() && !config.has("box")) {
            out.addProperty("area", areaName);
        }
        return c.done(out);
    }

    /** Centre of the field's bottom layer. Pure. */
    static Pos centerOf(Box b) {
        return new Pos((b.min().x() + b.max().x()) / 2, b.min().y(), (b.min().z() + b.max().z()) / 2);
    }

    /** Smallest {@code farm} range (horizontal radius around the centre) covering the box. Pure. */
    static int rangeFor(Box b) {
        int half = Math.max(b.width(), b.length()) / 2 + 1;
        return Math.max(1, Math.min(128, half));
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService svc) {
        return new Runtime(p, svc);
    }

    static final class Runtime extends KindRuntime {
        private final Pos center;
        private final int range;
        private final int duration;
        private final int farmers;
        private final int cycles;
        private final JsonArray depositPos;
        int rounds;
        long farmedMs;
        final JsonObject harvested;

        Runtime(Project p, ProjectService svc) {
            super(p, svc);
            center = Pos.fromJson(p.config.get("center"));
            range = Json.getInt(p.config, "range", 20);
            duration = Json.getInt(p.config, "durationSec", 600);
            farmers = Json.getInt(p.config, "farmers", 1);
            cycles = Json.getInt(p.config, "cycles", 0);
            depositPos = Json.getArr(p.config, "deposit");
            rounds = Json.getInt(p.state, "rounds", 0);
            farmedMs = Json.getLong(p.state, "farmedMs", 0);
            JsonObject h = Json.getObj(p.state, "harvested");
            harvested = h == null ? new JsonObject() : h.deepCopy();
        }

        @Override
        public void start(boolean restart) {
            super.start(restart);
            if (restart) {
                rounds = 0;
                farmedMs = 0;
                harvested.entrySet().clear();
            }
        }

        @Override
        public List<WorkItem> workItems(long now) {
            List<WorkItem> out = new ArrayList<>();
            if (center == null) {
                svc.failed(p, "the farm has no centre");
                return out;
            }
            if (cycles > 0 && rounds >= cycles) {
                if (planner.assignmentsOf(id()).isEmpty()) {
                    svc.done(p);
                }
                return out;
            }
            out.add(new WorkItem("farm", id(), "farm", "farmer", p.priority, null, dim, center, null, farmers,
                    "farm " + center + " r" + range, new JsonObject()));
            return out;
        }

        @Override
        public void begin(Assignment a, BotState b) {
            a.ctx = System.currentTimeMillis();
            a.phase("farm");
            planner.push(a, List.of(
                    Planner.entry(a, TaskTypes.FARM, Json.obj("center", Json.toTree(center), "range", range,
                            "durationSec", duration), duration + 600, a.item.label()),
                    deposit(a, resolve(depositPos, null))));
        }

        @Override
        public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
            Assignment.Result farm = last(results, TaskTypes.FARM);
            farmedMs += System.currentTimeMillis() - (Long) a.ctx;
            Map<String, Integer> moved = sum(results, TaskTypes.DEPOSIT, "moved");
            addAll(harvested, moved);
            if (farm == null || !farm.ok() && !Reasons.TIMEOUT.equals(farm.reason())) {
                planner.fail(a, farm == null ? Reasons.CANCELLED : farm.reason(), farm == null ? null : farm.message());
            } else {
                rounds++;
                planner.finish(a);
            }
            svc.changed(p);
        }

        @Override
        public JsonObject view(boolean full) {
            int items = harvested.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
            JsonObject progress = Json.obj("rounds", rounds, "harvested", items, "items", harvested.deepCopy(),
                    "farmingMin", farmedMs / 60_000);
            if (cycles > 0) {
                progress.addProperty("done", Math.min(rounds, cycles));
                progress.addProperty("total", cycles);
            }
            if (farmedMs > 60_000) {
                progress.addProperty("itemsPerHour", Math.round(items * 3_600_000.0 / farmedMs));
            }
            return view(progress);
        }

        @Override
        public JsonObject persist() {
            return Json.obj("rounds", rounds, "farmedMs", farmedMs, "harvested", harvested.deepCopy());
        }
    }
}
