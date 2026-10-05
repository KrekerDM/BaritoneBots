package io.github.krekerdm.baritonebots.manager.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player-built blocks (SPEC §5.7g). Pure, unit-tested.
 * <ul>
 * <li>{@link #DETECT}: what a {@code scan_blocks} query looks for to find houses near home.</li>
 * <li>{@link #STRONG}: blocks that natural terrain and mineshafts never have (doors, beds, glass, chests, torches,
 * ...); a cluster needs some of them to count as a house ({@link #isHouse}).</li>
 * <li>{@link #NO_BREAK}: what the bots never break outside the box of their own area task (Baritone's
 * {@code blocksToDisallowBreaking}); natural blocks — stone, dirt, ores, logs, leaves, plain and colored terracotta of
 * the badlands, cobblestone the bots use for pillars — stay breakable.</li>
 * </ul>
 */
public final class HouseBlocks {
    /** Never broken by default (protection.builtNoBreak); containers and beds are in protection.noBreak too. */
    public static final List<String> NO_BREAK = List.of(
            "minecraft:*_planks", "minecraft:*_stairs", "minecraft:*_slab",
            "minecraft:glass", "minecraft:tinted_glass", "minecraft:*_stained_glass", "minecraft:*glass_pane",
            "minecraft:*_door", "minecraft:*_trapdoor", "minecraft:*_bed", "minecraft:*_wool", "minecraft:*_carpet",
            "minecraft:*_concrete", "minecraft:*_glazed_terracotta",
            "minecraft:bricks", "minecraft:*_bricks", "minecraft:*_tiles",
            "minecraft:polished_*", "minecraft:smooth_stone", "minecraft:smooth_sandstone",
            "minecraft:smooth_red_sandstone", "minecraft:smooth_quartz", "minecraft:cut_*", "minecraft:chiseled_*",
            "minecraft:quartz_block", "minecraft:quartz_pillar",
            "minecraft:*_fence", "minecraft:*_fence_gate", "minecraft:*_wall",
            "minecraft:*lantern", "minecraft:*torch", "minecraft:*_sign", "minecraft:*_banner", "minecraft:ladder",
            "minecraft:iron_bars", "minecraft:bookshelf", "minecraft:crafting_table", "minecraft:*anvil",
            "minecraft:enchanting_table", "minecraft:brewing_stand", "minecraft:lectern", "minecraft:jukebox",
            "minecraft:cartography_table", "minecraft:fletching_table", "minecraft:smithing_table",
            "minecraft:loom", "minecraft:stonecutter", "minecraft:grindstone", "minecraft:composter",
            "minecraft:flower_pot", "minecraft:potted_*");

    /** Found by the house scan: the built blocks plus plain terracotta and cobblestone, which houses use too. */
    public static final List<String> DETECT;

    /** Only player builds (and villages) have these. */
    public static final List<String> STRONG = List.of(
            "minecraft:*_door", "minecraft:*_bed", "minecraft:glass", "minecraft:tinted_glass",
            "minecraft:*_stained_glass", "minecraft:*glass_pane", "minecraft:*_carpet", "minecraft:*_wool",
            "minecraft:*_concrete", "minecraft:*_glazed_terracotta", "minecraft:*lantern", "minecraft:torch",
            "minecraft:wall_torch", "minecraft:soul_torch", "minecraft:soul_wall_torch", "minecraft:copper_torch",
            "minecraft:copper_wall_torch", "minecraft:*_sign", "minecraft:*_banner", "minecraft:crafting_table",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:furnace",
            "minecraft:smoker", "minecraft:blast_furnace", "minecraft:bookshelf", "minecraft:flower_pot",
            "minecraft:potted_*", "minecraft:enchanting_table", "minecraft:*anvil", "minecraft:brewing_stand",
            "minecraft:jukebox", "minecraft:composter", "minecraft:*shulker_box");

    static {
        List<String> d = new ArrayList<>(NO_BREAK);
        d.addAll(List.of("minecraft:terracotta", "minecraft:*_terracotta", "minecraft:cobblestone",
                "minecraft:mossy_cobblestone", "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
                "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace", "minecraft:*shulker_box"));
        DETECT = List.copyOf(d);
    }

    /** A house needs at least this many detected blocks ... */
    public static final int MIN_BLOCKS = 10;
    /** ... with at least this many {@link #STRONG} ones (a door alone has two halves). */
    public static final int MIN_STRONG = 2;
    /** Wider clusters are not one house (a badlands mesa, a whole village street): skipped. */
    public static final int MAX_SIDE = 128;
    /** The zone reaches this far beyond the walls, and from the roof up. */
    public static final int MARGIN = 2;

    /** One {@code scan_blocks} cluster. */
    public record Cluster(Box box, int count, Map<String, Integer> ids) {
        /** Blocks of {@link #STRONG} kinds. */
        public int strong() {
            int n = 0;
            for (Map.Entry<String, Integer> e : ids.entrySet()) {
                if (Ids.matchesAny(STRONG, e.getKey())) {
                    n += e.getValue();
                }
            }
            return n;
        }
    }

    private HouseBlocks() {
    }

    /** Clusters of a {@code scan_blocks} answer. */
    public static List<Cluster> clusters(JsonObject data) {
        List<Cluster> out = new ArrayList<>();
        JsonArray a = data == null ? null : Json.getArr(data, "clusters");
        for (JsonElement e : a == null ? new JsonArray() : a) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            Box b = Box.fromJson(o.get("box"));
            if (b == null) {
                continue;
            }
            Map<String, Integer> ids = new LinkedHashMap<>();
            JsonObject io = Json.getObj(o, "ids");
            if (io != null) {
                io.entrySet().forEach(x -> ids.put(x.getKey(), x.getValue().getAsInt()));
            }
            out.add(new Cluster(b, Json.getInt(o, "count", 0), ids));
        }
        return out;
    }

    /** Enough built blocks, some only players place, and not wider than a house. */
    public static boolean isHouse(Cluster c) {
        return c.count() >= MIN_BLOCKS && c.strong() >= MIN_STRONG && c.box().width() <= MAX_SIDE
                && c.box().length() <= MAX_SIDE;
    }

    /** The protection zone of a house: {@link #MARGIN} blocks around the walls, one into the ground, roof + 2. */
    public static Box zoneOf(Box house) {
        return new Box(new Pos(house.min().x() - MARGIN, house.min().y() - 1, house.min().z() - MARGIN),
                new Pos(house.max().x() + MARGIN, house.max().y() + MARGIN, house.max().z() + MARGIN));
    }

    /** Zone boxes of the houses in a {@code scan_blocks} answer, overlapping ones merged. */
    public static List<Box> houseZones(JsonObject data) {
        List<Box> out = new ArrayList<>();
        for (Cluster c : clusters(data)) {
            if (isHouse(c)) {
                out.add(zoneOf(c.box()));
            }
        }
        return mergeOverlapping(out);
    }

    /** Unions boxes that intersect until none do. */
    public static List<Box> mergeOverlapping(List<Box> boxes) {
        List<Box> out = new ArrayList<>(boxes);
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < out.size(); i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    if (out.get(i).intersects(out.get(j))) {
                        out.set(i, out.get(i).union(out.get(j)));
                        out.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return out;
    }
}
