package io.github.krekerdm.baritonebots.manager.refs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.schematic.Schematic;
import io.github.krekerdm.baritonebots.common.schematic.SchematicLoader;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.projects.Project;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * {@code {"ref":"auto"}} (SPEC §5.7e): farm fields (farmland clusters near home), pens (fence / wall clusters that
 * hold the most animals) and flat building sites (heightmap, footprint of the schematic, away from projects, zones
 * and containers, within {@code autopilot.homeRadius}), plus container lists from the world index. Scans go through
 * the online bot nearest to the place, since a client only knows the chunks around itself. Loop-owned; callbacks
 * run on the loop.
 */
final class AutoDetect {
    static final int MAX_SCAN_DISTANCE = 128;
    static final long QUERY_MS = 20_000;
    static final List<String> FARM_ANIMALS = List.of("minecraft:cow", "minecraft:sheep", "minecraft:pig",
            "minecraft:chicken", "minecraft:rabbit", "minecraft:goat", "minecraft:mooshroom", "minecraft:horse",
            "minecraft:donkey", "minecraft:llama");
    static final List<String> PEN_TAGS = List.of("minecraft:fences", "minecraft:walls", "minecraft:fence_gates");

    /** What a detection found: a value for {@link Refs.Context#auto} plus extras (farm range, pen animal). */
    record Found(Object value, Map<String, Object> extras) {
    }

    private final Manager m;

    AutoDetect(Manager m) {
        this.m = m;
    }

    /**
     * Detects {@code arg} of {@code what} ({@code task type} or {@code project:<kind>}); {@code cb(found, null)} or
     * {@code cb(null, reason)}.
     */
    void detect(RefResolver.Ctx ctx, String what, String arg, String type, JsonObject args,
                BiConsumer<Found, String> cb) {
        String w = what.startsWith("project:") ? what.substring(8) : what;
        boolean project = what.startsWith("project:");
        switch (w + "." + arg) {
            case "farm.center", "farm.box" -> farm(ctx, cb);
            case "breed.box", "slaughter.box", "shear.box", "ranch.box" ->
                    pen(ctx, Json.getString(args, "animal", null), cb);
            case "build.origin" -> buildSite(ctx, args, project, cb);
            default -> {
                if (Refs.T_CONTAINERS.equals(type)) {
                    containers(ctx, w, arg, cb);
                } else {
                    cb.accept(null, "automatic detection is not available for " + w + "." + arg);
                }
            }
        }
    }

    // ------------------------------------------------------------------ farm fields

    private void farm(RefResolver.Ctx ctx, BiConsumer<Found, String> cb) {
        Pos center = center(ctx);
        BotState scanner = scanner(ctx, center);
        if (center == null || scanner == null) {
            cb.accept(null, noScanner(center));
            return;
        }
        int radius = scanRadius();
        query(scanner, QueryKinds.SCAN_BLOCKS, Json.obj("center", center, "radius", radius,
                "ids", Json.arr("minecraft:farmland"), "gap", 2, "maxClusters", 64), (data, err) -> {
            if (data == null) {
                cb.accept(null, "farmland scan failed: " + err);
                return;
            }
            Box best = nearest(clusters(data, 4), center);
            if (best == null) {
                cb.accept(null, "no farmland found within " + radius + " blocks of " + center);
                return;
            }
            Box field = new Box(best.min(), best.max().offset(0, 1, 0)); // crops grow on top of the farmland
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("range", Math.max(1, Math.min(128, Math.max(field.width(), field.length()) / 2 + 1)));
            cb.accept(new Found(field, extras), null);
        });
    }

    // ------------------------------------------------------------------ pens

