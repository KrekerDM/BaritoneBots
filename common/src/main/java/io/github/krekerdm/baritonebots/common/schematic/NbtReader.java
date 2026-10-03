package io.github.krekerdm.baritonebots.common.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal Java-edition NBT reader. Compression (gzip, zlib or none) is detected from the first bytes.
 * Tags become plain Java values: Byte, Short, Integer, Long, Float, Double, byte[], String, List&lt;Object&gt;,
 * Map&lt;String,Object&gt; (insertion-ordered), int[], long[].
 */
public final class NbtReader {
    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    /** Same nesting limit as vanilla. */
    public static final int MAX_DEPTH = 512;
    /** Guards against corrupt lengths allocating gigabytes before the stream runs out. */
    public static final int MAX_ARRAY_LENGTH = 1 << 27;

    /** Root tag name and its compound value. */
    public record Named(String name, Map<String, Object> value) {
    }

    private final DataInputStream in;

    private NbtReader(DataInputStream in) {
        this.in = in;
    }

    /** Reads the root compound of a file. */
    public static Map<String, Object> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    /** Reads the root compound; the stream is not closed. */
    public static Map<String, Object> read(InputStream in) throws IOException {
        return readNamed(in).value();
    }

    /** Reads the root tag with its name; the stream is not closed. */
    public static Named readNamed(InputStream raw) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(decompress(raw), 64 * 1024));
        NbtReader r = new NbtReader(data);
        int type = data.readUnsignedByte();
        if (type != TAG_COMPOUND) {
            throw new IOException("NBT root is not a compound (tag type " + type + ")");
        }
        String name = data.readUTF();
        return new Named(name, r.readCompound(1));
    }

    private static InputStream decompress(InputStream raw) throws IOException {
        BufferedInputStream b = new BufferedInputStream(raw, 8192);
        b.mark(2);
        int b0 = b.read();
        int b1 = b.read();
        b.reset();
        if (b0 == 0x1f && b1 == 0x8b) {
            return new GZIPInputStream(b, 64 * 1024);
        }
        // zlib header: CMF 0x78 and (CMF*256 + FLG) divisible by 31; a raw NBT stream starts with 0x0a instead.
        if (b0 == 0x78 && b1 >= 0 && ((b0 << 8) | b1) % 31 == 0) {
            return new InflaterInputStream(b);
        }
        return b;
    }

    private Object readPayload(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        }
        return switch (type) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT -> in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> in.readFloat();
            case TAG_DOUBLE -> in.readDouble();
            case TAG_BYTE_ARRAY -> {
                byte[] a = new byte[length()];
                in.readFully(a);
                yield a;
            }
            case TAG_STRING -> in.readUTF();
            case TAG_LIST -> readList(depth);
            case TAG_COMPOUND -> readCompound(depth);
            case TAG_INT_ARRAY -> {
                int[] a = new int[length()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                yield a;
            }
            case TAG_LONG_ARRAY -> {
                long[] a = new long[length()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                yield a;
            }
            default -> throw new IOException("unknown NBT tag type " + type);
        };
    }

    private int length() throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_ARRAY_LENGTH) {
            throw new IOException("bad NBT length " + len);
        }
        return len;
    }

    private List<Object> readList(int depth) throws IOException {
        int elementType = in.readUnsignedByte();
        int len = in.readInt();
        if (len <= 0) {
            return new ArrayList<>();
        }
        if (len > MAX_ARRAY_LENGTH) {
            throw new IOException("bad NBT list length " + len);
        }
        if (elementType == TAG_END) {
            throw new IOException("non-empty NBT list of TAG_End");
        }
        List<Object> out = new ArrayList<>(Math.min(len, 4096));
        for (int i = 0; i < len; i++) {
            out.add(readPayload(elementType, depth + 1));
        }
        return out;
    }

    private Map<String, Object> readCompound(int depth) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return out;
            }
            String name = in.readUTF();
            out.put(name, readPayload(type, depth + 1));
        }
    }
}
