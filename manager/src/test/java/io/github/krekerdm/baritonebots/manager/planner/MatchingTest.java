package io.github.krekerdm.baritonebots.manager.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Work item matching, role hysteresis, locks, retries and restock planning. */
class MatchingTest {
    private static final String OW = "minecraft:overworld";
    private static final long COOLDOWN = 120_000;

    private static WorkItem item(String id, String role, double prio, Pos at, int capacity) {
        return new WorkItem(id, "p1", "build_sector", role, prio, null, OW, at, null, capacity, id, null);
    }

    private static Matcher.Bot bot(String id, Pos pos, String... roles) {
        return new Matcher.Bot(id, OW, pos, List.of(roles));
    }

    private static Map<String, String> byBot(List<Matcher.Match> ms) {
        Map<String, String> out = new HashMap<>();
        ms.forEach(m -> out.put(m.botId(), m.item().id()));
        return out;
    }

    @Test
    void scoreIsPriorityMinusDistanceAndRoleChange() {
        RoleTracker roles = new RoleTracker();
        WorkItem near = item("near", "builder", 5, new Pos(64, 64, 0), 1);
        assertEquals(4.0, Matcher.score(bot("a", new Pos(0, 64, 0)), near, false), 1e-9);
        assertEquals(2.0, Matcher.score(bot("a", new Pos(0, 64, 0)), near, true), 1e-9);
        assertEquals(5.0, Matcher.score(bot("a", null), near, false), 1e-9, "unknown position: no distance term");
        assertFalse(roles.isChange("a", "builder"), "no role yet = no change");
    }

    @Test
    void closerBotsWinAndCapacityIsRespected() {
        RoleTracker roles = new RoleTracker();
        WorkItem far = item("far", "builder", 5, new Pos(640, 64, 0), 1);
        WorkItem home = item("home", "builder", 5, new Pos(0, 64, 0), 1);
        List<Matcher.Match> ms = Matcher.match(
                List.of(bot("a", new Pos(10, 64, 0)), bot("b", new Pos(600, 64, 0))),
                List.of(new Matcher.Offer(far, 1), new Matcher.Offer(home, 1)), roles, 0, COOLDOWN, null);
        assertEquals(Map.of("a", "home", "b", "far"), byBot(ms));

        WorkItem shared = item("sector", "builder", 5, new Pos(0, 64, 0), 2);
        List<Matcher.Match> two = Matcher.match(
                List.of(bot("a", null), bot("b", null), bot("c", null)),
                List.of(new Matcher.Offer(shared, 2)), roles, 0, COOLDOWN, null);
        assertEquals(2, two.size(), "two slots, three bots");
        assertEquals(List.of("a", "b"), two.stream().map(Matcher.Match::botId).toList(), "ties break on bot id");
    }

    @Test
    void allowedRolesAndEligibility() {
        RoleTracker roles = new RoleTracker();
        WorkItem mine = item("mine", "miner", 9, null, 1);
        WorkItem build = item("build", "builder", 1, null, 1);
        List<Matcher.Match> ms = Matcher.match(List.of(bot("a", null, "builder")),
                List.of(new Matcher.Offer(mine, 1), new Matcher.Offer(build, 1)), roles, 0, COOLDOWN, null);
        assertEquals(Map.of("a", "build"), byBot(ms), "a builder-only bot never mines");
        List<Matcher.Match> none = Matcher.match(List.of(bot("a", null)), List.of(new Matcher.Offer(mine, 1)), roles, 0,
                COOLDOWN, (b, w) -> false);
        assertTrue(none.isEmpty());
        WorkItem nether = new WorkItem("n", "p1", "mine", "miner", 9, null, "minecraft:the_nether", new Pos(0, 64, 0),
                null, 1, "n", null);
        assertTrue(Matcher.match(List.of(bot("a", new Pos(0, 64, 0))), List.of(new Matcher.Offer(nether, 1)), roles, 0,
                COOLDOWN, null).isEmpty(), "other dimension");
    }

    @Test
    void roleHysteresis() {
        RoleTracker roles = new RoleTracker();
        roles.assign("a", "builder", 1_000);
        WorkItem mine = item("mine", "miner", 9, null, 1);
        WorkItem build = item("build", "builder", 1, null, 1);
        List<Matcher.Offer> offers = List.of(new Matcher.Offer(mine, 1), new Matcher.Offer(build, 1));
        // within the cooldown the builder keeps building although mining scores higher
        assertEquals(Map.of("a", "build"), byBot(Matcher.match(List.of(bot("a", null)), offers, roles, 60_000, COOLDOWN, null)));
        assertTrue(Matcher.match(List.of(bot("a", null)), List.of(new Matcher.Offer(mine, 1)), roles, 60_000, COOLDOWN, null)
                .isEmpty(), "no switch inside the cooldown");
        // after it, the switch is allowed but costs 2: 9 - 2 = 7 > 1
        List<Matcher.Match> after = Matcher.match(List.of(bot("a", null)), offers, roles, 121_000, COOLDOWN, null);
        assertEquals("mine", after.getFirst().item().id());
        assertTrue(after.getFirst().roleChange());
        // a close call stays with the current role: 2.5 - 2 < 1
        WorkItem mineLow = item("mineLow", "miner", 2.5, null, 1);
        assertEquals("build", Matcher.match(List.of(bot("a", null)),
                List.of(new Matcher.Offer(mineLow, 1), new Matcher.Offer(build, 1)), roles, 500_000, COOLDOWN, null)
                .getFirst().item().id());
        // the timer restarts only on a real change
        roles.assign("a", "builder", 400_000);
        assertEquals(1_000, roles.since("a"));
        roles.assign("a", "miner", 400_000);
        assertEquals(400_000, roles.since("a"));
        assertFalse(roles.allows("a", "builder", 450_000, COOLDOWN));
    }

