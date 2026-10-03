package io.github.krekerdm.baritonebots.common.schematic;

import java.io.IOException;

/** A schematic that cannot be loaded; {@link #reason()} is a code the panel can translate. */
public final class SchematicException extends IOException {
    /** Not a known schematic format (or a legacy format with numeric ids). */
    public static final String UNSUPPORTED = "unsupported";
    /** Known format but inconsistent data (missing tags, bad palette indices, truncated arrays). */
    public static final String CORRUPT = "corrupt";
    /** Volume too large to hold in memory. */
    public static final String TOO_LARGE = "too_large";

    private final String reason;

    public SchematicException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
