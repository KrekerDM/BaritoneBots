package io.github.krekerdm.baritonebots.plugin.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Disk side of the journal. Records are queued from any thread and written by one daemon IO thread in batches;
 * reads run on the same thread after a flush, so they always see every record queued before them. The server
 * thread never waits for disk IO.
 */
public final class JournalStore {
    /** Records kept for a retry after write errors before the oldest are dropped. */
    static final int MAX_CARRY = 200_000;

    private final Path dir;
    private final ZoneId zone;
    private final long flushIntervalMs;
    private final Logger log;
    private final ScheduledExecutorService io;
    private final ConcurrentLinkedQueue<JournalRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong written = new AtomicLong();
    private volatile int retentionDays;
    private volatile String lastError;

    // IO thread only
    private final List<JournalRecord> carry = new ArrayList<>();
    private long segment;

    public JournalStore(Path dir, ZoneId zone, int retentionDays, long flushIntervalMs, Logger log) {
        this.dir = dir;
        this.zone = zone;
        this.retentionDays = Math.max(1, retentionDays);
        this.flushIntervalMs = Math.max(200, flushIntervalMs);
        this.log = log;
        this.io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BaritoneBots-Journal");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        segment = System.currentTimeMillis();
        io.scheduleWithFixedDelay(this::flushQuietly, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        io.scheduleWithFixedDelay(this::purgeQuietly, 0, 1, TimeUnit.HOURS);
    }

    /** Any thread; never blocks. */
    public void enqueue(JournalRecord r) {
        queue.add(r);
        queued.incrementAndGet();
    }

    public void setRetentionDays(int days) {
        retentionDays = Math.max(1, days);
    }

    /** Counts the records of {@code actor} with {@code time >= since}, keeping the newest {@code keepLatest}. */
    public CompletableFuture<JournalSummary> summary(String actor, long since, int keepLatest) {
        return CompletableFuture.supplyAsync(() -> {
            flush();
            JournalSummary.Builder b = new JournalSummary.Builder(since, keepLatest);
            scan(actor, since, b::add);
            return b.build();
        }, io);
    }

    /**
     * Records of {@code actor} with {@code time >= since}, oldest first. Stops after {@code limit} records and
     * reports {@code truncated} so a caller never loads an unbounded history into memory.
     */
    public CompletableFuture<ReadResult> read(String actor, long since, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            flush();
            List<JournalRecord> out = new ArrayList<>();
            boolean[] truncated = {false};
            ScanStats stats = scan(actor, since, r -> {
                if (out.size() < limit) {
                    out.add(r);
                } else {
                    truncated[0] = true;
                }
            });
            return new ReadResult(out, truncated[0], stats.badLines(), stats.damagedFiles());
        }, io);
    }

    private ScanStats scan(String actor, long since, Consumer<JournalRecord> sink) {
        String key = JournalRecord.key(actor);
        LocalDate first = LocalDate.ofInstant(Instant.ofEpochMilli(since), zone);
        int[] bad = {0};
        int damaged = 0;
        List<JournalFiles.Dated> files;
        try {
            files = JournalFiles.list(dir);
        } catch (IOException e) {
            throw new JournalException("cannot list " + dir + ": " + e.getMessage(), e);
        }
        for (JournalFiles.Dated f : files) {
            if (f.date().isBefore(first)) {
                continue;
            }
            try {
                boolean intact = JournalFiles.read(f.path(), line -> {
                    JournalRecord r;
                    try {
                        r = JournalCodec.decode(line);
                    } catch (IllegalArgumentException e) {
                        bad[0]++;
                        return;
                    }
                    if (r.time() >= since && r.actorKey().equals(key)) {
                        sink.accept(r);
                    }
                });
                if (!intact) {
                    damaged++;
                }
            } catch (IOException e) {
                throw new JournalException("cannot read " + f.path().getFileName() + ": " + e.getMessage(), e);
            }
        }
        return new ScanStats(bad[0], damaged);
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Journal flush failed", e);
        }
    }

    /** IO thread: writes every queued record, grouped into the file of its local date. */
    private void flush() {
        List<JournalRecord> batch = new ArrayList<>(carry);
        carry.clear();
        JournalRecord r;
        while ((r = queue.poll()) != null) {
            queued.decrementAndGet();
            batch.add(r);
        }
        if (batch.isEmpty()) {
            return;
        }
        Map<LocalDate, List<JournalRecord>> byDate = new LinkedHashMap<>();
        for (JournalRecord rec : batch) {
            byDate.computeIfAbsent(LocalDate.ofInstant(Instant.ofEpochMilli(rec.time()), zone), d -> new ArrayList<>())
                    .add(rec);
        }
        for (Map.Entry<LocalDate, List<JournalRecord>> e : byDate.entrySet()) {
            List<String> lines = new ArrayList<>(e.getValue().size());
            for (JournalRecord rec : e.getValue()) {
                lines.add(JournalCodec.encode(rec));
            }
            Path file = dir.resolve(JournalFiles.fileName(e.getKey(), segment));
            try {
                JournalFiles.append(file, lines);
                written.addAndGet(lines.size());
            } catch (IOException ex) {
                // The failed member may be half-written: continue in a fresh file so later records stay readable.
                segment = Math.max(segment + 1, System.currentTimeMillis());
                lastError = file.getFileName() + ": " + ex.getMessage();
                log.warning("Journal write failed (" + lastError + "); " + lines.size() + " records will be retried");
                carry.addAll(e.getValue());
            }
        }
        if (carry.size() > MAX_CARRY) {
            int drop = carry.size() - MAX_CARRY;
            carry.subList(0, drop).clear();
            log.severe("Journal cannot write to " + dir + "; dropped the " + drop + " oldest records");
        }
    }

    private void purgeQuietly() {
        try {
            LocalDate today = LocalDate.now(zone);
            for (JournalFiles.Dated f : JournalFiles.list(dir)) {
                if (JournalFiles.isExpired(f.date(), today, retentionDays)) {
                    Files.deleteIfExists(f.path());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "Journal cleanup failed", e);
        }
    }

    /** Writes everything still queued and stops the IO thread; waits at most {@code timeoutMs}. */
    public void close(long timeoutMs) {
        io.execute(this::flushQuietly);
        io.shutdown();
        try {
            if (!io.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warning("Journal writer did not finish in " + timeoutMs + " ms; " + queued.get()
                        + " records may be lost");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        }
    }

    public int queued() {
        return queued.get();
    }

    public long written() {
        return written.get();
    }

    public String lastError() {
        return lastError;
    }

    public Path dir() {
        return dir;
    }

    /**
     * @param badLines     lines that could not be decoded and were skipped
     * @param damagedFiles files whose tail could not be read (a crash during a write)
     */
    public record ReadResult(List<JournalRecord> records, boolean truncated, int badLines, int damagedFiles) {
    }

    private record ScanStats(int badLines, int damagedFiles) {
    }

    /** Unchecked wrapper so IO failures surface through the returned future. */
    public static final class JournalException extends RuntimeException {
        public JournalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
