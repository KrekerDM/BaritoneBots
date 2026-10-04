package io.github.krekerdm.baritonebots.manager.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schedules (SPEC §5.7b, config {@code schedules[]}): evaluated once per minute on the manager loop. A cron schedule
 * fires in every minute its expression matches (missed minutes up to 10 back are caught up); {@code day} /
 * {@code night} fire when the day or night starts (a transition, never at manager start). Day and night come from
 * the world day time an online bot of that server reports ({@code BotStatus.dayTime}), else from the server profile's
 * local {@code dayStart} / {@code nightStart} ({@link DayNight}). Loop-owned.
 */
public final class ScheduleService {
    public static final String DAY = "day";
    public static final String NIGHT = "night";
    static final int CATCH_UP_MINUTES = 10;

    private final Manager m;
    private final StepRunner runner;
    private final Map<String, Cron> crons = new HashMap<>();
    /** schedule id + "/" + server id → night at the last evaluation. */
    private final Map<String, Boolean> night = new HashMap<>();
    private final Map<String, JsonObject> status = new HashMap<>();
    private LocalDateTime last;

    ScheduleService(Manager m, StepRunner runner) {
        this.m = m;
        this.runner = runner;
    }

    /** The minute timer: evaluates every minute since the last evaluation (at most {@link #CATCH_UP_MINUTES}). */
    void tick() {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime from = last == null || last.isBefore(now.minusMinutes(CATCH_UP_MINUTES)) ? now : last.plusMinutes(1);
        for (LocalDateTime t = from; !t.isAfter(now); t = t.plusMinutes(1)) {
            evaluate(t);
        }
    }

    /** Evaluates every enabled schedule for the minute {@code t}. */
    public void evaluate(LocalDateTime t) {
        LocalDateTime minute = t.withSecond(0).withNano(0);
        if (minute.equals(last)) {
            return;
        }
        last = minute;
        ManagerConfig cfg = m.config.get();
        for (ManagerConfig.ScheduleDef s : cfg.schedules()) {
            if (!s.enabled()) {
                continue;
            }
            String when = s.when() == null ? "" : s.when().trim();
            if (DAY.equalsIgnoreCase(when) || NIGHT.equalsIgnoreCase(when)) {
                boolean wantNight = NIGHT.equalsIgnoreCase(when);
                for (ManagerConfig.ServerProfile sp : cfg.servers()) {
                    if (s.serverId() != null && !s.serverId().equalsIgnoreCase(sp.id())) {
                        continue;
                    }
                    boolean isNight = isNight(sp, worldDayTime(sp.id()), minute.toLocalTime());
                    Boolean before = night.put(s.id() + "/" + sp.id(), isNight);
                    if (before != null && before != isNight && isNight == wantNight) {
                        fire(s, sp.id(), when);
                    }
                }
                continue;
            }
            Cron c = cron(when);
            if (c != null && c.matches(minute)) {
                fire(s, s.serverId(), when);
            }
        }
    }

    /** @param worldDayTime the server's world day time reported by an online bot, or null to use the profile's hours */
    static boolean isNight(ManagerConfig.ServerProfile sp, Long worldDayTime, LocalTime now) {
        LocalTime day = DayNight.parse(sp.dayStart());
        LocalTime nightAt = DayNight.parse(sp.nightStart());
        return DayNight.isNight(worldDayTime, now, day == null ? LocalTime.of(7, 0) : day,
                nightAt == null ? LocalTime.of(22, 0) : nightAt);
    }

    /** World day time from the freshest status of an online bot on {@code serverId}, or null when none reports one. */
    private Long worldDayTime(String serverId) {
        Long best = null;
        long bestAt = Long.MIN_VALUE;
        for (BotState b : m.bots.all()) {
            if (!b.online() || b.status == null || b.status.dayTime() == null
                    || !String.valueOf(serverId).equalsIgnoreCase(String.valueOf(b.def.serverId()))) {
                continue;
            }
            if (b.status.time() > bestAt) {
                bestAt = b.status.time();
                best = b.status.dayTime();
            }
        }
        return best;
    }

    private Cron cron(String expr) {
        if (!crons.containsKey(expr)) {
            Cron c = null;
            try {
                c = Cron.parse(expr);
            } catch (IllegalArgumentException ignored) {
                // stays null: the validator rejects such schedules, a stale one is simply never due
            }
            crons.put(expr, c);
        }
        return crons.get(expr);
    }

    /** Runs a schedule now (also {@code POST /api/schedules/{id}/run}); returns the bots it was queued on. */
    public List<String> fire(ManagerConfig.ScheduleDef s, String serverId, String why) {
        String name = s.name() == null || s.name().isBlank() ? s.id() : s.name();
        List<BotState> bots = runner.pick(StepRunner.Target.of(s.botIds()), serverId, null);
        List<String> queued = new ArrayList<>();
        String error = null;
        for (BotState b : bots) {
            try {
                if (runner.run(b, s.steps(), StepRunner.ORIGIN_SCHEDULE + s.id(), s.high(), name) > 0) {
                    queued.add(b.id);
                }
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
                break;
            }
        }
        JsonObject st = Json.obj("lastFiredAt", System.currentTimeMillis(), "why", why, "bots", Json.arrOf(queued));
        if (error != null) {
            st.addProperty("error", error);
            m.event("schedule_failed", Levels.WARN, null, "event.schedule.failed", Map.of("name", name, "error", error),
                    Json.obj("scheduleId", s.id()));
        } else if (queued.isEmpty()) {
            st.addProperty("error", bots.isEmpty() ? "no bot available" : "still busy with the previous run");
            m.event("schedule_skipped", Levels.WARN, null, "event.schedule.skipped",
                    Map.of("name", name, "reason", st.get("error").getAsString()), Json.obj("scheduleId", s.id()));
        } else {
            m.event("schedule_fired", Levels.INFO, queued.size() == 1 ? queued.getFirst() : null, "event.schedule.fired",
                    Map.of("name", name, "bots", String.join(", ", queued)), Json.obj("scheduleId", s.id(),
                            "bots", Json.arrOf(queued)));
        }
        status.put(s.id(), st);
        return queued;
    }

    /** {@code GET /api/schedules}: the config entries with {@code next} (cron), {@code night} and the last firing. */
    public JsonArray view() {
        JsonArray out = new JsonArray();
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        for (ManagerConfig.ScheduleDef s : m.config.get().schedules()) {
            JsonObject o = Json.toObject(s);
            String when = s.when() == null ? "" : s.when().trim();
            if (!DAY.equalsIgnoreCase(when) && !NIGHT.equalsIgnoreCase(when)) {
                Cron c = cron(when);
                LocalDateTime next = c == null ? null : c.next(now);
                o.addProperty("next", next == null ? null : next.toString());
            } else {
                JsonObject n = new JsonObject();
                for (ManagerConfig.ServerProfile sp : m.config.get().servers()) {
                    if (s.serverId() == null || s.serverId().equalsIgnoreCase(sp.id())) {
                        n.addProperty(sp.id(), isNight(sp, worldDayTime(sp.id()), now.toLocalTime()));
                    }
                }
                o.add("night", n);
            }
            JsonObject st = status.get(s.id());
            if (st != null) {
                o.add("last", st.deepCopy());
            }
            out.add(o);
        }
        return out;
    }
}
