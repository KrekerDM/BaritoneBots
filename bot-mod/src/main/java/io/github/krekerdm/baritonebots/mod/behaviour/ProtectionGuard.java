package io.github.krekerdm.baritonebots.mod.behaviour;

import baritone.api.BaritoneAPI;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Protection guard (SPEC §2.5 {@code protection}, §4.3, §5.7g), called from the {@code MultiPlayerGameMode} mixin, so
 * it covers Baritone and the mod's own actions alike: refuses to start or continue breaking a protected block
 * ({@link ProtectionRules#breakRefusal}) and to place a block or fluid inside a protection zone
 * ({@link ProtectionRules#placeRefusal}; a click that opens or toggles a block — chest, door, lever — is a use, not a
 * placement, unless the bot sneaks).
 * <p>
 * Baritone plans around {@code noBreak} and {@code built} blocks because they are merged into
 * {@code blocksToDisallowBreaking} ({@link #noBreakBlocks}); it does not know the zones. When the guard refuses to
 * break a block inside a zone during a task, that block type is added to {@code blocksToDisallowBreaking} until the
 * task ends ("learned", at most {@value #MAX_LEARNED}; never a block the task is mining) and the current path is
 * dropped, so Baritone plans a way around the wall. Refusals are tracked as a streak: {@link #streakMs} feeds the task
 * manager, which fails the task with reason {@code protected} when the bot keeps running into a zone. Client thread
 * only.
 */
public final class ProtectionGuard {
    static final long STREAK_GAP_MS = 3_000;
    static final long EVENT_GAP_MS = 30_000;
    static final int MAX_LEARNED = 8;

    private static ProtectionRules.Scope scope;
    private static Set<Block> targets = Set.of();
    private static final List<Block> learned = new ArrayList<>();
    private static boolean taskRunning;
    private static long streakStart;
    private static long lastRefusal;
    private static long lastEvent;
    private static String lastText = "";

    private ProtectionGuard() {
    }

    // ------------------------------------------------------------------ task scope

    /** A task started: fresh streak, nothing learned, no area. */
    public static void beginTask() {
        boolean changed = scope != null || !learned.isEmpty();
        reset();
        taskRunning = true;
        if (changed) {
            refreshBaritone();
        }
    }

    /** The running task works inside {@code box} ({@code selection} / {@code build}: AREA, {@code farm}: CROPS). */
    public static void allowArea(Box box, ProtectionRules.Mode mode) {
        scope = box == null ? null : new ProtectionRules.Scope(box, mode);
        refreshBaritone();
    }

    /** Blocks the running task mines: never learned as "do not break". */
    public static void setTargets(Collection<Block> blocks) {
        targets = blocks == null ? Set.of() : new HashSet<>(blocks);
    }

    /** The task ended: area, targets and learned blocks are dropped. */
    public static void endTask() {
        boolean changed = scope != null || !learned.isEmpty();
        reset();
        if (changed) {
            refreshBaritone();
        }
    }

    private static void reset() {
        scope = null;
        targets = Set.of();
        learned.clear();
        taskRunning = false;
        streakStart = 0;
        lastRefusal = 0;
        lastText = "";
    }

    /**
     * Blocks for Baritone's {@code blocksToDisallowBreaking}: {@code noBreak}, plus {@code built} unless an AREA task
     * runs (its own box may hold built blocks it has to break; the guard still refuses them outside the box), plus the
     * blocks learned during this task.
     */
    public static List<Block> noBreakBlocks(BotConfig cfg) {
        BotConfig.Protection pr = cfg.protection();
        if (!pr.enabled()) {
            return List.of();
        }
        List<Block> out = new ArrayList<>(McIds.blocksMatching(pr.noBreak()));
        if (scope == null || scope.mode() != ProtectionRules.Mode.AREA) {
            for (Block b : McIds.blocksMatching(pr.built())) {
                if (!out.contains(b)) {
                    out.add(b);
                }
            }
        }
        for (Block b : learned) {
            if (!out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }

    private static void refreshBaritone() {
        BotRuntime bot = BotRuntime.get();
        if (bot != null) {
            bot.refreshNoBreak();
        }
    }

    // ------------------------------------------------------------------ checks (mixin)

    /** True when breaking {@code pos} must be refused. */
    public static boolean refuses(BlockPos pos) {
        BotRuntime bot = BotRuntime.get();
        ClientLevel level = bot == null ? null : bot.level();
        if (level == null || pos == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        String id = McIds.block(state);
        String why = ProtectionRules.breakRefusal(bot.config().protection(), McIds.dim(level), pos.getX(), pos.getY(),
                pos.getZ(), id, scope);
        if (why == null) {
            return false;
        }
        refused(bot, "break", pos, id, why);
        if (ProtectionRules.ZONE.equals(why)) {
            learn(bot, state.getBlock());
        }
        return true;
    }

    /** True when this right click would place a block or fluid inside a protection zone. */
    public static boolean refusesUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
        BotRuntime bot = BotRuntime.get();
        ClientLevel level = bot == null ? null : bot.level();
        if (level == null || player == null || hit == null) {
            return false;
        }
        Item item = player.getItemInHand(hand).getItem();
        String placed;
        if (item instanceof BlockItem bi) {
            placed = McIds.block(bi.getBlock());
        } else if (item instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY) {
            placed = "minecraft:fluid";
        } else {
            return false;
        }
        BlockPos clicked = hit.getBlockPos();
        BlockState at = level.getBlockState(clicked);
        if (!player.isSecondaryUseActive() && opens(level, clicked, at)) {
            return false; // the click opens / toggles the block; nothing is placed
        }
        BlockPos target = at.canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        String why = ProtectionRules.placeRefusal(bot.config().protection(), McIds.dim(level), target.getX(),
                target.getY(), target.getZ(), placed, scope);
        if (why == null) {
            return false;
        }
        refused(bot, "place", target, placed, why);
        return true;
    }

    /** Blocks whose right click does something instead of placing against them. */
    static boolean opens(ClientLevel level, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        if (b instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        if (b instanceof TrapDoorBlock) {
            return !s.is(Blocks.IRON_TRAPDOOR);
        }
        return b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BedBlock
                || s.getMenuProvider(level, pos) != null;
    }

    // ------------------------------------------------------------------ streak, event, learning

    private static void refused(BotRuntime bot, String action, BlockPos pos, String id, String why) {
        long now = System.currentTimeMillis();
        if (now - lastRefusal > STREAK_GAP_MS) {
            streakStart = now;
        }
        lastRefusal = now;
        String what = switch (why) {
            case ProtectionRules.ZONE -> "inside a protection zone";
            case ProtectionRules.NO_BREAK -> "protection.noBreak";
            default -> "a built block (protection.built)";
        };
        lastText = ("break".equals(action) ? "did not break " : "did not place ") + id + " at " + pos.toShortString()
                + ": " + what;
        if (now - lastEvent >= EVENT_GAP_MS) {
            lastEvent = now;
            bot.event(EventKinds.PROTECTED, Levels.WARN, lastText,
                    Json.obj("action", action, "pos", Positions.json(pos), "block", id, "why", why));
        }
    }

    private static void learn(BotRuntime bot, Block block) {
        if (!taskRunning || targets.contains(block) || learned.contains(block) || learned.size() >= MAX_LEARNED
                || BaritoneAPI.getSettings().blocksToDisallowBreaking.value.contains(block)) {
            return;
        }
        learned.add(block);
        bot.refreshNoBreak();
        if (bot.baritone() != null) {
            bot.baritone().getPathingBehavior().forceCancel(); // plan again, around the wall
        }
    }

    /** How long the bot has kept hitting protected blocks (refusals less than 3 s apart), 0 when it stopped. */
    public static long streakMs(long now) {
        return lastRefusal == 0 || now - lastRefusal > STREAK_GAP_MS ? 0 : now - streakStart;
    }

    /** The last refusal, for the task result. */
    public static String lastRefusal() {
        return lastText;
    }
}
