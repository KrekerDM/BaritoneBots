package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.common.schematic.Schematic;
import io.github.krekerdm.baritonebots.common.schematic.SchematicLoader;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.planner.Locks;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import io.github.krekerdm.baritonebots.manager.planner.ProductionWork;
import io.github.krekerdm.baritonebots.manager.planner.Resolver;
import io.github.krekerdm.baritonebots.manager.planner.Restock;
import io.github.krekerdm.baritonebots.manager.planner.Sectors;
import io.github.krekerdm.baritonebots.manager.planner.WorkItem;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.util.Hashing;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A running {@code build} project (SPEC §5.7 steps 1–5): schematic + placement → BOM ({@code bom} query, cached by
 * file hash + placement) → sectors (re-split when the number of builders changes) → builder loop per sector
 * ({@code progress} → restock with {@code take} from supply, then storage, bottom layers first → {@code build} with
 * the sector box → verify) → final pass over every sector. Deficits of the remaining BOM become haul / mine / craft /
 * smelt work through {@link Resolver} and {@link ProductionWork}; what cannot be produced is listed as manual.
 * Loop-owned, except {@link #prepare()} which loads the schematic on a worker thread.
 */
final class BuildRuntime implements ProjectRuntime, ProductionWork.Host {
    static final String PENDING = "pending";
    static final String ACTIVE = "active";
    static final String DONE = "done";
    static final String VERIFY = "verify";
    static final String BLOCKED = "blocked";
    static final String KIND_SECTOR = "build_sector";
    static final long QUERY_TIMEOUT_MS = 130_000;
    static final long RESPLIT_STABLE_MS = 30_000;
    static final int SWEEP_WIDTH = 32;
    static final int BUILD_TIMEOUT_SEC = 1800;
    static final long RATE_WINDOW_MS = 10 * 60_000L;

    /** One build sector. */
    static final class Sector {
        Box box;
        String state = PENDING;
        boolean verified;
        int total = -1;
        int correct;
        int missing;
        int wrong;
        int unloaded;
        int toClear;
        Map<String, Integer> remaining;
        long checkedAt;
        long waitUntil;
        int waits;
        final Set<String> bots = new LinkedHashSet<>();

        Sector(Box box) {
            this.box = box;
        }

        boolean finished() {
            return DONE.equals(state) || VERIFY.equals(state);
        }
    }

    /** Per-assignment state of a builder. */
    static final class BuilderCtx {
        final Sector sector;
        String step = "start";
        boolean inspected;
        boolean deposited;
        int missingRounds;
        int lockWaits;
        List<Box> sweep;
        int sweepIdx;
        final int[] agg = new int[4]; // missing, wrong, unloaded, toClear
        final Map<String, Integer> aggRemaining = new TreeMap<>();

        BuilderCtx(Sector s) {
            this.sector = s;
        }
    }

    private final Project p;
    private final ProjectService svc;
    private final Manager m;
    private final Planner planner;
    private final ProductionWork production;
    private final Path schematicsDir;
    // config
    private final String schematicName;
    private final Pos origin;
    private final String dim;
    private final int rotation;
    private final String mirror;
    private final List<Pos> supplyPos = new ArrayList<>();
    // prepared on a worker thread
    private Path file;
    private String fileHash;
    private Box footprint;
    private Box.Axis axis;
    private Map<String, int[]> minYByItem = Map.of();
    private boolean preparing;
    private int prepareGen;
    // BOM
    private Map<String, Integer> bom;
    private int bomBlocks = -1;
    private String bomKey;
    private boolean bomPending;
    private long bomNextAt;
    private int bomFailures;
    // sectors
    private final List<Sector> sectors = new ArrayList<>();
    private int builders;
    private int pendingBuilders = -1;
    private long pendingSince;
    private boolean finalPass;
    private boolean surveyPending;
    private long surveyNextAt;
    // deficits
    private Map<String, Integer> remaining;
    private Resolver.Plan plan;
    private final Map<String, String> notes = new TreeMap<>();
    private Set<String> manualKeys = new LinkedHashSet<>();
    private long supplySignature = -1;
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private boolean active;
    private String savedHash;

    BuildRuntime(Project p, ProjectService svc, Path schematicsDir) {
        this.p = p;
        this.svc = svc;
        this.m = svc.manager();
        this.planner = svc.planner();
        this.production = new ProductionWork(m, planner);
        this.schematicsDir = schematicsDir;
        JsonObject c = p.config;
        schematicName = Json.getString(c, "schematic", "");
        Pos o = Pos.fromJson(c.get("origin"));
        origin = o == null ? Pos.ZERO : o;
        dim = Dims.normalize(Json.getString(c, "dim", Dims.OVERWORLD));
        rotation = Json.getInt(c, "rotation", 0);
        mirror = Json.getString(c, "mirror", "none");
        JsonArray sup = Json.getArr(c, "supply");
        if (sup != null) {
            sup.forEach(e -> {
                Pos pos = Pos.fromJson(e);
                if (pos != null) {
                    supplyPos.add(pos);
                }
            });
        }
        restore(p.state);
    }

    // ------------------------------------------------------------------ WorkSource basics

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

    /** Supply containers: the configured positions, else every container with role supply in the dimension. */
    @Override
    public List<WorldDoc.Container> supply() {
        WorldDoc doc = planner.world(p.serverId);
        if (doc == null) {
            return List.of();
        }
        if (supplyPos.isEmpty()) {
            return planner.containers(p.serverId, dim, "supply");
        }
        List<WorldDoc.Container> out = new ArrayList<>();
        for (Pos pos : supplyPos) {
            WorldDoc.Container c = doc.containerAt(dim, pos);
            out.add(c != null ? c : new WorldDoc.Container("adhoc-" + pos, dim, pos, "minecraft:chest",
                    List.of("supply"), null, null, 0));
        }
        return out;
    }

    /** Supply first, then storage, then fuel containers (no duplicates). */
    @Override
    public List<WorldDoc.Container> sources() {
        List<WorldDoc.Container> out = new ArrayList<>(supply());
        for (String role : List.of("storage", "fuel")) {
            for (WorldDoc.Container c : planner.containers(p.serverId, dim, role)) {
                if (out.stream().noneMatch(x -> x.pos().equals(c.pos()))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start(boolean restart) {
        active = true;
        for (Sector s : sectors) {
            if (BLOCKED.equals(s.state)) {
                s.state = finalPass ? VERIFY : PENDING; // failures are forgotten on start / resume
            }
            s.waitUntil = 0;
            s.waits = 0;
        }
        if (restart) {
            if (!sectors.isEmpty() && sectors.stream().allMatch(s -> DONE.equals(s.state))) {
                beginFinalPass(); // started again after "done": check everything once more
            }
            notes.clear();
        }
        bomFailures = 0;
        bomNextAt = 0;
        surveyNextAt = 0;
    }

    @Override
    public void stop() {
        active = false;
        for (Sector s : sectors) {
            s.bots.clear();
            if (ACTIVE.equals(s.state)) {
                s.state = finalPass ? VERIFY : PENDING;
            }
        }
    }

    // ------------------------------------------------------------------ preparation (worker thread)

    private void prepare() {
        preparing = true;
        int gen = ++prepareGen;
        Path f = schematicsDir.resolve(schematicName);
        Thread t = new Thread(() -> {
            try {
                if (!Files.isRegularFile(f)) {
                    throw new java.io.IOException("schematic '" + schematicName + "' not found");
                }
                String hash = Hashing.file(f, "SHA-256");
                Schematic s = SchematicLoader.load(f);
                SchematicTransform tr = SchematicTransform.of(s, origin, rotation, mirror);
                Box fp = tr.footprint();
                Box.Axis ax = fp.longerHorizontalAxis();
                Map<String, int[]> minY = minYByItem(s, tr, fp, ax);
                m.loop.post(() -> prepared(gen, f, hash, fp, ax, minY, null));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                m.loop.post(() -> prepared(gen, f, null, null, null, null, msg));
            }
        }, "schematic-prepare-" + p.id);
        t.setDaemon(true);
        t.start();
    }

    /** Lowest world Y of every item per coordinate along the split axis ("bottom layers first"). */
    static Map<String, int[]> minYByItem(Schematic s, SchematicTransform tr, Box fp, Box.Axis ax) {
        int len = fp.size(ax);
        int base = coord(fp.a(), ax);
        List<String> palette = s.palette();
        String[] itemOf = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            itemOf[i] = Ids.isAir(palette.get(i)) ? null : itemOfBlock(Ids.stripState(Ids.normalize(palette.get(i))));
        }
        Map<String, int[]> out = new HashMap<>();
        for (int y = 0; y < s.height(); y++) {
            for (int z = 0; z < s.length(); z++) {
                for (int x = 0; x < s.width(); x++) {
                    String item = itemOf[s.paletteIndexAt(x, y, z)];
                    if (item == null) {
                        continue;
                    }
                    Pos w = tr.toWorld(x, y, z);
                    int[] arr = out.computeIfAbsent(item, k -> {
                        int[] a = new int[len];
                        Arrays.fill(a, Integer.MAX_VALUE);
                        return a;
                    });
                    int k = coord(w, ax) - base;
                    if (k >= 0 && k < len && w.y() < arr[k]) {
                        arr[k] = w.y();
                    }
                }
            }
        }
        return out;
    }

    /** Block id → the item a builder needs for it (wall variants, crops, wires). Unknown blocks map to themselves. */
    static String itemOfBlock(String block) {
        String path = Ids.path(block);
        String ns = block.substring(0, block.length() - path.length());
        String item = switch (path) {
            case "wall_torch" -> "torch";
            case "soul_wall_torch" -> "soul_torch";
            case "redstone_wall_torch" -> "redstone_torch";
            case "copper_wall_torch" -> "copper_torch";
            case "wheat" -> "wheat_seeds";
            case "carrots" -> "carrot";
            case "potatoes" -> "potato";
            case "beetroots" -> "beetroot_seeds";
            case "redstone_wire" -> "redstone";
            case "tripwire" -> "string";
            case "cocoa" -> "cocoa_beans";
            case "sweet_berry_bush" -> "sweet_berries";
            case "cave_vines", "cave_vines_plant" -> "glow_berries";
            case "kelp_plant" -> "kelp";
            case "bamboo_sapling" -> "bamboo";
            case "melon_stem", "attached_melon_stem" -> "melon_seeds";
            case "pumpkin_stem", "attached_pumpkin_stem" -> "pumpkin_seeds";
            default -> path.contains("_wall_") ? path.replace("_wall_", "_") : path;
        };
        if (path.startsWith("potted_")) {
            item = "flower_pot";
        }
        return ns + item;
    }

    private static int coord(Pos p, Box.Axis ax) {
        return switch (ax) {
            case X -> p.x();
            case Y -> p.y();
            case Z -> p.z();
        };
    }

    private void prepared(int gen, Path f, String hash, Box fp, Box.Axis ax, Map<String, int[]> minY, String error) {
        if (gen != prepareGen) {
            return;
        }
        preparing = false;
        if (error != null) {
            svc.failed(p, "schematic: " + error);
            return;
        }
        file = f;
        fileHash = hash;
        footprint = fp;
        axis = ax;
        minYByItem = minY;
        if (savedHash != null && !savedHash.equals(hash)) {
            Log.info("project %s: schematic changed on disk, sectors and BOM are recomputed", p.id);
            sectors.clear();
            bom = null;
            bomKey = null;
            remaining = null;
            finalPass = false;
        }
        savedHash = hash;
        String key = bomKeyNow();
        if (bom != null && !key.equals(bomKey)) {
            bom = null;
        }
        planner.tickSoon();
    }

    private String bomKeyNow() {
        return fileHash + "|" + origin + "|" + rotation + "|" + mirror;
    }

    private JsonObject schematicArgs(Box box) {
        JsonObject o = Json.obj("file", file.toAbsolutePath().toString(), "origin", origin, "rotation", rotation,
                "mirror", mirror);
        if (box != null) {
            o.add("box", Json.toTree(box));
        }
        return o;
    }

    // ------------------------------------------------------------------ the tick

    @Override
    public List<WorkItem> workItems(long now) {
        if (!active) {
            return List.of();
        }
        if (footprint == null) {
            if (!preparing) {
                prepare();
            }
            return List.of();
        }
        if (bom == null && !bomPending && now >= bomNextAt) {
            requestBom(now);
        }
        updateBuilders(now);
        survey(now);
        if (!sectors.isEmpty() && sectors.stream().allMatch(s -> DONE.equals(s.state))) {
            if (!finalPass) {
                beginFinalPass();
            } else if (sectors.stream().allMatch(s -> s.verified)) {
                svc.done(p);
                return List.of();
            }
        }
        List<WorkItem> items = new ArrayList<>(sectorItems(now));
        items.addAll(productionItems(now));
        return items;
    }

    private void beginFinalPass() {
        finalPass = true;
        for (Sector s : sectors) {
            s.state = VERIFY;
            s.verified = false;
            s.waitUntil = 0;
        }
        svc.changed(p);
    }

    /** Builders = allowed, enabled, online bots that may take the builder role. */
    private int countBuilders() {
        int n = 0;
        for (BotState b : m.bots.all()) {
            if (b.def.enabled() && b.online() && p.allows(b)
                    && (b.def.roles().isEmpty() || b.def.roles().contains("builder"))) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    private void updateBuilders(long now) {
        int n = countBuilders();
        if (sectors.isEmpty()) {
            builders = n;
            for (Box b : Sectors.split(footprint, n)) {
                sectors.add(new Sector(b));
            }
            svc.changed(p);
            return;
        }
        if (n == builders) {
            pendingBuilders = -1;
            return;
        }
        if (n != pendingBuilders) {
            pendingBuilders = n;
            pendingSince = now;
            return;
        }
        if (now - pendingSince < RESPLIT_STABLE_MS) {
            return;
        }
        resplit(n);
    }

    /** New builder count: unfinished sectors are cut again; builders on changed sectors start over. */
    void resplit(int n) {
        List<Sectors.Piece> pieces = sectors.stream().map(s -> new Sectors.Piece(s.box, s.finished())).toList();
        List<Sectors.Piece> next = Sectors.resplit(footprint, pieces, n);
        Map<Box, Sector> old = new HashMap<>();
        sectors.forEach(s -> old.put(s.box, s));
        List<Sector> fresh = new ArrayList<>();
        for (Sectors.Piece piece : next) {
            Sector s = old.get(piece.box());
            fresh.add(s != null ? s : new Sector(piece.box()));
        }
        for (Assignment a : planner.assignmentsOf(p.id)) {
            if (a.ctx instanceof BuilderCtx bc && !fresh.contains(bc.sector)) {
                planner.release(a, "resplit", true);
            }
        }
        sectors.clear();
        sectors.addAll(fresh);
        builders = n;
        pendingBuilders = -1;
        Log.info("project %s: %d builders, %d sectors", p.id, n, sectors.size());
        svc.changed(p);
    }

    private BotState queryBot() {
        BotState best = null;
        for (BotState b : m.bots.all()) {
            if (b.online() && p.allows(b)) {
                if (planner.assignmentOf(b.id) == null) {
                    return b;
                }
                best = best == null ? b : best;
            }
        }
        return best;
    }

    private void requestBom(long now) {
        BotState b = queryBot();
        if (b == null) {
            return;
        }
        bomPending = true;
        String key = bomKeyNow();
        planner.queryVia(b, QueryKinds.BOM, schematicArgs(null), QUERY_TIMEOUT_MS, (r, t) -> {
            bomPending = false;
            if (r != null && r.ok() && r.data() != null) {
                Map<String, Integer> items = new TreeMap<>();
                JsonObject it = Json.getObj(r.data(), "items");
                if (it != null) {
                    it.entrySet().forEach(e -> items.put(Ids.normalize(e.getKey()), e.getValue().getAsInt()));
                }
                bom = items;
                bomBlocks = Json.getInt(r.data(), "blocks", -1);
                bomKey = key;
                bomFailures = 0;
                if (remaining == null) {
                    remaining = new TreeMap<>(items);
                }
                svc.unblocked(p, "bom");
                svc.changed(p);
                return;
            }
            String err = t != null ? String.valueOf(t.getMessage()) : r == null ? "no answer" : r.error();
            if (err != null && (err.startsWith(Reasons.BAD_ARGS) || err.startsWith(Reasons.UNSUPPORTED))) {
                svc.failed(p, "the bot cannot read the schematic: " + err);
                return;
            }
            bomFailures++;
            bomNextAt = System.currentTimeMillis() + Math.min(600_000L, 30_000L << Math.min(5, bomFailures - 1));
            if (bomFailures >= 3) {
                svc.blocked(p, "bom", "BOM query failed: " + err, null);
            }
        });
    }

    /** Fills in sectors that were never checked (one query at a time), so deficits use real numbers. */
    private void survey(long now) {
        if (surveyPending || now < surveyNextAt) {
            return;
        }
        Sector target = null;
        for (Sector s : sectors) {
            if (s.remaining == null && s.bots.isEmpty()) {
                target = s;
                break;
            }
        }
        BotState b = target == null ? null : queryBot();
        if (b == null) {
            return;
        }
        Sector s = target;
        surveyPending = true;
        planner.queryVia(b, QueryKinds.PROGRESS, schematicArgs(s.box), QUERY_TIMEOUT_MS, (r, t) -> {
            surveyPending = false;
            if (r != null && r.ok() && r.data() != null && sectors.contains(s)) {
                apply(s, r.data());
                surveyNextAt = System.currentTimeMillis() + 1_000;
                if (s.missing == 0 && s.wrong == 0 && s.toClear == 0 && s.unloaded == 0 && s.bots.isEmpty()
                        && PENDING.equals(s.state)) {
                    s.state = DONE; // already built (seen fully loaded)
                }
                svc.changed(p);
            } else {
                surveyNextAt = System.currentTimeMillis() + 30_000;
            }
        });
    }

    /** Stores a progress answer in a sector and updates the project-wide remaining estimate. */
    private void apply(Sector s, JsonObject d) {
        s.total = Json.getInt(d, "total", s.total);
        s.correct = Json.getInt(d, "correct", 0);
        s.missing = Json.getInt(d, "missing", 0);
        s.wrong = Json.getInt(d, "wrong", 0);
        s.unloaded = Json.getInt(d, "unloaded", 0);
        s.toClear = Json.getInt(d, "toClear", 0);
        Map<String, Integer> rem = new TreeMap<>();
        JsonObject r = Json.getObj(d, "remaining");
        if (r != null) {
            r.entrySet().forEach(e -> rem.put(Ids.normalize(e.getKey()), e.getValue().getAsInt()));
        }
        s.remaining = rem;
        s.checkedAt = System.currentTimeMillis();
        recomputeRemaining();
        sample();
    }

    private void recomputeRemaining() {
        if (!sectors.isEmpty() && sectors.stream().allMatch(x -> x.remaining != null)) {
            Map<String, Integer> sum = new TreeMap<>();
            for (Sector x : sectors) {
                x.remaining.forEach((k, v) -> sum.merge(k, v, Integer::sum));
            }
            remaining = sum;
        }
    }

    private int placed() {
        return sectors.stream().mapToInt(s -> s.correct).sum();
    }

    private int total() {
        if (!sectors.isEmpty() && sectors.stream().allMatch(s -> s.total >= 0)) {
            return sectors.stream().mapToInt(s -> s.total).sum();
        }
        return bomBlocks;
    }

    private void sample() {
        long now = System.currentTimeMillis();
        int placed = placed();
        if (samples.isEmpty() || samples.peekLast()[1] != placed) {
            samples.addLast(new long[] {now, placed});
        }
        // keep one sample older than the window as the rate's starting point
        while (samples.size() > 2) {
            java.util.Iterator<long[]> it = samples.iterator();
            it.next();
            if (now - it.next()[0] > RATE_WINDOW_MS) {
                samples.removeFirst();
            } else {
                break;
            }
        }
    }

    /** Blocks per minute over the last 10 minutes (null until a minute of data exists). */
    Double rate() {
        if (samples.size() < 2) {
            return null;
        }
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long now = System.currentTimeMillis();
        long span = Math.max(last[0], now) - first[0];
        if (span < 60_000) {
            return null;
        }
        return (last[1] - first[1]) * 60_000.0 / span;
    }

    // ------------------------------------------------------------------ work items

    private List<WorkItem> sectorItems(long now) {
        List<WorkItem> out = new ArrayList<>();
        int cap = m.config.get().planner().maxBuildersPerSector();
        for (int i = 0; i < sectors.size(); i++) {
            Sector s = sectors.get(i);
            boolean open = PENDING.equals(s.state) || VERIFY.equals(s.state) || ACTIVE.equals(s.state) && s.bots.size() < cap;
            if (!open || s.waitUntil > now) {
                continue;
            }
            Pos center = new Pos((s.box.a().x() + s.box.b().x()) / 2, s.box.a().y(), (s.box.a().z() + s.box.b().z()) / 2);
            String label = (VERIFY.equals(s.state) ? "verify sector " : "build sector ") + (i + 1);
            out.add(new WorkItem("sector:" + i, p.id, KIND_SECTOR, "builder", p.priority + 1 - i * 0.001,
                    WorkItem.Needs.NONE, dim, center, Locks.sector(p.id, i), cap, label,
                    Json.obj("sector", i, "box", s.box)));
        }
        return out;
    }

    private List<WorkItem> productionItems(long now) {
        Map<String, Integer> rem = remaining != null ? remaining : bom;
        if (rem == null) {
            plan = null;
            return List.of();
        }
        List<WorldDoc.Container> supply = supply();
        Map<String, Integer> supplyTotals = new TreeMap<>();
        long signature = 0;
        for (WorldDoc.Container c : supply) {
            if (c.snapshot() != null) {
                c.snapshot().totals().forEach((k, v) -> supplyTotals.merge(k, v, Integer::sum));
            }
        }
        for (Map.Entry<String, Integer> e : supplyTotals.entrySet()) {
            if (rem.containsKey(e.getKey())) {
                signature += e.getValue();
            }
        }
        if (signature > supplySignature && supplySignature >= 0) {
            sectors.forEach(s -> {
                s.waitUntil = 0; // new materials arrived: waiting sectors may continue
                s.waits = 0;
            });
        }
        supplySignature = signature;
        Map<String, Integer> storage = new TreeMap<>();
        for (WorldDoc.Container c : sources()) {
            if (supply.stream().anyMatch(x -> x.pos().equals(c.pos()))) {
                continue;
            }
            Map<String, Integer> av = planner.available(c, null);
            if (av != null) {
                av.forEach((k, v) -> storage.merge(k, v, Integer::sum));
            }
        }
        Map<String, Integer> inBots = new TreeMap<>();
        Map<String, Integer> transit = new TreeMap<>();
        for (Assignment a : planner.assignmentsOf(p.id)) {
            a.promised.forEach((k, v) -> transit.merge(k, v, Integer::sum));
            BotState b = m.bots.get(a.botId);
            if (a.ctx instanceof BuilderCtx && b != null && b.status != null) {
                b.status.items().forEach((k, v) -> inBots.merge(k, v, Integer::sum));
            }
        }
        GameData data = m.gameData.current();
        Resolver.Env env = new Resolver.Env(!supply.isEmpty(), production.hasCraftingTable(this),
                production.furnaceTypes(this));
        plan = Resolver.resolve(bom == null ? rem : bom, rem, new Resolver.Stock(supplyTotals, storage, inBots, transit),
                data, env);
        Set<String> nowManual = new LinkedHashSet<>();
        plan.manual().forEach((item, count) -> {
            String key = "manual:" + item;
            nowManual.add(key);
            svc.blocked(p, key, count + " × " + Ids.path(item) + " must be added by hand",
                    Json.obj("item", item, "count", count));
        });
        for (String k : manualKeys) {
            if (!nowManual.contains(k)) {
                svc.unblocked(p, k);
            }
        }
        manualKeys = nowManual;
        Map<String, String> fresh = new TreeMap<>();
        List<WorkItem> items;
        if (sources().isEmpty()) {
            // produced items would have nowhere to go (and would be produced again and again)
            fresh.put("containers", "no containers with role supply or storage in " + dim);
            items = List.of();
        } else {
            items = production.items(p.id, this, plan, p.priority, data, fresh);
        }
        fresh.forEach((k, v) -> svc.blocked(p, "note:" + k, v, null));
        notes.clear();
        notes.putAll(fresh);
        return items;
    }

    @Override
    public boolean eligible(BotState b, WorkItem item) {
        return !ProductionWork.handles(item) || production.eligible(b, item, this);
    }

    // ------------------------------------------------------------------ assignments

    @Override
    public void begin(Assignment a, BotState b) {
        if (ProductionWork.handles(a.item)) {
            production.begin(a, b, this);
            return;
        }
        int idx = Json.getInt(a.item.data(), "sector", -1);
        Box box = Box.fromJson(a.item.data().get("box"));
        if (idx < 0 || idx >= sectors.size() || !sectors.get(idx).box.equals(box)) {
            planner.release(a, "stale", false);
            return;
        }
        Sector s = sectors.get(idx);
        if (DONE.equals(s.state)) {
            planner.release(a, "stale", false);
            return;
        }
        s.bots.add(b.id);
        s.state = ACTIVE;
        a.ctx = new BuilderCtx(s);
        progress(a, b, s.box, false);
        svc.changed(p);
    }

    @Override
    public void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results) {
        if (ProductionWork.handles(a.item)) {
            production.onBatchDone(a, b, results, this);
            return;
        }
        BuilderCtx c = (BuilderCtx) a.ctx;
        planner.releaseContainers(a);
        switch (c.step) {
            case "inspect" -> restock(a, b);
            case "deposit" -> {
                if (results.stream().anyMatch(r -> !r.ok())) {
                    waitForMaterials(a, c.sector); // could not unload: try again later
                } else {
                    restock(a, b);
                }
            }
            case "restock" -> {
                Map<String, Integer> taken = Restock.taken(results);
                if (taken.isEmpty() && !hasUseful(b, c.sector.remaining)) {
                    Assignment.Result bad = results.stream().filter(r -> !r.ok()).findFirst().orElse(null);
                    if (bad != null) {
                        planner.fail(a, bad.reason(), bad.message());
                    } else {
                        waitForMaterials(a, c.sector);
                    }
                } else {
                    build(a, c);
                }
            }
            case "build" -> {
                Assignment.Result r = results.stream().filter(x -> TaskTypes.BUILD.equals(x.type())).reduce((x, y) -> y)
                        .orElse(null);
                if (r == null) {
                    planner.release(a, "cancelled", true);
                } else if (r.ok() || Reasons.TIMEOUT.equals(r.reason())) {
                    progress(a, b, c.sector.box, false);
                } else if (Reasons.MISSING_MATERIALS.equals(r.reason())) {
                    if (++c.missingRounds > 3) {
                        waitForMaterials(a, c.sector);
                    } else {
                        progress(a, b, c.sector.box, false);
                    }
                } else {
                    planner.fail(a, r.reason(), r.message());
                }
            }
            case "sweep_goto" -> {
                Box sub = c.sweep.get(c.sweepIdx);
                progress(a, b, sub, true);
            }
            default -> planner.fail(a, Reasons.ERROR, "unexpected step " + c.step);
        }
    }

    private void progress(Assignment a, BotState b, Box box, boolean sweepPart) {
        BuilderCtx c = (BuilderCtx) a.ctx;
        c.step = sweepPart ? "sweep_check" : "check";
        a.phase(sweepPart ? "verify " + (c.sweepIdx + 1) + "/" + c.sweep.size() : "check");
        planner.query(a, QueryKinds.PROGRESS, schematicArgs(box), QUERY_TIMEOUT_MS,
                (r, t) -> onProgress(a, b, r, t, sweepPart));
    }

    private void onProgress(Assignment a, BotState b, QueryResult r, Throwable t, boolean sweepPart) {
        BuilderCtx c = (BuilderCtx) a.ctx;
        Sector s = c.sector;
        if (!sectors.contains(s)) {
            planner.release(a, "resplit", true);
            return;
        }
        if (t != null || r == null || !r.ok() || r.data() == null) {
            String err = t != null ? String.valueOf(t.getMessage()) : r == null ? "no answer" : r.error();
            if (err != null && (err.startsWith(Reasons.BAD_ARGS) || err.startsWith(Reasons.UNSUPPORTED))) {
                svc.failed(p, "the bot cannot read the schematic: " + err);
                return;
            }
            planner.fail(a, "query_failed", err);
            return;
        }
        JsonObject d = r.data();
        if (sweepPart) {
            c.agg[0] += Json.getInt(d, "missing", 0);
            c.agg[1] += Json.getInt(d, "wrong", 0);
            c.agg[2] += Json.getInt(d, "unloaded", 0);
            c.agg[3] += Json.getInt(d, "toClear", 0);
            JsonObject rem = Json.getObj(d, "remaining");
            if (rem != null) {
                rem.entrySet().forEach(e -> c.aggRemaining.merge(Ids.normalize(e.getKey()), e.getValue().getAsInt(), Integer::sum));
            }
            c.sweepIdx++;
            sweepStep(a, b);
            return;
        }
        apply(s, d);
        svc.changed(p);
        boolean clean = s.missing == 0 && s.wrong == 0 && s.toClear == 0;
        if (clean && s.unloaded == 0) {
            sectorDone(a, s);
        } else if (clean) {
            c.sweep = Sectors.sweep(s.box, SWEEP_WIDTH);
            c.sweepIdx = 0;
            Arrays.fill(c.agg, 0);
            c.aggRemaining.clear();
            sweepStep(a, b);
        } else {
            restock(a, b);
        }
    }

    private void sweepStep(Assignment a, BotState b) {
        BuilderCtx c = (BuilderCtx) a.ctx;
        if (c.sweepIdx >= c.sweep.size()) {
            Sector s = c.sector;
            if (c.agg[0] + c.agg[1] + c.agg[3] == 0) {
                if (c.agg[2] > 0) {
                    Log.info("project %s: sector %s accepted with %d positions never loaded", p.id, s.box, c.agg[2]);
                }
                s.unloaded = c.agg[2];
                sectorDone(a, s);
            } else {
                s.missing = c.agg[0];
                s.wrong = c.agg[1];
                s.unloaded = c.agg[2];
                s.toClear = c.agg[3];
                s.remaining = new TreeMap<>(c.aggRemaining);
                recomputeRemaining();
                restock(a, b);
            }
            return;
        }
        Box sub = c.sweep.get(c.sweepIdx);
        c.step = "sweep_goto";
        a.phase("walk " + (c.sweepIdx + 1) + "/" + c.sweep.size());
        int x = (sub.a().x() + sub.b().x()) / 2;
        int z = (sub.a().z() + sub.b().z()) / 2;
        planner.push(a, List.of(Planner.entry(a, TaskTypes.GOTO, Json.obj("x", x, "z", z, "range", 4), 600, null)));
    }

    private void sectorDone(Assignment a, Sector s) {
        s.state = DONE;
        s.verified = finalPass;
        s.waitUntil = 0;
        s.waits = 0;
        svc.changed(p);
        planner.finish(a);
    }

    /** The bot holds something the sector still needs. */
    private static boolean hasUseful(BotState b, Map<String, Integer> remaining) {
        if (b.status == null || remaining == null) {
            return false;
        }
        for (String item : remaining.keySet()) {
            if (b.status.items().getOrDefault(item, 0) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Lowest Y at which {@code item} is still in this sector's part of the schematic. */
    private int lowestY(String item, Box sectorBox) {
        int[] arr = minYByItem.get(item);
        if (arr == null || footprint == null) {
            return Integer.MAX_VALUE;
        }
        int base = coord(footprint.a(), axis);
        int from = Math.max(0, coord(sectorBox.a(), axis) - base);
        int to = Math.min(arr.length - 1, coord(sectorBox.b(), axis) - base);
        int min = Integer.MAX_VALUE;
        for (int k = from; k <= to; k++) {
            min = Math.min(min, arr[k]);
        }
        return min;
    }

    private void restock(Assignment a, BotState b) {
        BuilderCtx c = (BuilderCtx) a.ctx;
        Sector s = c.sector;
        Map<String, Integer> rem = s.remaining == null ? Map.of() : s.remaining;
        Map<String, Integer> have = b.status == null ? Map.of() : b.status.items();
        List<Restock.Need> needs = new ArrayList<>();
        rem.forEach((item, n) -> {
            int want = n - have.getOrDefault(item, 0);
            if (want > 0) {
                needs.add(new Restock.Need(item, want));
            }
        });
        needs.sort(Comparator.comparingInt((Restock.Need n) -> lowestY(n.item(), s.box)).thenComparing(Restock.Need::item));
        if (needs.isEmpty()) {
            build(a, c); // everything for the sector is in the inventory
            return;
        }
        int free = b.status == null ? 0 : b.status.freeSlots();
        int slots = Math.max(0, free - m.config.get().planner().restockFreeSlotsTarget());
        if (slots == 0 && !hasUseful(b, rem)) {
            if (c.deposited) {
                waitForMaterials(a, s); // still full after unloading once
                return;
            }
            c.deposited = true;
            c.step = "deposit";
            a.phase("unload");
            planner.push(a, List.of(Planner.entry(a, "deposit_storage", new JsonObject(), 0, null)));
            return;
        }
        Restock.Plan rp = Restock.plan(needs, sources(), x -> planner.available(x, a),
                x -> planner.locks().heldByOther(Planner.containerKey(x), a.botId), slots);
        if (rp.isEmpty() && !rp.inspect().isEmpty() && !c.inspected) {
            c.inspected = true;
            c.step = "inspect";
            a.phase("inspect");
            planner.push(a, List.of(Restock.inspectEntry(a, rp.inspect())));
            return;
        }
        List<QueueEntry> takes = slots == 0 ? List.of() : Restock.entries(planner, a, rp, Planner.posOf(b));
        if (!takes.isEmpty()) {
            c.step = "restock";
            a.phase("restock");
            planner.push(a, takes);
            return;
        }
        if (hasUseful(b, rem)) {
            build(a, c);
            return;
        }
        boolean lockedStock = false;
        for (WorldDoc.Container x : sources()) {
            Map<String, Integer> av = planner.available(x, a);
            if (av != null && planner.locks().heldByOther(Planner.containerKey(x), a.botId)
                    && needs.stream().anyMatch(n -> av.getOrDefault(n.item(), 0) > 0)) {
                lockedStock = true;
                break;
            }
        }
        if (lockedStock && c.lockWaits++ < 24) {
            a.phase("waiting for a free container");
            planner.later(a, 5_000, () -> restock(a, b));
            return;
        }
        waitForMaterials(a, s);
    }

    private void build(Assignment a, BuilderCtx c) {
        c.step = "build";
        a.phase("build");
        JsonObject args = schematicArgs(c.sector.box);
        args.addProperty("name", p.name + " #" + (sectors.indexOf(c.sector) + 1));
        planner.push(a, List.of(Planner.entry(a, TaskTypes.BUILD, args, BUILD_TIMEOUT_SEC, p.name)));
    }

    /** Nothing to build with: the sector waits (15 s, doubling to 2 min, reset when supply grows). */
    private void waitForMaterials(Assignment a, Sector s) {
        s.waits++;
        s.waitUntil = System.currentTimeMillis() + Math.min(120_000L, 15_000L << Math.min(3, s.waits - 1));
        svc.changed(p);
        planner.finish(a);
    }

    @Override
    public void onReleased(Assignment a, String why) {
        if (a.ctx instanceof BuilderCtx c) {
            Sector s = c.sector;
            s.bots.remove(a.botId);
            if (ACTIVE.equals(s.state) && s.bots.isEmpty()) {
                s.state = finalPass ? VERIFY : PENDING;
            }
            svc.changed(p);
        }
    }

    @Override
    public void onItemFailed(WorkItem item, String reason, String message) {
        String text = item.label() + ": " + reason + (message == null || message.isBlank() ? "" : " (" + message + ")");
        if (KIND_SECTOR.equals(item.kind())) {
            int idx = Json.getInt(item.data(), "sector", -1);
            if (idx >= 0 && idx < sectors.size() && sectors.get(idx).bots.isEmpty()) {
                sectors.get(idx).state = BLOCKED;
            }
        }
        svc.blocked(p, item.id(), text, Json.obj("item", item.id(), "reason", reason));
        svc.changed(p);
    }

    // ------------------------------------------------------------------ views and persistence

    @Override
    public JsonObject view(boolean full) {
        int total = total();
        int placed = placed();
        JsonObject progress = new JsonObject();
        if (total >= 0) {
            progress.addProperty("total", total);
            progress.addProperty("placed", placed);
            progress.addProperty("remaining", Math.max(0, total - placed));
            progress.addProperty("percent", total == 0 ? 100.0 : Math.round(placed * 1000.0 / total) / 10.0);
        }
        Double rate = rate();
        if (rate != null) {
            progress.addProperty("blocksPerMin", Math.round(rate * 10) / 10.0);
            if (rate > 0 && total > placed) {
                progress.addProperty("etaSec", (long) ((total - placed) / rate * 60));
            }
        }
        progress.addProperty("missing", sectors.stream().mapToInt(s -> s.missing).sum());
        progress.addProperty("wrong", sectors.stream().mapToInt(s -> s.wrong).sum());
        progress.addProperty("unloaded", sectors.stream().mapToInt(s -> s.unloaded).sum());
        progress.addProperty("sectorsDone", sectors.stream().filter(s -> DONE.equals(s.state)).count());
        progress.addProperty("sectors", sectors.size());
        progress.addProperty("finalPass", finalPass);
        JsonObject o = Json.obj("progress", progress);
        if (footprint != null) {
            o.add("footprint", Json.toTree(footprint));
        }
        o.add("sectors", sectorsView());
        JsonArray manual = new JsonArray();
        if (plan != null) {
            plan.manual().forEach((item, count) -> manual.add(Json.obj("item", item, "count", count)));
        }
        o.add("manual", manual);
        JsonArray blocked = new JsonArray();
        notes.forEach((k, v) -> blocked.add(Json.obj("key", k, "reason", v)));
        planner.retries().failedWithPrefix(p.id + "/").forEach((k, st) -> blocked.add(Json.obj(
                "key", k.substring(p.id.length() + 1), "reason", st.reason())));
        o.add("blocked", blocked);
        if (full) {
            JsonArray rows = new JsonArray();
            if (plan != null) {
                plan.rows().forEach(r -> rows.add(r.view()));
            } else if (bom != null) {
                bom.forEach((item, n) -> rows.add(Json.obj("item", item, "needed", n)));
            }
            o.add("bom", rows);
            JsonArray actions = new JsonArray();
            if (plan != null) {
                plan.actions().forEach(x -> actions.add(x.view()));
            }
            o.add("production", actions);
            o.add("gameData", m.gameData.view());
            if (bomBlocks >= 0) {
                o.addProperty("bomBlocks", bomBlocks);
            }
        }
        return o;
    }

    private JsonArray sectorsView() {
        JsonArray out = new JsonArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < sectors.size(); i++) {
            Sector s = sectors.get(i);
            String st = VERIFY.equals(s.state) ? PENDING : s.state;
            JsonObject o = Json.obj("index", i, "state", st, "box", s.box, "placed", s.correct,
                    "total", s.total >= 0 ? s.total : null, "missing", s.missing, "wrong", s.wrong,
                    "unloaded", s.unloaded, "verify", VERIFY.equals(s.state), "verified", s.verified);
            if (!s.bots.isEmpty()) {
                o.addProperty("botId", s.bots.iterator().next());
                o.add("botIds", Json.arrOf(s.bots));
            }
            if (s.waitUntil > now) {
                o.addProperty("waitingMaterialsUntil", s.waitUntil);
            }
            out.add(o);
        }
        return out;
    }

    @Override
    public JsonObject persist() {
        JsonArray secs = new JsonArray();
        for (Sector s : sectors) {
            JsonObject o = Json.obj("box", s.box, "state", ACTIVE.equals(s.state) ? (finalPass ? VERIFY : PENDING) : s.state,
                    "verified", s.verified, "total", s.total, "correct", s.correct, "missing", s.missing,
                    "wrong", s.wrong, "unloaded", s.unloaded, "toClear", s.toClear);
            if (s.remaining != null) {
                o.add("remaining", Json.toTree(s.remaining));
            }
            secs.add(o);
        }
        JsonObject o = Json.obj("fileHash", savedHash, "bomKey", bomKey, "bomBlocks", bomBlocks, "builders", builders,
                "finalPass", finalPass, "sectors", secs);
        if (bom != null) {
            o.add("bom", Json.toTree(bom));
        }
        if (remaining != null) {
            o.add("remaining", Json.toTree(remaining));
        }
        return o;
    }

    private void restore(JsonObject st) {
        if (st == null || st.isEmpty()) {
            return;
        }
        savedHash = Json.getString(st, "fileHash", null);
        bomKey = Json.getString(st, "bomKey", null);
        bomBlocks = Json.getInt(st, "bomBlocks", -1);
        builders = Json.getInt(st, "builders", 0);
        finalPass = Json.getBool(st, "finalPass", false);
        bom = readCounts(Json.getObj(st, "bom"));
        remaining = readCounts(Json.getObj(st, "remaining"));
        JsonArray secs = Json.getArr(st, "sectors");
        if (secs != null) {
            for (JsonElement e : secs) {
                JsonObject o = e.getAsJsonObject();
                Box b = Box.fromJson(o.get("box"));
                if (b == null) {
                    continue;
                }
                Sector s = new Sector(b);
                s.state = Json.getString(o, "state", PENDING);
                if (ACTIVE.equals(s.state)) {
                    s.state = finalPass ? VERIFY : PENDING;
                }
                s.verified = Json.getBool(o, "verified", false);
                s.total = Json.getInt(o, "total", -1);
                s.correct = Json.getInt(o, "correct", 0);
                s.missing = Json.getInt(o, "missing", 0);
                s.wrong = Json.getInt(o, "wrong", 0);
                s.unloaded = Json.getInt(o, "unloaded", 0);
                s.toClear = Json.getInt(o, "toClear", 0);
                s.remaining = readCounts(Json.getObj(o, "remaining"));
                sectors.add(s);
            }
        }
    }

    private static Map<String, Integer> readCounts(JsonObject o) {
        if (o == null) {
            return null;
        }
        Map<String, Integer> out = new TreeMap<>();
        o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsInt()));
        return out;
    }

    // ------------------------------------------------------------------ test access

    List<Sector> sectors() {
        return sectors;
    }

    Map<String, Integer> remainingEstimate() {
        return remaining;
    }

    Resolver.Plan plan() {
        return plan;
    }
}
