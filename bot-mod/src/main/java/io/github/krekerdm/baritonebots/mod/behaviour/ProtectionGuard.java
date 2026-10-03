package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Protection guard (SPEC §2.5 {@code protection}, §4.3): when {@code protection.enabled}, refuses to start or
 * continue breaking a block inside a protection zone (same dimension; a zone without {@code dim} applies to every
 * dimension) or whose id matches {@code noBreak}. Called from the {@code MultiPlayerGameMode} mixin, so it covers
 * Baritone and the mod's own actions alike. Baritone additionally avoids {@code noBreak} blocks when planning
 * because they are merged into {@code blocksToDisallowBreaking}; it does not know the zones.
 */
public final class ProtectionGuard {
    private static final RateLimiter LIMITER = new RateLimiter(1, 1000);

    private ProtectionGuard() {
    }

    /** True when breaking {@code pos} must be refused (client thread). */
    public static boolean refuses(BlockPos pos) {
        BotRuntime bot = BotRuntime.get();
        if (bot == null || pos == null) {
            return false;
        }
        BotConfig.Protection pr = bot.config().protection();
        ClientLevel level = bot.level();
        if (!pr.enabled() || level == null) {
            return false;
        }
        String dim = McIds.dim(level);
        for (BotConfig.Zone z : pr.zones()) {
            if (z == null || z.box() == null) {
                continue;
            }
            boolean sameDim = z.dim() == null || z.dim().isBlank() || Dims.normalize(z.dim()).equals(dim);
            if (sameDim && z.box().contains(pos.getX(), pos.getY(), pos.getZ())) {
                report(bot, pos, "inside a protection zone");
                return true;
            }
        }
        if (!pr.noBreak().isEmpty()) {
            String block = McIds.block(level.getBlockState(pos));
            if (Ids.matchesAny(pr.noBreak(), block)) {
                report(bot, pos, block + " is in protection.noBreak");
                return true;
            }
        }
        return false;
    }

    private static void report(BotRuntime bot, BlockPos pos, String why) {
        if (LIMITER.once("refuse:" + pos.asLong(), 10_000)) {
            bot.warn("refused to break " + pos.toShortString() + ": " + why);
        }
    }
}
