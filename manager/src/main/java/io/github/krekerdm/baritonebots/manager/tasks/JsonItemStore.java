package io.github.krekerdm.baritonebots.manager.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ConfigValidator;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;
import io.github.krekerdm.baritonebots.manager.util.Tokens;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A small JSON document store ({@code kits.json}, {@code scenarios.json}): {@code {"<key>":[{id, ...}]}}.
 * Items are validated and normalised by a function that throws {@link ValidationException}. Owned by the loop.
 */
public final class JsonItemStore {
    private final Path file;
    private final String key;
    private final String idPrefix;
    private final UnaryOperator<JsonObject> validator;
    private final Map<String, JsonObject> items = new LinkedHashMap<>();

    public JsonItemStore(Path file, String key, String idPrefix, UnaryOperator<JsonObject> validator) {
        this.file = file;
        this.key = key;
        this.idPrefix = idPrefix;
        this.validator = validator;
    }

    public void load() {
        try {
            JsonElement e = AtomicFiles.readJson(file);
            JsonArray a = e != null && e.isJsonObject() ? Json.getArr(e.getAsJsonObject(), key) : null;
            if (a == null) {
                return;
            }
            for (JsonElement it : a) {
                if (!it.isJsonObject()) {
                    continue;
                }
                JsonObject o = it.getAsJsonObject();
                String id = Json.getString(o, "id", null);
                if (id != null && ConfigValidator.ID.matcher(id).matches()) {
                    items.put(id.toLowerCase(Locale.ROOT), o);
                }
            }
        } catch (IOException e) {
            Log.warn("%s unreadable: %s", file.getFileName(), e.getMessage());
        }
    }

    public List<JsonObject> list() {
        List<JsonObject> out = new ArrayList<>();
        items.values().forEach(o -> out.add(o.deepCopy()));
        return out;
    }

    public JsonObject get(String id) {
        JsonObject o = id == null ? null : items.get(id.toLowerCase(Locale.ROOT));
        return o == null ? null : o.deepCopy();
    }

    public JsonObject require(String id) {
        JsonObject o = get(id);
        if (o == null) {
            throw ApiException.notFound(key + " '" + id + "'");
        }
        return o;
    }

    public JsonObject create(JsonObject body) {
        String id = Json.getString(body, "id", null);
        if (id == null || id.isBlank()) {
            id = Tokens.id(idPrefix);
        } else if (!ConfigValidator.ID.matcher(id).matches()) {
            throw ValidationException.of("id", "pattern");
        } else if (items.containsKey(id.toLowerCase(Locale.ROOT))) {
            throw ValidationException.of("id", "duplicate");
        }
        JsonObject o = validator.apply(body.deepCopy());
        o.addProperty("id", id);
        items.put(id.toLowerCase(Locale.ROOT), o);
        save();
        return o.deepCopy();
    }

    /** PUT semantics: the body replaces the item; the id stays. */
    public JsonObject replace(String id, JsonObject body) {
        JsonObject old = require(id);
        JsonObject o = validator.apply(body.deepCopy());
        o.addProperty("id", Json.getString(old, "id", id));
        items.put(id.toLowerCase(Locale.ROOT), o);
        save();
        return o.deepCopy();
    }

    public void delete(String id) {
        if (items.remove(id.toLowerCase(Locale.ROOT)) == null) {
            throw ApiException.notFound(key + " '" + id + "'");
        }
        save();
    }

    private void save() {
        JsonArray a = new JsonArray();
        items.values().forEach(a::add);
        try {
            AtomicFiles.writeJson(file, Json.obj(key, a));
        } catch (IOException e) {
            Log.error("cannot write " + file, e);
        }
    }
}
