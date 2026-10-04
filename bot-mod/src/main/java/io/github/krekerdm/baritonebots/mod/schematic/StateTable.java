package io.github.krekerdm.baritonebots.mod.schematic;

import io.github.krekerdm.baritonebots.common.schematic.SchematicTransform;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything derived from a schematic block state, computed once per distinct state (block states are interned,
 * so identity maps are exact): the placed state ({@code state.mirror(m).rotate(r)}), its {@link BomRules} cost and
 * per-scan counters. Not thread-safe: one table per scan, client thread only.
 */
final class StateTable {
    /** Properties a builder cannot or need not reproduce; ignored when comparing world and schematic. */
    private static final Set<String> VOLATILE = Set.of("waterlogged", "distance", "persistent", "power", "powered",
            "triggered", "open", "age", "stage", "moisture", "note", "instrument", "honey_level", "has_book",
            "has_record", "has_bottle_0", "has_bottle_1", "has_bottle_2", "enabled", "occupied", "locked", "in_wall",
            "disarmed", "attached", "lit", "signal_fire", "bloom", "sculk_sensor_phase", "shrieking", "can_summon",
            "hatch", "dusted", "berries", "unstable", "bottom", "extended", "short", "level", "bites", "charges",
            "eye", "drag", "snowy", "cracked", "ominous", "trial_spawner_state", "vault_state", "crafting", "delay",
            "tilt", "leaves", "thickness", "natural", "creaking_heart_state");
    /** Neighbour-derived connection properties of fences, panes, walls, redstone, tripwire, chorus, mushrooms. */
    private static final Set<String> CONNECTIONS = Set.of("north", "east", "south", "west", "up", "down");

    static final class Info {
        final BlockState raw;
        final BlockState placed;
        final BomRules.Cost cost;
        int total;
        int remaining;
        int unloaded;

        Info(BlockState raw, BlockState placed, BomRules.Cost cost) {
            this.raw = raw;
            this.placed = placed;
            this.cost = cost;
        }
    }

    private final Mirror mirror;
    private final Rotation rotation;
    private final IdentityHashMap<BlockState, Info> infos = new IdentityHashMap<>();
    private final IdentityHashMap<BlockState, IdentityHashMap<BlockState, Boolean>> matches = new IdentityHashMap<>();
    private final IdentityHashMap<BlockState, BomRules.Cost> worldCosts = new IdentityHashMap<>();
    private BlockState lastRaw;
    private Info lastInfo;

    StateTable(SchematicTransform t) {
        this.mirror = mcMirror(t.mirror());
        this.rotation = mcRotation(t);
    }

    Info info(BlockState raw) {
        if (raw == lastRaw) {
            return lastInfo;
        }
        Info i = infos.get(raw);
        if (i == null) {
            i = new Info(raw, raw.mirror(mirror).rotate(rotation), cost(raw));
            infos.put(raw, i);
        }
        lastRaw = raw;
        lastInfo = i;
        return i;
    }

    Collection<Info> all() {
        return infos.values();
    }

    /** Same block, every property equal except volatile / neighbour-derived ones (cached per pair). */
    boolean matches(BlockState world, BlockState want) {
        if (world == want) {
            return true;
        }
        if (world.getBlock() != want.getBlock()) {
            return false;
        }
        IdentityHashMap<BlockState, Boolean> byWorld = matches.get(want);
        if (byWorld == null) {
            byWorld = new IdentityHashMap<>();
            matches.put(want, byWorld);
        }
        Boolean r = byWorld.get(world);
        if (r == null) {
            r = propsMatch(world, want);
            byWorld.put(world, r);
        }
        return r;
    }

    /** Items still needed when the world holds the right block with fewer parts (single vs double slab, candles). */
    void addShortfall(Map<String, Integer> out, Info want, BlockState world) {
        BomRules.Cost have = worldCosts.computeIfAbsent(world, StateTable::cost);
        want.cost.items().forEach((item, n) -> {
            int d = n - have.items().getOrDefault(item, 0);
            if (d > 0) {
                out.merge(item, d, Integer::sum);
            }
        });
    }

    static Mirror mcMirror(SchematicTransform.Mirror m) {
        return Mirror.valueOf(m.name());
    }

    static Rotation mcRotation(SchematicTransform t) {
        return Rotation.valueOf(t.mcRotationName());
    }

    /** {@link BomRules} cost of a state (properties read by name, item via {@code Block#asItem}). */
    static BomRules.Cost cost(BlockState s) {
        return BomRules.cost(McIds.block(s), name -> prop(s, name), StateTable::itemOf,
                s.isAir() || s.getBlock() instanceof LiquidBlock);
    }

    static String prop(BlockState s, String name) {
        for (Property<?> p : s.getProperties()) {
            if (p.getName().equals(name)) {
                return valueName(s, p);
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    /** Item id placed as this block, or null when the block has no item. */
    static String itemOf(String blockId) {
        Item item = McIds.blockById(blockId).map(Block::asItem).orElse(Items.AIR);
        return item == Items.AIR ? null : McIds.item(item);
    }

    private static boolean propsMatch(BlockState world, BlockState want) {
        Block b = want.getBlock();
        boolean connections = b instanceof CrossCollisionBlock || b instanceof WallBlock
                || b instanceof RedStoneWireBlock || b instanceof TripWireBlock || b instanceof PipeBlock
                || b instanceof HugeMushroomBlock;
        boolean derivedShape = b instanceof StairBlock || b instanceof BaseRailBlock;
        for (Property<?> p : want.getProperties()) {
            String n = p.getName();
            if (VOLATILE.contains(n) || (connections && CONNECTIONS.contains(n)) || (derivedShape && "shape".equals(n))) {
                continue;
            }
            if (!Objects.equals(world.getValue(p), want.getValue(p))) {
                return false;
            }
        }
        return true;
    }
}
