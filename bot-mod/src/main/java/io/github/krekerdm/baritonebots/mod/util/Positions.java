package io.github.krekerdm.baritonebots.mod.util;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.geom.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Conversions between common geometry records and Minecraft positions. */
public final class Positions {
    private Positions() {
    }

    public static BlockPos toBlockPos(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    public static Pos toPos(BlockPos p) {
        return new Pos(p.getX(), p.getY(), p.getZ());
    }

    public static Vec3d toVec3d(Vec3 v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    /** {@code {"x","y","z"}} of a block position. */
    public static JsonObject json(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }

    public static Vec3 center(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }
}
