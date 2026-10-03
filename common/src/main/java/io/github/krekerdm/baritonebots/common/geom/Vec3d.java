package io.github.krekerdm.baritonebots.common.geom;

/** Double-precision position (entity / player coordinates); JSON {@code {"x","y","z"}}. */
public record Vec3d(double x, double y, double z) {
    /** Block position containing this point. */
    public Pos toPos() {
        return new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public double distSq(Vec3d o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec3d o) {
        return Math.sqrt(distSq(o));
    }

    public double horizontalDistance(Vec3d o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
