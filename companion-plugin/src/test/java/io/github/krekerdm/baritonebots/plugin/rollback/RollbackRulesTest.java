package io.github.krekerdm.baritonebots.plugin.rollback;

import io.github.krekerdm.baritonebots.plugin.Tokens;
import io.github.krekerdm.baritonebots.plugin.journal.JournalRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackRulesTest {
    private static JournalRecord rec(long time, String before, String after) {
        return new JournalRecord(time, JournalRecord.Action.BREAK, "Bot", "world", 0, 64, 0, before, after);
    }

    @Test
    void restoresOnlyWhatTheBotLeft() {
        assertTrue(RollbackRules.canRestore("minecraft:oak_stairs[facing=east]", "minecraft:oak_stairs[facing=west]"),
                "states of the same block may differ");
        assertTrue(RollbackRules.canRestore("minecraft:air", "minecraft:water[level=3]"), "empty-like blocks are interchangeable");
        assertFalse(RollbackRules.canRestore("minecraft:air", "minecraft:dirt"), "someone else built there since");
        assertFalse(RollbackRules.canRestore("minecraft:cobblestone", "minecraft:air"), "someone else broke it since");
    }

    @Test
    void undoesNewestFirstKeepingSameMillisecondOrder() {
        JournalRecord a = rec(10, "minecraft:stone", "minecraft:air");
        JournalRecord b = rec(20, "minecraft:air", "minecraft:dirt");
        JournalRecord c1 = rec(30, "minecraft:air", "minecraft:red_bed[part=foot]");
        JournalRecord c2 = rec(30, "minecraft:air", "minecraft:red_bed[part=head]");
        assertEquals(List.of(c2, c1, b, a), RollbackRules.undoOrder(List.of(b, a, c1, c2)));
    }

    @Test
    void tokens() {
        String t = Tokens.generate();
        assertEquals(Tokens.LENGTH, t.length());
        assertNotEquals(t, Tokens.generate());
        assertTrue(Tokens.matches(t, t));
        assertFalse(Tokens.matches(t, t + "x"));
        assertFalse(Tokens.matches(t, ""));
    }
}
