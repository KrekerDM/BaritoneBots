package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Litematica {@code .litematic}: several regions, each with a Position and a Size relative to the schematic
 * origin. A negative size component means the region extends from Position in the negative direction.
 * Region blocks are a packed long array (bits per entry = max(2, ceil(log2(paletteSize))), entries may span two
 * longs) in order {@code (y * sizeZ + z) * sizeX + x} relative to the region's minimum corner.
 */
final class LitematicaReader {
    private LitematicaReader() {
    }

    static boolean accepts(Map<String, Object> root) {
        return root.get("Regions") instanceof Map;
    }

    private record Region(String name, Pos min, int sx, int sy, int sz, String[] palette, long[] states, int bits) {
    }

    static Schematic read(Map<String, Object> root) throws SchematicException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        List<Region> parsed = new ArrayList<>();
        for (Map.Entry<String, Object> e : regions.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?>)) {
                throw Nbt.corrupt("region '" + e.getKey() + "' is not a compound");
            }
            @SuppressWarnings("unchecked")
            Region r = region(e.getKey(), (Map<String, Object>) e.getValue());
            if (r != null) {
                parsed.add(r);
            }
        }
        if (parsed.isEmpty()) {
            throw Nbt.corrupt("litematic has no non-empty regions");
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Region r : parsed) {
            minX = Math.min(minX, r.min.x());
            minY = Math.min(minY, r.min.y());
            minZ = Math.min(minZ, r.min.z());
            maxX = Math.max(maxX, r.min.x() + r.sx - 1);
            maxY = Math.max(maxY, r.min.y() + r.sy - 1);
            maxZ = Math.max(maxZ, r.min.z() + r.sz - 1);
        }
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int l = maxZ - minZ + 1;
        int[] indices = new int[PaletteBuilder.checkedVolume(w, h, l)];
        PaletteBuilder pb = new PaletteBuilder();

        for (Region r : parsed) {
            int[] ours = new int[r.palette.length];
            boolean[] air = new boolean[r.palette.length];
            for (int i = 0; i < ours.length; i++) {
                ours[i] = pb.add(r.palette[i]);
                air[i] = Ids.isAir(r.palette[i]);
            }
            long mask = (1L << r.bits) - 1;
            int ox = r.min.x() - minX;
            int oy = r.min.y() - minY;
            int oz = r.min.z() - minZ;
            long i = 0;
            for (int y = 0; y < r.sy; y++) {
                for (int z = 0; z < r.sz; z++) {
                    for (int x = 0; x < r.sx; x++, i++) {
                        int v = (int) get(r.states, i, r.bits, mask);
                        if (v >= ours.length) {
                            throw Nbt.corrupt("region '" + r.name + "' references palette index " + v
                                    + " of " + ours.length);
                        }
                        // Overlapping regions: a later region's air never erases an earlier region's block.
                        if (!air[v]) {
                            indices[((oy + y) * l + (oz + z)) * w + (ox + x)] = ours[v];
                        }
                    }
                }
            }
        }
        Map<String, Object> meta = Nbt.optCompound(root, "Metadata");
        String name = meta == null ? null : Nbt.optString(meta, "Name");
        return new Schematic(Schematic.Format.LITEMATICA, w, h, l, pb.states(), indices, new Pos(minX, minY, minZ),
                name, Nbt.optInt(root, "MinecraftDataVersion", 0));
    }

    private static Region region(String name, Map<String, Object> m) throws SchematicException {
        Pos pos = vec(Nbt.compound(m, "Position"));
        Pos size = vec(Nbt.compound(m, "Size"));
        if (size.x() == 0 || size.y() == 0 || size.z() == 0) {
            return null;
        }
        Pos min = new Pos(
                pos.x() + (size.x() < 0 ? size.x() + 1 : 0),
                pos.y() + (size.y() < 0 ? size.y() + 1 : 0),
                pos.z() + (size.z() < 0 ? size.z() + 1 : 0));
        int sx = Math.abs(size.x());
        int sy = Math.abs(size.y());
        int sz = Math.abs(size.z());
        long volume = PaletteBuilder.checkedVolume(sx, sy, sz);

        List<Object> paletteTags = Nbt.list(m, "BlockStatePalette");
        if (paletteTags.isEmpty()) {
            throw Nbt.corrupt("region '" + name + "' has an empty palette");
        }
        String[] palette = new String[paletteTags.size()];
        for (int i = 0; i < palette.length; i++) {
            if (!(paletteTags.get(i) instanceof Map<?, ?>)) {
                throw Nbt.corrupt("region '" + name + "' palette entry " + i + " is not a compound");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) paletteTags.get(i);
            palette[i] = state(entry, name, i);
        }
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.length - 1));
        long[] states = Nbt.longs(m, "BlockStates");
        long needed = (volume * bits + 63) / 64;
        if (states.length < needed) {
            throw Nbt.corrupt("region '" + name + "' has " + states.length + " block-state longs, needs " + needed);
        }
        return new Region(name, min, sx, sy, sz, palette, states, bits);
    }

    private static String state(Map<String, Object> entry, String region, int i) throws SchematicException {
        String id = Nbt.optString(entry, "Name");
        if (id == null || id.isBlank()) {
            throw Nbt.corrupt("region '" + region + "' palette entry " + i + " has no Name");
        }
        Map<String, Object> props = Nbt.optCompound(entry, "Properties");
        if (props == null || props.isEmpty()) {
            return Ids.normalize(id);
        }
        Map<String, String> p = new LinkedHashMap<>();
        props.forEach((k, v) -> p.put(k, String.valueOf(v)));
        return Ids.withProperties(id, p);
    }

    private static Pos vec(Map<String, Object> m) throws SchematicException {
        return new Pos(Nbt.integer(m, "x"), Nbt.integer(m, "y"), Nbt.integer(m, "z"));
    }

    /** Litematica's LitematicaBitArray#getAt: little-endian bit packing, entries may cross a long boundary. */
    static long get(long[] data, long index, int bits, long mask) {
        long startOffset = index * bits;
        int startArr = (int) (startOffset >>> 6);
        int endArr = (int) (((index + 1) * bits - 1) >>> 6);
        int startBit = (int) (startOffset & 63);
        if (startArr == endArr) {
            return (data[startArr] >>> startBit) & mask;
        }
        return ((data[startArr] >>> startBit) | (data[endArr] << (64 - startBit))) & mask;
    }
}
