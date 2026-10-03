package io.github.krekerdm.baritonebots.manager.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import java.util.Map;

/** Validation + normalisation of kits and scenarios (SPEC §5.5) before they are stored. */
public final class Validators {
    public static final int MAX_KIT_COUNT = 2304;

    private Validators() {
    }

    /** {@code {name, slots:{slotName:{any:[glob], count}}}}. */
    public static JsonObject kit(JsonObject body) {
        String name = Json.getString(body, "name", "").trim();
        if (name.isEmpty()) {
            throw ValidationException.of("name", "required");
        }
        JsonObject slots = Json.getObj(body, "slots");
        if (slots == null || slots.isEmpty()) {
            throw ValidationException.of("slots", "required");
        }
        JsonObject outSlots = new JsonObject();
        for (Map.Entry<String, JsonElement> e : slots.entrySet()) {
            String p = "slots." + e.getKey();
            if (e.getKey().isBlank() || e.getKey().length() > 32) {
                throw ValidationException.of(p, "pattern");
            }
            if (!e.getValue().isJsonObject()) {
                throw ValidationException.of(p, "type");
            }
            JsonObject s = e.getValue().getAsJsonObject();
            JsonArray any = new JsonArray();
            for (String g : Json.getStringList(s, "any")) {
                if (!g.isBlank()) {
                    any.add(Ids.normalizeGlob(g.trim()));
                }
            }
            if (any.isEmpty()) {
                throw ValidationException.of(p + ".any", "required");
            }
            int count = Json.getInt(s, "count", 1);
            if (count < 1 || count > MAX_KIT_COUNT) {
                throw ValidationException.of(p + ".count", "range");
            }
            outSlots.add(e.getKey().trim(), Json.obj("any", any, "count", count));
        }
        JsonObject out = Json.obj("name", name, "slots", outSlots);
        copyText(body, out, "description");
        return out;
    }

    /** {@code {name, repeat, steps:[{type, args, timeoutSec?, label?}]}}; {@code step} is accepted as alias of {@code type}. */
    public static JsonObject scenario(TaskCatalog catalog, JsonObject body) {
        String name = Json.getString(body, "name", "").trim();
        if (name.isEmpty()) {
            throw ValidationException.of("name", "required");
        }
        JsonElement stepsEl = body.get("steps");
        if (stepsEl != null && !stepsEl.isJsonNull() && !stepsEl.isJsonArray()) {
            throw ValidationException.of("steps", "type");
        }
        JsonArray steps = new JsonArray();
        JsonArray in = stepsEl == null || stepsEl.isJsonNull() ? new JsonArray() : stepsEl.getAsJsonArray();
        for (int i = 0; i < in.size(); i++) {
            String p = "steps[" + i + "]";
            if (!in.get(i).isJsonObject()) {
                throw ValidationException.of(p, "type");
            }
            steps.add(template(catalog, in.get(i).getAsJsonObject(), p));
        }
        JsonObject out = Json.obj("name", name, "repeat", Json.getBool(body, "repeat", false), "steps", steps);
        copyText(body, out, "description");
        return out;
    }

    /**
     * Normalises one TaskTemplate (task or manager step).
     *
     * @throws ValidationException with paths under {@code path}
     */
    public static JsonObject template(TaskCatalog catalog, JsonObject t, String path) {
        String type = Json.getString(t, "type", Json.getString(t, "step", "")).trim();
        if (!catalog.isKnown(type)) {
            throw ValidationException.of(path + ".type", type.isEmpty() ? "required" : "unknown_type");
        }
        JsonObject args;
        try {
            args = catalog.normalize(type, Json.getObj(t, "args"));
        } catch (TaskCatalog.BadArgsException e) {
            throw ValidationException.of(path + ".args" + (e.arg() == null ? "" : "." + e.arg()), e.code());
        }
        int timeout = Json.getInt(t, "timeoutSec", 0);
        if (timeout < 0) {
            throw ValidationException.of(path + ".timeoutSec", "range");
        }
        JsonObject out = Json.obj("type", type, "args", args);
        if (timeout > 0) {
            out.addProperty("timeoutSec", timeout);
        }
        copyText(t, out, "label");
        return out;
    }

    private static void copyText(JsonObject from, JsonObject to, String key) {
        String v = Json.getString(from, key, null);
        if (v != null && !v.isBlank()) {
            to.addProperty(key, v.length() > 500 ? v.substring(0, 500) : v);
        }
    }
}
