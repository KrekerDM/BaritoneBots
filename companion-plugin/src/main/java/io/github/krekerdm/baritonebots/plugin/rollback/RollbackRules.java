package io.github.krekerdm.baritonebots.plugin.rollback;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.plugin.journal.JournalRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Which journal records a rollback may undo, and in which order. */
public final class RollbackRules {
    /** Blocks a broken block typically turns into; any of them may be replaced by the restored block. */
    private static final Set<String> EMPTY_LIKE = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:water", "minecraft:lava", "minecraft:bubble_column");

    private RollbackRules() {
    }

    /**
     * True when the block at the position still is what the bot left there, so restoring {@code before} undoes
     * only the bot's change. Compared by block id, not full state: neighbours legitimately change states (stairs
     * shape, fence connections, crop age, water level) and must not block a rollback. A different block means a
     * player or the world changed it since, and the position is left alone.
     */
    public static boolean canRestore(String after, String current) {
        String a = Ids.stripState(Ids.normalize(after));
        String c = Ids.stripState(Ids.normalize(current));
        if (a.equals(c)) {
            return true;
        }
        return EMPTY_LIKE.contains(a) && EMPTY_LIKE.contains(c);
    }

    /** Newest change first, so a position changed several times ends in its oldest {@code before}. */
    public static List<JournalRecord> undoOrder(List<JournalRecord> chronological) {
        List<JournalRecord> out = new ArrayList<>(chronological);
        // Stable sort keeps the write order of records with the same millisecond (multi-block placements).
        out.sort((x, y) -> Long.compare(x.time(), y.time()));
        Collections.reverse(out);
        return out;
    }
}
