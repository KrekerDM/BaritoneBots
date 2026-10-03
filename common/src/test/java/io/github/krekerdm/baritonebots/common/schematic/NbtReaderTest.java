package io.github.krekerdm.baritonebots.common.schematic;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtReaderTest {

    /** The "hello world" example from the original NBT specification, written byte by byte. */
    @Test
    void readsHandWrittenBytes() throws IOException {
        byte[] bytes = {
                0x0a, 0x00, 0x0b, 'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd',
                0x08, 0x00, 0x04, 'n', 'a', 'm', 'e', 0x00, 0x09, 'B', 'a', 'n', 'a', 'n', 'r', 'a', 'm', 'a',
                0x00
        };
        NbtReader.Named named = NbtReader.readNamed(new ByteArrayInputStream(bytes));
        assertEquals("hello world", named.name());
        assertEquals(Map.of("name", "Bananrama"), named.value());
    }

    @Test
    void readsHandWrittenNumericArraysAndLists() throws IOException {
        byte[] bytes = {
                0x0a, 0x00, 0x00,
                // long array "L" = [1, -1]
                0x0c, 0x00, 0x01, 'L', 0x00, 0x00, 0x00, 0x02,
                0, 0, 0, 0, 0, 0, 0, 1,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                // short "S" = -2 (0xfffe)
                0x02, 0x00, 0x01, 'S', (byte) 0xff, (byte) 0xfe,
                // list "E" of TAG_End, length 0
                0x09, 0x00, 0x01, 'E', 0x00, 0x00, 0x00, 0x00, 0x00,
                // list "I" of int: [7]
                0x09, 0x00, 0x01, 'I', 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x07,
                0x00
        };
        Map<String, Object> root = NbtReader.read(new ByteArrayInputStream(bytes));
        assertArrayEquals(new long[]{1L, -1L}, (long[]) root.get("L"));
        assertEquals((short) -2, root.get("S"));
        assertEquals(List.of(), root.get("E"));
        assertEquals(List.of(7), root.get("I"));
    }

    @Test
    void roundTripsEveryTagTypeGzipZlibAndRaw() throws IOException {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("str", "Привет");
        nested.put("dbl", 2.5d);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("b", (byte) -3);
        root.put("s", (short) 300);
        root.put("i", 123456);
        root.put("l", 1L << 40);
        root.put("f", 1.5f);
        root.put("d", -0.25d);
        root.put("ba", new byte[]{1, 2, 3});
        root.put("st", "text");
        root.put("list", List.of(nested, nested));
        root.put("comp", nested);
        root.put("ia", new int[]{4, 5});
        root.put("la", new long[]{Long.MIN_VALUE, 9});

        byte[] raw = NbtWriter.write("root", root, false);
        byte[] gz = NbtWriter.write("root", root, true);
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
            d.write(raw);
        }
        for (byte[] bytes : List.of(raw, gz, z.toByteArray())) {
            NbtReader.Named named = NbtReader.readNamed(new ByteArrayInputStream(bytes));
            assertEquals("root", named.name());
            Map<String, Object> r = named.value();
            assertEquals((byte) -3, r.get("b"));
            assertEquals((short) 300, r.get("s"));
            assertEquals(123456, r.get("i"));
            assertEquals(1L << 40, r.get("l"));
            assertEquals(1.5f, r.get("f"));
            assertEquals(-0.25d, r.get("d"));
            assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) r.get("ba"));
            assertEquals("text", r.get("st"));
            List<?> list = assertInstanceOf(List.class, r.get("list"));
            assertEquals(2, list.size());
            assertEquals(nested, list.get(1));
            assertEquals("Привет", ((Map<?, ?>) r.get("comp")).get("str"));
            assertArrayEquals(new int[]{4, 5}, (int[]) r.get("ia"));
            assertArrayEquals(new long[]{Long.MIN_VALUE, 9}, (long[]) r.get("la"));
            assertEquals(List.copyOf(root.keySet()), List.copyOf(r.keySet()), "compound order is kept");
        }
    }

    @Test
    void rejectsNonCompoundRootAndTruncatedData() {
        assertThrows(IOException.class, () -> NbtReader.read(new ByteArrayInputStream(new byte[]{0x08, 0, 0, 0, 0})));
        byte[] truncated = {0x0a, 0x00, 0x00, 0x03, 0x00, 0x01, 'x', 0x00};
        assertThrows(IOException.class, () -> NbtReader.read(new ByteArrayInputStream(truncated)));
        byte[] negativeArray = {0x0a, 0x00, 0x00, 0x07, 0x00, 0x01, 'a', (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x00};
        IOException e = assertThrows(IOException.class, () -> NbtReader.read(new ByteArrayInputStream(negativeArray)));
        assertTrue(e.getMessage().contains("length"));
    }
}
