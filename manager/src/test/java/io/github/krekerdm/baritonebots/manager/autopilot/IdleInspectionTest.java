package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.manager.world.WorldDoc;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which containers idle inspection may walk to (live test: a bot was sent ~120 blocks away to inspect). */
class IdleInspectionTest {
    private static final String OW = "minecraft:overworld";

    private static WorldDoc.Container c(String block, String... roles) {
        return new WorldDoc.Container("c1", OW, new Pos(0, 64, 0), block, List.of(roles), "", null, 0);
    }

    @Test
    void homeOrUsableRoleFoundOnlyWithUseFound() {
        assertFalse(AutopilotSource.inspectable(c("minecraft:chest", "found"), false, false), "found outside home");
        assertTrue(AutopilotSource.inspectable(c("minecraft:chest", "found"), false, true), "found with useFound");
        assertTrue(AutopilotSource.inspectable(c("minecraft:chest", "found"), true, false), "found within home");
        assertFalse(AutopilotSource.inspectable(c("minecraft:hopper"), false, false), "no role outside home");
        assertTrue(AutopilotSource.inspectable(c("minecraft:hopper"), true, false), "anything within home");
        for (String role : List.of("storage", "kit", "supply", "inbox", "trash", "fuel", "sorted:food")) {
            assertTrue(AutopilotSource.inspectable(c("minecraft:chest", role), false, false), role);
        }
        assertTrue(AutopilotSource.inspectable(c("minecraft:furnace", "furnace"), false, false));
        assertFalse(AutopilotSource.inspectable(c("minecraft:crafting_table", "crafting"), true, false),
                "a crafting table holds nothing");
    }

    @Test
    void walkDistanceIsCapped() {
        Pos bot = new Pos(0, 64, 0);
        assertTrue(AutopilotSource.inReach(bot, OW, OW, new Pos(30, 64, 0), 48));
        assertFalse(AutopilotSource.inReach(bot, OW, OW, new Pos(120, 64, 0), 48), "~120 blocks away");
        assertFalse(AutopilotSource.inReach(bot, "minecraft:the_nether", OW, new Pos(1, 64, 0), 48), "other dimension");
        assertFalse(AutopilotSource.inReach(null, OW, OW, new Pos(1, 64, 0), 48), "position unknown");
        assertTrue(AutopilotSource.inReach(bot, OW, OW, new Pos(500, 64, 0), 0), "0 = no limit");
    }
}
