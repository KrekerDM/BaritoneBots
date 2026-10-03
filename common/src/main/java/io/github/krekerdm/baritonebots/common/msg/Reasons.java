package io.github.krekerdm.baritonebots.common.msg;

import java.util.List;

/** {@link TaskResult#reason()} codes for failed tasks (SPEC §3); the panel translates them via i18n. */
public final class Reasons {
    public static final String CANCELLED = "cancelled";
    public static final String TIMEOUT = "timeout";
    public static final String PATH_FAILED = "path_failed";
    public static final String NOT_FOUND = "not_found";
    public static final String INVENTORY_FULL = "inventory_full";
    public static final String CONTAINER_FAILED = "container_failed";
    public static final String MISSING_MATERIALS = "missing_materials";
    public static final String STUCK = "stuck";
    public static final String DIED = "died";
    public static final String DISCONNECTED = "disconnected";
    public static final String BAD_ARGS = "bad_args";
    public static final String UNSUPPORTED = "unsupported";
    public static final String ERROR = "error";

    public static final List<String> ALL = List.of(CANCELLED, TIMEOUT, PATH_FAILED, NOT_FOUND, INVENTORY_FULL,
            CONTAINER_FAILED, MISSING_MATERIALS, STUCK, DIED, DISCONNECTED, BAD_ARGS, UNSUPPORTED, ERROR);

    private Reasons() {
    }
}
