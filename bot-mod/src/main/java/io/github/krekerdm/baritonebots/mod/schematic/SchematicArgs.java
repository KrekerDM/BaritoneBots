package io.github.krekerdm.baritonebots.mod.schematic;

import baritone.api.schematic.ISchematic;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform;

/**
 * {@code {file, origin, rotation?=0, mirror?="none", box?}} shared by {@code build}, {@code bom} and {@code progress}.
 * {@link #parse} throws {@link IllegalArgumentException} with a readable message on bad input.
 */
public record SchematicArgs(String file, Pos origin, int rotation, SchematicTransform.Mirror mirror, Box box) {
    public static SchematicArgs parse(JsonObject a) {
        String file = Json.getString(a, "file", "");
        if (file.isBlank()) {
            throw new IllegalArgumentException("'file' (schematic path) is required");
        }
        Pos origin = Pos.fromJson(a.get("origin"));
        if (origin == null) {
            throw new IllegalArgumentException("'origin' {x,y,z} is required");
        }
        int rotation = SchematicTransform.normalizeRotation(Json.getInt(a, "rotation", 0));
        SchematicTransform.Mirror mirror = SchematicTransform.Mirror.parse(Json.getString(a, "mirror", "none"));
        Box box = null;
        if (a.has("box") && !a.get("box").isJsonNull()) {
            box = Box.fromJson(a.get("box"));
            if (box == null) {
                throw new IllegalArgumentException("'box' must be {a:{x,y,z}, b:{x,y,z}}");
            }
        }
        return new SchematicArgs(file.trim(), origin, rotation, mirror, box);
    }

    public Placement placement(ISchematic s) {
        return new Placement(new SchematicTransform(origin, rotation, mirror, s.widthX(), s.heightY(), s.lengthZ()));
    }
}
