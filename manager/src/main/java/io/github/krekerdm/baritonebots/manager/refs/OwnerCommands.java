package io.github.krekerdm.baritonebots.manager.refs;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotEvent;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.common.schematic.SchematicLoader;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.automation.StepRunner;
import io.github.krekerdm.baritonebots.manager.autopilot.SignRoles;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.projects.Project;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.world.HouseBlocks;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * In-game commands from the owner (SPEC §5.7e): the {@code owner_command} events of the bots are de-duplicated (every
 * bot on the server hears a public line), parsed ({@link OwnerCommand}) and mapped onto waypoints, areas, container
 * roles, tasks, steps and projects; the answer goes back as a whisper ({@code general.ownerReplyCommand}) from the bot
 * that heard the command, at most one per second per server, in {@code general.language}. A whispered command
 * addresses the bot it was whispered to; public ones go to every bot ({@code come}, {@code follow}, {@code stop},
 * {@code trash}) or to the best free bot ({@code progress}, {@code obtain}); {@code @name} / {@code @all} /
 * {@code @any} override that; a bot logged in with the owner's name is never a target. Queued work has origin {@value #ORIGIN}: manual, so it beats project work.
 */
public final class OwnerCommands {
    public static final String ORIGIN = "owner";
    static final long DEDUPE_MS = 2_500;
    static final long REPLY_GAP_MS = 1_000;
    static final int MAX_REPLY = 230;

    private final Manager m;
    private final Map<String, Long> recent = new HashMap<>();
    private final Map<String, Long> lastReply = new HashMap<>();
    /** Per server: the corners marked with pos1 / pos2 (index 0 / 1) and their dimension. */
    private final Map<String, Pos[]> corners = new HashMap<>();
    private final Map<String, String> cornerDims = new HashMap<>();

    public OwnerCommands(Manager m) {
        this.m = m;
    }

