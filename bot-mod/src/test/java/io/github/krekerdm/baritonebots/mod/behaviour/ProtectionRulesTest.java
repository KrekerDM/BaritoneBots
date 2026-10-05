package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProtectionRulesTest {
    private static final String OW = "minecraft:overworld";
    private static final Box HOUSE = new Box(new Pos(0, 60, 0), new Pos(10, 70, 10));
    private static final BotConfig.Protection PR = new BotConfig.Protection(true, List.of("minecraft:chest"),
            List.of(new BotConfig.Zone(OW, HOUSE)), List.of("minecraft:*_planks", "minecraft:glass"));

    @Test
    void zonesBuiltAndNoBreak() {
        assertEquals(ProtectionRules.ZONE, ProtectionRules.breakRefusal(PR, OW, 5, 64, 5, "minecraft:stone", null));
        assertNull(ProtectionRules.breakRefusal(PR, OW, 20, 64, 5, "minecraft:stone", null), "outside: natural block");
        assertEquals(ProtectionRules.BUILT, ProtectionRules.breakRefusal(PR, OW, 20, 64, 5, "minecraft:oak_planks", null),
                "built blocks are protected everywhere");
        assertEquals(ProtectionRules.NO_BREAK, ProtectionRules.breakRefusal(PR, OW, 20, 64, 5, "minecraft:chest", null));
        assertNull(ProtectionRules.breakRefusal(PR, "minecraft:the_nether", 5, 64, 5, "minecraft:stone", null),
                "a zone belongs to its dimension");
        BotConfig.Protection off = new BotConfig.Protection(false, PR.noBreak(), PR.zones(), PR.built());
        assertNull(ProtectionRules.breakRefusal(off, OW, 5, 64, 5, "minecraft:oak_planks", null));
    }

    @Test
    void placingInsideZones() {
        assertEquals(ProtectionRules.ZONE, ProtectionRules.placeRefusal(PR, OW, 1, 61, 1, "minecraft:cobblestone", null));
        assertNull(ProtectionRules.placeRefusal(PR, OW, 11, 61, 1, "minecraft:cobblestone", null));
        assertNull(ProtectionRules.placeRefusal(PR, OW, 11, 61, 1, "minecraft:oak_planks", null),
                "built blocks may be placed outside zones");
    }

    @Test
    void areaTaskWorksOnlyInsideItsOwnBox() {
        ProtectionRules.Scope sel = new ProtectionRules.Scope(new Box(new Pos(2, 60, 2), new Pos(4, 62, 4)),
                ProtectionRules.Mode.AREA);
        assertNull(ProtectionRules.breakRefusal(PR, OW, 3, 61, 3, "minecraft:oak_planks", sel));
        assertNull(ProtectionRules.placeRefusal(PR, OW, 3, 61, 3, "minecraft:stone", sel));
        assertEquals(ProtectionRules.ZONE, ProtectionRules.breakRefusal(PR, OW, 6, 61, 6, "minecraft:stone", sel),
                "the rest of the zone stays protected");
        assertEquals(ProtectionRules.BUILT, ProtectionRules.breakRefusal(PR, OW, 30, 61, 6, "minecraft:glass", sel));
        assertEquals(ProtectionRules.NO_BREAK, ProtectionRules.breakRefusal(PR, OW, 3, 61, 3, "minecraft:chest", sel),
                "noBreak blocks stay protected even inside the task's box");
    }

    @Test
    void farmTaskOnlyHarvestsAndReplantsCrops() {
        ProtectionRules.Scope farm = new ProtectionRules.Scope(new Box(new Pos(0, 55, 0), new Pos(20, 75, 20)),
                ProtectionRules.Mode.CROPS);
        assertNull(ProtectionRules.breakRefusal(PR, OW, 5, 61, 5, "minecraft:wheat", farm));
        assertNull(ProtectionRules.placeRefusal(PR, OW, 5, 61, 5, "minecraft:carrots", farm));
        assertEquals(ProtectionRules.ZONE, ProtectionRules.breakRefusal(PR, OW, 5, 61, 5, "minecraft:dirt", farm));
        assertEquals(ProtectionRules.BUILT, ProtectionRules.breakRefusal(PR, OW, 15, 61, 15, "minecraft:oak_planks", farm),
                "a farm never breaks built blocks, not even in its range");
        assertEquals(ProtectionRules.ZONE, ProtectionRules.placeRefusal(PR, OW, 5, 61, 5, "minecraft:cobblestone", farm));
    }
}
