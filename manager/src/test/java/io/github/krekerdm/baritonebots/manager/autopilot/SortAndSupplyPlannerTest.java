package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Inbox sorting plans and auto-supply take plans (SPEC §5.7a). */
class SortAndSupplyPlannerTest {
    private static final ManagerConfig.AutopilotCfg CFG = ManagerConfig.AutopilotCfg.defaults();
    private static final Categories CATS = new Categories(CFG.categories(), Fixtures.gameData());
    private static final TaskNeeds.Context CTX = new TaskNeeds.Context(Fixtures.gameData(), CFG, CATS, List.of());

    /** A container with a snapshot: items as id, count pairs, one slot each, 27 slots. */
    private static WorldDoc.Container chest(int x, List<String> roles, Object... items) {
        List<ContainerSnapshot.SlotItem> slots = new ArrayList<>();
        for (int i = 0; i < items.length; i += 2) {
            slots.add(new ContainerSnapshot.SlotItem(slots.size(), (String) items[i], (Integer) items[i + 1]));
        }
        WorldDoc.Snapshot s = new WorldDoc.Snapshot(27, 27 - slots.size(), slots, 1);
        return new WorldDoc.Container("c" + x, "minecraft:overworld", new Pos(x, 64, 0), "minecraft:chest", roles, "", s, 1);
    }

    @Test
    void inboxGoesToCategoryChestsThenEmptyStorageThenMisc() {
        WorldDoc.Container inbox = chest(0, List.of("inbox"), "minecraft:cobblestone", 64, "minecraft:raw_iron", 10,
                "minecraft:bread", 5, "minecraft:nether_star", 1);
        // stone chest: 26 full slots + one stack of 60 cobblestone → room for 4
        List<Object> full = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            full.add("minecraft:stone");
            full.add(64);
        }
        full.add("minecraft:cobblestone");
        full.add(60);
        WorldDoc.Container stone = chest(1, List.of("storage", "sorted:stone_building"), full.toArray());
        WorldDoc.Container empty = chest(2, List.of("storage"));
        WorldDoc.Container mixed = chest(3, List.of("storage"), "minecraft:dirt", 1);
        WorldDoc.Container misc = chest(4, List.of("sorted:misc"));
        SortPlanner.Plan plan = SortPlanner.plan(inbox, List.of(stone, empty, mixed, misc), CATS, 30);

        SortPlanner.Move ores = plan.moves().stream().filter(m -> m.category().equals("ores_ingots")).findFirst().orElseThrow();
        assertEquals(List.of(empty), ores.to(), "no ore chest yet: the empty storage chest takes the category");
        SortPlanner.Move stoneMove = plan.moves().stream().filter(m -> m.category().equals("stone_building")).findFirst().orElseThrow();
        assertEquals(64, stoneMove.total());
        assertEquals(stone, stoneMove.to().getFirst(), "top up the partial stack first");
        assertTrue(stoneMove.to().contains(misc), "the full category chest overflows into misc");
        SortPlanner.Move food = plan.moves().stream().filter(m -> m.category().equals("food")).findFirst().orElseThrow();
        assertEquals(List.of(misc), food.to(), "the empty chest is taken by ores; food overflows to misc");
        SortPlanner.Move other = plan.moves().stream().filter(m -> m.category().equals("misc")).findFirst().orElseThrow();
        assertEquals(List.of(misc), other.to());
        assertTrue(plan.moves().stream().noneMatch(m -> m.to().contains(mixed)), "mixed unassigned chests are never targets");
        assertTrue(plan.unsorted().isEmpty());
    }

    @Test
    void noRoomLeavesItemsAndSlotBudgetLimitsARound() {
        WorldDoc.Container inbox = chest(0, List.of("inbox"), "minecraft:bread", 64, "minecraft:raw_iron", 64,
                "minecraft:raw_gold", 64);
        SortPlanner.Plan none = SortPlanner.plan(inbox, List.of(), CATS, 30);
        assertTrue(none.isEmpty());
        assertEquals(Map.of("minecraft:bread", 64, "minecraft:raw_iron", 64, "minecraft:raw_gold", 64), none.unsorted());

        WorldDoc.Container ores = chest(1, List.of("sorted:ores_ingots"));
        WorldDoc.Container food = chest(2, List.of("sorted:food"));
        SortPlanner.Plan one = SortPlanner.plan(inbox, List.of(ores, food), CATS, 1);
        assertEquals(1, one.moves().size(), "one free slot: one stack this round");
        assertEquals(64, one.moves().getFirst().total());
        assertTrue(one.unsorted().isEmpty(), "what the budget left out is not 'unsorted'");
    }

    @Test
    void supplyTakesTheBestToolNearestFoodAndReportsMissing() {
        WorldDoc.Container near = chest(1, List.of("storage"), "minecraft:bread", 6, "minecraft:stone_pickaxe", 1);
        WorldDoc.Container far = chest(9, List.of("storage"), "minecraft:bread", 64, "minecraft:diamond_pickaxe", 1);
        List<SupplyPlanner.Source> sources = List.of(new SupplyPlanner.Source(near, near.snapshot().totals()),
                new SupplyPlanner.Source(far, far.snapshot().totals()));
        List<TaskNeeds.Need> needs = List.of(
                new TaskNeeds.Need(TaskNeeds.Kind.TOOL, List.of(), 1, 1, "pickaxe", "stone", false),
                new TaskNeeds.Need(TaskNeeds.Kind.FOOD, List.of(), 16, 8, null, null, false),
                new TaskNeeds.Need(TaskNeeds.Kind.ITEM, List.of("minecraft:glass"), 10, 10, null, null, false),
                new TaskNeeds.Need(TaskNeeds.Kind.BLOCKS, CFG.throwaway(), 64, 32, null, null, true));
        SupplyPlanner.Plan p = SupplyPlanner.plan(needs, sources, CTX, 10);
        Map<String, Integer> fromNear = p.takes().stream().filter(t -> t.container() == near).findFirst().orElseThrow().items();
        Map<String, Integer> fromFar = p.takes().stream().filter(t -> t.container() == far).findFirst().orElseThrow().items();
        assertEquals(1, fromFar.get("minecraft:diamond_pickaxe"), "best tier on offer");
        assertEquals(6, fromNear.get("minecraft:bread"), "nearest first");
        assertEquals(10, fromFar.get("minecraft:bread"));
        assertEquals(Map.of("glass", 10), p.missing(), "required and nowhere: reported; optional blocks are not");
        assertTrue(p.inspect().isEmpty());

        WorldDoc.Container unknown = new WorldDoc.Container("u", "minecraft:overworld", new Pos(5, 64, 0),
                "minecraft:chest", List.of("storage"), "", null, 0);
        SupplyPlanner.Plan withUnknown = SupplyPlanner.plan(needs, List.of(new SupplyPlanner.Source(unknown, null)), CTX, 10);
        assertEquals(List.of(unknown), withUnknown.inspect(), "unmet needs + unknown contents → inspect first");
        SupplyPlanner.Plan tight = SupplyPlanner.plan(List.of(new TaskNeeds.Need(TaskNeeds.Kind.FOOD, List.of(), 100, 8,
                null, null, false)), sources, CTX, 1);
        assertEquals(64, tight.takes().stream().mapToInt(t -> t.items().values().stream().mapToInt(Integer::intValue).sum()).sum(),
                "one free slot: one stack of bread (6 near + 58 far)");
    }
}
