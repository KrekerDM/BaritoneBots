package io.github.krekerdm.baritonebots.common.msg;

import com.google.gson.JsonObject;

/**
 * Outcome of one task, sent exactly once as {@code task_done} (SPEC §2.5). {@code reason} is a
 * {@link Reasons} code when {@code ok} is false; {@code data} carries task-specific results.
 */
public record TaskResult(String id, String type, boolean ok, String reason, String message, JsonObject data,
                         long durationMs) {

    public static TaskResult success(TaskSpec spec, String message, JsonObject data, long durationMs) {
        return new TaskResult(spec.id(), spec.type(), true, null, message, data, durationMs);
    }

    /** Failed result; a {@code null} reason becomes {@link Reasons#ERROR}. */
    public static TaskResult failure(TaskSpec spec, String reason, String message, JsonObject data, long durationMs) {
        return new TaskResult(spec.id(), spec.type(), false, reason == null ? Reasons.ERROR : reason, message, data,
                durationMs);
    }
}
