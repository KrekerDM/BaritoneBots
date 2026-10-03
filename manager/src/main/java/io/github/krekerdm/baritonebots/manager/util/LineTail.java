package io.github.krekerdm.baritonebots.manager.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Thread-safe bounded tail of text lines (launcher output is read on its own thread, served from HTTP threads). */
public final class LineTail {
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final int capacity;

    public LineTail(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(String line) {
        lines.addLast(line);
        while (lines.size() > capacity) {
            lines.removeFirst();
        }
    }

    /** Last {@code n} lines, oldest first. */
    public synchronized List<String> tail(int n) {
        List<String> all = new ArrayList<>(lines);
        return new ArrayList<>(all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size()));
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty();
    }
}
