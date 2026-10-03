package io.github.krekerdm.baritonebots.mod.util;

import java.util.HashMap;
import java.util.Map;

/** Thread-safe helpers for "at most N per window" and "once per key per interval" limits. */
public final class RateLimiter {
    private final int maxPerWindow;
    private final long windowMs;
    private long windowStart;
    private int used;
    private int suppressed;
    private final Map<String, Long> lastByKey = new HashMap<>();

    public RateLimiter(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    /** True if one more event fits in the current window; otherwise counts it as suppressed. */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= windowMs) {
            windowStart = now;
            used = 0;
        }
        if (used < maxPerWindow) {
            used++;
            return true;
        }
        suppressed++;
        return false;
    }

    /** Number of events suppressed since the last call (and resets it). */
    public synchronized int takeSuppressed() {
        int s = suppressed;
        suppressed = 0;
        return s;
    }

    /** True at most once per {@code intervalMs} for each key. */
    public synchronized boolean once(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = lastByKey.get(key);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        lastByKey.put(key, now);
        if (lastByKey.size() > 512) {
            lastByKey.entrySet().removeIf(e -> now - e.getValue() > 600_000);
        }
        return true;
    }

    /** Forgets a key so the next {@link #once} with it passes. */
    public synchronized void reset(String key) {
        lastByKey.remove(key);
    }
}
