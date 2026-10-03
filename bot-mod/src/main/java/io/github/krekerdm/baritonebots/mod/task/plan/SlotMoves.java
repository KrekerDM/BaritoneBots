package io.github.krekerdm.baritonebots.mod.task.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Click planning for count-accurate item moves with {@code PICKUP} / {@code THROW} clicks. Pure logic (no Minecraft
 * classes) so it is unit-tested; {@code ContainerSession} and the tasks turn the plans into menu clicks.
 */
public final class SlotMoves {
    /** Which slot a planned click goes to. */
    public enum Target { SOURCE, DEST }

    /** One {@code PICKUP} click: button 0 = left (pick up / put down the whole cursor), 1 = right (one item). */
    public record Click(Target target, int button) {
    }

    /** How to throw {@code n} items of a stack. */
    public enum ThrowMode {
        /** One whole-stack throw (button 1). */
        WHOLE,
        /** {@code n} single-item throws (button 0). */
        SINGLES,
        /** Split {@code n} items into an empty slot with {@link #partial}, then one whole-stack throw there. */
        SPLIT
    }

    private SlotMoves() {
    }

    /**
     * Clicks that move {@code n} of the {@code count} items in the source slot into a destination slot that is empty
     * or holds the same item with room for {@code n}. Picks up the whole stack, then either right-clicks single
     * items into the destination and puts the rest back, or right-clicks single items back into the source and puts
     * the rest into the destination, whichever needs fewer clicks. The cursor ends empty.
     */
    public static List<Click> partial(int count, int n) {
        List<Click> out = new ArrayList<>();
        if (count <= 0 || n <= 0) {
            return out;
        }
        out.add(new Click(Target.SOURCE, 0));
        if (n >= count) {
            out.add(new Click(Target.DEST, 0));
            return out;
        }
        int keep = count - n;
        if (n <= keep) {
            for (int i = 0; i < n; i++) {
                out.add(new Click(Target.DEST, 1));
            }
            out.add(new Click(Target.SOURCE, 0));
        } else {
            for (int i = 0; i < keep; i++) {
                out.add(new Click(Target.SOURCE, 1));
            }
            out.add(new Click(Target.DEST, 0));
        }
        return out;
    }

    /** Number of clicks {@link #partial} plans. */
    public static int cost(int count, int n) {
        if (count <= 0 || n <= 0) {
            return 0;
        }
        return n >= count ? 2 : Math.min(n, count - n) + 2;
    }

    /** Cheapest way to throw {@code n} of the {@code count} items in a slot. */
    public static ThrowMode throwMode(int count, int n, boolean emptySlotAvailable) {
        if (n >= count) {
            return ThrowMode.WHOLE;
        }
        if (emptySlotAvailable && cost(count, n) + 1 < n) {
            return ThrowMode.SPLIT;
        }
        return ThrowMode.SINGLES;
    }

    /** Crafts needed so that {@code want} items result when one craft yields {@code perCraft} (ceil division). */
    public static int craftsFor(int want, int perCraft) {
        if (want <= 0) {
            return 0;
        }
        int per = Math.max(1, perCraft);
        return (want + per - 1) / per;
    }
}
