package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.planner.Restock;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.tasks.Dispatcher;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared parts of the non-build project runtimes (gather, clear, farm, ranch, sort, smelt): work-source basics,
 * container resolution, a {@link ProductionWork.Host} view, deposits, inspections, blocked notes and the common view
 * fields. Subclasses keep their own state and implement the planning. Loop-owned.
 */
abstract class KindRuntime implements ProjectRuntime, ProductionWork.Host {
    static final String INSPECT = "inspect";

    final Project p;
    final ProjectService svc;
    final Manager m;
    final Planner planner;
    final String dim;
    /** "Why not" texts for the panel (key → reason). */
    final Map<String, String> notes = new TreeMap<>();
    boolean active;

    KindRuntime(Project p, ProjectService svc) {
        this.p = p;
        this.svc = svc;
        this.m = svc.manager();
        this.planner = svc.planner();
        this.dim = Dims.normalize(Json.getString(p.config, "dim", Dims.OVERWORLD));
    }

    // ------------------------------------------------------------------ WorkSource / Host basics

    @Override
    public String id() {
        return p.id;
    }

    @Override
    public String origin() {
        return TaskSpec.projectOrigin(p.id);
    }

    @Override
    public String serverId() {
        return p.serverId;
    }

    @Override
    public String dim() {
        return dim;
    }

    @Override
    public boolean allows(BotState b) {
        return p.allows(b);
    }

    @Override
    public String antiXray() {
        return m.config.get().server(p.serverId).map(ManagerConfig.ServerProfile::antiXray)
                .orElse(ManagerConfig.ServerProfile.ANTI_XRAY_NONE);
    }

    /** Delivery targets for {@link ProductionWork} (default: none, i.e. storage). */
    @Override
    public List<WorldDoc.Container> supply() {
        return List.of();
    }

