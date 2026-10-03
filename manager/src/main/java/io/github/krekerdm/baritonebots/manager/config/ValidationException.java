package io.github.krekerdm.baritonebots.manager.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Rejected settings change: {@code fields} maps a concrete path ({@code servers[1].id}) to an error code. */
public final class ValidationException extends RuntimeException {
    private final Map<String, String> fields;

    public ValidationException(Map<String, String> fields) {
        super("invalid values: " + fields);
        this.fields = new LinkedHashMap<>(fields);
    }

    public static ValidationException of(String path, String code) {
        return new ValidationException(Map.of(path, code));
    }

    public Map<String, String> fields() {
        return fields;
    }
}
