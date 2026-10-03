package io.github.krekerdm.baritonebots.plugin.journal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Counts of a bot's changes in a window plus the most recent few records (newest last).
 *
 * @param since window start, epoch ms
 */
public record JournalSummary(int breaks, int places, long since, List<JournalRecord> latest) {
    public JournalSummary {
        latest = List.copyOf(latest);
    }

    public int total() {
        return breaks + places;
    }

    /** Streams records in time order; keeps only {@code keepLatest} of them. */
    public static final class Builder {
        private final long since;
        private final int keepLatest;
        private final Deque<JournalRecord> latest = new ArrayDeque<>();
        private int breaks;
        private int places;

        public Builder(long since, int keepLatest) {
            this.since = since;
            this.keepLatest = Math.max(0, keepLatest);
        }

        public void add(JournalRecord r) {
            if (r.action() == JournalRecord.Action.BREAK) {
                breaks++;
            } else {
                places++;
            }
            if (keepLatest > 0) {
                latest.addLast(r);
                if (latest.size() > keepLatest) {
                    latest.removeFirst();
                }
            }
        }

        public JournalSummary build() {
            return new JournalSummary(breaks, places, since, new ArrayList<>(latest));
        }
    }
}