    /** Containers work may take from: supply, then storage, fuel, kit, inbox and category chests of the dimension. */
    @Override
    public List<WorldDoc.Container> sources() {
        List<WorldDoc.Container> out = new ArrayList<>(supply());
        WorldDoc doc = doc();
        if (doc == null) {
            return out;
        }
        for (String role : List.of("storage", "fuel", "kit", "inbox")) {
            for (WorldDoc.Container c : planner.containers(p.serverId, dim, role)) {
                if (out.stream().noneMatch(x -> x.pos().equals(c.pos()))) {
                    out.add(c);
                }
            }
        }
        for (WorldDoc.Container c : doc.containers) {
            if (c.sortedCategory() != null && Dims.normalize(c.dim()).equals(dim)
                    && out.stream().noneMatch(x -> x.pos().equals(c.pos()))) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public void start(boolean restart) {
        active = true;
        if (restart) {
            notes.clear();
        }
    }

    @Override
    public void stop() {
        active = false;
    }

    WorldDoc doc() {
        return planner.world(p.serverId);
    }

    /** A bot without roles takes anything; otherwise it needs {@code role}. */
    static boolean roleOk(BotState b, String role) {
        return b.def.roles().isEmpty() || b.def.roles().contains(role);
    }

    /** Configured positions (unknown ones as ad-hoc containers), else the containers with {@code role} in the dim. */
    List<WorldDoc.Container> resolve(JsonArray positions, String role) {
        WorldDoc doc = doc();
        if (doc == null) {
            return List.of();
        }
        if (positions == null || positions.isEmpty()) {
            return role == null ? List.of() : planner.containers(p.serverId, dim, role);
        }
        List<WorldDoc.Container> out = new ArrayList<>();
        for (JsonElement e : positions) {
            Pos pos = Pos.fromJson(e);
            if (pos == null) {
                continue;
            }
            WorldDoc.Container c = doc.containerAt(dim, pos);
            out.add(c != null ? c : new WorldDoc.Container("adhoc-" + pos, dim, pos, "minecraft:chest",
                    role == null ? List.of() : List.of(role), null, null, 0));
        }
        return out;
    }

    /** Deposit into {@code targets} (when given) or the {@code deposit_storage} step, keeping {@code planner.depositKeep}. */
    QueueEntry deposit(Assignment a, List<WorldDoc.Container> targets) {
        if (targets == null || targets.isEmpty()) {
            return Planner.entry(a, Dispatcher.STEP_DEPOSIT_STORAGE, new JsonObject(), 0, null);
        }
        List<Pos> pos = targets.stream().map(WorldDoc.Container::pos).toList();
        return Planner.entry(a, TaskTypes.DEPOSIT, Json.obj("containers", Json.arrOf(pos),
                "keep", Json.arrOf(m.config.get().planner().depositKeep())), Restock.TAKE_TIMEOUT_SEC, null);
    }

    /** An inspect item for never-seen containers (one per source, lock = the item key). */
    WorkItem inspectItem(String key, List<WorldDoc.Container> unknown, double priority) {
        return new WorkItem(INSPECT + ":" + key, id(), INSPECT, null, priority, null, dim, unknown.getFirst().pos(),
                p.id + "/" + INSPECT + ":" + key, 1, "inspect " + unknown.size() + " container(s)",
                Json.obj("containers", Json.arrOf(unknown.stream().map(WorldDoc.Container::pos).limit(8).toList())));
    }

    void beginInspect(Assignment a) {
        a.phase("inspect");
        planner.push(a, List.of(Planner.entry(a, TaskTypes.INSPECT,
                Json.obj("containers", a.item.data().get("containers").deepCopy()), Restock.TAKE_TIMEOUT_SEC, null)));
    }

    static List<WorldDoc.Container> unknown(List<WorldDoc.Container> cs) {
        return cs.stream().filter(c -> c.snapshot() == null && !c.id().startsWith("adhoc-")).toList();
    }

    /** First non-ok result of a batch, or null. */
    static Assignment.Result firstBad(List<Assignment.Result> results) {
        return results.stream().filter(r -> !r.ok()).findFirst().orElse(null);
    }

    static Assignment.Result last(List<Assignment.Result> results, String type) {
        Assignment.Result out = null;
        for (Assignment.Result r : results) {
            if (type.equals(r.type())) {
                out = r;
            }
        }
        return out;
    }

    /** Sums {@code data.<key>} maps ({@code moved}, {@code collected}, ...) of results of {@code type}. */
    static Map<String, Integer> sum(List<Assignment.Result> results, String type, String key) {
        Map<String, Integer> out = new TreeMap<>();
        for (Assignment.Result r : results) {
            if (type.equals(r.type()) && r.ok() && r.data() != null) {
                JsonObject o = Json.getObj(r.data(), key);
                if (o != null) {
                    o.entrySet().forEach(e -> out.merge(e.getKey(), e.getValue().getAsInt(), Integer::sum));
                }
            }
        }
        return out;
    }

    static void addAll(JsonObject totals, Map<String, Integer> add) {
        add.forEach((k, v) -> totals.addProperty(k, Json.getInt(totals, k, 0) + v));
    }

    @Override
    public void onItemFailed(WorkItem item, String reason, String message) {
        String text = item.label() + ": " + reason + (message == null || message.isBlank() ? "" : " (" + message + ")");
        notes.put(item.id(), text);
        svc.blocked(p, item.id(), text, Json.obj("item", item.id(), "reason", reason));
        svc.changed(p);
    }

    /** {@code blocked}: notes plus items in retry backoff. */
    JsonArray blockedView() {
        JsonArray blocked = new JsonArray();
        notes.forEach((k, v) -> blocked.add(Json.obj("key", k, "reason", v)));
        planner.retries().failedWithPrefix(p.id + "/").forEach((k, st) -> blocked.add(Json.obj(
                "key", k.substring(p.id.length() + 1), "reason", st.reason())));
        return blocked;
    }

    /** {@code {progress, blocked}} with {@code percent} computed from {@code done} / {@code total} when both exist. */
    JsonObject view(JsonObject progress) {
        if (progress.has("done") && progress.has("total")) {
            int total = progress.get("total").getAsInt();
            int done = progress.get("done").getAsInt();
            progress.addProperty("percent", total <= 0 ? 100.0 : Math.min(100.0, Math.round(done * 1000.0 / total) / 10.0));
        }
        return Json.obj("progress", progress, "blocked", blockedView());
    }

    @Override
    public void onReleased(Assignment a, String why) {
    }
}
