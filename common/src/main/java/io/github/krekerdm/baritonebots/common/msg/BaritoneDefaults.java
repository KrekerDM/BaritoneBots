package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Low-load Baritone settings shipped as {@link BotConfig#baritone()} defaults. Names are fields of
 * {@code baritone.api.Settings} (verified on the 1.19.0 / 26.2 branch); values are plain JSON scalars that the
 * bot mod converts to the setting's type by reflection.
 */
public final class BaritoneDefaults {
    private static final Map<String, JsonElement> DEFAULTS;

    static {
        Map<String, JsonElement> m = new LinkedHashMap<>();
        // Control goes through the link, never through chat; chat parsing would also react to other players.
        m.put("chatControl", new JsonPrimitive(false));
        m.put("renderPath", new JsonPrimitive(false));
        m.put("renderGoal", new JsonPrimitive(false));
        m.put("renderSelectionBoxes", new JsonPrimitive(false));
        m.put("renderSelection", new JsonPrimitive(false));
        m.put("logAsToast", new JsonPrimitive(false));
        m.put("desktopNotifications", new JsonPrimitive(false));
        m.put("notificationOnPathComplete", new JsonPrimitive(false));
        // Lets Baritone swap tools and throwaway blocks into the hotbar on its own.
        m.put("allowInventory", new JsonPrimitive(true));
        m.put("allowParkour", new JsonPrimitive(false));
        m.put("echoCommands", new JsonPrimitive(false));
        m.put("chatDebug", new JsonPrimitive(false));
        // Default 5: rescanning for ores 4x less often is the main CPU saving while mining.
        m.put("mineGoalUpdateInterval", new JsonPrimitive(20));
        m.put("mineMaxOreLocationsCount", new JsonPrimitive(32));
        // Defaults 4000 / 5000 ms; each plan-ahead search can hold a whole core that long.
        m.put("planAheadPrimaryTimeoutMS", new JsonPrimitive(1500));
        m.put("planAheadFailureTimeoutMS", new JsonPrimitive(3000));
        m.put("pruneRegionsFromRAM", new JsonPrimitive(true));
        DEFAULTS = Collections.unmodifiableMap(m);
    }

    private BaritoneDefaults() {
    }

    /** A fresh, mutable, insertion-ordered copy of the defaults. */
    public static Map<String, JsonElement> map() {
        Map<String, JsonElement> copy = new LinkedHashMap<>();
        DEFAULTS.forEach((k, v) -> copy.put(k, v.deepCopy()));
        return copy;
    }
}
