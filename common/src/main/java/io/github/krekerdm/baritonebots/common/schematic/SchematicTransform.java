package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;

import java.util.Locale;
import java.util.Objects;

/**
 * Placement of a schematic in the world: local (x,y,z) is mirrored, then rotated clockwise (seen from above)
 * about local (0,0,0), then translated by {@code origin} — the same order and pivot as Minecraft's
 * {@code StructureTemplate.transform} and Litematica placements. A rotated or mirrored schematic therefore
 * extends to the negative side of {@code origin}; {@link #footprint()} gives the real world box.
 * <p>
 * Only coordinates are transformed here. Block-state properties (facing, axis, shape, ...) must be rotated with
 * {@code BlockState#mirror(Mirror)} then {@code BlockState#rotate(Rotation)} by the bot mod, which has Minecraft;
 * {@link #mcRotationName()} and {@link Mirror#name()} match the vanilla enum constants.
 */
public record SchematicTransform(Pos origin, int rotation, Mirror mirror, int width, int height, int length) {
    /** Mirror modes; constant names equal {@code net.minecraft.world.level.block.Mirror}. */
    public enum Mirror {
        /** No mirroring. */
        NONE("none"),
        /** Flips Z (north ↔ south), vanilla {@code LEFT_RIGHT}. */
        LEFT_RIGHT("left_right"),
        /** Flips X (east ↔ west), vanilla {@code FRONT_BACK}. */
        FRONT_BACK("front_back");

        private final String id;

        Mirror(String id) {
            this.id = id;
        }

        /** lower_snake_case id used in task args. */
        public String id() {
            return id;
        }

        /** Parses an id ({@code null}/blank → NONE). */
        public static Mirror parse(String s) {
            if (s == null || s.isBlank()) {
                return NONE;
            }
            String n = s.trim().toLowerCase(Locale.ROOT);
            for (Mirror m : values()) {
                if (m.id.equals(n)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("unknown mirror: " + s);
        }
    }

    public SchematicTransform {
        Objects.requireNonNull(origin, "origin");
        rotation = normalizeRotation(rotation);
        mirror = mirror == null ? Mirror.NONE : mirror;
        if (width < 1 || height < 1 || length < 1) {
            throw new IllegalArgumentException("schematic sizes must be positive");
        }
    }

    /** Transform for a loaded schematic; {@code mirror} is an id like {@code "front_back"}. */
    public static SchematicTransform of(Schematic s, Pos origin, int rotation, String mirror) {
        return new SchematicTransform(origin, rotation, Mirror.parse(mirror), s.width(), s.height(), s.length());
    }

    /** Maps any multiple of 90 (negative allowed) to 0, 90, 180 or 270. */
    public static int normalizeRotation(int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("rotation must be a multiple of 90: " + degrees);
        }
        return Math.floorMod(degrees, 360);
    }

    /** Vanilla {@code Rotation} constant name: NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90. */
    public String mcRotationName() {
        return switch (rotation) {
            case 90 -> "CLOCKWISE_90";
            case 180 -> "CLOCKWISE_180";
            case 270 -> "COUNTERCLOCKWISE_90";
            default -> "NONE";
        };
    }

    /** World position of a local schematic position. */
    public Pos toWorld(int x, int y, int z) {
        int mx = mirror == Mirror.FRONT_BACK ? -x : x;
        int mz = mirror == Mirror.LEFT_RIGHT ? -z : z;
        int rx;
        int rz;
        switch (rotation) {
            case 90 -> {
                rx = -mz;
                rz = mx;
            }
            case 180 -> {
                rx = -mx;
                rz = -mz;
            }
            case 270 -> {
                rx = mz;
                rz = -mx;
            }
            default -> {
                rx = mx;
                rz = mz;
            }
        }
        return new Pos(origin.x() + rx, origin.y() + y, origin.z() + rz);
    }

    public Pos toWorld(Pos local) {
        return toWorld(local.x(), local.y(), local.z());
    }

    /** Inverse of {@link #toWorld}; the result may lie outside the schematic (check {@link #containsLocal}). */
    public Pos toLocal(int wx, int wy, int wz) {
        int rx = wx - origin.x();
        int rz = wz - origin.z();
        int mx;
        int mz;
        switch (rotation) {
            case 90 -> {
                mx = rz;
                mz = -rx;
            }
            case 180 -> {
                mx = -rx;
                mz = -rz;
            }
            case 270 -> {
                mx = -rz;
                mz = rx;
            }
            default -> {
                mx = rx;
                mz = rz;
            }
        }
        int x = mirror == Mirror.FRONT_BACK ? -mx : mx;
        int z = mirror == Mirror.LEFT_RIGHT ? -mz : mz;
        return new Pos(x, wy - origin.y(), z);
    }

    public Pos toLocal(Pos world) {
        return toLocal(world.x(), world.y(), world.z());
    }

    public boolean containsLocal(Pos local) {
        return local.x() >= 0 && local.y() >= 0 && local.z() >= 0
                && local.x() < width && local.y() < height && local.z() < length;
    }

    /** True when the world position is covered by the transformed schematic. */
    public boolean containsWorld(Pos world) {
        return containsLocal(toLocal(world));
    }

    /** World-space box covered by the transformed schematic. */
    public Box footprint() {
        return new Box(toWorld(0, 0, 0), toWorld(width - 1, height - 1, length - 1));
    }

    /** World X extent (width and length swap at 90/270). */
    public int worldSizeX() {
        return rotation == 90 || rotation == 270 ? length : width;
    }

    /** World Z extent. */
    public int worldSizeZ() {
        return rotation == 90 || rotation == 270 ? width : length;
    }
}