    @Test
    void locksAndRetries() {
        Locks locks = new Locks();
        String c = Locks.container(OW, new Pos(1, 2, 3));
        assertTrue(locks.acquire(c, "a", 1));
        assertFalse(locks.acquire(c, "b", 1));
        assertTrue(locks.acquire(c, "a", 1), "re-entrant for the holder");
        assertTrue(locks.heldByOther(c, "b"));
        String s = Locks.sector("p", 0);
        assertTrue(locks.acquire(s, "a", 2));
        assertTrue(locks.acquire(s, "b", 2));
        assertFalse(locks.acquire(s, "c", 2));
        locks.releaseAll("a");
        assertTrue(locks.acquire(c, "b", 1));
        assertEquals(1, locks.count(s));

        RetryBook book = new RetryBook();
        RetryBook.State st = book.fail("p/x", "path_failed", 0);
        assertEquals(RetryBook.BASE_MS, st.nextAt());
        assertFalse(book.ready("p/x", 10_000));
        assertTrue(book.ready("p/x", RetryBook.BASE_MS));
        assertEquals(RetryBook.BASE_MS * 2 + 1, book.fail("p/x", "stuck", 1).nextAt());
        assertTrue(book.fail("p/x", "stuck", 2).failed());
        assertFalse(book.ready("p/x", Long.MAX_VALUE - 1));
        assertEquals(1, book.failedWithPrefix("p/").size());
        book.clear("p/");
        assertTrue(book.ready("p/x", 0));
    }

    private static WorldDoc.Container chest(int x, String role, Map<String, Integer> items) {
        WorldDoc.Snapshot snap = null;
        if (items != null) {
            List<ContainerSnapshot.SlotItem> slots = new java.util.ArrayList<>();
            int i = 0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                slots.add(new ContainerSnapshot.SlotItem(i++, e.getKey(), e.getValue()));
            }
            snap = new WorldDoc.Snapshot(27, 27 - slots.size(), slots, 1);
        }
        return new WorldDoc.Container("c" + x, OW, new Pos(x, 64, 0), "minecraft:chest", List.of(role), null, snap, 1);
    }

    @Test
    void restockPlansWithinTheSlotBudget() {
        WorldDoc.Container supply = chest(1, "supply", Map.of("minecraft:stone", 100, "minecraft:glass", 10));
        WorldDoc.Container storage = chest(2, "storage", Map.of("minecraft:stone", 500, "minecraft:oak_door", 3));
        WorldDoc.Container unknown = chest(3, "storage", null);
        List<Restock.Need> needs = List.of(new Restock.Need("minecraft:glass", 20), new Restock.Need("minecraft:stone", 300),
                new Restock.Need("minecraft:oak_door", 2));
        Restock.Plan plan = Restock.plan(needs, List.of(supply, storage, unknown), c -> c.snapshot() == null ? null
                : c.snapshot().totals(), c -> false, 4);
        // glass 10 (1 slot) from supply; stone fills the remaining 3 slots: 100 supply + 92 storage
        assertEquals(2, plan.takes().size());
        assertEquals(Map.of("minecraft:glass", 10, "minecraft:stone", 100), plan.takes().get(0).items());
        assertEquals(Map.of("minecraft:stone", 92), plan.takes().get(1).items());
        assertEquals(List.of(unknown), plan.inspect());
        assertEquals(Map.of("minecraft:glass", 10, "minecraft:stone", 108, "minecraft:oak_door", 2), plan.missing());

        Restock.Plan locked = Restock.plan(needs, List.of(supply, storage), c -> c.snapshot().totals(), c -> c == supply, 36);
        assertEquals(1, locked.takes().size(), "the locked supply chest is skipped");
        assertEquals(storage, locked.takes().getFirst().container());
        assertEquals(Map.of("minecraft:stone", 300, "minecraft:oak_door", 2), locked.takes().getFirst().items());
    }

    @Test
    void stackSizes() {
        assertEquals(1, ItemStacks.maxStack("minecraft:diamond_pickaxe"));
        assertEquals(1, ItemStacks.maxStack("red_bed"));
        assertEquals(16, ItemStacks.maxStack("minecraft:oak_sign"));
        assertEquals(16, ItemStacks.maxStack("minecraft:bucket"));
        assertEquals(1, ItemStacks.maxStack("minecraft:water_bucket"));
        assertEquals(64, ItemStacks.maxStack("minecraft:stone"));
        assertEquals(2, ItemStacks.slots("minecraft:stone", 65));
        assertEquals(1.5, ItemStacks.burnItems("minecraft:birch_log"));
        assertEquals(0, ItemStacks.burnItems("minecraft:crimson_planks"));
        assertEquals(8, ItemStacks.burnItems("minecraft:coal"));
    }
}
