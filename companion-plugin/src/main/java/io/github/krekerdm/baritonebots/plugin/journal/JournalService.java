package io.github.krekerdm.baritonebots.plugin.journal;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * The journal as the rest of the plugin sees it: {@link #record} from the server thread, queries answered from the
 * in-memory index when it covers the window, otherwise from the files on the IO thread. Every returned future
 * completes on {@code mainThread}.
 */
public final class JournalService {
    /** The in-memory window (SPEC: "last 24 h"). */
    public static final long MEMORY_WINDOW_MS = TimeWindow.DAY_MS;

    private final JournalIndex index;
    private final JournalStore store;
    private final Executor mainThread;
    private final LongSupplier clock;

    public JournalService(JournalStore store, int perBotMemoryLimit, Executor mainThread, LongSupplier clock) {
        this.store = store;
        this.mainThread = mainThread;
        this.clock = clock;
        this.index = new JournalIndex(MEMORY_WINDOW_MS, perBotMemoryLimit, clock.getAsLong());
    }

    /** Server thread. */
    public void record(JournalRecord r) {
        index.add(r);
        store.enqueue(r);
    }

    /** Server thread; called periodically to keep the index inside its window. */
    public void prune() {
        index.prune(clock.getAsLong());
    }

    public CompletableFuture<JournalSummary> summary(String actor, long since, int keepLatest) {
        if (index.covers(actor, since)) {
            return CompletableFuture.completedFuture(index.summary(actor, since, keepLatest));
        }
        return onMain(store.summary(actor, since, keepLatest));
    }

    /** Records of {@code actor} since {@code since}, oldest first, at most {@code limit}. */
    public CompletableFuture<JournalStore.ReadResult> records(String actor, long since, int limit) {
        if (index.covers(actor, since)) {
            List<JournalRecord> all = index.query(actor, since);
            boolean truncated = all.size() > limit;
            return CompletableFuture.completedFuture(new JournalStore.ReadResult(
                    truncated ? List.copyOf(all.subList(0, limit)) : all, truncated, 0, 0));
        }
        return onMain(store.read(actor, since, limit));
    }

    private <T> CompletableFuture<T> onMain(CompletableFuture<T> f) {
        CompletableFuture<T> out = new CompletableFuture<>();
        f.whenComplete((value, error) -> {
            Runnable complete = () -> {
                if (error != null) {
                    out.completeExceptionally(error instanceof CompletionException ce && ce.getCause() != null
                            ? ce.getCause() : error);
                } else {
                    out.complete(value);
                }
            };
            try {
                mainThread.execute(complete);
            } catch (RuntimeException rejected) {
                // The plugin is being disabled; nobody waits on the server thread any more.
                complete.run();
            }
        });
        return out;
    }

    public int memoryRecords() {
        return index.size();
    }

    public JournalStore store() {
        return store;
    }

    public long now() {
        return clock.getAsLong();
    }
}
