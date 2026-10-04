package io.github.krekerdm.baritonebots.mod.task.plan;

import io.github.krekerdm.baritonebots.common.ids.Ids;

import java.util.List;
import java.util.Map;

/**
 * Branch-mining level for legit mining on anti-xray servers (SPEC §5.7b3, 26.x ore distribution): the Y used for
 * Baritone's {@code legitMineYLevel} when the task gives no {@code y}.
 */
public final class LegitMining {
    private static final Map<String, Integer> BEST_Y = Map.of(
            "diamond", -58,
            "redstone", -58,
            "gold", -16,
            "lapis", 0,
            "iron", 16,
            "copper", 48,
            "coal", 96);

    private LegitMining() {
    }

    /**
     * Best Y for the first overworld ore in {@code blockIds} with a known level ({@code minecraft:iron_ore},
     * {@code deepslate_iron_ore}, ...), or {@code null} (emerald, nether ores, non-ores).
     */
    public static Integer bestY(List<String> blockIds) {
        for (String raw : blockIds) {
            String id = Ids.normalize(raw.trim());
            if (!id.startsWith("minecraft:")) {
                continue;
            }
            String path = id.substring("minecraft:".length());
            if (path.startsWith("deepslate_")) {
                path = path.substring("deepslate_".length());
            }
            if (!path.endsWith("_ore") || path.startsWith("nether_")) {
                continue;
            }
            Integer y = BEST_Y.get(path.substring(0, path.length() - "_ore".length()));
            if (y != null) {
                return y;
            }
        }
        return null;
    }
}
