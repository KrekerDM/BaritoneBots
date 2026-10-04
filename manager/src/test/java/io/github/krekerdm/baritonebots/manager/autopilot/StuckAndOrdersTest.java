package io.github.krekerdm.baritonebots.manager.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.config.ConfigStore;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.config.Secrets;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.gamedata.Fixtures;
import io.github.krekerdm.baritonebots.manager.gamedata.GameData;
import io.github.krekerdm.baritonebots.manager.planner.Resolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stuck detection (SPEC §5.7b), standing-order deficits and the autopilot / orders settings. */
class StuckAndOrdersTest {
    private static final GameData DATA = Fixtures.gameData();
    private static final Resolver.Env ENV = new Resolver.Env(true, true, Set.of(GameData.SMELTING));

    @TempDir
    Path dir;

    private static StuckDetector.Sample at(String task, double x, String step, double progress) {
        return new StuckDetector.Sample(task, "mine", false, x, 64, 0, step, progress, "GoalBlock{1,2,3}");
    }

    @Test
    void stuckOnlyWithoutMovementOrProgressForTheWholeWindow() {
        StuckDetector d = new StuckDetector();
        long t = 1_000_000;
        assertFalse(d.update("b", at("t1", 0, "mining", 0.1), t, 60_000));
        assertFalse(d.update("b", at("t1", 0.4, "mining", 0.1), t + 59_000, 60_000), "jitter under a block is no movement");
        assertTrue(d.update("b", at("t1", 0.4, "mining", 0.1), t + 60_000, 60_000));
        assertFalse(d.update("b", at("t1", 0.4, "mining", 0.1), t + 61_000, 60_000), "fires once, then the window restarts");

        StuckDetector moving = new StuckDetector();
        moving.update("b", at("t1", 0, "a", 0), t, 60_000);
        moving.update("b", at("t1", 2, "a", 0), t + 50_000, 60_000);
        assertFalse(moving.update("b", at("t1", 2, "a", 0), t + 100_000, 60_000), "moved at 50 s: 50 s still");
        assertTrue(moving.update("b", at("t1", 2, "a", 0), t + 110_000, 60_000));

        StuckDetector progressing = new StuckDetector();
        progressing.update("b", at("t1", 0, "a", 0.1), t, 60_000);
        progressing.update("b", at("t1", 0, "a", 0.2), t + 40_000, 60_000);
        assertFalse(progressing.update("b", at("t1", 0, "a", 0.2), t + 70_000, 60_000), "task progress counts");
        progressing.update("b", at("t1", 0, "b", 0.2), t + 90_000, 60_000);
        assertFalse(progressing.update("b", at("t1", 0, "b", 0.2), t + 140_000, 60_000), "a new step counts");

        StuckDetector other = new StuckDetector();
        other.update("b", at("t1", 0, "a", 0), t, 60_000);
        assertFalse(other.update("b", at("t2", 0, "a", 0), t + 90_000, 60_000), "a new task restarts the window");
        assertFalse(other.update("b", new StuckDetector.Sample("t3", "guard", false, 0, 64, 0, null, -1, null),
                t + 200_000, 60_000));
        assertFalse(other.update("b", new StuckDetector.Sample("t3", "guard", false, 0, 64, 0, null, -1, null),
                t + 900_000, 60_000), "guard stands still by design");
        assertFalse(other.update("b", at("t4", 0, "a", 0), t, 0), "0 = off");
    }

    @Test
    void ordersTurnOnBelowMinAndFillUpToMax() {
        assertTrue(OrdersSource.active(false, 10, 0, 64, 128), "below min");
        assertFalse(OrdersSource.active(false, 100, 0, 64, 128), "between min and max: idle until it drops below min");
        assertTrue(OrdersSource.active(true, 100, 0, 64, 128), "once active, keep going up to max");
        assertFalse(OrdersSource.active(true, 100, 28, 64, 128), "what is in transit counts");
        assertFalse(OrdersSource.active(true, 130, 0, 64, 128));
    }

