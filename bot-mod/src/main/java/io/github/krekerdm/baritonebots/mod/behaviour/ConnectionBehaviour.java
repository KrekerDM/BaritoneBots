package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import io.github.krekerdm.baritonebots.mod.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Server connection (SPEC §2.2 connect/disconnect/chat, §4.3 reconnect): connects from the title screen when
 * {@code server.autoConnect} is set, reconnects with backoff from the disconnected screen unless the manager sent
 * {@code disconnect}, and reports {@code joined}/{@code disconnected}/{@code kicked}.
 */
public final class ConnectionBehaviour {
    private final BotRuntime bot;
    private boolean manualDisconnect;
    private boolean explicitConnect;
    private String addressOverride;
    private long nextAttemptMs;
    private long backoffMs;
    private boolean joined;
    private volatile boolean leftFlag;
    private Screen handledDisconnectScreen;
    private String pendingAddressSwitch;

    public ConnectionBehaviour(BotRuntime bot) {
        this.bot = bot;
        this.backoffMs = bot.config().server().reconnect().delaySec() * 1000L;
    }

    public void onConfig(BotConfig cfg) {
        long min = Math.max(1, cfg.server().reconnect().delaySec()) * 1000L;
        if (backoffMs < min) {
            backoffMs = min;
        }
    }

    /** Fabric JOIN (client thread). */
    public void onJoin() {
        joined = true;
        BotConfig.Reconnect rc = bot.config().server().reconnect();
        backoffMs = Math.max(1, rc.delaySec()) * 1000L;
        String server = currentAddress();
        bot.event(EventKinds.JOINED, Levels.INFO, "joined " + server, Json.obj("server", server));
        bot.login.onJoin();
        bot.status.markDirty();
    }

    /** Fabric DISCONNECT; may run on any thread, handled in {@link #tick}. */
    public void onLeave() {
        leftFlag = true;
    }

    public boolean joined() {
        return joined;
    }

    /** Address of the server the bot is on or connecting to. */
    public String currentAddress() {
        ServerData sd = bot.mc.getCurrentServer();
        if (sd != null && sd.ip != null) {
            return sd.ip;
        }
        return addressOverride != null ? addressOverride : bot.config().server().address();
    }

    public boolean connecting() {
        return bot.mc.gui.screen() instanceof ConnectScreen;
    }

    public void suppressReconnect() {
        manualDisconnect = true;
    }

    public void tick() {
        Minecraft mc = bot.mc;
        if (leftFlag) {
            leftFlag = false;
            bot.companion.onLeave();
            if (joined) {
                joined = false;
                bot.tasks.onDisconnected();
                bot.login.onLeave();
                bot.status.markDirty();
            }
        }
        if (bot.inGame()) {
            if (manualDisconnect) {
                leave("disconnect requested by manager");
            } else if (pendingAddressSwitch != null) {
                leave("switching server to " + pendingAddressSwitch);
            }
            return;
        }
        Screen screen = mc.gui.screen();
        if (screen instanceof DisconnectedScreen ds && ds != handledDisconnectScreen) {
            handledDisconnectScreen = ds;
            onDisconnectedScreen(ds);
        }
        if (mc.gui.overlay() != null || screen instanceof ConnectScreen) {
            return;
        }
        boolean idleScreen = screen instanceof TitleScreen || screen instanceof DisconnectedScreen;
        if (!idleScreen || manualDisconnect) {
            return;
        }
        boolean want = pendingAddressSwitch != null || explicitConnect || bot.config().server().autoConnect();
        if (want && System.currentTimeMillis() >= nextAttemptMs) {
            if (pendingAddressSwitch != null) {
                addressOverride = pendingAddressSwitch;
                pendingAddressSwitch = null;
            }
            doConnect();
        }
    }

