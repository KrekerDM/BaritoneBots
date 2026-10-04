package io.github.krekerdm.baritonebots.manager.projects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ConfigValidator;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Collects and normalises the kind-specific config of the non-build project kinds; errors are gathered with paths
 * relative to {@code config} and thrown together by {@link #done(JsonObject)}.
 */
final class KindConfig {
    /** Largest area a clear / farm / ranch project may cover (blocks). */
    static final long MAX_VOLUME = 4_000_000;

    final JsonObject in;
    final Map<String, String> errors = new LinkedHashMap<>();

    KindConfig(JsonObject in) {
        this.in = in == null ? new JsonObject() : in;
    }

    String dim() {
        return Dims.normalize(Json.getString(in, "dim", Dims.OVERWORLD));
    }

    boolean bool(String key, boolean def) {
        JsonElement e = in.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        String s = e.getAsString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("false")) {
            return Boolean.parseBoolean(s);
        }
        errors.put(key, "type");
        return def;
    }

    int integer(String key, int def, int min, int max) {
        JsonElement e = in.get(key);
        if (e == null || e.isJsonNull() || e.isJsonPrimitive() && e.getAsString().isBlank()) {
            return def;
        }
        try {
            double d = Double.parseDouble(e.getAsString().trim());
            if (d != Math.rint(d)) {
                errors.put(key, "type");
                return def;
            }
            if (d < min || d > max) {
                errors.put(key, "range");
                return def;
            }
            return (int) d;
        } catch (RuntimeException ex) {
            errors.put(key, "type");
            return def;
        }
    }

    /** An item / entity id ({@code minecraft:} added), or null when absent and not required. */
    String id(String key, boolean required) {
        String s = Json.getString(in, key, "").trim();
        if (s.isEmpty()) {
            if (required) {
                errors.put(key, "required");
            }
            return null;
        }
        if (!ConfigValidator.ITEM.matcher(s).matches()) {
            errors.put(key, "pattern");
            return null;
        }
        return Ids.normalize(s);
    }

    /** A glob list (string with commas or array), normalised; may be empty. */
    JsonArray globs(String key, boolean required) {
        JsonArray out = new JsonArray();
        JsonElement e = in.get(key);
        if (e != null && e.isJsonPrimitive()) {
            for (String part : e.getAsString().split("[,\\s]+")) {
                if (!part.isBlank()) {
                    out.add(Ids.normalizeGlob(part.trim()));
                }
            }
        } else if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive() && !x.getAsString().isBlank()) {
                    out.add(Ids.normalizeGlob(x.getAsString().trim()));
                }
            }
        } else if (e != null && !e.isJsonNull()) {
            errors.put(key, "type");
        }
        if (required && out.isEmpty() && !errors.containsKey(key)) {
            errors.put(key, "required");
        }
        return out;
    }

    /** Container positions (list of pos); may be empty. */
    JsonArray positions(String key) {
        JsonArray out = new JsonArray();
        JsonElement e = in.get(key);
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (!e.isJsonArray()) {
            errors.put(key, "type");
            return out;
        }
        for (int i = 0; i < e.getAsJsonArray().size(); i++) {
            Pos p = Pos.fromJson(e.getAsJsonArray().get(i));
            if (p == null) {
                errors.put(key + "[" + i + "]", "type");
            } else {
                out.add(Json.toTree(p));
            }
        }
        return out;
    }

    /** {@code {item: count}} or {@code [{item, count}]} → normalised {@code {id: count}}. */
    JsonObject itemCounts(String key, boolean required) {
        JsonObject out = new JsonObject();
        JsonElement e = in.get(key);
        if (e != null && e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                addCount(out, key + "." + en.getKey(), en.getKey(), en.getValue());
            }
        } else if (e != null && e.isJsonArray()) {
            for (int i = 0; i < e.getAsJsonArray().size(); i++) {
                JsonElement x = e.getAsJsonArray().get(i);
                if (!x.isJsonObject()) {
                    errors.put(key + "[" + i + "]", "type");
                    continue;
                }
                addCount(out, key + "[" + i + "]", Json.getString(x.getAsJsonObject(), "item", ""),
                        x.getAsJsonObject().get("count"));
            }
        } else if (e != null && !e.isJsonNull()) {
            errors.put(key, "type");
        }
        if (required && out.isEmpty() && errors.keySet().stream().noneMatch(k -> k.startsWith(key))) {
            errors.put(key, "required");
        }
        return out;
    }

    private void addCount(JsonObject out, String path, String item, JsonElement count) {
        String id = item == null ? "" : item.trim();
        if (!ConfigValidator.ITEM.matcher(id).matches()) {
            errors.put(path, "pattern");
            return;
        }
        try {
            int n = count == null ? 0 : Integer.parseInt(count.getAsString().trim());
            if (n < 1 || n > 1_000_000) {
                errors.put(path, "range");
                return;
            }
            out.addProperty(Ids.normalize(id), out.has(Ids.normalize(id)) ? out.get(Ids.normalize(id)).getAsInt() + n : n);
        } catch (RuntimeException ex) {
            errors.put(path, "type");
        }
    }

    /**
     * {@code box} or {@code area} (a named area of the server's world, resolved now: its box and dimension are
     * stored, the name is kept for display). Returns {@code [box, dim]}, or null on error.
     */
    Object[] area(WorldDoc doc, boolean required) {
        Box box = Box.fromJson(in.get("box"));
        String area = Json.getString(in, "area", "").trim();
        if (box == null && in.has("box") && !in.get("box").isJsonNull()) {
            errors.put("box", "type");
            return null;
        }
        String dim = dim();
        if (box == null && !area.isEmpty()) {
            WorldDoc.Area a = doc == null ? null : doc.areas.stream().filter(x -> x.name().equalsIgnoreCase(area))
                    .findFirst().orElse(null);
            if (a == null) {
                errors.put("area", "not_found");
                return null;
            }
            box = a.box();
            dim = Dims.normalize(a.dim());
        }
        if (box == null) {
            if (required) {
                errors.put("box", "required");
            }
            return null;
        }
        if (box.volume() > MAX_VOLUME) {
            errors.put("box", "range");
            return null;
        }
        return new Object[] {box, dim};
    }

    JsonObject done(JsonObject out) {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return out;
    }
}
