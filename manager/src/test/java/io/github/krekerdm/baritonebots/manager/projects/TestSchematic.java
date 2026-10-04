package io.github.krekerdm.baritonebots.manager.projects;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** Writes a minimal gzipped Sponge v2 schematic filled with one block (test helper). */
final class TestSchematic {
    private TestSchematic() {
    }

    static void spongeV2(Path file, int w, int h, int l, String block) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            name(out, "Schematic");
            intTag(out, "Version", 2);
            intTag(out, "DataVersion", 4100);
            shortTag(out, "Width", w);
            shortTag(out, "Height", h);
            shortTag(out, "Length", l);
            intTag(out, "PaletteMax", 1);
            out.writeByte(10);
            name(out, "Palette");
            intTag(out, block, 0);
            out.writeByte(0);
            out.writeByte(7);
            name(out, "BlockData");
            out.writeInt(w * h * l);
            out.write(new byte[w * h * l]); // varint 0 everywhere
            out.writeByte(0);
        }
        Files.createDirectories(file.getParent());
        Files.write(file, bytes.toByteArray());
    }

    private static void name(DataOutputStream out, String n) throws IOException {
        byte[] b = n.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }

    private static void intTag(DataOutputStream out, String n, int v) throws IOException {
        out.writeByte(3);
        name(out, n);
        out.writeInt(v);
    }

    private static void shortTag(DataOutputStream out, String n, int v) throws IOException {
        out.writeByte(2);
        name(out, n);
        out.writeShort(v);
    }
}
