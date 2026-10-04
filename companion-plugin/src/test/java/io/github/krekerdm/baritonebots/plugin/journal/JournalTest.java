package io.github.krekerdm.baritonebots.plugin.journal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {
    private static JournalRecord rec(long time, JournalRecord.Action a, String actor) {
        return new JournalRecord(time, a, actor, "world", 1, 64, -2, "minecraft:stone", "minecraft:air");
    }

    @Test
    void codecRoundTripsAndEscapes() {
        JournalRecord r = new JournalRecord(1_700_000_000_000L, JournalRecord.Action.PLACE, "Bot_1", "my\tworld\\x",
                -30_000_000, -64, 29_999_999, "minecraft:air", "minecraft:oak_sign[rotation=4,waterlogged=false]\n");
        String line = JournalCodec.encode(r);
        assertFalse(line.contains("\n"), "a record is always one line");
        assertEquals(10, line.split("\t", -1).length, "raw tabs only separate fields");
        assertEquals(r, JournalCodec.decode(line));
    }

    @Test
    void codecRejectsBadLines() {
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode("2\t1\tB\ta\tw\t1\t2\t3\tx\ty"));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode("1\t1\tX\ta\tw\t1\t2\t3\tx\ty"));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode("1\tnope\tB\ta\tw\t1\t2\t3\tx\ty"));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode("1\t1\tB\ta\tw\t1\t2\t3\tx\\"));
    }

    @Test
    void timeWindow() {
        assertEquals(1_000_000L - 5 * 60_000L, TimeWindow.since(1_000_000L, 5));
        assertEquals(14 * 24 * 60, TimeWindow.maxJournalMinutes(14));
        assertEquals(24 * 60, TimeWindow.maxJournalMinutes(0), "at least one day");
        assertTrue(TimeWindow.validMinutes(1, 10));
        assertTrue(TimeWindow.validMinutes(10, 10));
        assertFalse(TimeWindow.validMinutes(0, 10));
        assertFalse(TimeWindow.validMinutes(11, 10));

        long day = TimeWindow.DAY_MS;
        List<LocalDate> dates = TimeWindow.datesCovering(day - 1, 2 * day + 5, ZoneOffset.UTC);
        assertEquals(List.of(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 2), LocalDate.of(1970, 1, 3)), dates);
        assertTrue(TimeWindow.datesCovering(10, 5, ZoneOffset.UTC).isEmpty());
    }

    @Test
    void indexCoversOnlyWhatItSaw() {
        JournalIndex index = new JournalIndex(TimeWindow.DAY_MS, 3, 1_000);
        assertFalse(index.covers("Bot", 999), "older than the enable time is only on disk");
        assertTrue(index.covers("Bot", 1_000));
        for (int i = 0; i < 5; i++) {
            index.add(rec(2_000 + i, i % 2 == 0 ? JournalRecord.Action.BREAK : JournalRecord.Action.PLACE, "Bot"));
        }
        assertEquals(3, index.size(), "per-bot cap");
        assertFalse(index.covers("bot", 2_001), "evicted records make the index incomplete before them");
        assertTrue(index.covers("BOT", 2_002));
        assertEquals(List.of(2_003L, 2_004L), index.query("Bot", 2_003).stream().map(JournalRecord::time).toList());

        JournalSummary sum = index.summary("Bot", 2_002, 2);
        assertEquals(2, sum.breaks());
        assertEquals(1, sum.places());
        assertEquals(List.of(2_003L, 2_004L), sum.latest().stream().map(JournalRecord::time).toList(), "newest last");

        index.prune(2_004 + TimeWindow.DAY_MS);
        assertEquals(1, index.size());
        assertFalse(index.covers("Bot", 2_002));
    }

    @Test
    void retention() {
        LocalDate today = LocalDate.of(2026, 10, 4);
        assertFalse(JournalFiles.isExpired(today.minusDays(14), today, 14));
        assertTrue(JournalFiles.isExpired(today.minusDays(15), today, 14));
    }
}