    @Test
    void orderDeficitsResolveLikeBuildDeficits() {
        // 20 torches stocked, keep 64: 44 short; sticks and coal in storage → one craft of 44 (11 crafts)
        Resolver.Plan craft = OrdersSource.plan("minecraft:torch", 64, 20, 0,
                Map.of("minecraft:stick", 20, "minecraft:coal", 20), DATA, ENV);
        Resolver.Row row = craft.rows().getFirst();
        assertEquals(44, row.deficit());
        Resolver.Action a = craft.actions().stream().filter(x -> x.kind().equals(Resolver.CRAFT)).findFirst().orElseThrow();
        assertEquals(44, a.count());
        assertTrue(a.ready());

        // torches in other storage: haul them first
        Resolver.Plan haul = OrdersSource.plan("minecraft:torch", 64, 20, 4, Map.of("minecraft:torch", 100), DATA, ENV);
        Resolver.Action h = haul.actions().getFirst();
        assertEquals(Resolver.HAUL, h.kind());
        assertEquals(40, h.count(), "64 − 20 stocked − 4 in transit");

        // cobblestone: mine stone
        Resolver.Plan mine = OrdersSource.plan("minecraft:cobblestone", 128, 0, 0, Map.of(), DATA, ENV);
        assertEquals(Resolver.MINE, mine.actions().getFirst().kind());
        assertEquals(128, mine.actions().getFirst().count());

        // coal does not exist in the fixture world: manual
        Resolver.Plan manual = OrdersSource.plan("minecraft:coal", 16, 0, 0, Map.of(), DATA, ENV);
        assertEquals(Map.of("minecraft:coal", 16), manual.manual());
    }

    private ConfigStore store() throws IOException {
        Secrets secrets = new Secrets(dir.resolve("secrets.json"));
        secrets.load();
        ConfigStore s = new ConfigStore(dir.resolve("config.json"), secrets);
        s.load();
        return s;
    }

    @Test
    void autopilotSettingsAndPerBotOverride() throws IOException {
        ConfigStore s = store();
        ManagerConfig.AutopilotCfg ap = s.get().autopilot();
        assertTrue(ap.supply() && ap.sort() && ap.idleWork() && ap.discovery());
        assertEquals(32, ap.discoveryRadius());
        assertEquals(60, ap.stuckSec());
        assertEquals("misc", ap.categories().getLast().name());
        s.addItem("bots", Json.obj("id", "b1", "username", "Bot1", "serverId", "main",
                "autopilot", Json.obj("supply", false, "foodMin", 20, "bogus", 1)));
        ManagerConfig.AutopilotCfg b1 = ap.forBot(s.get().bot("b1").orElseThrow());
        assertFalse(b1.supply());
        assertEquals(20, b1.foodMin());
        assertTrue(b1.sort(), "keys not overridden keep the global value");
        assertThrows(ValidationException.class, () -> s.patch(Json.obj("autopilot",
                Json.obj("categories", Json.arr(Json.obj("name", "Bad Name", "globs", Json.arr()))))));
    }

    @Test
    void ordersAreValidatedAndDefaulted() throws IOException {
        ConfigStore s = store();
        s.addItem("orders", Json.obj("id", "torches", "serverId", "main", "item", "minecraft:torch", "min", 64,
                "max", 128));
        ManagerConfig.OrderDef o = s.get().orders().getFirst();
        assertTrue(o.enabled());
        assertEquals("storage", o.into(), "default delivery: storage");
        assertEquals(128, o.target());
        s.addItem("orders", Json.obj("id", "food", "serverId", "main", "item", "bread", "min", 32, "into", "sorted:food"));
        assertEquals(32, s.get().orders().get(1).target(), "no max: fill to min");
        assertThrows(ValidationException.class, () -> s.addItem("orders", Json.obj("id", "x", "serverId", "nope",
                "item", "torch", "min", 1)), "unknown server");
        assertThrows(ValidationException.class, () -> s.addItem("orders", Json.obj("id", "y", "serverId", "main",
                "item", "torch", "min", 10, "max", 5)), "max below min");
        assertThrows(ValidationException.class, () -> s.addItem("orders", Json.obj("id", "z", "serverId", "main",
                "item", "torch", "min", 10, "into", "sorted:Bad Cat")), "bad into");
        assertThrows(ValidationException.class, () -> s.addItem("orders", Json.obj("id", "torches", "serverId", "main",
                "item", "torch", "min", 10)), "duplicate id");
    }
}
