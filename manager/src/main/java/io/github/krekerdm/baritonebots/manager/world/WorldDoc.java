package io.github.krekerdm.baritonebots.manager.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.util.Tokens;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * World knowledge of one server profile (SPEC §5.6), stored as world/&lt;serverId&gt;.json. Mutable lists owned by
 * the manager loop.
 */
public final class WorldDoc {
    /** {@code found}: discovered away from home by the autopilot; a source only with {@code autopilot.useFound}. */
    public static final Set<String> CONTAINER_ROLES = Set.of("kit", "storage", "supply", "fuel", "inbox", "furnace",
            "crafting", "found", "trash");
    public static final String SORTED_PREFIX = "sorted:";
    public static final int MAX_DEATHS = 200;

    public record Waypoint(String name, String dim, Pos pos) {
    }

    public record Area(String name, String dim, Box box) {
    }

    /** Contents seen last time any bot had this container open. */
    public record Snapshot(int size, int free, List<ContainerSnapshot.SlotItem> items, long time) {
        public Snapshot {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public Map<String, Integer> totals() {
            Map<String, Integer> out = new LinkedHashMap<>();
            items.forEach(s -> out.merge(s.item(), s.count(), Integer::sum));
            return out;
        }
    }

    /** Who set a container's roles: precedence manual &gt; sign &gt; auto (SPEC §5.7e). */
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_SIGN = "sign";
    public static final String SOURCE_AUTO = "auto";
    /** Roles only a person sets (see {@link Container#manual()}). */
    static final Set<String> LEGACY_MANUAL = Set.of("inbox", "kit", "supply", "fuel", "trash");

