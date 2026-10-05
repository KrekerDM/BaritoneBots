package io.github.krekerdm.baritonebots.manager.autopilot;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.world.HouseBlocks;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Automatic house zones (SPEC §5.7g): with {@code servers[].protection.autoHouse}, the places worth protecting — home
 * waypoints, the other waypoints, clusters of indexed containers — are scanned with {@code scan_blocks} for built
 * blocks ({@link HouseBlocks#DETECT}) by an online bot within {@code houseRadius} of the place, at most one scan per
 * server at a time and every {@value #RESCAN_MIN} min per place (sooner when it never succeeded). Houses become zones
 * with source {@code auto} ({@link WorldDoc#mergeHouseZones}); a change is saved, shown in the panel, logged
 * ({@code house_zone}) and sent to the bots at once. Runs after each planner tick, like container discovery.
 * Loop-owned.
 */
public final class HouseScanner {
    static final int RESCAN_MIN = 15;
    static final long RETRY_MS = 60_000;
    static final long QUERY_MS = 60_000;
    /** Containers this close together count as one place. */
    static final int CONTAINER_GROUP = 16;
    static final int DY = 24;

    private final Manager m;
    private final Planner planner;
    private final Autopilot autopilot;
    /** place key → time of the next allowed scan. */
    private final Map<String, Long> due = new HashMap<>();
    private final Set<String> busy = new HashSet<>();

    /** A place to scan: dimension and centre. */
    record Place(String dim, Pos center) {
        String key(String serverId) {
            return serverId.toLowerCase(Locale.ROOT) + "|" + dim + "|" + (center.x() >> 3) + "|" + (center.z() >> 3);
        }
    }

    HouseScanner(Manager m, Planner planner, Autopilot autopilot) {
        this.m = m;
        this.planner = planner;
        this.autopilot = autopilot;
    }

    /** Settings of a server profile: {@code [autoHouse, houseRadius]}; null when protection is off. */
    static int[] settings(ManagerConfig.ServerProfile s) {
        JsonObject pr = s.protection();
        if (!Json.getBool(pr, "enabled", true)) {
            return null;
        }
        int radius = Math.max(8, Math.min(96, Json.getInt(pr, "houseRadius", 48)));
        return new int[] {Json.getBool(pr, "autoHouse", true) ? 1 : 0, radius};
    }

    /** After every planner tick (from {@link Autopilot#afterTick}) and on "find houses now". */
    public void tick(long now) {
        for (ManagerConfig.ServerProfile s : m.config.get().servers()) {
            int[] cfg = settings(s);
            if (cfg == null || cfg[0] == 0 || busy.contains(s.id().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (Place p : places(s.id(), cfg[1])) {
                String key = p.key(s.id());
                if (due.getOrDefault(key, 0L) > now) {
                    continue;
                }
                BotState bot = scanner(s.id(), p, cfg[1]);
                if (bot == null) {
                    continue;
                }
                due.put(key, now + RETRY_MS);
                scan(bot, s.id(), p, cfg[1], key);
                break; // one scan per server at a time
            }
        }
    }

    /** "Find houses now" (panel): every place of the server is due again. */
    public void rescan(String serverId) {
        String prefix = serverId.toLowerCase(Locale.ROOT) + "|";
        due.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private void scan(BotState bot, String sid, Place p, int radius, String key) {
        String lk = sid.toLowerCase(Locale.ROOT);
        busy.add(lk);
        query(bot, p.center(), radius, (data, err) -> {
            busy.remove(lk);
            if (data == null) {
                return; // tried again after RETRY_MS
            }
            due.put(key, System.currentTimeMillis() + RESCAN_MIN * 60_000L);
            List<Box> houses = HouseBlocks.houseZones(data);
            WorldDoc doc = m.worlds.get(sid);
            int changed = doc.mergeHouseZones(p.dim(), houses);
            if (changed > 0) {
                applied(sid, bot.id, changed, p);
            }
        });
    }

    /**
     * The zone of the structure at {@code at} (owner {@code protect}): the cluster of built blocks whose zone comes
     * within 4 blocks of the point (the biggest one), scanned by the nearest online bot within 64 blocks;
     * {@code cb(zone, true)}. Without a bot or a structure: 9 × 8 × 9 blocks around the point, {@code cb(box, false)}.
     */
    public void structureAt(String sid, String dim, Pos at, BiConsumer<Box, Boolean> cb) {
        Box fallback = new Box(at.offset(-4, -1, -4), at.offset(4, 6, 4));
        BotState bot = scanner(sid, new Place(dim, at), 64);
        if (bot == null) {
            cb.accept(fallback, false);
            return;
        }
        query(bot, at, 24, (data, err) -> {
            Box best = null;
            int bestCount = 0;
            for (HouseBlocks.Cluster c : HouseBlocks.clusters(data)) {
                Box zone = HouseBlocks.zoneOf(c.box());
                if (zone.expand(4).contains(at) && c.count() > bestCount) {
                    best = zone;
                    bestCount = c.count();
                }
            }
            cb.accept(best != null ? best : fallback, best != null);
        });
    }

    /** {@code scan_blocks} for built blocks around {@code center}; {@code cb(data, null)} or {@code cb(null, error)}. */
    void query(BotState bot, Pos center, int radius, BiConsumer<JsonObject, String> cb) {
        planner.queryVia(bot, QueryKinds.SCAN_BLOCKS, Json.obj("center", center, "radius", radius, "dy", DY,
                "ids", Json.arrOf(HouseBlocks.DETECT), "gap", 2, "maxClusters", 128), QUERY_MS, (r, t) -> {
            if (r == null || !r.ok() || r.data() == null) {
                cb.accept(null, t != null ? String.valueOf(t.getMessage()) : r == null ? "no answer" : String.valueOf(r.error()));
            } else {
                cb.accept(r.data(), null);
            }
        });
    }

    /** Zones changed: save, panel, event, bots. */
    void applied(String sid, String botId, int changed, Place p) {
        m.worlds.markDirty(sid);
        m.broadcastWorld(sid);
        m.pushConfigForServer(sid);
        m.event("house_zone", Levels.INFO, botId, "event.protection.houses", Map.of("count", changed,
                "x", p.center().x(), "y", p.center().y(), "z", p.center().z()));
    }

    /** Homes, other waypoints and container groups of the server, at most one place per {@code radius / 2}. */
    List<Place> places(String serverId, int radius) {
        WorldDoc doc = m.worlds.get(serverId);
        List<Place> raw = new ArrayList<>();
        autopilot.homes(serverId).forEach(w -> raw.add(new Place(Dims.normalize(w.dim()), w.pos())));
        doc.waypoints.forEach(w -> raw.add(new Place(Dims.normalize(w.dim()), w.pos())));
        raw.addAll(containerGroups(doc));
        List<Place> out = new ArrayList<>();
        for (Place p : raw) {
            boolean near = out.stream().anyMatch(q -> q.dim().equals(p.dim())
                    && q.center().horizontalDistance(p.center()) <= radius / 2.0);
            if (!near) {
                out.add(p);
            }
        }
        return out;
    }

    /** Centres of groups of indexed containers (roles other than {@code found}) within {@value #CONTAINER_GROUP}. */
    static List<Place> containerGroups(WorldDoc doc) {
        List<List<WorldDoc.Container>> groups = new ArrayList<>();
        for (WorldDoc.Container c : doc.containers) {
            if (c.roles().isEmpty() || c.roles().equals(List.of("found"))) {
                continue;
            }
            List<WorldDoc.Container> home = null;
            for (List<WorldDoc.Container> g : groups) {
                if (g.getFirst().dim().equals(c.dim()) && g.getFirst().pos().distance(c.pos()) <= CONTAINER_GROUP) {
                    home = g;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                groups.add(home);
            }
            home.add(c);
        }
        List<Place> out = new ArrayList<>();
        for (List<WorldDoc.Container> g : groups) {
            long x = 0;
            long y = 0;
            long z = 0;
            for (WorldDoc.Container c : g) {
                x += c.pos().x();
                y += c.pos().y();
                z += c.pos().z();
            }
            out.add(new Place(Dims.normalize(g.getFirst().dim()),
                    new Pos((int) (x / g.size()), (int) (y / g.size()), (int) (z / g.size()))));
        }
        return out;
    }

    /** The online bot of the server and dimension nearest to the place, within {@code radius} (its loaded chunks). */
    BotState scanner(String serverId, Place p, int radius) {
        BotState best = null;
        double bestD = Double.MAX_VALUE;
        for (BotState b : m.bots.all()) {
            Pos pos = Planner.posOf(b);
            if (!b.online() || b.dead || pos == null || !serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))
                    || b.status.dim() != null && !Dims.normalize(b.status.dim()).equals(p.dim())) {
                continue;
            }
            double d = pos.horizontalDistance(p.center());
            if (d < bestD) {
                best = b;
                bestD = d;
            }
        }
        return bestD <= radius ? best : null;
    }
}
