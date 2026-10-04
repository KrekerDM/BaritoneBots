package io.github.krekerdm.baritonebots.manager.planner;

import io.github.krekerdm.baritonebots.manager.bots.BotState;
import java.util.List;

/**
 * Anything that offers work to the {@link Planner}: a running project today; standing orders, autopilot or goals
 * later. All methods run on the manager loop.
 *
 * <p>Life of an assignment: the planner matches an idle bot to one of {@link #workItems(long)}, creates an
 * {@link Assignment} and calls {@link #begin}. The source then drives it with {@link Planner#push} (a batch of queue
 * entries with {@link #origin()}), {@link Planner#query}, {@link Planner#later}, and ends it with
 * {@link Planner#finish} or {@link Planner#fail}. When every entry of a batch has finished, the planner calls
 * {@link #onBatchDone}. {@link #onReleased} runs once for every assignment, however it ended (also when the planner
 * cancels it: manual task queued, bot offline, project paused).
 */
public interface WorkSource {
    /** Unique id (the project id). */
    String id();

    /** Queue origin of every entry this source pushes, e.g. {@code project:<id>}. */
    String origin();

    /** Server profile the work belongs to; only bots of that server are offered. */
    String serverId();

    /** May this bot work for the source at all (project bot list)? */
    boolean allows(BotState b);

    /** Work offered now. Called once per planner tick; may also update the source's own state. */
    List<WorkItem> workItems(long now);

    /** Extra per-bot check for one item (tools, inventory); default yes. */
    default boolean eligible(BotState b, WorkItem item) {
        return true;
    }

    void begin(Assignment a, BotState b);

    void onBatchDone(Assignment a, BotState b, List<Assignment.Result> results);

    /** The assignment ended ({@code why}: done, failed:&lt;reason&gt;, manual, offline, stopped, cancelled, ...). */
    void onReleased(Assignment a, String why);

    /** An item failed {@link RetryBook#MAX_FAILURES} times. */
    default void onItemFailed(WorkItem item, String reason, String message) {
    }
}
