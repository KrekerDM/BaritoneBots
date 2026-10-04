package io.github.krekerdm.baritonebots.manager.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.ContainerSnapshot;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import io.github.krekerdm.baritonebots.manager.planner.Planner;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cron, day / night, rule triggers, schedule firing, rule triggering + cooldown, config validation. */
class AutomationTest {
    @TempDir
    Path dir;
    private Manager m;

    @BeforeEach
    void setUp() throws Exception {
        m = new Manager(dir, true, true);
        m.initState();
        m.loop.awaitRun(() -> {
            m.config.patch(Json.obj("autopilot", Json.obj("supply", false, "sort", false, "idleWork", false,
                    "discovery", false, "stuckSec", 0)));
            m.config.addItem("bots", Json.obj("id", "bot1", "username", "Bot1", "serverId", "main"));
        });
    }

    @AfterEach
    void tearDown() {
        m.loop.shutdown(1_000);
    }

    private static LocalDateTime at(int h, int min) {
        return LocalDateTime.of(2026, 10, 5, h, min); // a Monday
    }

    // ------------------------------------------------------------------ pure parts

    @Test
    void cronMatchesLikeVixieCron() {
        Cron every30 = Cron.parse("*/30 * * * *");
        assertTrue(every30.matches(at(10, 0)));
        assertTrue(every30.matches(at(10, 30)));
        assertFalse(every30.matches(at(10, 15)));
        Cron work = Cron.parse("0 9-17/2 * * MON-FRI");
        assertTrue(work.matches(at(9, 0)));
        assertTrue(work.matches(at(17, 0)));
        assertFalse(work.matches(at(10, 0)));
        assertFalse(work.matches(LocalDateTime.of(2026, 10, 4, 9, 0)), "Sunday");
        Cron sunday = Cron.parse("0 0 * * 7");
        assertTrue(sunday.matches(LocalDateTime.of(2026, 10, 4, 0, 0)));
        Cron domOrDow = Cron.parse("0 12 1 * SAT"); // day 1 OR Saturday
        assertTrue(domOrDow.matches(LocalDateTime.of(2026, 10, 1, 12, 0)));
        assertTrue(domOrDow.matches(LocalDateTime.of(2026, 10, 10, 12, 0)));
        assertFalse(domOrDow.matches(LocalDateTime.of(2026, 10, 9, 12, 0)));
        assertEquals(at(11, 0), Cron.parse("0 * * * *").next(at(10, 0)));
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), Cron.parse("0 0 1 JAN *").next(at(10, 0)));
        for (String bad : List.of("", "* * * *", "60 * * * *", "* 24 * * *", "*/0 * * * *", "5-1 * * * *", "a * * * *")) {
            assertFalse(Cron.valid(bad), bad);
        }
    }

    @Test
    void dayAndNightFromLocalTimeOrWorldTime() {
        LocalTime day = LocalTime.of(7, 0);
        LocalTime night = LocalTime.of(22, 0);
        assertFalse(DayNight.isNight(null, LocalTime.of(12, 0), day, night));
        assertTrue(DayNight.isNight(null, LocalTime.of(23, 30), day, night));
        assertTrue(DayNight.isNight(null, LocalTime.of(6, 59), day, night));
        assertFalse(DayNight.isNight(null, LocalTime.of(7, 0), day, night));
        // night shift: "day" from 20:00 to 06:00
        assertTrue(DayNight.isNight(null, LocalTime.of(12, 0), LocalTime.of(20, 0), LocalTime.of(6, 0)));
        assertTrue(DayNight.isNight(18_000L, LocalTime.NOON, day, night), "world time wins when known");
        assertFalse(DayNight.isNight(24_000L + 1_000, LocalTime.MIDNIGHT, day, night));
        assertEquals(LocalTime.of(7, 5), DayNight.parse("7:05"));
        assertNull(DayNight.parse("25:00"));
    }

    @Test
    void triggersParseAndReject() {
        assertEquals("tool_low", Trigger.parse(Json.obj("event", "tool_low")).event());
        assertEquals(new Pos(1, 64, -3), Trigger.parse(Json.obj("containerFull", "1, 64, -3")).pos());
        assertEquals("c-1", Trigger.parse(Json.obj("containerFull", "c-1")).container());
        Trigger ib = Trigger.parse(Json.obj("itemBelow", Json.obj("item", "bread", "count", 16)));
        assertEquals("minecraft:bread", ib.item());
        assertEquals(16, ib.count());
        assertEquals(6.0, Trigger.parse(Json.obj("healthBelow", 6)).health());
        assertTrue(Trigger.parse(Json.obj("healthBelow", 6)).botScoped());
        assertEquals("Steve", Trigger.parse(Json.obj("playerOnline", "Steve")).player());
        assertEquals(":one_trigger", msg(Json.obj("event", "x", "healthBelow", 3)));
        assertEquals(":unknown_trigger", msg(Json.obj("when", 1)));
        assertEquals("itemBelow.count:range", msg(Json.obj("itemBelow", Json.obj("item", "bread", "count", 0))));
        assertEquals("healthBelow:type", msg(Json.obj("healthBelow", "lots")));
        assertEquals(Boolean.TRUE, RuleService.joinLeave("Steve joined the game", "Steve"));
        assertEquals(Boolean.FALSE, RuleService.joinLeave("[-] Steve left the game", "Steve"));
        assertEquals(Boolean.TRUE, RuleService.joinLeave("Игрок Steve зашёл на сервер", "Steve"));
        assertNull(RuleService.joinLeave("Steven joined the game", "Steve"));
        assertNull(RuleService.joinLeave("<Steve> hello", "Steve"));
    }

    private static String msg(JsonObject cond) {
        return assertThrows(IllegalArgumentException.class, () -> Trigger.parse(cond)).getMessage();
    }

    // ------------------------------------------------------------------ config validation

    @Test
    void schedulesAndRulesAreValidated() {
        ValidationException e = assertThrows(ValidationException.class, () -> m.loop.await(() -> m.config.addItem("schedules",
                Json.obj("id", "s1", "when", "every hour", "botIds", Json.arr("nobody"), "steps", Json.arr(Json.obj("type", "fly"))))));
        assertEquals("cron", e.fields().get("schedules[0].when"));
        assertEquals("not_found", e.fields().get("schedules[0].botIds"));
        assertEquals("unknown_type", e.fields().get("schedules[0].steps[0].type"));
        ValidationException r = assertThrows(ValidationException.class, () -> m.loop.await(() -> m.config.addItem("rules",
                Json.obj("id", "r1", "if", Json.obj("healthBelow", 0), "then", Json.arr()))));
        assertEquals("range", r.fields().get("rules[0].if.healthBelow"));
        assertEquals("required", r.fields().get("rules[0].then"));
        ValidationException d = assertThrows(ValidationException.class, () -> m.loop.await(() ->
                m.config.updateItem("servers", "main", Json.obj("nightStart", "late"))));
        assertEquals("pattern", d.fields().get("servers[0].nightStart"));
        m.loop.await(() -> m.config.addItem("schedules", Json.obj("id", "ok", "when", "night", "botIds", "all",
                "steps", Json.arr(Json.obj("type", "home")))));
        assertEquals(1, m.config.get().schedules().size());
    }

    // ------------------------------------------------------------------ schedules

    private BotState bot() {
        return m.loop.await(() -> m.bots.require("bot1"));
    }

    private long queued(String origin) {
        return m.loop.await(() -> bot().queue.items().stream().filter(e -> e.hasOrigin(origin)).count());
    }

    @Test
    void cronAndNightSchedulesFireOnTheirMinute() {
        m.loop.awaitRun(() -> {
            m.config.addItem("schedules", Json.obj("id", "sort", "name", "Sort", "when", "*/5 * * * *",
                    "botIds", Json.arr("bot1"), "steps", Json.arr(Json.obj("type", "sort_storage"))));
            m.config.addItem("schedules", Json.obj("id", "home", "when", "night", "botIds", Json.arr("bot1"),
                    "steps", Json.arr(Json.obj("type", "home"))));
        });
        ScheduleService s = m.automation.schedules();
        m.loop.awaitRun(() -> s.evaluate(at(21, 58)));
        assertEquals(0, queued("schedule:sort"));
        m.loop.awaitRun(() -> s.evaluate(at(22, 0)));
        assertEquals(1, queued("schedule:sort"), "cron minute matched");
        assertEquals(1, queued("schedule:home"), "night started (day → night)");
        m.loop.awaitRun(() -> s.evaluate(at(22, 1)));
        m.loop.awaitRun(() -> s.evaluate(at(22, 5)));
        assertEquals(1, queued("schedule:sort"), "still queued from the last run: skipped, no pile-up");
        assertEquals(1, queued("schedule:home"), "no new transition");
        m.loop.awaitRun(() -> m.dispatcher.clear(bot()));
        m.loop.awaitRun(() -> s.evaluate(at(22, 10)));
        assertEquals(1, queued("schedule:sort"));
        assertEquals(0, queued("schedule:home"));
        JsonObject view = m.loop.await(() -> s.view().get(0).getAsJsonObject());
        assertEquals(List.of("bot1"), Json.getStringList(view.getAsJsonObject("last"), "bots"));
    }

    @Test
    void scheduleEntriesAreSoftOrigins() {
        assertTrue(Planner.isSoftOrigin("schedule:x"));
        assertTrue(Planner.isSoftOrigin("rule:x"));
        assertFalse(Planner.isSoftOrigin("panel"));
        assertFalse(Planner.isPlannerOrigin("rule:x"));
    }

    // ------------------------------------------------------------------ rules

    private void event(String kind, String botId) {
        m.loop.awaitRun(() -> m.automation.onEvent(new ManagerEvent(1, System.currentTimeMillis(), kind, "warn", "bot",
                botId, kind, null, null, null)));
        m.loop.awaitRun(() -> { }); // the firing is posted
    }

    @Test
    void eventRulesFireOnTheBotWithCooldown() {
        m.loop.awaitRun(() -> m.config.addItem("rules", Json.obj("id", "tool", "if", Json.obj("event", "tool_low"),
                "then", Json.arr(Json.obj("type", "home")), "cooldownSec", 3600)));
        event("tool_low", "bot1");
        assertEquals(1, queued("rule:tool"), "bot event → runs on that bot");
        m.loop.awaitRun(() -> m.dispatcher.clear(bot()));
        event("tool_low", "bot1");
        assertEquals(0, queued("rule:tool"), "cooldown");
        event("rule_fired", "bot1");
        m.loop.awaitRun(() -> m.config.updateItem("rules", "tool", Json.obj("cooldownSec", 0)));
        event("food_low", "bot1");
        assertEquals(0, queued("rule:tool"), "other event kind");
        event("tool_low", "bot1");
        assertEquals(1, queued("rule:tool"));
    }

    @Test
    void containerFullFiresOnTheRisingEdge() {
        m.loop.awaitRun(() -> {
            m.worlds.get("main").applySections(Json.obj("containers", Json.arr(Json.obj("dim", "minecraft:overworld",
                    "pos", Json.obj("x", 5, "y", 64, "z", 5), "block", "minecraft:chest", "roles", Json.arr("inbox")))));
            m.config.addItem("rules", Json.obj("id", "full", "if", Json.obj("containerFull", "5,64,5"),
                    "then", Json.arr(Json.obj("type", "sort_storage")), "botIds", Json.arr("bot1"), "cooldownSec", 0));
        });
        snapshot(0);
        assertEquals(1, queued("rule:full"));
        m.loop.awaitRun(() -> m.dispatcher.clear(bot()));
        snapshot(0);
        assertEquals(0, queued("rule:full"), "still full: no new edge");
        snapshot(3);
        snapshot(0);
        assertEquals(1, queued("rule:full"), "emptied and full again");
    }

    private void snapshot(int free) {
        m.loop.awaitRun(() -> {
            m.worlds.onSnapshot("main", new ContainerSnapshot("minecraft:overworld", new Pos(5, 64, 5), "minecraft:chest", 27,
                    free, List.of(new ContainerSnapshot.SlotItem(0, "minecraft:dirt", 64)), System.currentTimeMillis(), false));
            m.automation.onSnapshot("main");
        });
    }

    @Test
    void itemBelowUsesTheIndexedStock() {
        m.loop.awaitRun(() -> m.config.addItem("rules", Json.obj("id", "bread", "if",
                Json.obj("itemBelow", Json.obj("item", "minecraft:bread", "count", 10)),
                "then", Json.arr(Json.obj("type", "obtain", "args", Json.obj("item", "minecraft:bread", "count", 16))),
                "botIds", Json.arr("bot1"))));
        m.loop.awaitRun(() -> m.automation.onSnapshot("main"));
        assertEquals(0, queued("rule:bread"), "nothing inspected yet: unknown, no firing");
        m.loop.awaitRun(() -> {
            m.worlds.get("main").applySections(Json.obj("containers", Json.arr(Json.obj("dim", "minecraft:overworld",
                    "pos", Json.obj("x", 1, "y", 64, "z", 1), "block", "minecraft:chest", "roles", Json.arr("storage")))));
            m.worlds.onSnapshot("main", new ContainerSnapshot("minecraft:overworld", new Pos(1, 64, 1), "minecraft:chest", 27,
                    26, List.of(new ContainerSnapshot.SlotItem(0, "minecraft:bread", 4)), System.currentTimeMillis(), false));
            m.automation.onSnapshot("main");
        });
        assertEquals(1, queued("rule:bread"));
    }
}
