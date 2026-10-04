package io.github.krekerdm.baritonebots.manager.autopilot;

import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.manager.planner.ItemStacks;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans how one inbox is emptied into category chests (SPEC §5.7a auto-sort): per category, the chests with role
 * {@code sorted:<category>} that have room, then empty unassigned storage chests (they adopt the category once
 * filled), then {@code sorted:misc}. One {@link Move} = one {@code transfer} task (take from the inbox, deposit into
 * the {@code to} list in order). Room comes from the targets' snapshots (free slots + partial stacks of the same
 * item); what fits nowhere stays in the inbox ({@link Plan#unsorted}). Pure.
 */
public final class SortPlanner {
    public record Move(WorldDoc.Container from, String category, List<WorldDoc.Container> to,
                       Map<String, Integer> items) {
        public int total() {
            return items.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public record Plan(List<Move> moves, Map<String, Integer> unsorted) {
        public boolean isEmpty() {
            return moves.isEmpty();
        }
    }

    /** Free space of one target, updated while planning. */
    private static final class Room {
        final WorldDoc.Container c;
        int freeSlots;
        final Map<String, Integer> partial = new HashMap<>();

        Room(WorldDoc.Container c) {
            this.c = c;
            WorldDoc.Snapshot s = c.snapshot();
            freeSlots = s == null ? 0 : Math.max(0, s.free());
            if (s != null) {
                for (ContainerSnapshot.SlotItem it : s.items()) {
                    int space = ItemStacks.maxStack(it.item()) - it.count();
                    if (space > 0) {
                        partial.merge(it.item(), space, Integer::sum);
                    }
                }
            }
        }

        /** Places up to {@code n} of {@code item}; returns how many fit. */
        int place(String item, int n) {
            int put = Math.min(n, partial.getOrDefault(item, 0));
            if (put > 0) {
                partial.merge(item, -put, Integer::sum);
            }
            int max = ItemStacks.maxStack(item);
            while (put < n && freeSlots > 0) {
                int add = Math.min(max, n - put);
                put += add;
                freeSlots--;
                if (add < max) {
                    partial.merge(item, max - add, Integer::sum);
                }
            }
            return put;
        }
    }

    private SortPlanner() {
    }

    /**
     * @param inbox      the inbox (with a snapshot)
     * @param targets    every candidate target in the inbox's dimension: {@code sorted:*} chests and unassigned
     *                   storage chests (only empty ones are used), all with snapshots
     * @param slotBudget inventory slots the bot may fill in one round
     */
    public static Plan plan(WorldDoc.Container inbox, List<WorldDoc.Container> targets, Categories cats, int slotBudget) {
        Map<String, Integer> totals = inbox.snapshot() == null ? Map.of() : inbox.snapshot().totals();
        Map<String, Map<String, Integer>> groups = cats.group(totals);
        Map<String, List<Room>> byCat = new LinkedHashMap<>();
        List<Room> spare = new ArrayList<>();
        for (WorldDoc.Container t : targets) {
            if (t.snapshot() == null || t.pos().equals(inbox.pos()) && t.dim().equals(inbox.dim())) {
                continue;
            }
            String cat = t.sortedCategory();
            if (cat != null) {
                byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(new Room(t));
            } else if (t.snapshot().items().isEmpty()) {
                spare.add(new Room(t));
            }
        }
        List<Move> moves = new ArrayList<>();
        Map<String, Integer> unsorted = new LinkedHashMap<>();
        int budget = Math.max(1, slotBudget);
        for (Map.Entry<String, Map<String, Integer>> g : groups.entrySet()) {
            String cat = g.getKey();
            List<Room> chain = new ArrayList<>(byCat.getOrDefault(cat, List.of()));
            Map<String, Integer> moved = new LinkedHashMap<>();
            List<WorldDoc.Container> used = new ArrayList<>();
            for (Map.Entry<String, Integer> it : g.getValue().entrySet()) {
                String item = it.getKey();
                int want = it.getValue();
                int maxBySlots = budget * ItemStacks.maxStack(item);
                want = Math.min(want, maxBySlots);
                int left = want;
                left = fill(chain, item, left, used);
                while (left > 0 && !spare.isEmpty()) {
                    Room r = spare.removeFirst(); // an empty unassigned chest takes this category
                    chain.add(r);
                    byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(r);
                    left = fill(List.of(r), item, left, used);
                }
                if (left > 0 && !Categories.MISC.equals(cat)) {
                    left = fill(byCat.getOrDefault(Categories.MISC, List.of()), item, left, used);
                }
                int put = want - left;
                if (put > 0) {
                    moved.merge(item, put, Integer::sum);
                    budget -= ItemStacks.slots(item, put);
                }
                int rest = want - put; // no room anywhere (what the slot budget left out comes next round)
                if (rest > 0) {
                    unsorted.merge(item, rest, Integer::sum);
                }
                if (budget <= 0) {
                    break;
                }
            }
            if (!moved.isEmpty()) {
                moves.add(new Move(inbox, cat, List.copyOf(used), moved));
            }
            if (budget <= 0) {
                break;
            }
        }
        return new Plan(moves, unsorted);
    }

    private static int fill(List<Room> rooms, String item, int left, List<WorldDoc.Container> used) {
        for (Room r : rooms) {
            if (left <= 0) {
                break;
            }
            int put = r.place(item, left);
            if (put > 0) {
                left -= put;
                if (!used.contains(r.c)) {
                    used.add(r.c);
                }
            }
        }
        return left;
    }
}