    private void onDisconnectedScreen(DisconnectedScreen ds) {
        Component reason = null;
        Object details = Reflect.get(ds, "details");
        if (details instanceof DisconnectionDetails dd) {
            reason = dd.reason();
        }
        String text = reason == null ? "unknown" : reason.getString();
        boolean kicked = reason != null && isServerKick(reason);
        if (!manualDisconnect) {
            bot.event(kicked ? EventKinds.KICKED : EventKinds.DISCONNECTED, Levels.WARN,
                    (kicked ? "kicked: " : "disconnected: ") + text, Json.obj("reason", text));
        }
        BotConfig.Reconnect rc = bot.config().server().reconnect();
        if (rc.enabled() && !manualDisconnect) {
            nextAttemptMs = System.currentTimeMillis() + backoffMs;
            bot.log.info("reconnecting in " + backoffMs / 1000 + " s");
            backoffMs = Math.min(backoffMs * 2, Math.max(rc.delaySec(), rc.maxDelaySec()) * 1000L);
        } else if (!rc.enabled()) {
            explicitConnect = false;
            nextAttemptMs = Long.MAX_VALUE;
        }
        bot.status.markDirty();
    }

    /**
     * Connection problems use vanilla {@code disconnect.*}/{@code connect.*} translation keys (lost, timeout,
     * refused, end of stream); everything else (plugin kick messages, bans, whitelist, server shutdown text) is
     * treated as a kick by the server.
     */
    private static boolean isServerKick(Component reason) {
        if (reason.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            return !(key.startsWith("disconnect.") || key.startsWith("connect."));
        }
        return true;
    }

    /** {@code connect}: connect to {@code address} or the configured one; re-enables reconnects. */
    public void connect(String address) {
        manualDisconnect = false;
        explicitConnect = true;
        String target = address == null || address.isBlank() ? null : address.trim();
        if (bot.inGame()) {
            if (target != null && !target.equalsIgnoreCase(currentAddress())) {
                pendingAddressSwitch = target;
            }
            return;
        }
        if (target != null) {
            addressOverride = target;
        }
        nextAttemptMs = 0;
        backoffMs = Math.max(1, bot.config().server().reconnect().delaySec()) * 1000L;
    }

    /** {@code disconnect}: leave and stay on the title screen until the next {@code connect}. */
    public void disconnect() {
        manualDisconnect = true;
        explicitConnect = false;
        pendingAddressSwitch = null;
        if (bot.inGame()) {
            leave("disconnect requested by manager");
        } else if (connecting()) {
            bot.mc.gui.setScreen(new TitleScreen());
        }
    }

    private void leave(String why) {
        bot.tasks.cancelCurrent(why);
        boolean wasJoined = joined;
        joined = false;
        bot.login.onLeave();
        bot.mc.disconnectFromWorld(Component.literal(why));
        if (wasJoined) {
            bot.event(EventKinds.DISCONNECTED, Levels.INFO, why, Json.obj("reason", why));
        }
        nextAttemptMs = 0;
        bot.status.markDirty();
    }

    private void doConnect() {
        String addr = addressOverride != null ? addressOverride : bot.config().server().address();
        if (addr == null || addr.isBlank() || !ServerAddress.isValidAddress(addr)) {
            bot.event(EventKinds.ERROR, Levels.ERROR, "invalid server address '" + addr + "'", Json.obj("address", addr));
            nextAttemptMs = Long.MAX_VALUE;
            return;
        }
        // Avoid a reconnect storm if the attempt dies without ever showing a screen we can react to.
        nextAttemptMs = System.currentTimeMillis() + Math.max(backoffMs, 5_000);
        ModInfo.LOG.info("Connecting to {}", addr);
        ConnectScreen.startConnecting(new TitleScreen(), bot.mc, ServerAddress.parseString(addr),
                new ServerData(bot.config().username() + "@" + addr, addr, ServerData.Type.OTHER), false, null);
        bot.status.markDirty();
    }

    /** {@code chat}: sends chat, or a command when the text starts with {@code /}. */
    public void chat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!bot.inGame() || bot.mc.player.connection == null) {
            bot.warn("chat dropped: not on a server");
            return;
        }
        String t = text.strip();
        if (t.startsWith("/")) {
            String cmd = t.substring(1);
            bot.mc.player.connection.sendCommand(cmd.length() > 32_000 ? cmd.substring(0, 32_000) : cmd);
        } else {
            bot.mc.player.connection.sendChat(t.length() > 256 ? t.substring(0, 256) : t);
        }
    }
}
