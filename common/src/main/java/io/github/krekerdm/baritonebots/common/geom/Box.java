package io.github.krekerdm.baritonebots.common.geom;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inclusive block box; JSON {@code {"a":pos,"b":pos}}. The constructor normalises the corners so that
 * {@code a} is the minimum and {@code b} the maximum corner, whatever order they were given in.
 */
public record Box(Pos a, Pos b) {
    /** Axis used by {@link #split(int, Axis, int)}. */
    public enum Axis { X, Y, Z }

    public Box {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Pos min = new Pos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        Pos max = new Pos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        a = min;
        b = max;
    }

    /** Box starting at {@code min} with the given positive sizes. */
    public static Box ofSize(Pos min, int width, int height, int length) {
        if (width < 1 || height < 1 || length < 1) {
            throw new IllegalArgumentException("box sizes must be positive");
        }
        return new Box(min, min.offset(width - 1, height - 1, length - 1));
    }

    /** Reads {@code {"a":pos,"b":pos}}; returns {@code null} when malformed. */
    public static Box fromJson(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        Pos pa = Pos.fromJson(o.get("a"));
        Pos pb = Pos.fromJson(o.get("b"));
        return pa == null || pb == null ? null : new Box(pa, pb);
    }

    public Pos min() {
        return a;
    }

    public Pos max() {
        return b;
    }

    /** Size along X. */
    public int width() {
        return b.x() - a.x() + 1;
    }

    /** Size along Y. */
    public int height() {
        return b.y() - a.y() + 1;
    }

    /** Size along Z. */
    public int length() {
        return b.z() - a.z() + 1;
    }

    public int size(Axis axis) {
        return switch (axis) {
            case X -> width();
            case Y -> height();
            case Z -> length();
        };
    }

    public long volume() {
        return (long) width() * height() * length();
    }

    public Vec3d center() {
        return new Vec3d((a.x() + b.x() + 1) / 2.0, (a.y() + b.y() + 1) / 2.0, (a.z() + b.z() + 1) / 2.0);
    }

    public boolean contains(int x, int y, int z) {
        return x >= a.x() && x <= b.x() && y >= a.y() && y <= b.y() && z >= a.z() && z <= b.z();
    }

    public boolean contains(Pos p) {
        return contains(p.x(), p.y(), p.z());
    }

    /** True when {@code other} lies completely inside this box. */
    public boolean contains(Box other) {
        return contains(other.a) && contains(other.b);
    }

    public boolean intersects(Box o) {
        return a.x() <= o.b.x() && b.x() >= o.a.x()
                && a.y() <= o.b.y() && b.y() >= o.a.y()
                && a.z() <= o.b.z() && b.z() >= o.a.z();
    }

    /** Overlapping part of both boxes, empty when they do not intersect. */
    public Optional<Box> intersection(Box o) {
        if (!intersects(o)) {
            return Optional.empty();
        }
        return Optional.of(new Box(
                new Pos(Math.max(a.x(), o.a.x()), Math.max(a.y(), o.a.y()), Math.max(a.z(), o.a.z())),
                new Pos(Math.min(b.x(), o.b.x()), Math.min(b.y(), o.b.y()), Math.min(b.z(), o.b.z()))));
    }

    /** Smallest box containing both boxes. */
    public Box union(Box o) {
        return new Box(
                new Pos(Math.min(a.x(), o.a.x()), Math.min(a.y(), o.a.y()), Math.min(a.z(), o.a.z())),
                new Pos(Math.max(b.x(), o.b.x()), Math.max(b.y(), o.b.y()), Math.max(b.z(), o.b.z())));
    }

    /** Grows (or shrinks with a negative amount) every side by {@code n}; never inverts the box. */
    public Box expand(int n) {
        Pos lo = a.offset(-n, -n, -n);
        Pos hi = b.offset(n, n, n);
        if (lo.x() > hi.x() || lo.y() > hi.y() || lo.z() > hi.z()) {
            Pos c = center().toPos();
            return new Box(c, c);
        }
        return new Box(lo, hi);
    }

    /** The longer horizontal axis (X on a tie). */
    public Axis longerHorizontalAxis() {
        return length() > width() ? Axis.Z : Axis.X;
    }

    /** {@link #split(int, Axis, int)} along {@link #longerHorizontalAxis()}. */
    public List<Box> split(int n, int minWidth) {
        return split(n, longerHorizontalAxis(), minWidth);
    }

    /**
     * Cuts the box into up to {@code n} consecutive slabs along {@code axis}, ordered by increasing coordinate.
     * Fewer slabs are returned when {@code n} slabs would be narrower than {@code minWidth}
     * (always at least one); widths differ by at most one block.
     */
    public List<Box> split(int n, Axis axis, int minWidth) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        int total = size(axis);
        int count = Math.min(n, Math.max(1, total / Math.max(1, minWidth)));
        int base = total / count;
        int rem = total % count;
        List<Box> out = new ArrayList<>(count);
        int start = switch (axis) {
            case X -> a.x();
            case Y -> a.y();
            case Z -> a.z();
        };
        for (int i = 0; i < count; i++) {
            int w = base + (i < rem ? 1 : 0);
            int end = start + w - 1;
            out.add(switch (axis) {
                case X -> new Box(new Pos(start, a.y(), a.z()), new Pos(end, b.y(), b.z()));
                case Y -> new Box(new Pos(a.x(), start, a.z()), new Pos(b.x(), end, b.z()));
                case Z -> new Box(new Pos(a.x(), a.y(), start), new Pos(b.x(), b.y(), end));
            });
            start = end + 1;
        }
        return out;
    }
}
