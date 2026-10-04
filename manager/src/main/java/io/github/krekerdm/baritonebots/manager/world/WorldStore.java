package io.github.krekerdm.baritonebots.manager.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.manager.ManagerLoop;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * world/&lt;serverId&gt;.json documents (SPEC §5.6), loaded on first use and saved 2 s after the last change.
 * Owned by the manager loop.
 */
public final class WorldStore {
    private static final long SAVE_DELAY_MS = 2_000;

    private final Path dir;
    private final ManagerLoop loop;
    private final Map<String, WorldDoc> docs = new LinkedHashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private boolean saveScheduled;

    public WorldStore(Path dir, ManagerLoop loop) {
        this.dir = dir;
        this.loop = loop;
    }

    private static String key(String serverId) {
        return serverId.toLowerCase(Locale.ROOT);
    }

    /** The document of a server profile; ids are validated by the config ({@code [A-Za-z0-9_-]}), so safe as file names. */
    public WorldDoc get(String serverId) {
        return docs.computeIfAbsent(key(serverId), k -> load(k));
    }

    private WorldDoc load(String k) {
        Path file = dir.resolve(k + ".json");
        try {
            JsonElement e = AtomicFiles.readJson(file);
            if (e != null && e.isJsonObject()) {
                return WorldDoc.fromJson(e.getAsJsonObject());
            }
        } catch (IOException | ValidationException | IllegalStateException e) {
            Log.warn("world %s unreadable (%s); starting empty, old file kept as backup", k, e.getMessage());
            try {
                AtomicFiles.backupCorrupt(file);
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
        return new WorldDoc();
    }

    public void markDirty(String serverId) {
        dirty.add(key(serverId));
        if (!saveScheduled) {
            saveScheduled = true;
            loop.schedule(this::flush, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void flush() {
        saveScheduled = false;
        for (String k : List.copyOf(dirty)) {
            WorldDoc d = docs.get(k);
            if (d == null) {
                continue;
            }
            try {
                AtomicFiles.writeJson(dir.resolve(k + ".json"), d.toJson());
            } catch (IOException e) {
                Log.error("cannot save world " + k, e);
            }
        }
        dirty.clear();
    }

    /** Applies a bot's container snapshot; unknown containers are added without roles. Returns the stored container. */
    public WorldDoc.Container onSnapshot(String serverId, ContainerSnapshot s) {
        if (s.pos() == null) {
            return null;
        }
        WorldDoc d = get(serverId);
        String dim = Dims.normalize(s.dim() == null ? Dims.OVERWORLD : s.dim());
        WorldDoc.Snapshot snap = new WorldDoc.Snapshot(s.size(), s.free(), s.items(), s.time());
        WorldDoc.Container c = d.containerAt(dim, s.pos());
        WorldDoc.Container updated = c == null
                ? new WorldDoc.Container(Tokens.id("c"), dim, s.pos(), s.block(), List.of(), "", snap, s.time())
                : c.withSnapshot(s.block(), snap, s.time());
        d.replaceContainer(updated);
        markDirty(serverId);
        return updated;
    }

    public void addDeath(String serverId, WorldDoc.Death death) {
        get(serverId).addDeath(death);
        markDirty(serverId);
    }

    /**
     * Merges a {@code containers_nearby} answer ({@code [{pos, dim, block}]}) into the index.
     *
     * @return number of containers added
     */
    public int mergeDiscovered(String serverId, JsonArray found) {
        return mergeDiscovered(serverId, found, (dim, pos, block) -> defaultRoles(block));
    }

    /** Roles for a container seen for the first time. */
    public interface RoleChooser {
        List<String> roles(String dim, Pos pos, String block);
    }

    /**
     * Merges a {@code containers_nearby} answer; new containers get their roles from {@code chooser}.
     *
     * @return number of containers added
     */
    public int mergeDiscovered(String serverId, JsonArray found, RoleChooser chooser) {
        WorldDoc d = get(serverId);
        int added = 0;
        for (JsonElement e : found) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            Pos pos = Pos.fromJson(o.get("pos"));
            if (pos == null) {
                continue;
            }
            String dim = Dims.normalize(Json.getString(o, "dim", Dims.OVERWORLD));
            String block = Json.getString(o, "block", "minecraft:chest");
            WorldDoc.Container c = d.containerAt(dim, pos);
            if (c == null) {
                List<String> roles = chooser.roles(dim, pos, block);
                d.containers.add(new WorldDoc.Container(Tokens.id("c"), dim, pos, block,
                        roles == null ? List.of() : roles, "", null, 0));
                added++;
            }
        }
        if (added > 0) {
            markDirty(serverId);
        }
        return added;
    }

    /** Replaces a container's roles (autopilot category adoption). */
    public void setRoles(String serverId, WorldDoc.Container c, List<String> roles) {
        get(serverId).replaceContainer(c.withRoles(roles));
        markDirty(serverId);
    }

    /** Furnaces and crafting tables get their obvious role; chests and barrels stay unassigned. */
    public static List<String> defaultRoles(String block) {
        String b = block == null ? "" : block;
        if (b.endsWith("furnace") || b.endsWith("smoker")) {
            return List.of("furnace");
        }
        if (b.endsWith("crafting_table")) {
            return List.of("crafting");
        }
        return List.of();
    }

    /** Protected zones of the server as {@code {dim, box}} objects for BotConfig. */
    public List<JsonObject> zonesFor(String serverId) {
        List<JsonObject> out = new ArrayList<>();
        for (WorldDoc.Zone z : get(serverId).zones) {
            out.add(Json.obj("dim", z.dim(), "box", z.box()));
        }
        return out;
    }
}
