package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * A task sent to a bot (SPEC §2.5): {@code type} from {@link TaskTypes}, task-specific {@code args},
 * {@code timeoutSec} (0 = none), optional {@code label} and {@code origin}
 * ({@code panel}, {@code scenario:<runId>}, {@code project:<id>}).
 */
public record TaskSpec(String id, String type, JsonObject args, int timeoutSec, String label, String origin) {
    public static final String ORIGIN_PANEL = "panel";
    public static final String ORIGIN_SCENARIO_PREFIX = "scenario:";
    public static final String ORIGIN_PROJECT_PREFIX = "project:";

    public TaskSpec {
        Objects.requireNonNull(type, "type");
        if (args == null) {
            args = new JsonObject();
        }
        if (timeoutSec < 0) {
            timeoutSec = 0;
        }
    }

    public static TaskSpec of(String id, String type, JsonObject args) {
        return new TaskSpec(id, type, args, 0, null, null);
    }

    public static String scenarioOrigin(String runId) {
        return ORIGIN_SCENARIO_PREFIX + runId;
    }

    public static String projectOrigin(String projectId) {
        return ORIGIN_PROJECT_PREFIX + projectId;
    }
}
