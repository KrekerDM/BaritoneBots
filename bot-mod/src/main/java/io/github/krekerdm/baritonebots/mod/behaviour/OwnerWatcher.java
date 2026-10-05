package io.github.krekerdm.baritonebots.mod.behaviour;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import io.github.krekerdm.baritonebots.mod.util.RateLimiter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Owner commands and the {@code owner} query (SPEC §5.7e). Chat lines from {@code config.owner.player} that start
 * with the prefix become {@code owner_command} events carrying where the owner stands and looks: position, yaw /
 * pitch of the tracked player entity and the block hit by a ray from the eyes (64 blocks, block outlines, fluids
 * ignored). At most 5 commands per second. Client thread only.
 */
public final class OwnerWatcher {
    public static final double LOOK_RANGE = 64.0;

    private final BotRuntime bot;
    private final RateLimiter limiter = new RateLimiter(5, 1000);
    private OwnerChat chat;

    public OwnerWatcher(BotRuntime bot) {
        this.bot = bot;
        onConfig(bot.config());
    }

    public void onConfig(BotConfig cfg) {
        chat = new OwnerChat(cfg.owner());
        for (String bad : chat.invalidPatterns()) {
            bot.warn("owner chat pattern ignored: " + bad);
        }
    }

    /** Player chat with a known sender. Returns true when the line was an owner command. */
    public boolean onPlayerChat(String sender, String body, boolean whisper) {
        return emit(chat.fromPlayer(sender, body, whisper));
    }

    /** A system line (chat plugins, rendered whispers). Returns true when it was an owner command. */
    public boolean onSystemMessage(String text, boolean overlay) {
        return !overlay && emit(chat.fromSystem(text));
    }

    private boolean emit(OwnerChat.Command c) {
        if (c == null) {
            return false;
        }
        if (!limiter.tryAcquire()) {
            return true; // still an owner line: never forwarded as plain chat
        }
        JsonObject d = info(c.player());
        d.remove("found");
        d.addProperty("text", c.text());
        d.addProperty("player", c.player());
        d.addProperty("via", c.via());
        if (chat.ownerUnknown()) {
            d.addProperty("candidate", true); // no owner yet: the manager only offers this player as the owner
        }
        bot.event(EventKinds.OWNER_COMMAND, Levels.INFO, "owner command: " + c.text(), d);
        return true;
    }

    /**
     * {@code {found, pos, dim, yaw, pitch, lookBlock, lookBlockId}} of a player the client tracks; when not tracked
     * only {@code found:false} and the bot's dimension.
     */
    public JsonObject info(String name) {
        ClientLevel level = bot.level();
        if (level == null || name == null || name.isBlank()) {
            return Json.obj("found", false, "pos", null, "dim", null, "yaw", null, "pitch", null, "lookBlock", null,
                    "lookBlockId", null);
        }
        String dim = McIds.dim(level);
        Player owner = null;
        for (Player p : level.players()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) {
                owner = p;
                break;
            }
        }
        if (owner == null) {
            return Json.obj("found", false, "pos", null, "dim", dim, "yaw", null, "pitch", null, "lookBlock", null,
                    "lookBlockId", null);
        }
        float yaw = Mth.wrapDegrees(owner.getYHeadRot());
        float pitch = owner.getXRot();
        BlockPos look = lookBlock(level, owner, yaw, pitch);
        return Json.obj("found", true, "pos", Positions.json(owner.blockPosition()), "dim", dim,
                "yaw", (double) Math.round(yaw * 10) / 10.0, "pitch", (double) Math.round(pitch * 10) / 10.0,
                "lookBlock", look == null ? null : Positions.json(look),
                "lookBlockId", look == null ? null : McIds.block(level.getBlockState(look)));
    }

    /** First block outline hit from the player's eyes along yaw / pitch within {@link #LOOK_RANGE}, or null. */
    static BlockPos lookBlock(ClientLevel level, Player p, float yaw, float pitch) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.calculateViewVector(pitch, yaw).scale(LOOK_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().immutable() : null;
    }

    /** The configured owner's name. */
    public String ownerName() {
        return bot.config().owner().player();
    }
}
