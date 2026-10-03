package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable block grid of a loaded schematic in local coordinates {@code 0..width-1 × 0..height-1 × 0..length-1}
 * (x, y, z), with local (0,0,0) at the minimum corner of the whole schematic — the same convention Baritone
 * uses for its build origin. Block states are canonical strings (see {@link Ids#canonicalState}).
 */
public final class Schematic {
    /** Source file format. */
    public enum Format {
        SPONGE_V1("sponge_v1"), SPONGE_V2("sponge_v2"), SPONGE_V3("sponge_v3"), LITEMATICA("litematica");

        private final String id;

        Format(String id) {
            this.id = id;
        }

        /** lower_snake_case id for JSON. */
        public String id() {
            return id;
        }
    }

    private final Format format;
    private final int width;
    private final int height;
    private final int length;
    private final List<String> palette;
    private final int[] blocks;
    private final Pos offset;
    private final String name;
    private final int dataVersion;
    private final int[] paletteCounts;
    private final long nonAir;

    /**
     * @param palette block states; {@code indices} reference it, in order {@code (y * length + z) * width + x}
     * @param indices taken over without copying (schematics can be large); the caller must not modify it
     * @param offset  position of local (0,0,0) relative to the file's own origin (WorldEdit copy origin,
     *                Litematica placement origin); informational
     */
    public Schematic(Format format, int width, int height, int length, List<String> palette, int[] indices,
                     Pos offset, String name, int dataVersion) {
        if (width < 1 || height < 1 || length < 1) {
            throw new IllegalArgumentException("schematic sizes must be positive");
        }
        if ((long) width * height * length != indices.length) {
            throw new IllegalArgumentException("indices length does not match the size");
        }
        this.format = Objects.requireNonNull(format, "format");
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = List.copyOf(palette);
        this.blocks = indices;
        this.offset = offset == null ? Pos.ZERO : offset;
        this.name = name;
        this.dataVersion = dataVersion;
        this.paletteCounts = new int[this.palette.size()];
        for (int idx : indices) {
            if (idx < 0 || idx >= paletteCounts.length) {
                throw new IllegalArgumentException("palette index out of range: " + idx);
            }
            paletteCounts[idx]++;
        }
        long count = 0;
        for (int i = 0; i < paletteCounts.length; i++) {
            if (!Ids.isAir(this.palette.get(i))) {
                count += paletteCounts[i];
            }
        }
        this.nonAir = count;
    }

    public Format format() {
        return format;
    }

    /** Size along X. */
    public int width() {
        return width;
    }

    /** Size along Y. */
    public int height() {
        return height;
    }

    /** Size along Z. */
    public int length() {
        return length;
    }

    public long volume() {
        return blocks.length;
    }

    /** Distinct block states, some of which may be unused. */
    public List<String> palette() {
        return palette;
    }

    /** See the constructor; {@link Pos#ZERO} when the file has no origin information. */
    public Pos offset() {
        return offset;
    }

    /** Name stored in the file (Litematica / Sponge metadata), or {@code null}. */
    public String name() {
        return name;
    }

    /** Minecraft data version the file was saved with, 0 when absent. */
    public int dataVersion() {
        return dataVersion;
    }

    /** Local bounds {@code (0,0,0)..(width-1,height-1,length-1)}. */
    public Box bounds() {
        return Box.ofSize(Pos.ZERO, width, height, length);
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < width && y < height && z < length;
    }

    /** Palette index at a local position. */
    public int paletteIndexAt(int x, int y, int z) {
        if (!contains(x, y, z)) {
            throw new IndexOutOfBoundsException("outside schematic: " + x + " " + y + " " + z);
        }
        return blocks[(y * length + z) * width + x];
    }

    /** Block state at a local position; {@code minecraft:air} (or cave/void air) for empty cells. */
    public String blockAt(int x, int y, int z) {
        return palette.get(paletteIndexAt(x, y, z));
    }

    /** Number of cells that are not air. */
    public long nonAirCount() {
        return nonAir;
    }

    /** Non-air block counts by full block state, sorted by state. */
    public Map<String, Integer> countsByState() {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < paletteCounts.length; i++) {
            String s = palette.get(i);
            if (paletteCounts[i] > 0 && !Ids.isAir(s)) {
                out.merge(s, paletteCounts[i], Integer::sum);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Non-air block counts by block id (state stripped), sorted by id. These are blocks, not items. */
    public Map<String, Integer> countsByBlockId() {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < paletteCounts.length; i++) {
            String s = palette.get(i);
            if (paletteCounts[i] > 0 && !Ids.isAir(s)) {
                out.merge(Ids.stripState(s), paletteCounts[i], Integer::sum);
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
