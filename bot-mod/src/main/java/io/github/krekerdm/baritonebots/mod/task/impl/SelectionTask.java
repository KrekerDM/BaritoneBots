package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.ReplaceSchematic;
import baritone.api.schematic.ShellSchematic;
import baritone.api.schematic.WallsSchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code selection op box [block] [from]} with Baritone's builder, the way its {@code #sel} command does it:
 * <ul>
 *   <li>{@code clear}: {@code clearArea};</li>
 *   <li>{@code fill}: {@link FillSchematic} of {@code block};</li>
 *   <li>{@code walls} / {@code shell}: the fill wrapped in {@link WallsSchematic} (x/z faces) /
 *       {@link ShellSchematic} (all six faces);</li>
 *   <li>{@code replace}: {@link ReplaceSchematic} — only blocks matching {@code from} (globs) become {@code block}.</li>
 * </ul>
 * {@code IBuilderProcess.build(name, schematic, boxMin)}; builder inactive → ok. Builder paused (Baritone pauses
 * when it cannot place) → {@code missing_materials} with {@code data.missing {item:count}} = blocks still to place in
 * the box (loaded chunks) minus the inventory, or {@code stuck} when the inventory holds enough.
 */
public final class SelectionTask implements TaskExecutor {
    private static final long MAX_SCAN_VOLUME = 4_000_000;

    private String op;
    private Box box;
    private Block block;
    private List<Block> from = List.of();
    private int inactive;

    @Override
    public void start(TaskContext ctx) {
        JsonObject a = ctx.args();
        op = Json.getString(a, "op", "");
        if (!TaskTypes.SELECTION_OPS.contains(op)) {
            ctx.fail(Reasons.BAD_ARGS, "unknown selection op '" + op + "'", null);
            return;
        }
        box = Box.fromJson(a.get("box"));
        if (box == null) {
            ctx.fail(Reasons.BAD_ARGS, "selection needs 'box'", null);
            return;
        }
        BlockPos min = Positions.toBlockPos(box.min());
        BlockPos max = Positions.toBlockPos(box.max());
        IBuilderProcess builder = ctx.baritone().getBuilderProcess();
        if ("clear".equals(op)) {
            builder.clearArea(min, max);
            ctx.step("clearing " + box.min() + " .. " + box.max(), -1);
            return;
        }
        String blockId = Json.getString(a, "block", null);
        block = blockId == null ? null : McIds.blockById(blockId).orElse(null);
        if (block == null) {
            ctx.fail(Reasons.BAD_ARGS, "selection " + op + " needs a valid 'block'", null);
            return;
        }
        ISchematic schematic = new FillSchematic(box.width(), box.height(), box.length(), new BlockOptionalMeta(block));
        switch (op) {
            case "walls" -> schematic = new WallsSchematic(schematic);
            case "shell" -> schematic = new ShellSchematic(schematic);
            case "replace" -> {
                from = McIds.blocksMatching(TaskArgs.globs(a, "from"));
                if (from.isEmpty()) {
                    ctx.fail(Reasons.BAD_ARGS, "selection replace needs 'from' (blocks to replace)", null);
                    return;
                }
                schematic = new ReplaceSchematic(schematic, new BlockOptionalMetaLookup(from));
            }
            default -> {
            }
        }
        builder.build("BaritoneBots " + op, schematic, min);
        ctx.step(op + " " + box.min() + " .. " + box.max() + " with " + McIds.block(block), -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        IBuilderProcess b = ctx.baritone().getBuilderProcess();
        if (b.isActive() && b.isPaused()) {
            if (block == null) {
                ctx.fail(Reasons.STUCK, "Baritone builder is paused", null);
                return;
            }
            Map<String, Integer> missing = missing(ctx);
            if (missing.isEmpty()) {
                ctx.fail(Reasons.STUCK, "Baritone builder is paused", null);
            } else {
                ctx.fail(Reasons.MISSING_MATERIALS, "not enough " + McIds.block(block),
                        Json.obj("missing", TaskArgs.counts(missing)));
            }
            return;
        }
        if (b.isActive()) {
            inactive = 0;
        } else if (++inactive >= 5) {
            ctx.succeed("clear".equals(op) ? "area cleared" : "selection " + op + " done", null);
        }
    }

    /** Placements still needed in the box (loaded chunks only) minus what the inventory holds. */
    private Map<String, Integer> missing(TaskContext ctx) {
        Map<String, Integer> out = new TreeMap<>();
        ClientLevel level = ctx.bot().level();
        Item item = block.asItem();
        if (level == null || box.volume() > MAX_SCAN_VOLUME) {
            return out;
        }
        int needed = 0;
        int w = box.width();
        int h = box.height();
        int l = box.length();
        BlockPos min = Positions.toBlockPos(box.min());
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    if (!inMask(x, y, z, w, h, l)) {
                        continue;
                    }
                    BlockPos pos = min.offset(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(pos);
                    boolean needs = "replace".equals(op) ? from.contains(st.getBlock()) : st.getBlock() != block;
                    if (needs) {
                        needed++;
                    }
                }
            }
        }
        String id = item == Items.AIR ? McIds.block(block) : McIds.item(item);
        int have = item == Items.AIR ? 0 : Inv.count(ctx.player(), s -> s.getItem() == item);
        if (needed > have) {
            out.put(id, needed - have);
        }
        return out;
    }

    /** Same shapes as Baritone's mask schematics: walls = x/z faces, shell = all faces. */
    private boolean inMask(int x, int y, int z, int w, int h, int l) {
        boolean side = x == 0 || z == 0 || x == w - 1 || z == l - 1;
        return switch (op) {
            case "walls" -> side;
            case "shell" -> side || y == 0 || y == h - 1;
            default -> true;
        };
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (ctx.baritone() != null) {
            ctx.baritone().getBuilderProcess().onLostControl();
        }
    }
}