    private void pen(RefResolver.Ctx ctx, String animal, BiConsumer<Found, String> cb) {
        Pos center = center(ctx);
        BotState scanner = scanner(ctx, center);
        if (center == null || scanner == null) {
            cb.accept(null, noScanner(center));
            return;
        }
        int radius = scanRadius();
        query(scanner, QueryKinds.SCAN_BLOCKS, Json.obj("center", center, "radius", radius,
                "tags", Json.arrOf(PEN_TAGS), "gap", 1, "maxClusters", 64), (data, err) -> {
            if (data == null) {
                cb.accept(null, "fence scan failed: " + err);
                return;
            }
            List<Box> pens = clusters(data, 8);
            if (pens.isEmpty()) {
                cb.accept(null, "no fenced pen found within " + radius + " blocks of " + center);
                return;
            }
            JsonArray types = new JsonArray();
            (animal == null || animal.isBlank() ? FARM_ANIMALS : List.of(Ids.normalize(animal))).forEach(types::add);
            query(scanner, QueryKinds.ENTITIES, Json.obj("radius", MAX_SCAN_DISTANCE, "types", types), (ents, err2) -> {
                List<double[]> animals = new ArrayList<>();
                List<String> kinds = new ArrayList<>();
                JsonArray list = ents == null ? null : Json.getArr(ents, "entities");
                for (JsonElement e : list == null ? new JsonArray() : list) {
                    JsonObject o = e.getAsJsonObject();
                    animals.add(new double[] {Json.getDouble(o, "x", 0), Json.getDouble(o, "y", 0), Json.getDouble(o, "z", 0)});
                    kinds.add(Json.getString(o, "type", ""));
                }
                Box best = null;
                int bestCount = 0;
                Map<String, Integer> bestKinds = new LinkedHashMap<>();
                for (Box p : pens) {
                    Map<String, Integer> inside = new LinkedHashMap<>();
                    for (int i = 0; i < animals.size(); i++) {
                        double[] a = animals.get(i);
                        if (a[0] >= p.min().x() && a[0] < p.max().x() + 1 && a[2] >= p.min().z() && a[2] < p.max().z() + 1
                                && a[1] >= p.min().y() - 2 && a[1] <= p.max().y() + 2) {
                            inside.merge(kinds.get(i), 1, Integer::sum);
                        }
                    }
                    int n = inside.values().stream().mapToInt(Integer::intValue).sum();
                    if (n > bestCount || n == bestCount && n > 0 && best != null
                            && dist(p, center) < dist(best, center)) {
                        best = p;
                        bestCount = n;
                        bestKinds = inside;
                    }
                }
                if (best == null) {
                    cb.accept(null, (animal == null ? "no animals" : "no " + Ids.path(Ids.normalize(animal)))
                            + " inside a fenced pen within " + radius + " blocks of " + center);
                    return;
                }
                Map<String, Object> extras = new LinkedHashMap<>();
                bestKinds.entrySet().stream().max(Map.Entry.comparingByValue())
                        .ifPresent(e -> extras.put("animal", e.getKey()));
                cb.accept(new Found(new Box(best.min(), best.max().offset(0, 1, 0)), extras), null);
            });
        });
    }

    // ------------------------------------------------------------------ build sites

    private void buildSite(RefResolver.Ctx ctx, JsonObject args, boolean project, BiConsumer<Found, String> cb) {
        Path file = schematicFile(args, project);
        if (file == null) {
            cb.accept(null, "the schematic is needed to find a site that fits it");
            return;
        }
        int rotation = Math.floorMod(Json.getInt(args, "rotation", 0), 360);
        String mirror = Json.getString(args, "mirror", "none");
        Pos center = center(ctx);
        BotState scanner = scanner(ctx, center);
        if (center == null || scanner == null) {
            cb.accept(null, noScanner(center));
            return;
        }
        Thread.ofVirtual().name("autodetect-schematic").start(() -> {
            Box fp0;
            try {
                Schematic s = SchematicLoader.load(file);
                fp0 = SchematicTransform.of(s, Pos.ZERO, rotation, mirror).footprint();
            } catch (Exception e) {
                m.loop.post(() -> cb.accept(null, "cannot read " + file.getFileName() + ": " + e.getMessage()));
                return;
            }
            m.loop.post(() -> site(ctx, scanner, center, fp0, cb));
        });
    }

