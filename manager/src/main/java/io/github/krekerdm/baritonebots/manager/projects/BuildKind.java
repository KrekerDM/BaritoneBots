package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.schematic.SchematicLoader;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code build} projects (SPEC §5.7): config {@code {schematic, origin, dim, rotation, mirror, supply?:[pos]}}; the
 * schematic is a file name in {@code schematics/}. Without {@code supply} the server's containers with role
 * {@code supply} in that dimension are used.
 */
public final class BuildKind implements ProjectKind {
    public static final String KIND = "build";
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.()+\\-]{0,95}");
    static final List<String> MIRRORS = List.of("none", "front_back", "left_right");

    private final Manager m;

    public BuildKind(Manager m) {
        this.m = m;
    }

    @Override
    public String kind() {
        return KIND;
    }

    public Path schematicsDir() {
        return m.dataDir.resolve("schematics");
    }

    @Override
    public JsonObject normalizeConfig(JsonObject c, String serverId) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = Json.getString(c, "schematic", "").trim();
        if (name.isEmpty()) {
            errors.put("schematic", "required");
        } else if (!NAME.matcher(name).matches() || name.contains("..") || !SchematicLoader.hasSupportedExtension(name)) {
            errors.put("schematic", "pattern");
        } else if (!Files.isRegularFile(schematicsDir().resolve(name))) {
            errors.put("schematic", "not_found");
        }
        Pos origin = Pos.fromJson(c.get("origin"));
        if (origin == null) {
            errors.put("origin", c.has("origin") ? "type" : "required");
        }
        String dim = Dims.normalize(Json.getString(c, "dim", Dims.OVERWORLD));
        int rotation = 0;
        JsonElement rot = c.get("rotation");
        try {
            rotation = rot == null || rot.isJsonNull() ? 0 : Integer.parseInt(rot.getAsString().trim());
            if (rotation % 90 != 0) {
                errors.put("rotation", "enum");
            }
            rotation = Math.floorMod(rotation, 360);
        } catch (RuntimeException e) {
            errors.put("rotation", "type");
        }
        String mirror = Json.getString(c, "mirror", "none").trim().toLowerCase(java.util.Locale.ROOT);
        if (!MIRRORS.contains(mirror)) {
            errors.put("mirror", "enum");
        }
        JsonArray supply = new JsonArray();
        JsonElement s = c.get("supply");
        if (s != null && !s.isJsonNull()) {
            if (!s.isJsonArray()) {
                errors.put("supply", "type");
            } else {
                for (int i = 0; i < s.getAsJsonArray().size(); i++) {
                    Pos p = Pos.fromJson(s.getAsJsonArray().get(i));
                    if (p == null) {
                        errors.put("supply[" + i + "]", "type");
                    } else {
                        supply.add(Json.toTree(p));
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return Json.obj("schematic", name, "origin", origin, "dim", dim, "rotation", rotation, "mirror", mirror,
                "supply", supply);
    }

    @Override
    public ProjectRuntime runtime(Project p, ProjectService service) {
        return new BuildRuntime(p, service, schematicsDir());
    }
}
