package io.github.krekerdm.baritonebots.mod.behaviour;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.BotStatus;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends {@code status} (SPEC §2.3/§2.5) every {@code status.activeIntervalTicks} while a task runs, every
 * {@code status.idleIntervalTicks} otherwise, and right away when the state changes or {@link #markDirty} is
 * called (throttled to once per 5 ticks).
 */
public final class StatusReporter {
    private final BotRuntime bot;
    private boolean dirty = true;
    private long lastSentTick = -10_000;
    private String lastState;
    private final com.sun.management.OperatingSystemMXBean os;

    public StatusReporter(BotRuntime bot) {
        this.bot = bot;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.os = bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
    }

    public void markDirty() {
        dirty = true;
    }

    public void tick() {
        if (!bot.link.isLinked()) {
            dirty = true;
            return;
        }
        String state = state();
        if (!state.equals(lastState)) {
            dirty = true;
        }
        BotConfig.StatusOpts opts = bot.config().status();
        int interval = Math.max(1, bot.tasks.running() ? opts.activeIntervalTicks() : opts.idleIntervalTicks());
        long since = bot.ticks() - lastSentTick;
        if (since >= interval || (dirty && since >= 5)) {
            dirty = false;
            lastSentTick = bot.ticks();
            lastState = state;
            bot.send(MessageTypes.STATUS, build(state));
        }
    }

    /** Current lifecycle state (SPEC §2.5 {@code BotStatus.state}). */
    public String state() {
        if (!bot.inGame()) {
            Screen s = bot.mc.gui.screen();
            if (s instanceof ConnectScreen) {
                return BotStatus.CONNECTING;
            }
            if (s instanceof DisconnectedScreen) {
                return BotStatus.DISCONNECTED;
            }
            if (bot.mc.gui.overlay() != null || s == null) {
                return BotStatus.STARTING;
            }
            return BotStatus.MENU;
        }
        if (bot.life.dead() || bot.player().isDeadOrDying()) {
            return BotStatus.DEAD;
        }
        if (bot.login.isLoggingIn()) {
            return BotStatus.LOGGING_IN;
        }
        return BotStatus.ONLINE;
    }

    private BotStatus build(String state) {
        BotConfig cfg = bot.config();
        LocalPlayer p = bot.player();
        boolean game = bot.inGame();
        Runtime rt = Runtime.getRuntime();
        double cpu = os == null ? -1 : os.getProcessCpuLoad();
        int ping = -1;
        if (game && p.connection != null) {
            PlayerInfo info = p.connection.getPlayerInfo(p.getUUID());
            if (info != null) {
                ping = info.getLatency();
            }
        }
        BotStatus.Perf perf = new BotStatus.Perf((int) ((rt.totalMemory() - rt.freeMemory()) >> 20),
                (int) (rt.maxMemory() >> 20), cpu < 0 ? 0 : cpu, bot.mc.getFps(), ping);
        long now = System.currentTimeMillis();
        String server = bot.connection.joined() || bot.connection.connecting() ? bot.connection.currentAddress() : null;
        if (!game) {
            return new BotStatus(cfg.botId(), cfg.username(), state, server, null, null, 0, 0, 0, 0, 0, 0, 0,
                    List.of(), null, null, 0, Map.of(), bot.tasks.info(), baritoneInfo(), perf, bot.uptimeSec(), now,
                    null);
        }
        List<String> armor = new ArrayList<>(4);
        for (EquipmentSlot slot : Inv.ARMOR_HEAD_TO_FEET) {
            armor.add(McIds.item(p.getItemBySlot(slot)));
        }
        return new BotStatus(cfg.botId(), cfg.username(), state, server, McIds.dim(bot.level()),
                Positions.toVec3d(p.position()), p.getYRot(), p.getXRot(), p.getHealth(), p.getMaxHealth(),
                p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.experienceLevel, armor,
                McIds.item(p.getMainHandItem()), McIds.item(p.getOffhandItem()), Inv.freeSlots(p),
                Inv.totals(p, false), bot.tasks.info(), baritoneInfo(), perf, bot.uptimeSec(), now, dayTime());
    }

    /** Overworld clock (26.x world clocks) as the client sees it, folded to 0..23999; null when unavailable. */
    private Long dayTime() {
        try {
            return bot.level() == null ? null : Math.floorMod(bot.level().getOverworldClockTime(), 24_000L);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BotStatus.BaritoneInfo baritoneInfo() {
        IBaritone b = bot.baritone();
        if (b == null || !bot.inGame()) {
            return new BotStatus.BaritoneInfo(null, false, null, null);
        }
        Optional<IBaritoneProcess> proc = b.getPathingControlManager().mostRecentInControl();
        String process = proc.filter(IBaritoneProcess::isActive).map(IBaritoneProcess::displayName).orElse(null);
        Goal goal = b.getPathingBehavior().getGoal();
        Double eta = b.getPathingBehavior().estimatedTicksToGoal().map(t -> t / 20.0).orElse(null);
        return new BotStatus.BaritoneInfo(process, b.getPathingBehavior().isPathing(),
                goal == null ? null : goal.toString(), eta);
    }
}