    /** {@code owner_command} event of a bot (loop). */
    public void onCommand(BotState b, BotEvent ev) {
        String sid = b.def.serverId();
        JsonObject d = ev.data() == null ? new JsonObject() : ev.data();
        String text = Json.getString(d, "text", "");
        if (sid == null) {
            return;
        }
        if (m.config.get().general().ownerOrNull() == null) {
            m.ownerDetect.onCommand(b, d); // no owner yet: offer the player in the panel, run nothing
            return;
        }
        long now = System.currentTimeMillis();
        String key = sid.toLowerCase(Locale.ROOT) + "|" + text.toLowerCase(Locale.ROOT);
        Long seen = recent.get(key);
        if (seen != null && now - seen < DEDUPE_MS) {
            return; // the same public line heard by another bot
        }
        recent.put(key, now);
        recent.values().removeIf(t -> now - t > 60_000);
        OwnerCommand cmd = OwnerCommand.parse(text);
        boolean whisper = "whisper".equals(Json.getString(d, "via", ""));
        try {
            run(b, sid, cmd, d, whisper);
        } catch (ValidationException | ApiException | IllegalArgumentException | TaskCatalog.BadArgsException e) {
            reply(b, "failed", Map.of("message", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            Log.error("owner command '" + text + "' failed", e);
            reply(b, "failed", Map.of("message", e.toString()));
        }
    }

    private void run(BotState b, String sid, OwnerCommand cmd, JsonObject d, boolean whisper) {
        Refs.Owner owner = RefResolver.ownerOf(d);
        String dim = Dims.normalize(Json.getString(d, "dim", owner != null && owner.dim() != null ? owner.dim() : Dims.OVERWORLD));
        if (!cmd.known()) {
            reply(b, "unknown", Map.of("cmd", cmd.verb(), "help", help()));
            return;
        }
        switch (cmd.verb()) {
            case OwnerCommand.HELP -> reply(b, "help", Map.of("help", help()));
            case OwnerCommand.HERE, OwnerCommand.HOME -> waypoint(b, sid, cmd, owner, dim);
            case OwnerCommand.POS1, OwnerCommand.POS2 -> corner(b, sid, cmd, owner, dim);
            case OwnerCommand.AREA -> area(b, sid, cmd);
            case OwnerCommand.CHEST -> chest(b, sid, cmd, owner, dim);
            case OwnerCommand.COME, OwnerCommand.FOLLOW -> comeOrFollow(b, sid, cmd, owner, whisper);
            case OwnerCommand.STOP -> stop(b, sid, cmd, whisper);
            case OwnerCommand.BUILD -> build(b, sid, cmd, owner, dim);
            case OwnerCommand.FARM, OwnerCommand.RANCH -> fieldProject(b, sid, cmd, dim);
            case OwnerCommand.PROTECT -> protect(b, sid, cmd, owner, dim);
            case OwnerCommand.UNPROTECT -> unprotect(b, sid, owner, dim);
            case OwnerCommand.TRASH -> {
                String to = trashTarget(cmd.arg(0));
                queue(b, sid, cmd, whisper, true, Json.obj("type", "trash", "args", Json.obj("to", to)), "trash");
            }
            case OwnerCommand.PROGRESS -> {
                String tier = OwnerCommand.tier(cmd.arg(0) == null ? "iron" : cmd.arg(0));
                if (tier == null) {
                    reply(b, "badTier", Map.of());
                    return;
                }
                queue(b, sid, cmd, whisper, false, Json.obj("type", "progress", "args", Json.obj("tier", tier)),
                        "progress " + tier);
            }
            case OwnerCommand.OBTAIN -> {
                String item = cmd.arg(0);
                if (item == null || !item.matches("[A-Za-z0-9_:.\\-/]+")) {
                    reply(b, "badItem", Map.of());
                    return;
                }
                int count = 1;
                try {
                    count = cmd.arg(1) == null ? 1 : Math.max(1, Math.min(2304, Integer.parseInt(cmd.arg(1))));
                } catch (NumberFormatException e) {
                    reply(b, "badItem", Map.of());
                    return;
                }
                String id = Ids.normalize(item.toLowerCase(Locale.ROOT));
                queue(b, sid, cmd, whisper, false, Json.obj("type", "obtain", "args", Json.obj("item", id, "count", count)),
                        "obtain " + count + " " + Ids.path(id));
            }
            default -> reply(b, "unknown", Map.of("cmd", cmd.verb(), "help", help()));
        }
    }

    // ------------------------------------------------------------------ world knowledge

    private void waypoint(BotState b, String sid, OwnerCommand cmd, Refs.Owner owner, String dim) {
        boolean home = OwnerCommand.HOME.equals(cmd.verb());
        String name = home ? "home" : String.join(" ", cmd.args()).trim();
        if (name.isEmpty()) {
            reply(b, "needName", Map.of("cmd", cmd.verb()));
            return;
        }
        if (owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        WorldDoc doc = m.worlds.get(sid);
        doc.waypoints.removeIf(w -> w.name().equalsIgnoreCase(name));
        doc.waypoints.add(new WorldDoc.Waypoint(name, dim, owner.pos()));
        m.worlds.markDirty(sid);
        m.broadcastWorld(sid);
        Map<String, Object> args = xyz(owner.pos());
        args.put("name", name);
        reply(b, home ? "home" : "waypoint", args);
    }

    private void corner(BotState b, String sid, OwnerCommand cmd, Refs.Owner owner, String dim) {
        if (owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        Pos p = owner.look() != null ? owner.look() : owner.pos();
        int i = OwnerCommand.POS1.equals(cmd.verb()) ? 0 : 1;
        String key = sid.toLowerCase(Locale.ROOT);
        Pos[] c = corners.computeIfAbsent(key, k -> new Pos[2]);
        if (cornerDims.containsKey(key) && !cornerDims.get(key).equals(dim)) {
            c[1 - i] = null; // the other corner is in another dimension
        }
        c[i] = p;
        cornerDims.put(key, dim);
        Map<String, Object> args = xyz(p);
        args.put("n", i + 1);
        reply(b, "corner", args);
    }

    private void area(BotState b, String sid, OwnerCommand cmd) {
        String name = String.join(" ", cmd.args()).trim();
        if (name.isEmpty()) {
            reply(b, "needName", Map.of("cmd", cmd.verb()));
            return;
        }
        String key = sid.toLowerCase(Locale.ROOT);
        Pos[] c = corners.get(key);
        if (c == null || c[0] == null || c[1] == null) {
            reply(b, "noCorners", Map.of());
            return;
        }
        Box box = new Box(c[0], c[1]);
        WorldDoc doc = m.worlds.get(sid);
        doc.areas.removeIf(a -> a.name().equalsIgnoreCase(name));
        doc.areas.add(new WorldDoc.Area(name, cornerDims.getOrDefault(key, Dims.OVERWORLD), box));
        m.worlds.markDirty(sid);
        m.broadcastWorld(sid);
        reply(b, "area", Map.of("name", name, "w", box.width(), "h", box.height(), "l", box.length()));
    }

    private void chest(BotState b, String sid, OwnerCommand cmd, Refs.Owner owner, String dim) {
        if (owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        if (owner.look() == null) {
            reply(b, "noLook", Map.of());
            return;
        }
        String word = String.join(" ", cmd.args()).trim();
        String role = roleFor(word);
        if (role == null) {
            reply(b, "badRole", Map.of("role", word));
            return;
        }
        WorldDoc doc = m.worlds.get(sid);
        WorldDoc.Container c = doc.containerAt(dim, owner.look());
        if (c == null) {
            String id = owner.lookBlockId();
            if (id == null || !isContainerBlock(id)) {
                reply(b, "notContainer", Map.of());
                return;
            }
            c = new WorldDoc.Container(io.github.krekerdm.baritonebots.manager.util.Tokens.id("c"), dim, owner.look(),
                    id, List.of(), "", null, 0);
            doc.containers.add(c);
        }
        List<String> roles = new ArrayList<>(List.of(role));
        m.worlds.setRoles(sid, c, roles, WorldDoc.SOURCE_MANUAL);
        m.broadcastWorld(sid);
        Map<String, Object> args = xyz(owner.look());
        args.put("roles", role);
        reply(b, "chest", args);
    }

    /** A role / category from a word: the sign dictionary, a role name, {@code sorted:x} or a category name. */
    String roleFor(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        ManagerConfig cfg = m.config.get();
        List<String> viaDict = SignRoles.roles(w, null, cfg.autopilot().signWords(), null);
        if (!viaDict.isEmpty()) {
            return viaDict.getFirst();
        }
        if (WorldDoc.CONTAINER_ROLES.contains(w)) {
            return w;
        }
        String cat = w.startsWith(WorldDoc.SORTED_PREFIX) ? w.substring(WorldDoc.SORTED_PREFIX.length()) : w;
        for (ManagerConfig.Category c : cfg.autopilot().categories()) {
            if (c.name().equals(cat)) {
                return WorldDoc.SORTED_PREFIX + cat;
            }
        }
        return null;
    }

    private static boolean isContainerBlock(String id) {
        String p = Ids.path(id);
        return p.equals("chest") || p.equals("trapped_chest") || p.equals("barrel") || p.endsWith("shulker_box")
                || p.endsWith("furnace") || p.equals("smoker") || p.equals("crafting_table") || p.equals("hopper")
                || p.equals("dispenser") || p.equals("dropper");
    }

    // ------------------------------------------------------------------ bots

    private void comeOrFollow(BotState b, String sid, OwnerCommand cmd, Refs.Owner owner, boolean whisper) {
        String player = m.config.get().general().ownerOrNull();
        boolean come = OwnerCommand.COME.equals(cmd.verb());
        if (come && owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        List<BotState> bots = targets(b, sid, cmd, whisper, true);
        if (bots.isEmpty()) {
            reply(b, "noBots", Map.of());
            return;
        }
        JsonObject template = come
                ? Json.obj("type", TaskTypes.GOTO, "args", Json.obj("x", owner.pos().x(), "y", owner.pos().y(),
                        "z", owner.pos().z(), "range", 2), "label", "owner: come")
                : Json.obj("type", TaskTypes.FOLLOW, "args", Json.obj("player", player, "radius", 3),
                        "label", "owner: follow");
        for (BotState t : bots) {
            m.dispatcher.addTemplate(t, template.deepCopy(), TaskQueue.Mode.REPLACE, ORIGIN);
        }
        reply(b, come ? "come" : "follow", Map.of("bots", names(bots)));
    }

    private void stop(BotState b, String sid, OwnerCommand cmd, boolean whisper) {
        List<BotState> bots = targets(b, sid, cmd, whisper, true);
        for (BotState t : bots) {
            m.dispatcher.clear(t);
            m.autopilot.releaseHold(t);
        }
        reply(b, bots.isEmpty() ? "noBots" : "stop", Map.of("bots", names(bots)));
    }

    /** Queues one step / task on the target bots. */
    private void queue(BotState b, String sid, OwnerCommand cmd, boolean whisper, boolean allByDefault,
                       JsonObject template, String what) {
        List<BotState> bots = targets(b, sid, cmd, whisper, allByDefault);
        if (bots.isEmpty()) {
            reply(b, "noBots", Map.of());
            return;
        }
        template.addProperty("label", "owner: " + what);
        for (BotState t : bots) {
            m.dispatcher.addTemplate(t, template.deepCopy(), TaskQueue.Mode.APPEND, ORIGIN);
        }
        reply(b, "queued", Map.of("what", what, "bots", names(bots)));
    }

    /** Bots a command addresses (see the class comment). */
    List<BotState> targets(BotState heard, String sid, OwnerCommand cmd, boolean whisper, boolean allByDefault) {
        String t = cmd.target();
        if (t == null) {
            t = whisper ? heard.id : allByDefault ? OwnerCommand.TARGET_ALL : OwnerCommand.TARGET_ANY;
        }
        String owner = m.config.get().general().ownerOrNull();
        StepRunner runner = m.automation.runner();
        if (OwnerCommand.TARGET_ALL.equals(t) || OwnerCommand.TARGET_ANY.equals(t)) {
            return runner.pick(StepRunner.Target.of(Json.toTree(t)), sid, null, x -> isOwnerBot(x, owner));
        }
        for (BotState x : m.bots.all()) {
            if (sid.equalsIgnoreCase(String.valueOf(x.def.serverId())) && !isOwnerBot(x, owner)
                    && (x.id.equalsIgnoreCase(t) || t.equalsIgnoreCase(x.def.username()))) {
                return List.of(x);
            }
        }
        return List.of();
    }

    /** A bot logged in with the owner's own name never executes the owner's commands (it would obey itself). */
    static boolean isOwnerBot(BotState b, String owner) {
        return owner != null && b.def.username() != null && b.def.username().equalsIgnoreCase(owner);
    }

    // ------------------------------------------------------------------ projects

    private void build(BotState b, String sid, OwnerCommand cmd, Refs.Owner owner, String dim) {
        if (owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        if (owner.look() == null) {
            reply(b, "noLook", Map.of());
            return;
        }
        String wanted = cmd.arg(0);
        List<String> files = schematics();
        String file = wanted == null ? null : matchSchematic(wanted, files);
        if (file == null) {
            reply(b, "noSchematic", Map.of("name", wanted == null ? "?" : wanted,
                    "list", files.isEmpty() ? "—" : String.join(", ", files.subList(0, Math.min(5, files.size())))));
            return;
        }
        Integer rot = null;
        if (cmd.arg(1) != null) {
            try {
                rot = Math.floorMod(Integer.parseInt(cmd.arg(1).replace("°", "")), 360);
            } catch (NumberFormatException ignored) {
                rot = null;
            }
        }
        int rotation = rot != null && rot % 90 == 0 ? rot : rotationFromYaw(owner.yaw());
        Pos origin = owner.look().offset(0, 1, 0); // the looked-at block is the ground under the building
        String base = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        JsonObject body = Json.obj("name", trim80(base + " (" + m.config.get().general().ownerOrNull() + ")"),
                "kind", "build", "serverId", sid, "bots", Project.ANY,
                "config", Json.obj("schematic", file, "origin", origin, "dim", dim, "rotation", rotation, "mirror", "none"),
                "start", true);
        m.projects.create(body);
        Map<String, Object> args = xyz(origin);
        args.put("name", base);
        args.put("rot", rotation);
        reply(b, "build", args);
    }

    /** {@code farm} / {@code ranch}: start the server's stopped project of that kind, or create one at the auto spot. */
    private void fieldProject(BotState b, String sid, OwnerCommand cmd, String dim) {
        String kind = OwnerCommand.FARM.equals(cmd.verb()) ? "farm" : "ranch";
        for (Project p : m.projects.all()) {
            if (p.kind.equals(kind) && p.serverId.equalsIgnoreCase(sid)) {
                if (Project.RUNNING.equals(p.status)) {
                    reply(b, "running", Map.of("name", p.name));
                } else {
                    m.projects.action(p.id, Project.PAUSED.equals(p.status) ? "resume" : "start");
                    reply(b, "started", Map.of("name", p.name));
                }
                return;
            }
        }
        JsonObject config = Json.obj("box", Json.obj("ref", Refs.AUTO), "dim", dim);
        if ("ranch".equals(kind) && cmd.arg(0) != null) {
            config.addProperty("animal", Ids.normalize(cmd.arg(0).toLowerCase(Locale.ROOT)));
        }
        String name = "farm".equals(kind) ? "Farm" : "Ranch";
        JsonObject body = Json.obj("name", name, "kind", kind, "serverId", sid, "bots", Project.ANY, "config", config,
                "start", true);
        m.refs.resolveProject(body).whenComplete((resolved, err) -> m.loop.post(() -> {
            if (err != null) {
                Throwable c = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                        ? err.getCause() : err;
                reply(b, "failed", Map.of("message", String.valueOf(c.getMessage())));
                return;
            }
            try {
                m.projects.create(resolved);
                reply(b, "started", Map.of("name", name));
            } catch (RuntimeException e) {
                reply(b, "failed", Map.of("message", String.valueOf(e.getMessage())));
            }
        }));
    }

    private List<String> schematics() {
        Path dir = m.dataDir.resolve("schematics");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(SchematicLoader::hasSupportedExtension).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Exact name, name without extension, or a unique prefix (case-insensitive). */
    public static String matchSchematic(String wanted, List<String> files) {
        String w = wanted.toLowerCase(Locale.ROOT);
        List<String> prefix = new ArrayList<>();
        for (String f : files) {
            String fl = f.toLowerCase(Locale.ROOT);
            String stem = fl.contains(".") ? fl.substring(0, fl.lastIndexOf('.')) : fl;
            if (fl.equals(w) || stem.equals(w)) {
                return f;
            }
            if (fl.startsWith(w)) {
                prefix.add(f);
            }
        }
        return prefix.size() == 1 ? prefix.getFirst() : null;
    }

    /**
     * Rotation that makes the schematic extend away from the owner: yaw 0 (south, +z) = 0°, west = 90°, north = 180°,
     * east = 270° (clockwise from above, as in SPEC §3).
     */
    static int rotationFromYaw(double yaw) {
        return Math.floorMod((int) Math.round(yaw / 90.0) * 90, 360);
    }

    static String trashTarget(String word) {
        if (word == null) {
            return "drop";
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "store", "storage", "сдать", "склад", "сдай" -> "store";
            case "chest", "trash_chest", "сундук", "мусорка" -> "trash_chest";
            default -> "drop";
        };
    }

    // ------------------------------------------------------------------ protection (SPEC §5.7g)

    /**
     * {@code protect [name]}: the structure the owner looks at (else stands in) becomes a manual zone — the cluster of
     * built blocks there, {@link HouseBlocks#zoneOf}; without one, 9 × 8 × 9 blocks around the point. Switched-off
     * zones it touches are dropped (the owner wants protection there now).
     */
    private void protect(BotState b, String sid, OwnerCommand cmd, Refs.Owner owner, String dim) {
        if (owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        ManagerConfig.ServerProfile server = m.config.get().server(sid).orElse(null);
        if (server == null || !Json.getBool(server.protection(), "enabled", true)) {
            reply(b, "protectOff", Map.of());
            return;
        }
        Pos at = owner.look() != null && owner.look().distance(owner.pos()) <= 32 ? owner.look() : owner.pos();
        String name = String.join(" ", cmd.args()).trim();
        m.autopilot.houses().structureAt(sid, dim, at, (box, found) -> {
            WorldDoc doc = m.worlds.get(sid);
            doc.zones.removeIf(z -> z.off() && z.dim().equals(dim) && z.box().intersects(box));
            doc.zones.add(new WorldDoc.Zone(trim80(name), dim, box, WorldDoc.SOURCE_MANUAL, false));
            m.worlds.markDirty(sid);
            m.broadcastWorld(sid);
            m.pushConfigForServer(sid);
            Map<String, Object> args = xyz(box.min());
            args.put("w", box.width());
            args.put("h", box.height());
            args.put("l", box.length());
            reply(b, found ? "protected" : "protectedBox", args);
        });
    }

    /** {@code unprotect}: zones at the point the owner looks at (else stands): automatic ones off, manual ones removed. */
    private void unprotect(BotState b, String sid, Refs.Owner owner, String dim) {
        if (owner == null) {
            reply(b, "notSeen", Map.of());
            return;
        }
        WorldDoc doc = m.worlds.get(sid);
        List<WorldDoc.Zone> hit = new ArrayList<>();
        for (Pos p : owner.look() == null ? List.of(owner.pos()) : List.of(owner.look(), owner.pos())) {
            doc.zonesNear(dim, p, 0).stream().filter(WorldDoc.Zone::active).filter(z -> !hit.contains(z)).forEach(hit::add);
        }
        if (hit.isEmpty()) {
            reply(b, "noZone", Map.of());
            return;
        }
        for (WorldDoc.Zone z : hit) {
            int i = doc.zones.indexOf(z);
            if (z.auto()) {
                doc.zones.set(i, new WorldDoc.Zone(z.name(), z.dim(), z.box(), z.source(), true));
            } else {
                doc.zones.remove(i);
            }
        }
        m.worlds.markDirty(sid);
        m.broadcastWorld(sid);
        m.pushConfigForServer(sid);
        reply(b, "unprotected", Map.of("n", hit.size()));
    }

    // ------------------------------------------------------------------ replies

    private String help() {
        return m.i18n.t(lang(), "owner.reply.commands", Map.of("p", m.config.get().general().commandPrefixOrDefault()));
    }

    private String lang() {
        return m.config.get().general().language();
    }

    /** Whispers {@code owner.reply.<key>} to the owner from {@code b} (rate-limited per server). */
    void reply(BotState b, String key, Map<String, ?> args) {
        replyTo(b, m.config.get().general().ownerOrNull(), key, args);
    }

    /** Whispers {@code owner.reply.<key>} to {@code player} from {@code b} (rate-limited per server). */
    void replyTo(BotState b, String player, String key, Map<String, ?> args) {
        ManagerConfig.General g = m.config.get().general();
        String owner = player;
        if (owner == null || b == null || !b.linked() || b.def.serverId() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String sk = b.def.serverId().toLowerCase(Locale.ROOT);
        Long last = lastReply.get(sk);
        if (last != null && now - last < REPLY_GAP_MS) {
            Log.info("owner reply dropped (rate limit): %s", key);
            return;
        }
        lastReply.put(sk, now);
        String text = m.i18n.t(lang(), "owner.reply." + key, args).replace('\n', ' ');
        if (text.length() > MAX_REPLY) {
            text = text.substring(0, MAX_REPLY - 1) + "…";
        }
        String line = g.replyCommandOrDefault().replace("{player}", owner).replace("{text}", text);
        b.session.send(MessageTypes.CHAT, Json.obj("text", line));
    }

    private static Map<String, Object> xyz(Pos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.x());
        m.put("y", p.y());
        m.put("z", p.z());
        return m;
    }

    private static String names(List<BotState> bots) {
        return String.join(", ", bots.stream().map(x -> x.def.username() == null ? x.id : x.def.username()).toList());
    }

    private static String trim80(String s) {
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
