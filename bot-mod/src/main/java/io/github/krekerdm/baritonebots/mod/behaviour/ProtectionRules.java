package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;

import java.util.List;

/**
 * Pure decisions of the protection guard (SPEC §5.7g), unit-tested without Minecraft: may the bot break the block
 * {@code id} at x/y/z, or place {@code id} there?
 * <ul>
 * <li>{@code noBreak} blocks are never broken (chests, beds, spawners, ...), not even inside a task's own box.</li>
 * <li>Inside a protection zone nothing is broken or placed, and {@code built} blocks (planks, glass, doors, ...)
 * are never broken anywhere.</li>
 * <li>Exception: the running area task ({@link Scope}). {@link Mode#AREA} ({@code selection}, {@code build}: the user
 * chose that box) may break and place anything but {@code noBreak} inside its box; {@link Mode#CROPS} ({@code farm})
 * may only harvest and replant crops inside its box.</li>
 * </ul>
 */
public final class ProtectionRules {
    public static final String ZONE = "zone";
    public static final String NO_BREAK = "noBreak";
    public static final String BUILT = "built";

    /** What the running task may do inside its own box. */
    public enum Mode { AREA, CROPS }

    /** The running task's own box and mode; {@code null} = no area task. */
    public record Scope(Box area, Mode mode) {
    }

    /** Crop blocks a farm task harvests and replants (the planted form of seeds / crops). */
    public static final List<String> CROPS = List.of("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes",
            "minecraft:beetroots", "minecraft:pumpkin", "minecraft:melon", "minecraft:pumpkin_stem",
            "minecraft:melon_stem", "minecraft:sugar_cane", "minecraft:cactus", "minecraft:nether_wart",
            "minecraft:cocoa", "minecraft:sweet_berry_bush", "minecraft:bamboo", "minecraft:torchflower_crop",
            "minecraft:pitcher_crop", "minecraft:torchflower", "minecraft:pitcher_plant");

    private ProtectionRules() {
    }

    /** {@link #ZONE}, {@link #NO_BREAK} or {@link #BUILT} when breaking must be refused, else null. */
    public static String breakRefusal(BotConfig.Protection pr, String dim, int x, int y, int z, String id, Scope scope) {
        if (pr == null || !pr.enabled()) {
            return null;
        }
        if (Ids.matchesAny(pr.noBreak(), id)) {
            return NO_BREAK;
        }
        if (ownWork(scope, x, y, z, id)) {
            return null;
        }
        if (zoneAt(pr, dim, x, y, z) != null) {
            return ZONE;
        }
        return Ids.matchesAny(pr.built(), id) ? BUILT : null;
    }

    /** {@link #ZONE} when placing {@code id} at x/y/z must be refused, else null. */
    public static String placeRefusal(BotConfig.Protection pr, String dim, int x, int y, int z, String id, Scope scope) {
        if (pr == null || !pr.enabled() || ownWork(scope, x, y, z, id)) {
            return null;
        }
        return zoneAt(pr, dim, x, y, z) != null ? ZONE : null;
    }

    /** Is this the running area task's own work inside its box? */
    static boolean ownWork(Scope scope, int x, int y, int z, String id) {
        if (scope == null || scope.area() == null || !scope.area().contains(x, y, z)) {
            return false;
        }
        return scope.mode() == Mode.AREA || Ids.matchesAny(CROPS, id);
    }

    /** The zone holding x/y/z in {@code dim} (a zone without {@code dim} applies to every dimension), or null. */
    public static BotConfig.Zone zoneAt(BotConfig.Protection pr, String dim, int x, int y, int z) {
        for (BotConfig.Zone zone : pr.zones()) {
            if (zone == null || zone.box() == null) {
                continue;
            }
            boolean sameDim = zone.dim() == null || zone.dim().isBlank() || dim == null
                    || Dims.normalize(zone.dim()).equals(Dims.normalize(dim));
            if (sameDim && zone.box().contains(x, y, z)) {
                return zone;
            }
        }
        return null;
    }
}