    private void site(RefResolver.Ctx ctx, BotState scanner, Pos center, Box fp0, BiConsumer<Found, String> cb) {
        int radius = Math.max(16, Math.min(96, m.config.get().autopilot().homeRadius()));
        int w = fp0.width();
        int l = fp0.length();
        query(scanner, QueryKinds.HEIGHTMAP, Json.obj("center", center, "radius", radius), (data, err) -> {
            if (data == null) {
                cb.accept(null, "heightmap failed: " + err);
                return;
            }
            FlatSite.Grid grid = FlatSite.parse(data);
            FlatSite.Site s = FlatSite.find(grid, w, l, center.x(), center.z(), radius, exclusions(ctx), 1);
            if (s == null) {
                cb.accept(null, "no flat " + w + "×" + l + " site within " + radius + " blocks of home");
                return;
            }
            // footprint min corner → schematic origin (the footprint is relative to origin 0,0,0)
            Pos origin = new Pos(s.x() - fp0.min().x(), s.y(), s.z() - fp0.min().z());
            cb.accept(new Found(origin, Map.of()), null);
        });
    }

    private Path schematicFile(JsonObject args, boolean project) {
        String raw = Json.getString(args, project ? "schematic" : "file", "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        Path dir = m.dataDir.resolve("schematics");
        Path p;
        try {
            p = Path.of(raw);
            if (!p.isAbsolute()) {
                p = dir.resolve(raw).normalize();
                if (!p.startsWith(dir.normalize())) {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return Files.isRegularFile(p) ? p : null;
    }

    /** Boxes a new build must keep away from: projects, protected zones, indexed containers. */
    private List<Box> exclusions(RefResolver.Ctx ctx) {
        List<Box> out = new ArrayList<>();
        String dim = ctx.dimOr(Dims.OVERWORLD);
        for (Project p : m.projects.all()) {
            if (!p.serverId.equalsIgnoreCase(ctx.serverId) || !dim.equals(Dims.normalize(Json.getString(p.config, "dim",
                    Dims.OVERWORLD)))) {
                continue;
            }
            Box b = Box.fromJson(p.config.get("box"));
            if (b == null && p.runtime != null) {
                b = Box.fromJson(p.runtime.view(false).get("footprint"));
            }
            Pos origin = Pos.fromJson(p.config.get("origin"));
            if (b == null && origin != null) {
                b = new Box(origin, origin);
            }
            if (b != null) {
                out.add(b);
            }
        }
        WorldDoc doc = ctx.doc();
        if (doc != null) {
            for (WorldDoc.Zone z : doc.zones) {
                if (z.active() && Dims.normalize(z.dim()).equals(dim)) {
                    out.add(z.box());
                }
            }
            for (WorldDoc.Container c : doc.containers) {
                if (Dims.normalize(c.dim()).equals(dim)) {
                    out.add(new Box(c.pos(), c.pos()));
                }
            }
        }
        m.config.get().server(ctx.serverId).ifPresent(s -> {
            JsonArray zones = Json.getArr(s.protection(), "zones");
            for (JsonElement z : zones == null ? new JsonArray() : zones) {
                if (z.isJsonObject() && dim.equals(Dims.normalize(Json.getString(z.getAsJsonObject(), "dim",
                        Dims.OVERWORLD)))) {
                    Box b = Box.fromJson(z.getAsJsonObject().get("box"));
                    if (b != null) {
                        out.add(b);
                    }
                }
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ container lists

    private void containers(RefResolver.Ctx ctx, String what, String arg, BiConsumer<Found, String> cb) {
        WorldDoc doc = ctx.doc();
        String dim = ctx.dimOr(Dims.OVERWORLD);
        if (doc == null) {
            cb.accept(null, "no world index for this server");
            return;
        }
        List<String> roles = switch (arg) {
            case "supply" -> List.of("supply");
            case "furnaces" -> List.of("furnace");
            case "inbox" -> List.of("inbox");
            default -> List.of("inbox", "storage");
        };
        if ("inspect".equals(what)) {
            roles = List.of();
        }
        Pos from = ctx.bot != null && Planner.posOf(ctx.bot) != null ? Planner.posOf(ctx.bot) : center(ctx);
        List<WorldDoc.Container> list = new ArrayList<>();
        for (WorldDoc.Container c : doc.containers) {
            if (!Dims.normalize(c.dim()).equals(dim)) {
                continue;
            }
            boolean ok = roles.isEmpty() ? c.snapshot() == null && (from == null || c.pos().distance(from) <= 32)
                    : roles.stream().anyMatch(c::hasRole);
            if (ok) {
                list.add(c);
            }
        }
        if (list.isEmpty()) {
            cb.accept(null, roles.isEmpty() ? "no never-inspected containers nearby"
                    : "no containers with role " + String.join(" / ", roles) + " in " + dim);
            return;
        }
        Pos at = from;
        list.sort(Comparator.comparingDouble(c -> at == null ? 0 : c.pos().distance(at)));
        List<Pos> out = new ArrayList<>();
        for (WorldDoc.Container c : list) {
            if (out.size() < 16) {
                out.add(c.pos());
            }
        }
        cb.accept(new Found(out, Map.of()), null);
    }

    // ------------------------------------------------------------------ helpers

    /** Where to look: the home waypoint, else the bot. */
    private Pos center(RefResolver.Ctx ctx) {
        Refs.Located home = ctx.home();
        if (home != null && home.pos() != null && (home.dim() == null || ctx.dim() == null
                || Dims.normalize(home.dim()).equals(Dims.normalize(ctx.dim())))) {
            return home.pos();
        }
        return ctx.bot == null ? null : Planner.posOf(ctx.bot);
    }

    /** The online bot of the server and dimension nearest to {@code center}, within {@link #MAX_SCAN_DISTANCE}. */
    private BotState scanner(RefResolver.Ctx ctx, Pos center) {
        if (center == null) {
            return null;
        }
        String dim = ctx.dimOr(Dims.OVERWORLD);
        BotState best = null;
        double bestD = Double.MAX_VALUE;
        for (BotState b : m.bots.all()) {
            Pos p = Planner.posOf(b);
            if (!b.online() || b.dead || p == null || !ctx.serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()))
                    || b.status.dim() != null && !Dims.normalize(b.status.dim()).equals(dim)) {
                continue;
            }
            double d = p.horizontalDistance(center);
            if (d < bestD) {
                best = b;
                bestD = d;
            }
        }
        return bestD <= MAX_SCAN_DISTANCE ? best : null;
    }

    private static String noScanner(Pos center) {
        return center == null ? "no home waypoint and no bot position to look around"
                : "no online bot within " + MAX_SCAN_DISTANCE + " blocks of " + center
                + " (a bot only sees the chunks around itself)";
    }

    private int scanRadius() {
        return Math.max(48, Math.min(96, m.config.get().autopilot().homeRadius() * 2));
    }

    private void query(BotState b, String kind, JsonObject args, BiConsumer<JsonObject, String> cb) {
        m.planner.queryVia(b, kind, args, QUERY_MS, (r, t) -> {
            if (r == null || !r.ok() || r.data() == null) {
                cb.accept(null, t != null ? String.valueOf(t.getMessage()) : r == null ? "no answer"
                        : String.valueOf(r.error()));
            } else {
                cb.accept(r.data(), null);
            }
        });
    }

    /** Cluster boxes with at least {@code min} blocks. */
    static List<Box> clusters(JsonObject data, int min) {
        List<Box> out = new ArrayList<>();
        JsonArray a = Json.getArr(data, "clusters");
        for (JsonElement e : a == null ? new JsonArray() : a) {
            JsonObject o = e.getAsJsonObject();
            Box b = Box.fromJson(o.get("box"));
            if (b != null && Json.getInt(o, "count", 0) >= min) {
                out.add(b);
            }
        }
        return out;
    }

    /** The box whose centre is nearest to {@code p} (x/z), or null. */
    static Box nearest(List<Box> boxes, Pos p) {
        return boxes.stream().min(Comparator.comparingDouble(b -> dist(b, p))).orElse(null);
    }

    static double dist(Box b, Pos p) {
        double cx = (b.min().x() + b.max().x()) / 2.0;
        double cz = (b.min().z() + b.max().z()) / 2.0;
        return Math.hypot(cx - p.x(), cz - p.z());
    }
}
