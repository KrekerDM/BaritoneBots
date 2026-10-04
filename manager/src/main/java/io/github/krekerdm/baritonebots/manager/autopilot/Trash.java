package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.tasks.KitPlanner;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code trash} manager step and auto-trash (SPEC §5.7f). {@code trash {profile?, to?: drop|store|trash_chest}}
 * reads the live inventory, computes the junk under the keep profile ({@link KeepPlanner#plan}) and expands into one
 * {@code drop} or {@code deposit} of exactly those stacks ({@code slots}). Internal {@code _junk: true} = auto-trash:
 * only {@code autopilot.autoTrash.junk} beyond its {@code keepCounts}, thrown on the spot.
 */
public final class Trash {
    public static final String TO_DROP = "drop";
    public static final String TO_STORE = "store";
    public static final String TO_TRASH_CHEST = "trash_chest";
    public static final String ROLE = "trash";
    /** Task types whose {@code inventory_full} may be answered by throwing junk on the spot. */
    public static final Set<String> AUTO_TYPES = Set.of(TaskTypes.MINE, TaskTypes.SELECTION, TaskTypes.EXPLORE);
    static final int TRASH_RADIUS = 160;

    private final Manager m;
    private final Autopilot ap;

    Trash(Manager m, Autopilot ap) {
        this.m = m;
        this.ap = ap;
    }

    /**
     * Should an {@code inventory_full} of {@code type} first throw junk on the spot? Uses the last status: auto-trash
     * on, a mining / clearing task, and at least two junk stacks (one is enough when there is no storage to go to).
     */
    public boolean autoTrashFits(BotState b, String type, boolean storageKnown) {
        ManagerConfig.AutoTrash at = ap.cfg(b).autoTrash();
        if (!at.enabled() || !AUTO_TYPES.contains(type) || b.status == null) {
            return false;
        }
        int stacks = KeepPlanner.junkStacks(b.status.items(), at.junk(), at.keepCounts());
        return stacks >= 2 || stacks >= 1 && !storageKnown;
    }

    /** Runs the {@code trash} step. */
    public void run(BotState b, QueueEntry e) {
        boolean junkOnly = Json.getBool(e.args(), "_junk", false);
        String to = Json.getString(e.args(), "to", TO_DROP);
        if (!List.of(TO_DROP, TO_STORE, TO_TRASH_CHEST).contains(to)) {
            m.dispatcher.failStep(b, e, Reasons.BAD_ARGS, "to must be drop, store or trash_chest");
            return;
        }
        ManagerConfig cfg = m.config.get();
        String profileName = Json.getString(e.args(), "profile", "");
        JsonObject profile = junkOnly ? null : cfg.keepProfile(profileName);
        if (!junkOnly && profile == null) {
            m.dispatcher.failStep(b, e, Reasons.NOT_FOUND, "keep profile '" + profileName + "' not found");
            return;
        }
        List<Pos> targets = List.of();
        if (TO_STORE.equals(to)) {
            targets = m.dispatcher.storageTargets(b);
        } else if (TO_TRASH_CHEST.equals(to)) {
            targets = trashChests(b);
        }
        if (!TO_DROP.equals(to) && targets.isEmpty()) {
            m.dispatcher.failStep(b, e, Reasons.NOT_FOUND, TO_STORE.equals(to)
                    ? "no containers with role 'storage' or 'inbox' in this dimension"
                    : "no container with role 'trash' in this dimension");
            return;
        }
        List<Pos> containers = targets;
        m.dispatcher.startStep(b, e);
        m.planner.queryVia(b, QueryKinds.INVENTORY, new JsonObject(), Autopilot.QUERY_TIMEOUT_MS, (r, t) -> {
            if (!m.dispatcher.isRunning(b, e)) {
                return;
            }
            if (r == null || !r.ok() || r.data() == null) {
                m.dispatcher.finishStep(b, e, false, Reasons.ERROR, "inventory query failed", null, null, false);
                return;
            }
            List<KeepPlanner.Stack> stacks = KeepPlanner.stacks(r.data());
            List<KeepPlanner.Pick> picks;
            if (junkOnly) {
                ManagerConfig.AutoTrash at = ap.cfg(b).autoTrash();
                picks = KeepPlanner.junk(stacks, at.junk(), at.keepCounts());
            } else {
                List<String> blocks = ap.cfg(b).throwaway();
                TaskNeeds.Context ctx = ap.needsContext(b);
                picks = KeepPlanner.plan(stacks, KeepPlanner.worn(r.data()), KeepPlanner.Profile.of(profile, blocks),
                        ctx::isFood, !TO_STORE.equals(to));
            }
            Map<String, Integer> junk = KeepPlanner.totals(picks);
            JsonObject data = Json.obj("junk", Json.toTree(junk), "to", to,
                    "freedSlots", KeepPlanner.freedSlots(stacks, picks));
            if (picks.isEmpty()) {
                m.dispatcher.finishStep(b, e, true, null, "nothing to throw away", data, null, false);
                return;
            }
            JsonArray slots = new JsonArray();
            picks.forEach(p -> slots.add(p.toJson()));
            QueueEntry child = TO_DROP.equals(to)
                    ? m.dispatcher.childEntry(e, TaskTypes.DROP, Json.obj("slots", slots), 120)
                    : m.dispatcher.childEntry(e, TaskTypes.DEPOSIT, Json.obj("containers", Json.arrOf(containers),
                            "slots", slots), 300);
            int items = junk.values().stream().mapToInt(Integer::intValue).sum();
            m.event("trash", Levels.INFO, b.id, junkOnly ? "event.trash.auto" : "event.trash.planned",
                    Map.of("bot", b.id, "items", items, "stacks", picks.size(), "to", to), data);
            m.dispatcher.finishStep(b, e, true, null, items + " items", data, List.of(child), false);
        });
    }

    /** Containers with role {@code trash} in the bot's dimension, nearest-neighbour order. */
    List<Pos> trashChests(BotState b) {
        WorldDoc doc = b.def.serverId() == null ? null : m.worlds.get(b.def.serverId());
        if (doc == null) {
            return List.of();
        }
        String dim = b.status != null && b.status.dim() != null ? Dims.normalize(b.status.dim()) : Dims.OVERWORLD;
        Pos at = Planner.posOf(b);
        List<Pos> list = doc.containers.stream()
                .filter(c -> c.hasRole(ROLE) && Dims.normalize(c.dim()).equals(dim)
                        && (at == null || c.pos().distance(at) <= TRASH_RADIUS))
                .map(WorldDoc.Container::pos).toList();
        return KitPlanner.nearestNeighbour(list, at, p -> p);
    }
}
