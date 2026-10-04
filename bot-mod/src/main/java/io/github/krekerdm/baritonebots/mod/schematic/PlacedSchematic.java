package io.github.krekerdm.baritonebots.mod.schematic;

import baritone.api.schematic.ISchematic;
import io.github.krekerdm.baritonebots.common.geom.Box;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The transformed schematic as Baritone's builder sees it: a box-shaped schematic covering {@code region}
 * (footprint ∩ mask, world space), built with {@code IBuilderProcess.build(name, this, region.min)}. Coordinates are
 * mapped back to the source schematic with {@link Placement} (mirror, then clockwise rotation about local 0,0,0 —
 * the same as common's {@code SchematicTransform}); block states are mirrored then rotated. Cropping to the mask box
 * makes positions outside it "don't care" for Baritone (it only looks inside the schematic box), which for an
 * axis-aligned box is exactly what a {@code MaskSchematic} would do, without Baritone scanning the rest.
 * <p>
 * Thread-safe: Baritone also calls {@link #desiredState} from its path-calculation thread.
 */
public final class PlacedSchematic implements ISchematic {
    private final ISchematic source;
    private final Placement placement;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Mirror mirror;
    private final Rotation rotation;
    private final Rotation inverse;
    private final ConcurrentHashMap<BlockState, BlockState> placed = new ConcurrentHashMap<>();

    public PlacedSchematic(ISchematic source, Placement placement, Box region) {
        this.source = source;
        this.placement = placement;
        this.baseX = region.min().x();
        this.baseY = region.min().y();
        this.baseZ = region.min().z();
        this.sizeX = region.width();
        this.sizeY = region.height();
        this.sizeZ = region.length();
        this.mirror = StateTable.mcMirror(placement.transform().mirror());
        this.rotation = StateTable.mcRotation(placement.transform());
        this.inverse = switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return false;
        }
        int wx = baseX + x;
        int wz = baseZ + z;
        return source.inSchematic(placement.localX(wx, wz), placement.localY(baseY + y), placement.localZ(wx, wz),
                unplace(current));
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        int wx = baseX + x;
        int wz = baseZ + z;
        BlockState s = source.desiredState(placement.localX(wx, wz), placement.localY(baseY + y),
                placement.localZ(wx, wz), unplace(current), approxPlaceable);
        if (s == null) {
            return null;
        }
        BlockState p = placed.get(s);
        if (p == null) {
            p = s.mirror(mirror).rotate(rotation);
            placed.putIfAbsent(s, p);
        }
        return p;
    }

    /** World-orientation state back into schematic orientation (inverse of mirror-then-rotate). */
    private BlockState unplace(BlockState current) {
        if (current == null || (rotation == Rotation.NONE && mirror == Mirror.NONE)) {
            return current;
        }
        return current.rotate(inverse).mirror(mirror);
    }

    @Override
    public void reset() {
        source.reset();
    }

    @Override
    public int widthX() {
        return sizeX;
    }

    @Override
    public int heightY() {
        return sizeY;
    }

    @Override
    public int lengthZ() {
        return sizeZ;
    }
}
