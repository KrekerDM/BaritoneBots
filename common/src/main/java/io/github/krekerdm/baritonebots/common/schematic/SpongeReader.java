package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.geom.Pos;

import java.util.Map;

/**
 * Sponge schematic v1/v2 (root: Palette + BlockData) and v3 (root "Schematic" with Blocks{Palette, Data}).
 * Block indices are varints in order {@code (y * Length + z) * Width + x}.
 */
final class SpongeReader {
    private SpongeReader() {
    }

    /** True when the root looks like a Sponge schematic of any version. */
    static boolean accepts(Map<String, Object> root) {
        Map<String, Object> s = unwrap(root);
        // MCEdit/Schematica legacy files also have Width/Height/Length but store numeric ids in a "Blocks" byte array.
        return s.containsKey("Width") && s.containsKey("Height") && s.containsKey("Length")
                && !(s.get("Blocks") instanceof byte[]);
    }

    /** v3 wraps everything in a "Schematic" compound under an unnamed root. */
    private static Map<String, Object> unwrap(Map<String, Object> root) {
        Map<String, Object> inner = Nbt.optCompound(root, "Schematic");
        return inner != null ? inner : root;
    }

    static Schematic read(Map<String, Object> root) throws SchematicException {
        Map<String, Object> s = unwrap(root);
        int version = Nbt.optInt(s, "Version", 2);
        int w = Nbt.unsignedShort(s, "Width");
        int h = Nbt.unsignedShort(s, "Height");
        int l = Nbt.unsignedShort(s, "Length");
        int volume = PaletteBuilder.checkedVolume(w, h, l);

        Schematic.Format format;
        Map<String, Object> palette;
        byte[] data;
        Map<String, Object> blocks = Nbt.optCompound(s, "Blocks");
        if (version >= 3 || blocks != null) {
            format = Schematic.Format.SPONGE_V3;
            if (blocks == null) {
                // v3 allows a schematic without a block container (entities/biomes only): all air.
                return new Schematic(format, w, h, l, new PaletteBuilder().states(), new int[volume],
                        offsetV3(s), metadataName(s), Nbt.optInt(s, "DataVersion", 0));
            }
            palette = Nbt.compound(blocks, "Palette");
            data = Nbt.bytes(blocks, "Data");
        } else {
            format = version <= 1 ? Schematic.Format.SPONGE_V1 : Schematic.Format.SPONGE_V2;
            palette = Nbt.compound(s, "Palette");
            data = Nbt.bytes(s, "BlockData");
        }

        String[] byIndex = paletteByIndex(palette);
        PaletteBuilder pb = new PaletteBuilder();
        int[] ours = new int[byIndex.length];
        for (int i = 0; i < byIndex.length; i++) {
            ours[i] = byIndex[i] == null ? -1 : pb.add(byIndex[i]);
        }

        int[] indices = new int[volume];
        int p = 0;
        for (int i = 0; i < volume; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (p >= data.length) {
                    throw Nbt.corrupt("block data ends after " + i + " of " + volume + " blocks");
                }
                int b = data[p++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw Nbt.corrupt("varint too long in block data");
                }
            }
            if (value < 0 || value >= ours.length || ours[value] < 0) {
                throw Nbt.corrupt("block data references palette index " + value + " which is not in the palette");
            }
            indices[i] = ours[value];
        }
        Pos offset = format == Schematic.Format.SPONGE_V3 ? offsetV3(s) : offsetV2(s);
        return new Schematic(format, w, h, l, pb.states(), indices, offset, metadataName(s),
                Nbt.optInt(s, "DataVersion", 0));
    }

    /** Palette as an array indexed by the file's palette values; gaps are null. */
    private static String[] paletteByIndex(Map<String, Object> palette) throws SchematicException {
        int max = -1;
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            if (!(e.getValue() instanceof Number n) || n.intValue() < 0) {
                throw Nbt.corrupt("palette entry '" + e.getKey() + "' has no valid index");
            }
            max = Math.max(max, n.intValue());
        }
        if (max >= 1 << 24) {
            throw Nbt.corrupt("palette index " + max + " is implausibly large");
        }
        String[] out = new String[max + 1];
        for (Map.Entry<String, Object> e : palette.entrySet()) {
            out[((Number) e.getValue()).intValue()] = e.getKey();
        }
        return out;
    }

    /** v3 "Offset" is min corner minus the copy origin. */
    private static Pos offsetV3(Map<String, Object> s) {
        int[] o = Nbt.optInts(s, "Offset");
        return o != null && o.length == 3 ? new Pos(o[0], o[1], o[2]) : Pos.ZERO;
    }

    /** v1/v2: WorldEdit stores min-minus-origin in Metadata.WEOffset*, "Offset" holds the absolute min corner. */
    private static Pos offsetV2(Map<String, Object> s) {
        Map<String, Object> meta = Nbt.optCompound(s, "Metadata");
        if (meta != null && meta.get("WEOffsetX") instanceof Number x
                && meta.get("WEOffsetY") instanceof Number y && meta.get("WEOffsetZ") instanceof Number z) {
            return new Pos(x.intValue(), y.intValue(), z.intValue());
        }
        int[] o = Nbt.optInts(s, "Offset");
        return o != null && o.length == 3 ? new Pos(o[0], o[1], o[2]) : Pos.ZERO;
    }

    private static String metadataName(Map<String, Object> s) {
        Map<String, Object> meta = Nbt.optCompound(s, "Metadata");
        return meta == null ? null : Nbt.optString(meta, "Name");
    }
}
