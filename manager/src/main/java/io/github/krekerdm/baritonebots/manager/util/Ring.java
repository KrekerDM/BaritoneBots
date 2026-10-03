package io.github.krekerdm.baritonebots.manager.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Bounded FIFO that drops the oldest element; not thread-safe (owned by the manager loop). */
public final class Ring<T> {
    private final ArrayDeque<T> items = new ArrayDeque<>();
    private int capacity;

    public Ring(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void add(T item) {
        items.addLast(item);
        while (items.size() > capacity) {
            items.removeFirst();
        }
    }

    public void resize(int newCapacity) {
        capacity = Math.max(1, newCapacity);
        while (items.size() > capacity) {
            items.removeFirst();
        }
    }

    public int size() {
        return items.size();
    }

    public void clear() {
        items.clear();
    }

    /** Last {@code n} elements, oldest first. */
    public List<T> tail(int n) {
        List<T> all = new ArrayList<>(items);
        return all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size());
    }

    /** Last {@code n} elements matching the filter, oldest first. */
    public List<T> tail(int n, Predicate<T> filter) {
        List<T> all = new ArrayList<>(items);
        List<T> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
            if (filter.test(all.get(i))) {
                out.add(all.get(i));
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }
}
