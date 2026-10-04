package io.github.krekerdm.baritonebots.manager.automation;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * Day or night for {@code day} / {@code night} schedules. A bot's world time would be the natural source (night =
 * ticks 13000..22999 of 24000), but {@code BotStatus} carries no world time today, so callers pass {@code null} and
 * the server profile's local {@code dayStart} / {@code nightStart} decide. Pure.
 */
public final class DayNight {
    static final int NIGHT_FROM = 13_000;
    static final int NIGHT_TO = 23_000;

    private DayNight() {
    }

    /** {@code H:MM} / {@code HH:MM} (24 h), or null when invalid. */
    public static LocalTime parse(String hhmm) {
        if (hhmm == null || !hhmm.trim().matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        String t = hhmm.trim();
        try {
            return LocalTime.parse(t.length() == 4 ? "0" + t : t);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * @param worldDayTime the world's day time in ticks (taken mod 24000), or null when unknown
     * @param now          local wall-clock time
     * @param dayStart     local time the day starts
     * @param nightStart   local time the night starts (before or after {@code dayStart})
     */
    public static boolean isNight(Long worldDayTime, LocalTime now, LocalTime dayStart, LocalTime nightStart) {
        if (worldDayTime != null) {
            long t = Math.floorMod(worldDayTime, 24_000L);
            return t >= NIGHT_FROM && t < NIGHT_TO;
        }
        if (dayStart.equals(nightStart)) {
            return false;
        }
        if (dayStart.isBefore(nightStart)) {
            return now.isBefore(dayStart) || !now.isBefore(nightStart);
        }
        return !now.isBefore(nightStart) && now.isBefore(dayStart);
    }
}
