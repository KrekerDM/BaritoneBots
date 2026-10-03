package io.github.krekerdm.baritonebots.mod.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies {@code BotConfig.baritone} to {@link BaritoneAPI#getSettings()} (SPEC §2.5).
 * <p>
 * Values are converted to the setting's declared type by Baritone's own parser ({@link SettingsUtil#parseAndApply}),
 * which handles Boolean/Integer/Long/Float/Double/String, {@code List<Block>}/{@code List<Item>} (comma-separated
 * ids), Color, Vec3i and maps. Settings applied by an earlier config but absent from the new one are reset to
 * Baritone's defaults. Client thread only.
 */
public final class BaritoneSettingsApplier {
    private static final String DISALLOW_BREAKING = "blockstodisallowbreaking";

    private final Set<String> applied = new HashSet<>();

    /**
     * @param values          setting name → JSON value ({@code null} JSON resets to default)
     * @param protectedBlocks blocks added to {@code blocksToDisallowBreaking} (protection.noBreak)
     * @return human-readable problems (unknown names, bad values); empty when everything applied
     */
    public List<String> apply(Map<String, JsonElement> values, List<Block> protectedBlocks) {
        Settings settings = BaritoneAPI.getSettings();
        for (String name : applied) {
            Settings.Setting<?> s = settings.byLowerName.get(name);
            if (s != null) {
                s.reset();
            }
        }
        applied.clear();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            Settings.Setting<?> setting = settings.byLowerName.get(lower);
            if (setting == null) {
                problems.add("unknown Baritone setting '" + e.getKey() + "' skipped");
                continue;
            }
            if (setting.isJavaOnly()) {
                problems.add("Baritone setting '" + e.getKey() + "' can only be set from code, skipped");
                continue;
            }
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                setting.reset();
                continue;
            }
            String text = toSettingString(v);
            if (text == null) {
                problems.add("Baritone setting '" + e.getKey() + "': unsupported JSON value " + v);
                continue;
            }
            try {
                SettingsUtil.parseAndApply(settings, lower, text);
                applied.add(lower);
            } catch (RuntimeException ex) {
                problems.add("Baritone setting '" + e.getKey() + "' = '" + text + "' rejected: " + ex.getMessage());
            }
        }

        if (!protectedBlocks.isEmpty()) {
            List<Block> merged = new ArrayList<>(settings.blocksToDisallowBreaking.value);
            for (Block b : protectedBlocks) {
                if (!merged.contains(b)) {
                    merged.add(b);
                }
            }
            settings.blocksToDisallowBreaking.value = merged;
            applied.add(DISALLOW_BREAKING);
        }
        return problems;
    }

    /** Routes Baritone's chat output to {@code sink} instead of the chat HUD. */
    public static void redirectLogger(Consumer<Component> sink) {
        BaritoneAPI.getSettings().logger.value = sink;
    }

    /** JSON → the text form Baritone's settings parser reads; {@code null} if it has none. */
    static String toSettingString(JsonElement v) {
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isNumber()) {
                BigDecimal d = p.getAsBigDecimal();
                // 20.0 must become "20" or Integer/Long settings fail to parse it.
                return d.stripTrailingZeros().scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
            }
            return p.getAsString();
        }
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            List<String> parts = new ArrayList<>();
            for (JsonElement el : a) {
                if (!el.isJsonPrimitive()) {
                    return null;
                }
                parts.add(toSettingString(el));
            }
            return String.join(",", parts);
        }
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) {
                    return null;
                }
                parts.add(e.getKey() + "->" + toSettingString(e.getValue()));
            }
            return String.join(",", parts);
        }
        return null;
    }
}
