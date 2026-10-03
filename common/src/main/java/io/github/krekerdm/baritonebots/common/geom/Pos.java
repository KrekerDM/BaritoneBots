package io.github.krekerdm.baritonebots.common.geom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Integer block position; JSON {@code {"x":int,"y":int,"z":int}}. */
public record Pos(int x, int y, int z) {
    public static final Pos ZERO = new Pos(0, 0, 0);

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Pos add(Pos o) {
        return new Pos(x + o.x, y + o.y, z + o.z);
    }

    public Pos subtract(Pos o) {
        return new Pos(x - o.x, y - o.y, z - o.z);
    }

    /** Squared euclidean distance between block coordinates. */
    public long distSq(Pos o) {
        long dx = x - o.x;
        long dy = y - o.y;
        long dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Pos o) {
        return Math.sqrt(distSq(o));
    }

    /** Horizontal (x/z) euclidean distance. */
    public double horizontalDistance(Pos o) {
        long dx = x - o.x;
        long dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vec3d center() {
        return new Vec3d(x + 0.5, y + 0.5, z + 0.5);
    }

    /** Parses {@code "x y z"} or {@code "x,y,z"} (whitespace and commas both accepted). */
    public static Pos parse(String s) {
        String[] parts = s.trim().split("[\\s,]+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected 3 coordinates: " + s);
        }
        return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** Reads {@code {"x","y","z"}} or {@code [x,y,z]}; returns {@code null} for anything else. */
    public static Pos fromJson(JsonElement e) {
        if (e == null) {
            return null;
        }
        try {
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("x") || !o.has("y") || !o.has("z")) {
                    return null;
                }
                return new Pos(floorInt(o.get("x")), floorInt(o.get("y")), floorInt(o.get("z")));
            }
            if (e.isJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                if (a.size() != 3) {
                    return null;
                }
                return new Pos(floorInt(a.get(0)), floorInt(a.get(1)), floorInt(a.get(2)));
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    private static int floorInt(JsonElement e) {
        return (int) Math.floor(e.getAsDouble());
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
