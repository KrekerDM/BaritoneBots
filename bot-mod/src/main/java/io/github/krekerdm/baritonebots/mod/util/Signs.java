package io.github.krekerdm.baritonebots.mod.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Labels on containers (SPEC §5.7e): sign texts and item frames attached to a container block. A sign counts when it
 * is a wall (or wall hanging) sign fixed to the container, a standing sign on top of it, a ceiling hanging sign under
 * it, or a wall sign fixed to the block right above the container (the usual "label above the chest"). Both halves
 * of a double chest count. Client thread only.
 */
public final class Signs {
    private Signs() {
    }

    /** Lower-cased sign text (null = no sign) and the framed item id (null = no frame). */
    public record Info(String signText, String frameItem) {
        public static final Info NONE = new Info(null, null);
    }

    /** Framed item per block the frame hangs on, for every item frame inside {@code area}. */
    public static Map<BlockPos, String> frames(ClientLevel level, AABB area) {
        Map<BlockPos, String> out = new HashMap<>();
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, area)) {
            ItemStack item = f.getItem();
            if (item.isEmpty() || f.getDirection() == null) {
                continue;
            }
            BlockPos attached = f.getPos().relative(f.getDirection().getOpposite());
            out.putIfAbsent(attached.immutable(), McIds.item(item));
        }
        return out;
    }

    /** Sign text and framed item of the container at {@code pos}; {@code frames} from {@link #frames}. */
    public static Info near(ClientLevel level, BlockPos pos, Map<BlockPos, String> frames) {
        List<BlockPos> parts = parts(level, pos);
        Set<String> texts = new LinkedHashSet<>();
        String frame = null;
        for (BlockPos p : parts) {
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (attachedTo(level.getBlockState(n), n, p, d)) {
                    text(level, n, texts);
                }
            }
            BlockPos above = p.above();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = above.relative(d);
                BlockState st = level.getBlockState(n);
                if (st.getBlock() instanceof WallSignBlock && n.relative(st.getValue(WallSignBlock.FACING).getOpposite()).equals(above)) {
                    text(level, n, texts);
                }
            }
            if (frame == null && frames != null) {
                frame = frames.get(p);
            }
        }
        String joined = String.join(" ", texts).strip().toLowerCase(Locale.ROOT);
        return new Info(joined.isEmpty() ? null : joined, frame);
    }

    /** The container block and, for a double chest, its other half. */
    private static List<BlockPos> parts(ClientLevel level, BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(2);
        out.add(pos.immutable());
        BlockState st = level.getBlockState(pos);
        if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE)
                && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            out.add(ChestBlock.getConnectedBlockPos(pos, st).immutable());
        }
        return out;
    }

    /** Is the sign at {@code n} (neighbour of {@code p} in direction {@code d}) fixed to {@code p}? */
    private static boolean attachedTo(BlockState st, BlockPos n, BlockPos p, Direction d) {
        if (!(st.getBlock() instanceof SignBlock)) {
            return false;
        }
        if (st.getBlock() instanceof WallSignBlock) {
            return n.relative(st.getValue(WallSignBlock.FACING).getOpposite()).equals(p);
        }
        if (st.getBlock() instanceof WallHangingSignBlock) {
            return d.getAxis().isHorizontal();
        }
        if (st.getBlock() instanceof StandingSignBlock) {
            return d == Direction.UP;
        }
        if (st.getBlock() instanceof CeilingHangingSignBlock) {
            return d == Direction.DOWN;
        }
        return false;
    }

    private static void text(ClientLevel level, BlockPos n, Set<String> out) {
        BlockEntity be = level.getBlockEntity(n);
        if (!(be instanceof SignBlockEntity sign)) {
            return;
        }
        for (boolean front : new boolean[] {true, false}) {
            SignText t = sign.getText(front);
            StringBuilder sb = new StringBuilder();
            for (Component c : t.getMessages(false)) {
                String s = c.getString().strip();
                if (!s.isEmpty()) {
                    sb.append(sb.isEmpty() ? "" : " ").append(s);
                }
            }
            if (!sb.isEmpty()) {
                out.add(sb.toString());
            }
        }
    }
}
