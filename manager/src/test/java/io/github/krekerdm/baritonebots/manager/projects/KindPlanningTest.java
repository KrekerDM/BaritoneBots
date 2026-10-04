package io.github.krekerdm.baritonebots.manager.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Resolver;
import io.github.krekerdm.baritonebots.manager.process.MicrosoftLogin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Planning parts of the gather / clear / farm / ranch / smelt kinds, config validation, Microsoft login parsing. */
class KindPlanningTest {
    private static final GameData DATA = Fixtures.gameData();

    @TempDir
    Path dir;

    @Test
    void gatherTargetsAreBaselinePlusQuota() {
        Map<String, Integer> target = GatherKind.targets(Map.of("minecraft:stone", 64), Map.of("minecraft:stone", 10));
        assertEquals(74, target.get("minecraft:stone"));
        Resolver.Env env = new Resolver.Env(true, true, Set.of(GameData.SMELTING));
        // 20 in the targets already, 30 cobblestone elsewhere: smelt 30, mine the rest of the cobblestone
        Resolver.Plan p = GatherKind.plan(target, Map.of("minecraft:stone", 20), Map.of(),
                Map.of("minecraft:cobblestone", 30, "minecraft:coal", 64), DATA, env);
        assertTrue(p.actions().stream().anyMatch(a -> a.kind().equals(Resolver.SMELT) && a.item().equals("minecraft:stone")),
                p.actions().toString());
        Resolver.Plan met = GatherKind.plan(target, Map.of("minecraft:stone", 80), Map.of(), Map.of(), DATA, env);
        assertTrue(met.actions().isEmpty(), "quota met: nothing to do");
        Resolver.Plan transit = GatherKind.plan(target, Map.of("minecraft:stone", 20), Map.of("minecraft:stone", 54),
                Map.of(), DATA, env);
        assertTrue(transit.actions().isEmpty(), "what is on its way counts");
    }

    @Test
    void clearSlabsSamplesAndClearedBlocks() {
        Box box = new Box(new Pos(0, 60, 0), new Pos(19, 70, 9));
        List<Box> slabs = ClearKind.slabs(box, 3);
        assertEquals(3, slabs.size());
        assertEquals(box.volume(), slabs.stream().mapToLong(Box::volume).sum());
        assertEquals(1, ClearKind.slabs(new Box(new Pos(0, 0, 0), new Pos(5, 1, 5)), 4).size(), "min width 4");
        List<Pos> s = ClearKind.samples(slabs.getFirst());
        assertEquals(5, s.size());
        assertTrue(s.stream().allMatch(slabs.getFirst()::contains));
        assertEquals(1, ClearKind.samples(new Box(new Pos(1, 1, 1), new Pos(1, 1, 1))).size());
        assertTrue(ClearKind.cleared("minecraft:air"));
        assertTrue(ClearKind.cleared("minecraft:water[level=0]"));
        assertTrue(ClearKind.cleared("cave_air"));
        assertFalse(ClearKind.cleared("minecraft:stone"));
    }

    @Test
    void farmCentreRangeAndRanchSlaughter() {
        Box field = new Box(new Pos(10, 64, 10), new Pos(29, 64, 19));
        assertEquals(new Pos(19, 64, 14), FarmKind.centerOf(field));
        assertEquals(11, FarmKind.rangeFor(field));
        assertFalse(RanchKind.slaughter(12, 20));
        assertTrue(RanchKind.slaughter(20, 20));
        assertEquals(645_000, SmeltKind.cookMs(GameData.SMELTING, 64));
        assertEquals(325_000, SmeltKind.cookMs(GameData.BLASTING, 64));
    }

    @Test
    void smelterPicksInputAndFuel() {
        List<String> ores = List.of("minecraft:raw_*", "minecraft:sand");
        Smelter.Load l = Smelter.plan(GameData.SMELTING, Map.of("minecraft:raw_iron", 100, "minecraft:sand", 30,
                "minecraft:coal", 16), ores, List.of(), DATA, 64);
        assertEquals("minecraft:raw_iron", l.input(), "most stock first");
        assertEquals(64, l.count());
        assertEquals("minecraft:coal", l.fuel());
        assertEquals(8, l.fuelCount());
        Smelter.Load blast = Smelter.plan(GameData.BLASTING, Map.of("minecraft:sand", 300, "minecraft:raw_iron", 5,
                "minecraft:coal", 1), ores, List.of(), DATA, 64);
        assertEquals("minecraft:raw_iron", blast.input(), "sand cannot be blasted");
        assertEquals(5, blast.count());
        Smelter.Load noFuel = Smelter.plan(GameData.SMELTING, Map.of("minecraft:sand", 10), ores, List.of(), DATA, 64);
        assertNull(noFuel.fuel());
        assertNull(Smelter.plan(GameData.SMELTING, Map.of("minecraft:coal", 9), ores, List.of(), DATA, 64));
        Smelter.Load logs = Smelter.plan(GameData.SMELTING, Map.of("minecraft:sand", 64, "minecraft:oak_log", 64,
                "minecraft:coal", 64), ores, List.of("minecraft:*_log"), DATA, 64);
        assertEquals("minecraft:oak_log", logs.fuel(), "fuel globs restrict the fuel");
        assertEquals(43, logs.fuelCount());
        Smelter.Load bamboo = Smelter.plan(GameData.SMELTING, Map.of("minecraft:sand", 64, "minecraft:bamboo", 640),
                ores, List.of("minecraft:bamboo"), DATA, 64);
        assertEquals(16, bamboo.count(), "a load never needs more than 64 fuel items");
        assertEquals(64, bamboo.fuelCount());
    }

