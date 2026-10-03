package io.github.krekerdm.baritonebots.manager.tasks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Per-bot {@code current} + {@code queue} (SPEC §5.5). Pure bookkeeping, no IO: the dispatcher decides when to
 * start entries and what to send to the bot.
 */
public final class TaskQueue {
    public enum Mode {
        APPEND, FRONT, REPLACE;

        public static Mode parse(String s) {
            if (s == null || s.isBlank()) {
                return APPEND;
            }
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    private final ArrayDeque<QueueEntry> items = new ArrayDeque<>();
    private QueueEntry current;
    private long currentStartedAt;

    public QueueEntry current() {
        return current;
    }

    public long currentStartedAt() {
        return currentStartedAt;
    }

    public List<QueueEntry> items() {
        return List.copyOf(items);
    }

    public QueueEntry peek() {
        return items.peekFirst();
    }

    public boolean isIdle() {
        return current == null && items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    /**
     * Adds entries in the given order. {@code FRONT} puts the whole batch before existing items;
     * {@code REPLACE} drops the queued items first.
     *
     * @return the running entry that must be cancelled ({@code REPLACE} only), else null; the caller cancels it
     *         and then calls {@link #abortCurrent()}
     */
    public QueueEntry add(List<QueueEntry> entries, Mode mode) {
        switch (mode) {
            case APPEND -> items.addAll(entries);
            case FRONT -> pushFront(entries);
            case REPLACE -> {
                items.clear();
                items.addAll(entries);
                return current;
            }
        }
        return null;
    }

    /** Puts entries at the head, keeping their order. */
    public void pushFront(List<QueueEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            items.addFirst(entries.get(i));
        }
    }

    /** Inserts after the leading entries that match {@code skip} (e.g. pending death recovery). */
    public void insertAfterLeading(Predicate<QueueEntry> skip, QueueEntry entry) {
        List<QueueEntry> head = new ArrayList<>();
        while (!items.isEmpty() && skip.test(items.peekFirst())) {
            head.add(items.pollFirst());
        }
        items.addFirst(entry);
        pushFront(head);
    }

    public QueueEntry poll() {
        return items.pollFirst();
    }

    public void start(QueueEntry e, long now) {
        current = e;
        currentStartedAt = now;
    }

    /** Clears {@code current} when its id matches; returns it, or null for a stale / unknown id. */
    public QueueEntry finish(String id) {
        if (current != null && current.id().equals(id)) {
            QueueEntry done = current;
            current = null;
            currentStartedAt = 0;
            return done;
        }
        return null;
    }

    /** Forgets the running entry (after a cancel was sent); returns it. */
    public QueueEntry abortCurrent() {
        QueueEntry c = current;
        current = null;
        currentStartedAt = 0;
        return c;
    }

    public QueueEntry removeQueued(String id) {
        Iterator<QueueEntry> it = items.iterator();
        while (it.hasNext()) {
            QueueEntry e = it.next();
            if (e.id().equals(id)) {
                it.remove();
                return e;
            }
        }
        return null;
    }

    /** Removes queued entries matching the filter (the running one is untouched); returns them. */
    public List<QueueEntry> removeIf(Predicate<QueueEntry> filter) {
        List<QueueEntry> removed = new ArrayList<>();
        items.removeIf(e -> {
            if (filter.test(e)) {
                removed.add(e);
                return true;
            }
            return false;
        });
        return removed;
    }

    public List<QueueEntry> clearQueued() {
        List<QueueEntry> removed = new ArrayList<>(items);
        items.clear();
        return removed;
    }

    /** Listed ids first, in the given order; unlisted entries keep their relative order after them. */
    public void reorder(List<String> ids) {
        Map<String, QueueEntry> byId = new LinkedHashMap<>();
        items.forEach(e -> byId.put(e.id(), e));
        List<QueueEntry> ordered = new ArrayList<>();
        for (String id : ids) {
            QueueEntry e = byId.remove(id);
            if (e != null) {
                ordered.add(e);
            }
        }
        ordered.addAll(byId.values());
        items.clear();
        items.addAll(ordered);
    }

    public boolean anyMatch(Predicate<QueueEntry> filter) {
        return (current != null && filter.test(current)) || items.stream().anyMatch(filter);
    }
}
