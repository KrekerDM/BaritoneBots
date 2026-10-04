package io.github.krekerdm.baritonebots.manager.planner;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One bot working on one {@link WorkItem}. Loop-owned; sources keep their per-assignment state in {@link #ctx}. */
public final class Assignment {
    /** A finished queue entry of the current batch. */
    public record Result(QueueEntry entry, boolean ok, String reason, String message, JsonObject data) {
        public String type() {
            return entry.type();
        }
    }

    public final String botId;
    public final WorkSource source;
    public final WorkItem item;
    public final long since;
    /** Free text for the panel ("restock", "build", "verify 2/4", ...). */
    public String phase = "start";
    public long phaseSince;
    /** Source-specific state. */
    public Object ctx;
    final Set<String> locks = new LinkedHashSet<>();
    final List<Result> batch = new ArrayList<>();
    boolean batchOpen;
    CompletableFuture<?> query;
    boolean ended;
    /** Items this work will deliver (counted as "in transit" by deficit calculations). */
    public final Map<String, Integer> promised = new LinkedHashMap<>();
    /** Container lock key → items this assignment is about to take there. */
    public final Map<String, Map<String, Integer>> reserved = new LinkedHashMap<>();

    Assignment(String botId, WorkSource source, WorkItem item, long now) {
        this.botId = botId;
        this.source = source;
        this.item = item;
        this.since = now;
        this.phaseSince = now;
    }

    public boolean ended() {
        return ended;
    }

    public boolean waitingForBatch() {
        return batchOpen;
    }

    public void phase(String p) {
        phase = p;
        phaseSince = System.currentTimeMillis();
    }

    public JsonObject view() {
        return Json.obj("botId", botId, "role", item.role(), "workItem", item.view(), "since", since,
                "phase", phase, "phaseSince", phaseSince);
    }
}
