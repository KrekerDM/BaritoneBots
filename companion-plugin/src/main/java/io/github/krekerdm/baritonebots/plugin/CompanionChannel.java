package io.github.krekerdm.baritonebots.plugin;

import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Transport on {@code baritonebots:main} (SPEC §7): raw UTF-8 JSON envelopes, no framing. Enforces the size limits
 * and always hands messages to the handler on the server thread.
 */
final class CompanionChannel implements PluginMessageListener {
    static final String CHANNEL = Protocol.PLUGIN_CHANNEL;

    private final BaritoneBotsPlugin plugin;
    private BiConsumer<Player, Envelope> handler;

    CompanionChannel(BaritoneBotsPlugin plugin) {
        this.plugin = plugin;
    }

    void register(BiConsumer<Player, Envelope> handler) {
        this.handler = handler;
        Messenger m = plugin.getServer().getMessenger();
        m.registerOutgoingPluginChannel(plugin, CHANNEL);
        m.registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    void unregister() {
        Messenger m = plugin.getServer().getMessenger();
        m.unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        m.unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || handler == null) {
            return;
        }
        if (message.length > Protocol.MAX_C2S_PLUGIN_BYTES) {
            plugin.debug("Dropped " + message.length + " byte message from " + player.getName() + " (limit "
                    + Protocol.MAX_C2S_PLUGIN_BYTES + ")");
            return;
        }
        Envelope env;
        try {
            env = Envelope.decode(new String(message, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            plugin.debug("Dropped malformed message from " + player.getName() + ": " + e.getMessage());
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            handler.accept(player, env);
        } else {
            // Paper delivers custom payloads on the server thread today; this keeps the handler safe if that changes.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    handler.accept(player, env);
                }
            });
        }
    }

    /**
     * Sends {@code env} to {@code p}. Paper silently drops messages for clients that did not register the channel,
     * so that case is checked and reported here instead.
     *
     * @return false when the message was not sent
     */
    boolean send(Player p, Envelope env) {
        byte[] bytes = env.encode().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Protocol.MAX_S2C_PLUGIN_BYTES) {
            plugin.getLogger().warning("Not sending '" + env.t() + "' to " + p.getName() + ": " + bytes.length
                    + " bytes exceeds " + Protocol.MAX_S2C_PLUGIN_BYTES);
            return false;
        }
        if (!p.isOnline() || !p.getListeningPluginChannels().contains(CHANNEL)) {
            plugin.debug("Not sending '" + env.t() + "' to " + p.getName() + ": client does not listen on " + CHANNEL);
            return false;
        }
        p.sendPluginMessage(plugin, CHANNEL, bytes);
        return true;
    }
}
