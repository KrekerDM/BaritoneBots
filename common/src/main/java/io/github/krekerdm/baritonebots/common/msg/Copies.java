package io.github.krekerdm.baritonebots.common.msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Defensive copies for record components; Gson hands records mutable (and possibly null) collections. */
final class Copies {
    private Copies() {
    }

    /** Unmodifiable copy without null elements; {@code null} → empty. */
    static <T> List<T> list(List<T> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        return in.stream().filter(Objects::nonNull).toList();
    }

    /** Unmodifiable copy that keeps null elements (e.g. empty armor slots); {@code null} → empty. */
    static <T> List<T> listWithNulls(List<T> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(in));
    }

    /** Unmodifiable insertion-ordered copy without null keys or values; {@code null} → empty. */
    static <K, V> Map<K, V> map(Map<K, V> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<K, V> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return Collections.unmodifiableMap(out);
    }
}
