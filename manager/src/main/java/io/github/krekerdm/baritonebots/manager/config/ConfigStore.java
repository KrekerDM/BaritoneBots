package io.github.krekerdm.baritonebots.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.util.AtomicFiles;
import io.github.krekerdm.baritonebots.manager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * config.json (SPEC §5.3): the validated JSON tree plus its typed {@link ManagerConfig}. Every change is a JSON
 * merge patch (RFC 7396) that is normalised (defaults filled in), validated, saved atomically and announced to
 * listeners as {@code (old, new)}. Companion tokens are moved to {@link Secrets}. Owned by the manager loop.
 */
public final class ConfigStore {
    private final Path file;
    private final Secrets secrets;
    private final List<BiConsumer<ManagerConfig, ManagerConfig>> listeners = new ArrayList<>();
    private JsonObject root;
    private ManagerConfig config;

    public ConfigStore(Path file, Secrets secrets) {
        this.file = file;
        this.secrets = secrets;
    }

    /**
     * Loads (or creates) config.json.
     *
     * @throws ValidationException when the file exists but breaks the schema; the manager refuses to start
     *                             rather than overwrite a hand-edited file
     */
    public void load() throws IOException {
        JsonElement e = AtomicFiles.readJson(file);
        JsonObject loaded = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        Map<String, String> tokens = extractTokens(loaded);
        JsonObject normalized = normalize(loaded);
        ManagerConfig typed = check(normalized);
        tokens.forEach(secrets::setCompanionToken);
        root = normalized;
        config = typed;
        ensureCompanionTokens();
        if (e == null || !tokens.isEmpty()) {
            save();
        }
    }

    public ManagerConfig get() {
        return config;
    }

    /** Copy of the stored tree. */
    public JsonObject json() {
        return root.deepCopy();
    }

    /** The tree as the panel sees it: companion tokens filled in from secrets.json. */
    public JsonObject viewForPanel() {
        JsonObject view = root.deepCopy();
        JsonArray servers = Json.getArr(view, "servers");
        if (servers != null) {
            for (JsonElement s : servers) {
                JsonObject so = s.getAsJsonObject();
                JsonObject companion = Json.getObj(so, "companion");
                if (companion == null) {
                    companion = new JsonObject();
                    so.add("companion", companion);
                }
                companion.addProperty("token", secrets.companionToken(Json.getString(so, "id", "")));
            }
        }
        return view;
    }

    public void addListener(BiConsumer<ManagerConfig, ManagerConfig> listener) {
        listeners.add(listener);
    }

    /** Applies a merge patch to the whole config. */
    public ManagerConfig patch(JsonObject patch) {
        JsonObject candidate = Json.deepMerge(root, patch);
        Map<String, String> tokens = extractTokens(candidate);
        JsonObject normalized = normalize(candidate);
        ManagerConfig typed = check(normalized);
        tokens.forEach(secrets::setCompanionToken);
        ManagerConfig old = config;
        root = normalized;
        config = typed;
        ensureCompanionTokens();
        save();
        for (BiConsumer<ManagerConfig, ManagerConfig> l : listeners) {
            try {
                l.accept(old, typed);
            } catch (RuntimeException ex) {
                Log.error("config listener failed", ex);
            }
        }
        return typed;
    }

    // ---------------------------------------------------------------- list item helpers (servers, bots)

    /** Appends an item to {@code servers} or {@code bots}; missing fields get defaults. Returns the stored item. */
    public JsonObject addItem(String list, JsonObject item) {
        JsonArray items = listCopy(list);
        items.add(item.deepCopy());
        patch(Json.obj(list, items));
        return findItem(list, Json.getString(item, "id", "")).orElseThrow();
    }

    /** Merge-patches one item; its {@code id} cannot change. */
    public JsonObject updateItem(String list, String id, JsonObject itemPatch) {
        JsonArray items = listCopy(list);
        int idx = indexOf(items, id);
        if (idx < 0) {
            throw new NoSuchItemException(list, id);
        }
        String patchId = Json.getString(itemPatch, "id", null);
        if (patchId != null && !patchId.equalsIgnoreCase(id)) {
            throw ValidationException.of(list + "[" + idx + "].id", "immutable");
        }
        items.set(idx, Json.deepMerge(items.get(idx).getAsJsonObject(), itemPatch));
        patch(Json.obj(list, items));
        return findItem(list, id).orElseThrow();
    }

