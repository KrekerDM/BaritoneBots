package io.github.krekerdm.baritonebots.manager.planner;

import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Named locks with a capacity (SPEC §5.7): a container is used by one bot at a time, a build sector by up to
 * {@code planner.maxBuildersPerSector}. Holders are bot ids. Pure bookkeeping, owned by the manager loop.
 */
public final class Locks {
    private final Map<String, Set<String>> held = new LinkedHashMap<>();

    public static String container(String dim, Pos pos) {
        return "container:" + Dims.normalize(dim) + ":" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    public static String sector(String projectId, int index) {
        return "sector:" + projectId + ":" + index;
    }

    /** True when {@code holder} already holds the lock or a slot is free. */
    public boolean available(String key, String holder, int capacity) {
        Set<String> h = held.get(key);
        return h == null || h.contains(holder) || h.size() < Math.max(1, capacity);
    }

    public int count(String key) {
        Set<String> h = held.get(key);
        return h == null ? 0 : h.size();
    }

    public boolean acquire(String key, String holder, int capacity) {
        if (!available(key, holder, capacity)) {
            return false;
        }
        held.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(holder);
        return true;
    }

    public void release(String key, String holder) {
        Set<String> h = held.get(key);
        if (h != null && h.remove(holder) && h.isEmpty()) {
            held.remove(key);
        }
    }

    public void releaseAll(String holder) {
        held.values().forEach(h -> h.remove(holder));
        held.values().removeIf(Set::isEmpty);
    }

    public Set<String> holders(String key) {
        return Set.copyOf(held.getOrDefault(key, Set.of()));
    }

    /** Is {@code key} held by anyone other than {@code holder}? */
    public boolean heldByOther(String key, String holder) {
        Set<String> h = held.get(key);
        return h != null && (h.size() > 1 || !h.contains(holder));
    }
}
