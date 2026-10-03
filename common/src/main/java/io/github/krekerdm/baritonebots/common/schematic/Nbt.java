package io.github.krekerdm.baritonebots.common.schematic;

import java.util.List;
import java.util.Map;

/** Typed access into {@link NbtReader} trees for the format readers; failures become {@link SchematicException}. */
final class Nbt {
    private Nbt() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> m, String key) throws SchematicException {
        Object v = m.get(key);
        if (v instanceof Map<?, ?> c) {
            return (Map<String, Object>) c;
        }
        throw missing(key, "compound");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> optCompound(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String key) throws SchematicException {
        Object v = m.get(key);
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw missing(key, "list");
    }

    static int integer(Map<String, Object> m, String key) throws SchematicException {
        if (m.get(key) instanceof Number n) {
            return n.intValue();
        }
        throw missing(key, "number");
    }

    static int optInt(Map<String, Object> m, String key, int def) {
        return m.get(key) instanceof Number n ? n.intValue() : def;
    }

    /** Sponge sizes are unsigned shorts; other integer tag types are taken as they are. */
    static int unsignedShort(Map<String, Object> m, String key) throws SchematicException {
        Object v = m.get(key);
        if (v instanceof Short s) {
            return s & 0xFFFF;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw missing(key, "short");
    }

    static String optString(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s : null;
    }

    static byte[] bytes(Map<String, Object> m, String key) throws SchematicException {
        if (m.get(key) instanceof byte[] b) {
            return b;
        }
        throw missing(key, "byte array");
    }

    static long[] longs(Map<String, Object> m, String key) throws SchematicException {
        if (m.get(key) instanceof long[] l) {
            return l;
        }
        throw missing(key, "long array");
    }

    static int[] optInts(Map<String, Object> m, String key) {
        return m.get(key) instanceof int[] a ? a : null;
    }

    static SchematicException corrupt(String message) {
        return new SchematicException(SchematicException.CORRUPT, message);
    }

    private static SchematicException missing(String key, String type) {
        return corrupt("missing or invalid " + type + " tag '" + key + "'");
    }
}
