package io.github.krekerdm.baritonebots.common.schematic;

import io.github.krekerdm.baritonebots.common.geom.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicLoaderTest {
    private static final String STAIRS = "minecraft:oak_stairs[facing=north,half=bottom]";

    /** 2x2x2: palette indices 1 = stone, 2 = stairs (unsorted props), 200 = gold (two-byte varint). */
    private static Map<String, Object> spongeV2() {
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        palette.put("minecraft:stone", 1);
        palette.put("minecraft:oak_stairs[half=bottom,facing=north]", 2);
        palette.put("minecraft:gold_block", 200);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("WEOffsetX", -1);
        meta.put("WEOffsetY", 0);
        meta.put("WEOffsetZ", -1);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Version", 2);
        s.put("DataVersion", 4100);
        s.put("Width", (short) 2);
        s.put("Height", (short) 2);
        s.put("Length", (short) 2);
        s.put("PaletteMax", 201);
        s.put("Palette", palette);
        // order (y * L + z) * W + x
        s.put("BlockData", new byte[]{1, 0, 2, (byte) 0xC8, 0x01, 0, 0, 0, 1});
        s.put("Metadata", meta);
        s.put("Offset", new int[]{100, 64, 100});
        return s;
    }

    @Test
    void loadsSpongeV2FromGzippedFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("house.schem");
        Files.write(file, NbtWriter.write("Schematic", spongeV2(), true));
        Schematic s = SchematicLoader.load(file);

        assertEquals(Schematic.Format.SPONGE_V2, s.format());
        assertEquals(2, s.width());
        assertEquals(2, s.height());
        assertEquals(2, s.length());
        assertEquals("minecraft:stone", s.blockAt(0, 0, 0));
        assertEquals("minecraft:air", s.blockAt(1, 0, 0));
        assertEquals(STAIRS, s.blockAt(0, 0, 1));
        assertEquals("minecraft:gold_block", s.blockAt(1, 0, 1));
        assertEquals("minecraft:air", s.blockAt(0, 1, 0));
        assertEquals("minecraft:stone", s.blockAt(1, 1, 1));
        assertEquals(4, s.nonAirCount());
        assertEquals(Map.of("minecraft:stone", 2, "minecraft:oak_stairs", 1, "minecraft:gold_block", 1), s.countsByBlockId());
        assertEquals(Map.of("minecraft:stone", 2, STAIRS, 1, "minecraft:gold_block", 1), s.countsByState());
        assertEquals(new Pos(-1, 0, -1), s.offset(), "WorldEdit offset wins over the absolute Offset");
        assertEquals(4100, s.dataVersion());
        assertThrows(IndexOutOfBoundsException.class, () -> s.blockAt(2, 0, 0));
    }

    @Test
    void loadsSpongeV1AndUncompressed() throws IOException {
        Map<String, Object> v1 = spongeV2();
        v1.put("Version", 1);
        v1.remove("DataVersion");
        Schematic s = SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("Schematic", v1, false)));
        assertEquals(Schematic.Format.SPONGE_V1, s.format());
        assertEquals(STAIRS, s.blockAt(0, 0, 1));
        assertEquals(0, s.dataVersion());
    }

    @Test
    void loadsSpongeV3() throws IOException {
        Map<String, Object> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        palette.put("minecraft:oak_planks", 1);
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette);
        // 3x1x2: row z=0: planks, air, planks; row z=1: air, air, planks
        blocks.put("Data", new byte[]{1, 0, 1, 0, 0, 1});
        blocks.put("BlockEntities", List.of());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Version", 3);
        s.put("DataVersion", 4100);
        s.put("Width", (short) 3);
        s.put("Height", (short) 1);
        s.put("Length", (short) 2);
        s.put("Offset", new int[]{-2, 0, 5});
        s.put("Blocks", blocks);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Schematic", s);

        Schematic sch = SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("", root, true)));
        assertEquals(Schematic.Format.SPONGE_V3, sch.format());
        assertEquals("minecraft:oak_planks", sch.blockAt(0, 0, 0));
        assertEquals("minecraft:air", sch.blockAt(1, 0, 0));
        assertEquals("minecraft:oak_planks", sch.blockAt(2, 0, 1));
        assertEquals(3, sch.nonAirCount());
        assertEquals(new Pos(-2, 0, 5), sch.offset());
    }

    private static Map<String, Object> vec(int x, int y, int z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    private static Map<String, Object> state(String name, Map<String, Object> props) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name);
        if (props != null) {
            m.put("Properties", props);
        }
        return m;
    }

    private static int mainValue(int x, int y, int z) {
        return (x + 3 * y + 7 * z) % 5;
    }

    @Test
    void loadsLitematicWithNegativeSizeRegionAndSpanningEntries() throws IOException {
        // Region "main": 3x3x3 at the origin, palette of 5 → 3 bits; entry 21 starts at bit 63 and spans two longs.
        List<Object> mainPalette = List.of(
                state("minecraft:air", null),
                state("minecraft:stone", null),
                state("minecraft:dirt", null),
                state("minecraft:oak_log", new LinkedHashMap<>(Map.of("axis", "y"))),
                state("minecraft:glass", null));
        int[] mainValues = new int[27];
        int i = 0;
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    mainValues[i++] = mainValue(x, y, z);
                }
            }
        }
        Map<String, Object> main = new LinkedHashMap<>();
        main.put("Position", vec(0, 0, 0));
        main.put("Size", vec(3, 3, 3));
        main.put("BlockStatePalette", mainPalette);
        main.put("BlockStates", NbtWriter.packLitematica(mainValues, 3));
        main.put("TileEntities", List.of());

        // Region "neg": Position (0,0,-1), Size (2,1,-2) → occupies x 0..1, y 0, z -2..-1.
        Map<String, Object> neg = new LinkedHashMap<>();
        neg.put("Position", vec(0, 0, -1));
        neg.put("Size", vec(2, 1, -2));
        neg.put("BlockStatePalette", List.of(state("minecraft:air", null), state("minecraft:gold_block", null)));
        neg.put("BlockStates", NbtWriter.packLitematica(new int[]{1, 0, 1, 1}, 2));

        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put("main", main);
        regions.put("neg", neg);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Name", "Test");
        meta.put("EnclosingSize", vec(3, 3, 5));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("MinecraftDataVersion", 4100);
        root.put("Version", 6);
        root.put("Metadata", meta);
        root.put("Regions", regions);

        Schematic s = SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("", root, true)));
        assertEquals(Schematic.Format.LITEMATICA, s.format());
        assertEquals(3, s.width());
        assertEquals(3, s.height());
        assertEquals(5, s.length());
        assertEquals(new Pos(0, 0, -2), s.offset(), "local (0,0,0) is the enclosing min corner");
        assertEquals("Test", s.name());
        assertEquals(4100, s.dataVersion());

        assertEquals("minecraft:gold_block", s.blockAt(0, 0, 0));
        assertEquals("minecraft:air", s.blockAt(1, 0, 0));
        assertEquals("minecraft:gold_block", s.blockAt(0, 0, 1));
        assertEquals("minecraft:gold_block", s.blockAt(1, 0, 1));
        assertEquals("minecraft:air", s.blockAt(2, 0, 0));

        String[] names = {"minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:oak_log[axis=y]", "minecraft:glass"};
        long expectedNonAir = 3;
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    int v = mainValue(x, y, z);
                    assertEquals(names[v], s.blockAt(x, y, z + 2), "main region at " + x + " " + y + " " + z);
                    if (v != 0) {
                        expectedNonAir++;
                    }
                }
            }
        }
        assertEquals(expectedNonAir, s.nonAirCount());
        assertEquals(3, s.countsByBlockId().get("minecraft:gold_block"));
        assertTrue(s.countsByState().containsKey("minecraft:oak_log[axis=y]"));
    }

    @Test
    void decodesEveryEntryOfAWideBitArray() {
        int bits = 5;
        int[] values = new int[200];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i * 7 + 3) % 32;
        }
        long[] packed = NbtWriter.packLitematica(values, bits);
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], LitematicaReader.get(packed, i, bits, (1L << bits) - 1), "entry " + i);
        }
    }

    @Test
    void rejectsLegacyAndUnknownFormats() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("Width", (short) 1);
        legacy.put("Height", (short) 1);
        legacy.put("Length", (short) 1);
        legacy.put("Materials", "Alpha");
        legacy.put("Blocks", new byte[]{1});
        legacy.put("Data", new byte[]{0});
        SchematicException e = assertThrows(SchematicException.class,
                () -> SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("Schematic", legacy, true))));
        assertEquals(SchematicException.UNSUPPORTED, e.reason());

        Map<String, Object> other = new LinkedHashMap<>();
        other.put("foo", 1);
        SchematicException e2 = assertThrows(SchematicException.class,
                () -> SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("", other, false))));
        assertEquals(SchematicException.UNSUPPORTED, e2.reason());
    }

    @Test
    void rejectsCorruptSpongeData() {
        Map<String, Object> s = spongeV2();
        s.put("BlockData", new byte[]{1, 0, 2});
        SchematicException e = assertThrows(SchematicException.class,
                () -> SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("Schematic", s, false))));
        assertEquals(SchematicException.CORRUPT, e.reason());

        Map<String, Object> s2 = spongeV2();
        s2.put("BlockData", new byte[]{1, 0, 2, 5, 1, 0, 0, 0, 1});
        SchematicException e2 = assertThrows(SchematicException.class,
                () -> SchematicLoader.load(new ByteArrayInputStream(NbtWriter.write("Schematic", s2, false))));
        assertEquals(SchematicException.CORRUPT, e2.reason());
    }

    @Test
    void extensionCheck() {
        assertTrue(SchematicLoader.hasSupportedExtension("House.SCHEM"));
        assertTrue(SchematicLoader.hasSupportedExtension("a.litematic"));
        assertEquals(false, SchematicLoader.hasSupportedExtension("a.nbt"));
        assertNull(new Schematic(Schematic.Format.SPONGE_V2, 1, 1, 1, List.of("minecraft:air"), new int[1], null, null, 0).name());
    }
}
