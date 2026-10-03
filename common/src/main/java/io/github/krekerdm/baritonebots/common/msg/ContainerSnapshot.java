package io.github.krekerdm.baritonebots.common.msg;

import io.github.krekerdm.baritonebots.common.geom.Pos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contents of a container block the bot opened (SPEC §2.5); sent when it opens and again when it closes
 * ({@code open=false}). {@code size} counts container slots only, {@code free} the empty ones.
 */
public record ContainerSnapshot(String dim, Pos pos, String block, int size, int free, List<SlotItem> items,
                                long time, boolean open) {
    public ContainerSnapshot {
        items = Copies.list(items);
    }

    /** Item totals by id. */
    public Map<String, Integer> totals() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (SlotItem s : items) {
            out.merge(s.item(), s.count(), Integer::sum);
        }
        return out;
    }

    /** One non-empty container slot. */
    public record SlotItem(int slot, String item, int count) {
    }
}
