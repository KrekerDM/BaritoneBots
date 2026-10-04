package io.github.krekerdm.baritonebots.mod.task.plan;

import io.github.krekerdm.baritonebots.common.ids.Ids;

import java.util.Map;
import java.util.Optional;

/**
 * Default breeding food per animal type for {@code breed} when no {@code food} is given. Other animals fall back to
 * the entity's own food check ({@code Animal#isFood}, item tags synced to the client) against the inventory.
 */
public final class AnimalFood {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("minecraft:cow", "minecraft:wheat"),
            Map.entry("minecraft:mooshroom", "minecraft:wheat"),
            Map.entry("minecraft:sheep", "minecraft:wheat"),
            Map.entry("minecraft:goat", "minecraft:wheat"),
            Map.entry("minecraft:pig", "minecraft:carrot"),
            Map.entry("minecraft:chicken", "minecraft:wheat_seeds"),
            Map.entry("minecraft:rabbit", "minecraft:carrot"),
            Map.entry("minecraft:horse", "minecraft:golden_carrot"),
            Map.entry("minecraft:donkey", "minecraft:golden_carrot"),
            Map.entry("minecraft:llama", "minecraft:hay_block"),
            Map.entry("minecraft:trader_llama", "minecraft:hay_block"),
            Map.entry("minecraft:turtle", "minecraft:seagrass"));

    private AnimalFood() {
    }

    /** Default food item id for an entity type id ({@code cow} or {@code minecraft:cow}), if known. */
    public static Optional<String> defaultFood(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(DEFAULTS.get(Ids.normalize(entityId.trim())));
    }
}
