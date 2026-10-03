package io.github.krekerdm.baritonebots.plugin.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Minute windows for journal and rollback requests. */
public final class TimeWindow {
    public static final long MINUTE_MS = 60_000L;
    public static final long DAY_MS = 24 * 60 * MINUTE_MS;
    /** Upper bound for {@code rollback.mode: command}, where the external plugin keeps its own history. */
    public static final int MAX_COMMAND_MINUTES = 365 * 24 * 60;

    private TimeWindow() {
    }

    /** Start of the window that ends at {@code now}. */
    public static long since(long now, int minutes) {
        return now - minutes * MINUTE_MS;
    }

    /** The journal cannot answer for more than it keeps. */
    public static int maxJournalMinutes(int retentionDays) {
        return Math.max(1, retentionDays) * 24 * 60;
    }

    public static boolean validMinutes(int minutes, int max) {
        return minutes >= 1 && minutes <= max;
    }

    /** Every local date that a record with {@code since <= time <= until} can belong to, oldest first. */
    public static List<LocalDate> datesCovering(long since, long until, ZoneId zone) {
        List<LocalDate> out = new ArrayList<>();
        if (until < since) {
            return out;
        }
        LocalDate first = LocalDate.ofInstant(Instant.ofEpochMilli(since), zone);
        LocalDate last = LocalDate.ofInstant(Instant.ofEpochMilli(until), zone);
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }
}
