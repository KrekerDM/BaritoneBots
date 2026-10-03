package io.github.krekerdm.baritonebots.manager.tasks;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.TaskSpec;
import io.github.krekerdm.baritonebots.manager.util.Tokens;

/**
 * One queued item (SPEC §5.5): a bot task template or a manager-side step ({@code kit}, {@code wait}, ...).
 * Immutable; retries create a copy with a new id.
 *
 * @param origin      {@code panel}, {@code scenario:<runId>}, {@code project:<id>}, {@code recovery}
 * @param attempts    how often this work was dispatched before (died / link lost retries)
 * @param fullRetries inventory_full → deposit_storage retries spent on this entry
 */
public record QueueEntry(String id, String type, JsonObject args, int timeoutSec, String label, String origin,
                         long createdAt, int attempts, int fullRetries) {
    public static final String ORIGIN_RECOVERY = "recovery";

    public QueueEntry {
        args = args == null ? new JsonObject() : args;
        timeoutSec = Math.max(0, timeoutSec);
    }

    public static QueueEntry of(String type, JsonObject args, int timeoutSec, String label, String origin) {
        return new QueueEntry(Tokens.id("t"), type, args, timeoutSec, label, origin, System.currentTimeMillis(), 0, 0);
    }

    /**
     * Reads a TaskTemplate {@code {type, args?, timeoutSec?, label?}}.
     *
     * @throws IllegalArgumentException when {@code type} is missing
     */
    public static QueueEntry fromTemplate(JsonObject template, String origin) {
        String type = Json.getString(template, "type", null);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("task template without type");
        }
        JsonObject args = Json.getObj(template, "args");
        return of(type.trim(), args == null ? new JsonObject() : args.deepCopy(), Json.getInt(template, "timeoutSec", 0),
                Json.getString(template, "label", null), origin);
    }

    /** Same work again under a new id. */
    public QueueEntry retry() {
        return new QueueEntry(Tokens.id("t"), type, args, timeoutSec, label, origin, System.currentTimeMillis(),
                attempts + 1, fullRetries);
    }

    public QueueEntry withFullRetries(int n) {
        return new QueueEntry(id, type, args, timeoutSec, label, origin, createdAt, attempts, n);
    }

    public QueueEntry withArgs(JsonObject newArgs) {
        return new QueueEntry(id, type, newArgs, timeoutSec, label, origin, createdAt, attempts, fullRetries);
    }

    public boolean hasOrigin(String o) {
        return o != null && o.equals(origin);
    }

    public TaskSpec toSpec() {
        return new TaskSpec(id, type, args, timeoutSec, label, origin);
    }

    public JsonObject toJson() {
        return Json.toObject(this);
    }
}
