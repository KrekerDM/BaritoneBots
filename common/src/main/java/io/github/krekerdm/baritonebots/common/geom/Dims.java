package io.github.krekerdm.baritonebots.common.geom;

import io.github.krekerdm.baritonebots.common.ids.Ids;

import java.util.List;
import java.util.Locale;

/** Vanilla dimension ids and a normaliser for user input. */
public final class Dims {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";
    public static final List<String> VANILLA = List.of(OVERWORLD, NETHER, END);

    private Dims() {
    }

    /** {@code "nether"} → {@code minecraft:the_nether}, {@code "end"} → {@code minecraft:the_end}, else {@link Ids#normalize}. */
    public static String normalize(String dim) {
        if (dim == null || dim.isBlank()) {
            return OVERWORLD;
        }
        String s = dim.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "nether", "minecraft:nether", "the_nether" -> NETHER;
            case "end", "minecraft:end", "the_end" -> END;
            case "overworld", "world" -> OVERWORLD;
            default -> Ids.normalize(s);
        };
    }
}
