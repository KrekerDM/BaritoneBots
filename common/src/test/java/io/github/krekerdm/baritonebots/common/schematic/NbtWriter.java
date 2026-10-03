package io.github.krekerdm.baritonebots.common.schematic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Test-only NBT writer: Java values → tag types by class, the inverse of {@link NbtReader}. */
final class NbtWriter {
    private NbtWriter() {
    }

    static byte[] write(String rootName, Map<String, Object> root, boolean gzip) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream sink = gzip ? new GZIPOutputStream(bytes) : bytes;
        try (DataOutputStream out = new DataOutputStream(sink)) {
            out.writeByte(NbtReader.TAG_COMPOUND);
            out.writeUTF(rootName);
            writePayload(out, root);
        }
        return bytes.toByteArray();
    }

    static int typeOf(Object v) {
        return switch (v) {
            case Byte b -> NbtReader.TAG_BYTE;
            case Short s -> NbtReader.TAG_SHORT;
            case Integer i -> NbtReader.TAG_INT;
            case Long l -> NbtReader.TAG_LONG;
            case Float f -> NbtReader.TAG_FLOAT;
            case Double d -> NbtReader.TAG_DOUBLE;
            case byte[] a -> NbtReader.TAG_BYTE_ARRAY;
            case String s -> NbtReader.TAG_STRING;
            case List<?> l -> NbtReader.TAG_LIST;
            case Map<?, ?> m -> NbtReader.TAG_COMPOUND;
            case int[] a -> NbtReader.TAG_INT_ARRAY;
            case long[] a -> NbtReader.TAG_LONG_ARRAY;
            default -> throw new IllegalArgumentException("no NBT type for " + v.getClass());
        };
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case byte[] a -> {
                out.writeInt(a.length);
                out.write(a);
            }
            case String s -> out.writeUTF(s);
            case List<?> list -> {
                out.writeByte(list.isEmpty() ? NbtReader.TAG_END : typeOf(list.getFirst()));
                out.writeInt(list.size());
                for (Object o : list) {
                    writePayload(out, o);
                }
            }
            case Map<?, ?> m -> {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(NbtReader.TAG_END);
            }
            case int[] a -> {
                out.writeInt(a.length);
                for (int i : a) {
                    out.writeInt(i);
                }
            }
            case long[] a -> {
                out.writeInt(a.length);
                for (long l : a) {
                    out.writeLong(l);
                }
            }
            default -> throw new IllegalArgumentException("no NBT type for " + v.getClass());
        }
    }

    /** Litematica-style packing (LitematicaBitArray#setAt), independent of the reader. */
    static long[] packLitematica(int[] values, int bits) {
        long[] arr = new long[(int) (((long) values.length * bits + 63) / 64)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            long v = values[i] & mask;
            long start = (long) i * bits;
            int si = (int) (start >> 6);
            int ei = (int) (((long) (i + 1) * bits - 1) >> 6);
            int sb = (int) (start & 63);
            arr[si] = arr[si] & ~(mask << sb) | (v << sb);
            if (si != ei) {
                int endOffset = 64 - sb;
                int spill = bits - endOffset;
                arr[ei] = (arr[ei] >>> spill << spill) | (v >> endOffset);
            }
        }
        return arr;
    }
}
