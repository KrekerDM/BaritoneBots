package io.github.krekerdm.baritonebots.plugin.journal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * In-memory records of the last {@code windowMs} per bot, so the usual "last N minutes" questions never touch the
 * disk. Each bot's list is capped; when the cap or the window evicts records, the index remembers from which
 * time on it is still complete ({@link #covers}), and older questions go to the files. Not thread-safe: the
 * plugin uses it from the server thread only.
 */
public final class JournalIndex {
    private final long windowMs;
    private final int perActorLimit;
    private final long startedAt;
    private final Map<String, Deque<JournalRecord>> byActor = new HashMap<>();
    /** Per bot: every record with time >= this value is still in memory (later than {@link #startedAt}). */
    private final Map<String, Long> completeSince = new HashMap<>();
    private long prunedBefore = Long.MIN_VALUE;
    private int size;

    /**
     * @param startedAt time from which this index saw every record (plugin enable time); anything older is only
     *                  on disk
     */
    public JournalIndex(long windowMs, int perActorLimit, long startedAt) {
        if (windowMs <= 0 || perActorLimit <= 0) {
            throw new IllegalArgumentException("window and limit must be positive");
        }
        this.windowMs = windowMs;
        this.perActorLimit = perActorLimit;
        this.startedAt = startedAt;
    }

    public void add(JournalRecord r) {
        String key = r.actorKey();
        Deque<JournalRecord> d = byActor.computeIfAbsent(key, k -> new ArrayDeque<>());
        d.addLast(r);
        size++;
        while (d.size() > perActorLimit) {
            JournalRecord evicted = d.removeFirst();
            size--;
            completeSince.merge(key, evicted.time() + 1, Math::max);
        }
    }

    /** Drops records older than the window. */
    public void prune(long now) {
        long cutoff = now - windowMs;
        prunedBefore = Math.max(prunedBefore, cutoff);
        Iterator<Map.Entry<String, Deque<JournalRecord>>> it = byActor.entrySet().iterator();
        while (it.hasNext()) {
            Deque<JournalRecord> d = it.next().getValue();
            while (!d.isEmpty() && d.peekFirst().time() < cutoff) {
                d.removeFirst();
                size--;
            }
            if (d.isEmpty()) {
                it.remove();
            }
        }
    }

    /** True when every record of {@code actor} with time >= {@code since} is in memory. */
    public boolean covers(String actor, long since) {
        return since >= completeFrom(JournalRecord.key(actor));
    }

    private long completeFrom(String key) {
        long from = Math.max(startedAt, prunedBefore);
        Long evicted = completeSince.get(key);
        return evicted == null ? from : Math.max(from, evicted);
    }

    /** Records of {@code actor} with time >= {@code since}, oldest first. */
    public List<JournalRecord> query(String actor, long since) {
        Deque<JournalRecord> d = byActor.get(JournalRecord.key(actor));
        if (d == null || d.isEmpty()) {
            return List.of();
        }
        List<JournalRecord> out = new ArrayList<>();
        Iterator<JournalRecord> it = d.descendingIterator();
        while (it.hasNext()) {
            JournalRecord r = it.next();
            if (r.time() < since) {
                break;
            }
            out.add(r);
        }
        Collections.reverse(out);
        return out;
    }

    public JournalSummary summary(String actor, long since, int keepLatest) {
        JournalSummary.Builder b = new JournalSummary.Builder(since, keepLatest);
        for (JournalRecord r : query(actor, since)) {
            b.add(r);
        }
        return b.build();
    }

    /** Records held in memory over all bots. */
    public int size() {
        return size;
    }

    public void clear() {
        byActor.clear();
        size = 0;
    }
}
