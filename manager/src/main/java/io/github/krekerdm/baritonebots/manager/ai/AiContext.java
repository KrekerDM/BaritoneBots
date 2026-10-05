package io.github.krekerdm.baritonebots.manager.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.schematic.SchematicLoader;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.refs.OwnerCommands;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the model may refer to, captured on the manager loop before a request (SPEC §5.7c): the live catalog, bots,
 * waypoints and areas per server, kits, keep profiles, schematics and sorting categories, plus the request text
 * (for the "no invented coordinates" check) and the bots the request is limited to. Immutable; the prompt, the
 * output schema and the validation all read from the same snapshot.
 *
 * @param allowedBots bot ids the request may use (empty = every bot)
 */
public record AiContext(String lang, TaskCatalog catalog, List<Bot> bots, Map<String, List<String>> waypoints,
                        Map<String, List<String>> areas, Map<String, String> kits, List<String> profiles,
                        List<String> schematics, List<String> categories, List<String> servers, String owner,
                        String text, Set<String> allowedBots) {

    /** A bot as the model sees it. {@code roles} empty = any role. */
    public record Bot(String id, String username, String serverId, boolean online, List<String> roles, String task) {
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+");

    public AiContext {
        bots = List.copyOf(bots);
        waypoints = Map.copyOf(waypoints);
        areas = Map.copyOf(areas);
        kits = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(kits));
        profiles = List.copyOf(profiles);
        schematics = List.copyOf(schematics);
        categories = List.copyOf(categories);
        servers = List.copyOf(servers);
        allowedBots = Set.copyOf(allowedBots);
    }

    /**
     * Snapshot of the live state (loop only).
     *
     * @param botIds the bots the request is limited to (null / empty = all); unknown ids are ignored
     */
    public static AiContext capture(Manager m, String text, List<String> botIds) {
        ManagerConfig cfg = m.config.get();
        List<Bot> bots = new ArrayList<>();
        for (BotState b : m.bots.all()) {
            if (!b.def.enabled()) {
                continue;
            }
            String task = b.status != null && b.status.task() != null ? b.status.task().type()
                    : b.queue.current() != null ? b.queue.current().type() : null;
            bots.add(new Bot(b.id, b.def.username(), b.def.serverId(), b.online() && !b.dead, b.def.roles(), task));
        }
        Map<String, List<String>> waypoints = new LinkedHashMap<>();
        Map<String, List<String>> areas = new LinkedHashMap<>();
        List<String> servers = new ArrayList<>();
        for (ManagerConfig.ServerProfile s : cfg.servers()) {
            servers.add(s.id());
            WorldDoc doc = m.worlds.get(s.id());
            waypoints.put(s.id(), doc.waypoints.stream().map(WorldDoc.Waypoint::name).toList());
            areas.put(s.id(), doc.areas.stream().map(WorldDoc.Area::name).toList());
        }
        Map<String, String> kits = new LinkedHashMap<>();
        for (JsonObject k : m.kits.list()) {
            kits.put(Json.getString(k, "id", ""), Json.getString(k, "name", ""));
        }
        List<String> categories = new ArrayList<>();
        cfg.autopilot().categories().forEach(c -> categories.add(c.name()));
        Set<String> allowed = new LinkedHashSet<>();
        if (botIds != null) {
            for (String id : botIds) {
                BotState b = m.bots.get(id);
                if (b != null) {
                    allowed.add(b.id);
                }
            }
        }
        return new AiContext(cfg.general().language(), m.catalog, bots, waypoints, areas, kits,
                new ArrayList<>(cfg.keepProfiles().keySet()), schematics(m.dataDir.resolve("schematics")), categories,
                servers, cfg.general().ownerOrNull(), text, allowed);
    }

    static List<String> schematics(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(SchematicLoader::hasSupportedExtension).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ lookups (case-insensitive)

    /** A bot by id or username, or null. */
    public Bot bot(String idOrName) {
        if (idOrName == null) {
            return null;
        }
        String s = idOrName.trim();
        for (Bot b : bots) {
            if (b.id().equalsIgnoreCase(s)) {
                return b;
            }
        }
        for (Bot b : bots) {
            if (b.username() != null && b.username().equalsIgnoreCase(s)) {
                return b;
            }
        }
        return null;
    }

    /** Bots the request may use, in id order. */
    public List<Bot> usableBots() {
        return bots.stream().filter(b -> allowedBots.isEmpty() || allowedBots.contains(b.id())).toList();
    }

    /** The server a request without a named bot works on: the allowed bots' server, the only server, or the busiest. */
    public String defaultServer() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Bot b : usableBots()) {
            if (b.serverId() != null) {
                count.merge(b.serverId(), 1, Integer::sum);
            }
        }
        if (count.isEmpty()) {
            return servers.isEmpty() ? null : servers.getFirst();
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    public String serverOf(String botId) {
        Bot b = bot(botId);
        return b != null && b.serverId() != null ? b.serverId() : defaultServer();
    }

    /** Exact waypoint name on {@code server} (every server when null), or null. */
    public String waypoint(String server, String name) {
        return named(waypoints, server, name);
    }

    public String area(String server, String name) {
        return named(areas, server, name);
    }

    private static String named(Map<String, List<String>> byServer, String server, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : byServer.entrySet()) {
            if (server != null && !e.getKey().equalsIgnoreCase(server)) {
                continue;
            }
            for (String n : e.getValue()) {
                if (n.equalsIgnoreCase(name.trim())) {
                    return n;
                }
            }
        }
        return null;
    }

    /** Kit id by id or name, or null. */
    public String kit(String idOrName) {
        if (idOrName == null) {
            return null;
        }
        for (Map.Entry<String, String> e : kits.entrySet()) {
            if (e.getKey().equalsIgnoreCase(idOrName.trim()) || e.getValue().equalsIgnoreCase(idOrName.trim())) {
                return e.getKey();
            }
        }
        return null;
    }

    public String profile(String name) {
        if (name == null) {
            return null;
        }
        return profiles.stream().filter(p -> p.equalsIgnoreCase(name.trim())).findFirst().orElse(null);
    }

    /** Schematic file: exact name, name without extension or a unique prefix. */
    public String schematic(String wanted) {
        return wanted == null || wanted.isBlank() ? null : OwnerCommands.matchSchematic(wanted.trim(), schematics);
    }

    public boolean hasCategory(String name) {
        return categories.stream().anyMatch(c -> c.equalsIgnoreCase(name));
    }

    /** Whole numbers written in the request; literal coordinates must come from here. */
    public Set<Long> numbers() {
        Set<Long> out = new HashSet<>();
        if (text != null) {
            Matcher mt = NUMBER.matcher(text);
            while (mt.find()) {
                try {
                    out.add(Long.parseLong(mt.group()));
                } catch (NumberFormatException ignored) {
                    // longer than a long: cannot be a coordinate
                }
            }
        }
        return out;
    }

    /** Russian or English for the model's notes and summaries. */
    public String languageName() {
        return "ru".equals(lang == null ? "" : lang.toLowerCase(Locale.ROOT)) ? "Russian" : "English";
    }

    static boolean blank(JsonElement v) {
        return v == null || v.isJsonNull() || v.isJsonPrimitive() && v.getAsString().isBlank();
    }
}
