package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.autopilot.SortPlanner;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code sort} projects (SPEC §5.7): sort chosen inbox containers into the category chests with the autopilot's
 * sorter ({@link SortPlanner}, category adoption, overflow). Config {@code {inbox?:[pos], continuous:false, dim}};
 * without {@code inbox} every container with role {@code inbox} in the dimension. One {@code sort:<inbox>} item per
 * inbox with something to move (role sorter, lock = the inbox) runs {@code transfer}s sized to the bot's free slots.
 * One-shot projects are done when no inbox has anything movable left; items without room anywhere are reported.
 */
public final class SortKind implements ProjectKind {
    public static final String KIND = "sort";
    static final String SORT = "sort";
    private final Manager m;

    public SortKind(Manager m) {
        this.m = m;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject normalizeConfig(JsonObject config, String serverId) {
        KindConfig c = new KindConfig(config);
        JsonArray inbox = c.positions("inbox");
        boolean continuous = c.bool("continuous", false);
        return c.done(Json.obj("inbox", inbox, "continuous", continuous, "dim", c.dim()));
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService svc) {
        return new Runtime(p, svc);
    }

    static final class Runtime extends KindRuntime {
        private final JsonArray inboxPos;
        private final boolean continuous;
        int initial = -1;
        int remaining;
        int moved;
        private final Map<String, Integer> unsorted = new TreeMap<>();

        Runtime(Project p, ProjectService svc) {
            super(p, svc);
            inboxPos = Json.getArr(p.config, "inbox");
            continuous = Json.getBool(p.config, "continuous", false);
            initial = Json.getInt(p.state, "initial", -1);
            moved = Json.getInt(p.state, "moved", 0);
        }

        @Override
        public void start(boolean restart) {
            super.start(restart);
            if (restart) {
                initial = -1;
                moved = 0;
            }
        }

        List<WorldDoc.Container> inboxes() {
            return resolve(inboxPos, "inbox");
        }

        @Override
        public List<WorkItem> workItems(long now) {
            List<WorkItem> out = new ArrayList<>();
            List<WorldDoc.Container> inboxes = inboxes();
            if (inboxes.isEmpty()) {
                notes.put("inbox", "no inbox container in " + dim);
                svc.blocked(p, "inbox", notes.get("inbox"), null);
                return out;
            }
            notes.remove("inbox");
            List<WorldDoc.Container> unknown = unknown(inboxes);
            if (!unknown.isEmpty()) {
                out.add(inspectItem("inbox", unknown, p.priority));
                return out;
            }
            m.autopilot.adoptCategories(p.serverId);
            unsorted.clear();
            int items = 0;
            for (WorldDoc.Container inbox : inboxes) {
                if (inbox.snapshot() == null || inbox.snapshot().items().isEmpty()) {
                    continue;
                }
                items += inbox.snapshot().totals().values().stream().mapToInt(Integer::intValue).sum();
                SortPlanner.Plan plan = m.autopilot.sortPlan(p.serverId, inbox, 27);
                plan.unsorted().forEach((k, v) -> unsorted.merge(k, v, Integer::sum));
                if (!plan.isEmpty()) {
                    String key = Planner.containerKey(inbox);
                    out.add(new WorkItem(SORT + ":" + key, id(), SORT, "sorter", p.priority, null, dim, inbox.pos(), key, 1,
                            "sort " + inbox.pos(), Json.obj("container", inbox.id(), "pos", inbox.pos())));
                }
            }
            remaining = items;
            if (initial < 0) {
                initial = items;
                svc.changed(p);
            }
            if (!unsorted.isEmpty()) {
                List<String> parts = new ArrayList<>();
                unsorted.forEach((k, v) -> parts.add(v + " × " + Ids.path(k)));
                notes.put("room", "no room for " + String.join(", ", parts));
                svc.blocked(p, "room", notes.get("room"), Json.obj("unsorted", Json.toTree(unsorted)));
            } else {
                notes.remove("room");
                svc.unblocked(p, "room");
            }
            if (!continuous && out.isEmpty() && planner.assignmentsOf(id()).isEmpty()) {
                svc.done(p);
            }
            return out;
        }

        @Override
        public boolean eligible(BotState b, WorkItem w) {
            return INSPECT.equals(w.kind()) || roleOk(b, "sorter") || roleOk(b, "hauler");
        }

        @Override
        public void begin(Assignment a, BotState b) {
            if (INSPECT.equals(a.item.kind())) {
                beginInspect(a);
                return;
            }
            WorldDoc doc = doc();
            WorldDoc.Container inbox = doc == null ? null : doc.containers.stream()
                    .filter(c -> c.id().equals(Json.getString(a.item.data(), "container", ""))).findFirst().orElse(null);
            List<QueueEntry> moves = inbox == null ? List.of() : m.autopilot.sortMoves(p.serverId, a, b, inbox);
            if (moves.isEmpty()) {
                planner.finish(a);
                return;
            }
            a.phase("sort");
            planner.push(a, moves);
        }

        @Override
        public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
            if (!INSPECT.equals(a.item.kind())) {
                for (Assignment.Result r : results) {
                    if (r.ok() && r.data() != null) {
                        JsonObject mv = Json.getObj(r.data(), "moved");
                        if (mv != null) {
                            moved += mv.entrySet().stream().mapToInt(e -> e.getValue().getAsInt()).sum();
                        }
                    }
                }
            }
            Assignment.Result bad = firstBad(results);
            if (bad != null && results.stream().noneMatch(Assignment.Result::ok)) {
                planner.fail(a, bad.reason(), bad.message());
            } else {
                planner.finish(a);
            }
            svc.changed(p);
        }

        @Override
        public JsonObject view(boolean full) {
            JsonObject progress = Json.obj("remaining", remaining, "moved", moved, "unsorted", Json.toTree(unsorted));
            if (initial >= 0) {
                progress.addProperty("total", Math.max(initial, remaining));
                progress.addProperty("done", Math.max(0, Math.max(initial, remaining) - remaining));
            }
            return view(progress);
        }

        @Override
        public JsonObject persist() {
            return Json.obj("initial", initial, "moved", moved);
        }
    }
}