    /**
     * {@code snapshot == null} means the contents are unknown (never inspected). {@code signText} / {@code frameItem}
     * = the label last seen on the container (null = none); {@code roleSource} = who set {@code roles}
     * ({@link #SOURCE_MANUAL}, {@link #SOURCE_SIGN}, {@link #SOURCE_AUTO}; null = auto).
     */
    public record Container(String id, String dim, Pos pos, String block, List<String> roles, String label,
                            Snapshot snapshot, long lastSeen, String signText, String frameItem, String roleSource) {
        public Container {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public Container(String id, String dim, Pos pos, String block, List<String> roles, String label,
                         Snapshot snapshot, long lastSeen) {
            this(id, dim, pos, block, roles, label, snapshot, lastSeen, null, null, null);
        }

        public boolean hasRole(String role) {
            return roles.contains(role);
        }

        public Container withSnapshot(String newBlock, Snapshot s, long seen) {
            return new Container(id, dim, pos, newBlock == null ? block : newBlock, roles, label, s, seen, signText,
                    frameItem, roleSource);
        }

        /** New roles from an automatic source (keeps the recorded source). */
        public Container withRoles(List<String> newRoles) {
            return new Container(id, dim, pos, block, newRoles, label, snapshot, lastSeen, signText, frameItem,
                    roleSource);
        }

        public Container withRoles(List<String> newRoles, String source) {
            return new Container(id, dim, pos, block, newRoles, label, snapshot, lastSeen, signText, frameItem, source);
        }

        public Container withLabel(String newSignText, String newFrameItem) {
            return new Container(id, dim, pos, block, roles, label, snapshot, lastSeen, newSignText, newFrameItem,
                    roleSource);
        }

        /**
         * Roles set by hand. Containers saved before role sources existed count as manual when they carry a role the
         * autopilot never assigns ({@code inbox}, {@code kit}, {@code supply}, {@code fuel}, {@code trash}).
         */
        public boolean manual() {
            return SOURCE_MANUAL.equals(roleSource) || roleSource == null && roles.stream().anyMatch(LEGACY_MANUAL::contains);
        }

        public boolean fromSign() {
            return SOURCE_SIGN.equals(roleSource);
        }

        /** The {@code sorted:<category>} role's category, or null. */
        public String sortedCategory() {
            for (String r : roles) {
                if (r.startsWith(SORTED_PREFIX)) {
                    return r.substring(SORTED_PREFIX.length());
                }
            }
            return null;
        }
    }

    public record Zone(String name, String dim, Box box) {
    }

    public record Death(String botId, String dim, Pos pos, String cause, long time) {
    }

    public final List<Waypoint> waypoints = new ArrayList<>();
    public final List<Area> areas = new ArrayList<>();
    public final List<Container> containers = new ArrayList<>();
    public final List<Zone> zones = new ArrayList<>();
    public final List<Death> deaths = new ArrayList<>();

    public JsonObject toJson() {
        return Json.obj("waypoints", Json.arrOf(waypoints), "areas", Json.arrOf(areas),
                "containers", Json.arrOf(containers), "zones", Json.arrOf(zones), "deaths", Json.arrOf(deaths));
    }

    public Waypoint waypoint(String name) {
        return waypoints.stream().filter(w -> w.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Container containerAt(String dim, Pos pos) {
        return containers.stream().filter(c -> c.dim().equals(dim) && c.pos().equals(pos)).findFirst().orElse(null);
    }

    public void replaceContainer(Container updated) {
        for (int i = 0; i < containers.size(); i++) {
            if (containers.get(i).id().equals(updated.id())) {
                containers.set(i, updated);
                return;
            }
        }
        containers.add(updated);
    }

    /** Container id → roles, to compare before / after a panel edit ({@link #markManualRoles}). */
    public Map<String, List<String>> rolesById() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        containers.forEach(c -> out.put(c.id(), c.roles()));
        return out;
    }

    /**
     * After the user edited the containers: those whose roles changed, and new ones given roles, count as set by hand
     * ({@link #SOURCE_MANUAL}, never overridden by signs or the autopilot).
     */
    public void markManualRoles(Map<String, List<String>> before) {
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            List<String> old = before.get(c.id());
            boolean changed = old == null ? !c.roles().isEmpty() : !old.equals(c.roles());
            if (changed && !SOURCE_MANUAL.equals(c.roleSource())) {
                containers.set(i, c.withRoles(c.roles(), SOURCE_MANUAL));
            }
        }
    }

    public void addDeath(Death d) {
        deaths.add(d);
        while (deaths.size() > MAX_DEATHS) {
            deaths.removeFirst();
        }
    }

    /**
     * Replaces the sections present in {@code body} (arrays replace wholesale, like a JSON merge patch).
     *
     * @throws ValidationException naming the first broken entry
     */
    public void applySections(JsonObject body) {
        if (body.has("waypoints")) {
            List<Waypoint> l = new ArrayList<>();
            each(body, "waypoints", (o, p) -> {
                require(!Json.getString(o, "name", "").isBlank(), p + ".name");
                l.add(new Waypoint(Json.getString(o, "name", "").trim(), dim(o, p), pos(o, "pos", p)));
            });
            waypoints.clear();
            waypoints.addAll(l);
        }
        if (body.has("areas")) {
            List<Area> l = new ArrayList<>();
            each(body, "areas", (o, p) -> l.add(new Area(Json.getString(o, "name", ""), dim(o, p), box(o, p))));
            areas.clear();
            areas.addAll(l);
        }
        if (body.has("zones")) {
            List<Zone> l = new ArrayList<>();
            each(body, "zones", (o, p) -> l.add(new Zone(Json.getString(o, "name", ""), dim(o, p), box(o, p))));
            zones.clear();
            zones.addAll(l);
        }
        if (body.has("containers")) {
            List<Container> l = new ArrayList<>();
            each(body, "containers", (o, p) -> {
                List<String> roles = Json.getStringList(o, "roles");
                for (String r : roles) {
                    require(CONTAINER_ROLES.contains(r) || (r.startsWith(SORTED_PREFIX) && r.length() > SORTED_PREFIX.length()),
                            p + ".roles");
                }
                Snapshot snap = null;
                JsonObject so = Json.getObj(o, "snapshot");
                if (so != null) {
                    try {
                        snap = Json.fromJson(so, Snapshot.class);
                    } catch (JsonParseException | IllegalStateException e) {
                        throw ValidationException.of(p + ".snapshot", "type");
                    }
                }
                String id = Json.getString(o, "id", null);
                String source = Json.getString(o, "roleSource", null);
                if (source != null && !List.of(SOURCE_MANUAL, SOURCE_SIGN, SOURCE_AUTO).contains(source)) {
                    throw ValidationException.of(p + ".roleSource", "enum");
                }
                l.add(new Container(id == null || id.isBlank() ? Tokens.id("c") : id, dim(o, p), pos(o, "pos", p),
                        Json.getString(o, "block", "minecraft:chest"), roles, Json.getString(o, "label", ""), snap,
                        Json.getLong(o, "lastSeen", 0), Json.getString(o, "signText", null),
                        Json.getString(o, "frameItem", null), source));
            });
            containers.clear();
            containers.addAll(l);
        }
        if (body.has("deaths")) {
            List<Death> l = new ArrayList<>();
            each(body, "deaths", (o, p) -> l.add(new Death(Json.getString(o, "botId", ""), dim(o, p),
                    pos(o, "pos", p), Json.getString(o, "cause", ""), Json.getLong(o, "time", 0))));
            deaths.clear();
            deaths.addAll(l);
        }
    }

    public static WorldDoc fromJson(JsonObject o) {
        WorldDoc d = new WorldDoc();
        d.applySections(o);
        return d;
    }

    private interface ItemReader {
        void read(JsonObject item, String path);
    }

    private static void each(JsonObject body, String key, ItemReader reader) {
        JsonElement e = body.get(key);
        if (e == null || e.isJsonNull()) {
            return;
        }
        require(e.isJsonArray(), key);
        JsonArray a = e.getAsJsonArray();
        for (int i = 0; i < a.size(); i++) {
            String p = key + "[" + i + "]";
            require(a.get(i).isJsonObject(), p);
            reader.read(a.get(i).getAsJsonObject(), p);
        }
    }

    private static String dim(JsonObject o, String p) {
        String d = Json.getString(o, "dim", Dims.OVERWORLD);
        require(!d.isBlank(), p + ".dim");
        return Dims.normalize(d);
    }

    private static Pos pos(JsonObject o, String key, String p) {
        Pos pos = Pos.fromJson(o.get(key));
        require(pos != null, p + "." + key);
        return pos;
    }

    private static Box box(JsonObject o, String p) {
        Box b = Box.fromJson(o.get("box"));
        require(b != null, p + ".box");
        return b;
    }

    private static void require(boolean ok, String path) {
        if (!ok) {
            throw ValidationException.of(path, "type");
        }
    }
}