    public void removeItem(String list, String id) {
        JsonArray items = listCopy(list);
        int idx = indexOf(items, id);
        if (idx < 0) {
            throw new NoSuchItemException(list, id);
        }
        items.remove(idx);
        patch(Json.obj(list, items));
    }

    public java.util.Optional<JsonObject> findItem(String list, String id) {
        JsonArray items = Json.getArr(viewForPanel(), list);
        int idx = items == null ? -1 : indexOf(items, id);
        return idx < 0 ? java.util.Optional.empty() : java.util.Optional.of(items.get(idx).getAsJsonObject());
    }

    private JsonArray listCopy(String list) {
        JsonArray a = Json.getArr(viewForPanel(), list);
        return a == null ? new JsonArray() : a;
    }

    private static int indexOf(JsonArray items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (Json.getString(items.get(i).getAsJsonObject(), "id", "").equalsIgnoreCase(id)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- normalisation

    /**
     * Fills defaults: objects are merged under the user's values, list items are merged onto their item
     * defaults, and free-form maps ({@code baritone}) are taken as given so deleting a default setting sticks.
     */
    static JsonObject normalize(JsonObject in) {
        JsonObject out = Json.deepMerge(SettingsSchema.defaults(), in);
        for (SchemaField f : SettingsSchema.fields()) {
            if (!f.isItemField() && (SchemaField.MAP.equals(f.type()) || SchemaField.JSON.equals(f.type()))) {
                JsonElement given = in.get(f.path());
                if (given != null && !given.isJsonNull() && !f.path().contains(".")) {
                    out.add(f.path(), given.deepCopy());
                }
            }
        }
        normalizeList(out, "servers");
        normalizeList(out, "bots");
        JsonObject runtime = Json.getObj(out, "runtime");
        if (runtime != null) {
            JsonArray mods = Json.getArr(runtime, "mods");
            if (mods != null) {
                runtime.add("mods", mergeItems(mods, SettingsSchema.itemDefaults("runtime.mods")));
            }
        }
        return out;
    }

    private static void normalizeList(JsonObject out, String list) {
        JsonArray items = Json.getArr(out, list);
        if (items != null) {
            out.add(list, mergeItems(items, SettingsSchema.itemDefaults(list)));
        }
    }

    private static JsonArray mergeItems(JsonArray items, JsonObject defaults) {
        JsonArray merged = new JsonArray();
        for (JsonElement item : items) {
            merged.add(item.isJsonObject() ? Json.deepMerge(defaults, item.getAsJsonObject()) : item);
        }
        return merged;
    }

    /** Removes {@code servers[].companion.token} from the tree and returns them by server id. */
    private static Map<String, String> extractTokens(JsonObject tree) {
        Map<String, String> tokens = new LinkedHashMap<>();
        JsonArray servers = Json.getArr(tree, "servers");
        if (servers == null) {
            return tokens;
        }
        for (JsonElement s : servers) {
            if (!s.isJsonObject()) {
                continue;
            }
            JsonObject companion = Json.getObj(s.getAsJsonObject(), "companion");
            String id = Json.getString(s.getAsJsonObject(), "id", null);
            if (companion != null && companion.has("token")) {
                String token = Json.getString(companion, "token", "");
                companion.remove("token");
                if (id != null && !token.isBlank()) {
                    tokens.put(id, token);
                }
            }
        }
        return tokens;
    }

    private void ensureCompanionTokens() {
        for (ManagerConfig.ServerProfile s : config.servers()) {
            if (s.companion().enabled()) {
                secrets.ensureCompanionToken(s.id());
            }
        }
    }

    private static ManagerConfig check(JsonObject normalized) {
        Map<String, String> errors = ConfigValidator.validate(normalized);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        try {
            return ManagerConfig.fromJson(normalized);
        } catch (JsonParseException | IllegalStateException e) {
            throw ValidationException.of("", "type");
        }
    }

    private void save() {
        try {
            AtomicFiles.writeJson(file, root);
        } catch (IOException e) {
            Log.error("cannot write " + file, e);
        }
    }

    /** No server/bot with that id. HTTP maps it to 404. */
    public static final class NoSuchItemException extends RuntimeException {
        public NoSuchItemException(String list, String id) {
            super(list + " has no item '" + id + "'");
        }
    }
}
