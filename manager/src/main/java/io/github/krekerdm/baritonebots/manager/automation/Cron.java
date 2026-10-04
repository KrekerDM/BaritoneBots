package io.github.krekerdm.baritonebots.manager.automation;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * A 5-field cron expression ({@code minute hour day-of-month month day-of-week}) as in Vixie cron: {@code *},
 * {@code a}, {@code a-b}, {@code a,b}, steps ({@code *}{@code /n}, {@code a-b/n}, {@code a/n}), month names
 * {@code JAN..DEC}, weekday names {@code SUN..SAT}, day of week 0-7 (0 and 7 = Sunday). When both day fields are
 * restricted a day matches if either matches. Immutable, pure.
 */
public final class Cron {
    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP",
            "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    private final String text;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet doms;
    private final BitSet months;
    private final BitSet dows;
    private final boolean domAny;
    private final boolean dowAny;

    private Cron(String text, BitSet[] f, boolean domAny, boolean dowAny) {
        this.text = text;
        this.minutes = f[0];
        this.hours = f[1];
        this.doms = f[2];
        this.months = f[3];
        this.dows = f[4];
        this.domAny = domAny;
        this.dowAny = dowAny;
    }

    /** @throws IllegalArgumentException with a short reason when the expression is invalid */
    public static Cron parse(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("empty");
        }
        String[] f = expr.trim().split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("needs 5 fields");
        }
        BitSet dow = field(f[4], 0, 7, DAYS, 0);
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        BitSet[] fields = {field(f[0], 0, 59, null, 0), field(f[1], 0, 23, null, 0), field(f[2], 1, 31, null, 0),
                field(f[3], 1, 12, MONTHS, 1), dow};
        return new Cron(expr.trim(), fields, f[2].equals("*"), f[4].equals("*"));
    }

    public static boolean valid(String expr) {
        try {
            parse(expr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static BitSet field(String s, int min, int max, List<String> names, int nameBase) {
        BitSet out = new BitSet(max + 1);
        for (String part : s.split(",", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("empty list item in '" + s + "'");
            }
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = number(part.substring(slash + 1), 1, max, null, 0);
                range = part.substring(0, slash);
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] ab = range.split("-", 2);
                from = number(ab[0], min, max, names, nameBase);
                to = number(ab[1], min, max, names, nameBase);
                if (to < from) {
                    throw new IllegalArgumentException("bad range '" + range + "'");
                }
            } else {
                from = number(range, min, max, names, nameBase);
                to = slash >= 0 ? max : from;
            }
            for (int v = from; v <= to; v += step) {
                out.set(v);
            }
        }
        return out;
    }

    private static int number(String s, int min, int max, List<String> names, int nameBase) {
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (names != null && names.contains(t)) {
            return names.indexOf(t) + nameBase;
        }
        try {
            int v = Integer.parseInt(t);
            if (v < min || v > max) {
                throw new IllegalArgumentException("'" + s + "' outside " + min + "-" + max);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a number");
        }
    }

    /** Does the minute {@code t} (seconds ignored) match? */
    public boolean matches(LocalDateTime t) {
        if (!minutes.get(t.getMinute()) || !hours.get(t.getHour()) || !months.get(t.getMonthValue())) {
            return false;
        }
        boolean dom = doms.get(t.getDayOfMonth());
        int d = t.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : t.getDayOfWeek().getValue();
        boolean dow = dows.get(d);
        if (domAny && dowAny) {
            return true;
        }
        if (domAny) {
            return dow;
        }
        if (dowAny) {
            return dom;
        }
        return dom || dow;
    }

    /** The next matching minute strictly after {@code t} (searches about 5 years; null when there is none). */
    public LocalDateTime next(LocalDateTime t) {
        LocalDateTime c = t.withSecond(0).withNano(0).plusMinutes(1);
        LocalDateTime end = c.plusYears(5);
        while (c.isBefore(end)) {
            if (!months.get(c.getMonthValue())) {
                c = c.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
            } else if (!hours.get(c.getHour())) {
                c = c.plusHours(1).withMinute(0);
            } else if (matches(c)) {
                return c;
            } else {
                c = c.plusMinutes(1);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return text;
    }
}
