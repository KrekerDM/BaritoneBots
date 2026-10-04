package io.github.krekerdm.baritonebots.manager.planner;

import java.util.HashMap;
import java.util.Map;

/**
 * Failure bookkeeping per work item key: exponential backoff ({@link #BASE_MS} · 2^(n−1), capped at {@link #MAX_MS}),
 * and after {@link #MAX_FAILURES} failures the item counts as failed until {@link #clear(String)}. Pure, loop-owned.
 */
public final class RetryBook {
    public static final int MAX_FAILURES = 3;
    public static final long BASE_MS = 30_000;
    public static final long MAX_MS = 600_000;

    public record State(int failures, long nextAt, String reason, boolean failed) {
    }

    private final Map<String, State> states = new HashMap<>();

    /** Records a failure; returns the new state ({@code failed} once the limit is reached). */
    public State fail(String key, String reason, long now) {
        State old = states.get(key);
        int n = old == null ? 1 : old.failures() + 1;
        boolean failed = n >= MAX_FAILURES;
        long delay = Math.min(MAX_MS, BASE_MS << Math.min(10, n - 1));
        State s = new State(n, failed ? Long.MAX_VALUE : now + delay, reason, failed);
        states.put(key, s);
        return s;
    }

    public void success(String key) {
        states.remove(key);
    }

    /** Not failed and not inside a backoff window. */
    public boolean ready(String key, long now) {
        State s = states.get(key);
        return s == null || (!s.failed() && now >= s.nextAt());
    }

    public State state(String key) {
        return states.get(key);
    }

    /** Forgets every key starting with {@code prefix} (a project restarted / resumed). */
    public void clear(String prefix) {
        states.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public Map<String, State> failedWithPrefix(String prefix) {
        Map<String, State> out = new HashMap<>();
        states.forEach((k, v) -> {
            if (v.failed() && k.startsWith(prefix)) {
                out.put(k, v);
            }
        });
        return out;
    }
}