    @Test
    void microsoftLoginOutputIsParsed() {
        MicrosoftLogin.Line code = MicrosoftLogin.parse("Go to https://www.microsoft.com/link?otc=AB12CD34");
        assertEquals("code", code.kind());
        assertEquals("https://www.microsoft.com/link?otc=AB12CD34", code.url());
        assertEquals("AB12CD34", code.code());
        assertEquals("Steve_99", MicrosoftLogin.parse("[12:00:01] Logged into account Steve_99 successfully!").account());
        MicrosoftLogin.Line fail = MicrosoftLogin.parse("Failed to login with device code: java.io.IOException: boom");
        assertEquals("failed", fail.kind());
        assertTrue(fail.error().contains("boom"));
        assertNull(MicrosoftLogin.parse("Starting login process 0, enter 'login -cancel 0' to cancel the login process."));
    }

    @Test
    void kindConfigsAreValidated() throws Exception {
        Manager m = new Manager(dir, true, true);
        m.initState();
        try {
            m.loop.awaitRun(() -> m.worlds.get("main").applySections(Json.obj("areas", Json.arr(Json.obj("name", "Quarry",
                    "dim", "minecraft:overworld", "box", Json.obj("a", Json.obj("x", 0, "y", 50, "z", 0),
                            "b", Json.obj("x", 15, "y", 60, "z", 15)))))));
            JsonObject clear = m.loop.await(() -> m.projects.create(project("clear", Json.obj("area", "quarry"))));
            assertEquals(16, Box.fromJson(clear.getAsJsonObject("config").get("box")).width(), "area resolved to its box");
            assertEquals("Quarry".toLowerCase(), clear.getAsJsonObject("config").get("area").getAsString().toLowerCase());
            assertEquals("not_found", fields(m, "clear", Json.obj("area", "nowhere")).get("config.area"));
            assertEquals("required", fields(m, "clear", Json.obj()).get("config.box"));
            JsonObject gather = m.loop.await(() -> m.projects.create(project("gather",
                    Json.obj("quotas", Json.arr(Json.obj("item", "torch", "count", "64"))))));
            assertEquals(64, gather.getAsJsonObject("config").getAsJsonObject("quotas").get("minecraft:torch").getAsInt());
            assertEquals("required", fields(m, "gather", Json.obj("quotas", Json.obj())).get("config.quotas"));
            assertEquals("pattern", fields(m, "gather", Json.obj("quotas", Json.obj("torch", 1), "into", "a b")).get("config.into"));
            assertEquals("range", fields(m, "ranch", Json.obj("box", box(), "animal", "cow", "max", 4, "keep", 6)).get("config.keep"));
            assertEquals("sheep_only", fields(m, "ranch", Json.obj("box", box(), "animal", "cow", "shear", true)).get("config.shear"));
            JsonObject farm = m.loop.await(() -> m.projects.create(project("farm", Json.obj("box", box()))));
            assertEquals(5, Json.getInt(farm.getAsJsonObject("config"), "range", 0));
            assertEquals("required", fields(m, "smelt", Json.obj()).get("config.inputs"));
            JsonObject smelt = m.loop.await(() -> m.projects.create(project("smelt", Json.obj("inputs", "raw_iron, raw_gold"))));
            assertEquals(2, smelt.getAsJsonObject("config").getAsJsonArray("inputs").size());
            JsonObject sort = m.loop.await(() -> m.projects.create(project("sort", Json.obj())));
            assertEquals("draft", Json.getString(sort, "status", ""));
            assertTrue(sort.has("progress"));
        } finally {
            m.loop.shutdown(1_000);
        }
    }

    private static JsonObject box() {
        return Json.obj("a", Json.obj("x", 0, "y", 64, "z", 0), "b", Json.obj("x", 8, "y", 64, "z", 8));
    }

    private static JsonObject project(String kind, JsonObject config) {
        return Json.obj("name", kind + " test", "kind", kind, "serverId", "main", "bots", "any", "config", config);
    }

    private static Map<String, String> fields(Manager m, String kind, JsonObject config) {
        return assertThrows(ValidationException.class, () -> m.loop.await(() -> m.projects.create(project(kind, config))))
                .fields();
    }
}
