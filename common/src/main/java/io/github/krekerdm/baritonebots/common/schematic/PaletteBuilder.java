package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.ids.Ids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deduplicating palette of canonical block states; index 0 is always {@code minecraft:air}. */
final class PaletteBuilder {
    static final int AIR = 0;

    private final List<String> states = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();

    PaletteBuilder() {
        add(Ids.AIR);
    }

    /** Index of the canonical form of {@code state}, adding it when new. */
    int add(String state) {
        String canonical = Ids.canonicalState(state);
        return index.computeIfAbsent(canonical, s -> {
            states.add(s);
            return states.size() - 1;
        });
    }

    List<String> states() {
        return states;
    }

    /** Volume guard shared by the readers: the grid is one int per cell. */
    static int checkedVolume(long w, long h, long l) throws SchematicException {
        if (w < 1 || h < 1 || l < 1) {
            throw Nbt.corrupt("schematic size " + w + "x" + h + "x" + l + " is empty");
        }
        long v = w * h * l;
        if (v > Integer.MAX_VALUE - 16) {
            throw new SchematicException(SchematicException.TOO_LARGE,
                    "schematic volume " + v + " blocks is too large");
        }
        return (int) v;
    }
}
