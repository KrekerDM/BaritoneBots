package io.github.krekerdm.baritonebots.mod.util;

import io.github.krekerdm.baritonebots.common.ids.Ids;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Conversions between Minecraft objects and the protocol's namespaced id strings. */
public final class McIds {
    private McIds() {
    }

    /** Item id of a stack, {@code null} for an empty stack. */
    public static String item(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : item(stack.getItem());
    }

    public static String item(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String block(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static String block(BlockState state) {
        return block(state.getBlock());
    }

    /** Full canonical state string, e.g. {@code minecraft:oak_stairs[facing=north,half=bottom,...]}. */
    public static String state(BlockState state) {
        return Ids.canonicalState(BlockStateParser.serialize(state));
    }

    public static String entity(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    public static String entityType(EntityType<?> type) {
        return EntityType.getKey(type).toString();
    }

    /** Dimension id of a level, e.g. {@code minecraft:overworld}. */
    public static String dim(Level level) {
        return level.dimension().identifier().toString();
    }

    public static Optional<Block> blockById(String id) {
        return lookup(BuiltInRegistries.BLOCK, id);
    }

    public static Optional<Item> itemById(String id) {
        return lookup(BuiltInRegistries.ITEM, id);
    }

    public static Optional<EntityType<?>> entityTypeById(String id) {
        return lookup(BuiltInRegistries.ENTITY_TYPE, id);
    }

    /** Every registered block whose id matches one of the globs (SPEC §1 glob rules). */
    public static List<Block> blocksMatching(Collection<String> globs) {
        List<Block> out = new ArrayList<>();
        if (globs.isEmpty()) {
            return out;
        }
        for (Block b : BuiltInRegistries.BLOCK) {
            if (Ids.matchesAny(globs, block(b))) {
                out.add(b);
            }
        }
        return out;
    }

    private static <T> Optional<T> lookup(Registry<T> registry, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        Identifier id = Identifier.tryParse(Ids.stripState(Ids.normalize(rawId)));
        if (id == null || !registry.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(registry.getValue(id));
    }
}
