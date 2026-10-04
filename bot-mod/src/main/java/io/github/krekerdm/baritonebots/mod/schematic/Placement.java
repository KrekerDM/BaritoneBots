package io.github.krekerdm.baritonebots.mod.schematic;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform;

/**
 * Allocation-free form of {@link SchematicTransform} for per-block loops: mirror, then rotate clockwise about local
 * (0,0,0), then translate by the origin (same order and pivot, see DECISIONS "common: transform order and pivot").
 * Y is never rotated. Pure code (no Minecraft classes), unit-tested against {@link SchematicTransform}.
 */
public final class Placement {
    private final SchematicTransform transform;
    private final int ox;
    private final int oy;
    private final int oz;
    private final int rotation;
    private final boolean flipX;
    private final boolean flipZ;
    private final Box footprint;

    public Placement(SchematicTransform t) {
        this.transform = t;
        this.ox = t.origin().x();
        this.oy = t.origin().y();
        this.oz = t.origin().z();
        this.rotation = t.rotation();
        this.flipX = t.mirror() == SchematicTransform.Mirror.FRONT_BACK;
        this.flipZ = t.mirror() == SchematicTransform.Mirror.LEFT_RIGHT;
        this.footprint = t.footprint();
    }

    public SchematicTransform transform() {
        return transform;
    }

    public int worldX(int x, int z) {
        int mx = flipX ? -x : x;
        int mz = flipZ ? -z : z;
        return ox + switch (rotation) {
            case 90 -> -mz;
            case 180 -> -mx;
            case 270 -> mz;
            default -> mx;
        };
    }

    public int worldY(int y) {
        return oy + y;
    }

    public int worldZ(int x, int z) {
        int mx = flipX ? -x : x;
        int mz = flipZ ? -z : z;
        return oz + switch (rotation) {
            case 90 -> mx;
            case 180 -> -mz;
            case 270 -> -mx;
            default -> mz;
        };
    }

    public int localX(int wx, int wz) {
        int rx = wx - ox;
        int rz = wz - oz;
        int mx = switch (rotation) {
            case 90 -> rz;
            case 180 -> -rx;
            case 270 -> -rz;
            default -> rx;
        };
        return flipX ? -mx : mx;
    }

    public int localY(int wy) {
        return wy - oy;
    }

    public int localZ(int wx, int wz) {
        int rx = wx - ox;
        int rz = wz - oz;
        int mz = switch (rotation) {
            case 90 -> -rx;
            case 180 -> -rz;
            case 270 -> rx;
            default -> rz;
        };
        return flipZ ? -mz : mz;
    }

    /** World box covered by the transformed schematic. */
    public Box footprint() {
        return footprint;
    }

    /** {@code footprint ∩ mask} ({@code mask} null = whole footprint), or {@code null} when they do not overlap. */
    public Box region(Box mask) {
        if (mask == null) {
            return footprint;
        }
        return footprint.intersection(mask).orElse(null);
    }

    /** True when the world position lies in the footprint. */
    public boolean covers(int wx, int wy, int wz) {
        return footprint.contains(wx, wy, wz);
    }

    @Override
    public String toString() {
        return "Placement{origin=" + new Pos(ox, oy, oz) + ",rotation=" + rotation + ",mirror="
                + transform.mirror().id() + ",footprint=" + footprint + "}";
    }
}
