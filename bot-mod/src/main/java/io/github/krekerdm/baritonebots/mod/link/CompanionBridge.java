package io.github.krekerdm.baritonebots.mod.link;

import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.Protocol;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.BotConfig;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.EventKinds;
import io.github.krekerdm.baritonebots.common.msg.Levels;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Companion plugin bridge (SPEC §7): raw UTF-8 JSON envelopes on {@code baritonebots:main}, registered in both
 * directions. After joining a server with {@code companion.enabled}, sends {@code hello {token, botId, modVersion}}
 * every 2 s until the plugin answers {@code welcome} (event {@code companion verified}) or {@code reject}
 * ({@code rejected}); no answer within 30 s = {@code absent}. Every plugin message is forwarded to the manager as
 * {@code plugin {payload}}; the manager's {@code plugin {payload}} is sent to the server (≤ 32 767 bytes).
 * Inbound payloads are queued by the network handler and handled on the client tick.
 */
public final class CompanionBridge {
    public static final long HELLO_INTERVAL_MS = 2_000;
    public static final long HELLO_TIMEOUT_MS = 30_000;

    /** The channel's payload: the raw bytes of one envelope. */
    public record RawPayload(byte[] data) implements CustomPacketPayload {
        public static final Type<RawPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Protocol.PLUGIN_CHANNEL_NAMESPACE, Protocol.PLUGIN_CHANNEL_PATH));
        public static final StreamCodec<RegistryFriendlyByteBuf, RawPayload> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBytes(p.data()),
                buf -> {
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    return new RawPayload(b);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private enum State { OFF, HELLO, VERIFIED, REJECTED, ABSENT }

    private final BotRuntime bot;
    private final Queue<byte[]> inbound = new ConcurrentLinkedQueue<>();
    private State state = State.OFF;
    private long helloSince;
    private long nextHello;

    public CompanionBridge(BotRuntime bot) {
        this.bot = bot;
    }

    /** Registers the payload type (both directions) and the receiver; call once during client init. */
    public void register() {
        PayloadTypeRegistry.clientboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, context) -> {
            if (payload.data().length <= Protocol.MAX_S2C_PLUGIN_BYTES) {
                inbound.add(payload.data());
            }
        });
    }

    /** Fabric JOIN (client thread): start the handshake when enabled. */
    public void onJoin() {
        inbound.clear();
        state = State.OFF;
        if (bot.config().companion().enabled()) {
            startHello();
        }
    }

    public void onLeave() {
        state = State.OFF;
        inbound.clear();
    }

    /** New config: start the handshake if the companion was just enabled while in game. */
    public void onConfig(BotConfig cfg) {
        if (cfg.companion().enabled() && state == State.OFF && bot.inGame()) {
            startHello();
        } else if (!cfg.companion().enabled() && state == State.HELLO) {
            state = State.OFF;
        }
    }

    private void startHello() {
        state = State.HELLO;
        helloSince = System.currentTimeMillis();
        nextHello = 0;
    }

    public void tick() {
        byte[] msg;
        int budget = 50;
        while (budget-- > 0 && (msg = inbound.poll()) != null) {
            receive(msg);
        }
        if (state != State.HELLO || !bot.inGame()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - helloSince > HELLO_TIMEOUT_MS) {
            state = State.ABSENT;
            bot.event(EventKinds.COMPANION, Levels.INFO, "companion plugin did not answer within "
                    + HELLO_TIMEOUT_MS / 1000 + " s", Json.obj("state", EventKinds.COMPANION_ABSENT));
            return;
        }
        if (now >= nextHello) {
            nextHello = now + HELLO_INTERVAL_MS;
            BotConfig.Companion c = bot.config().companion();
            send(Envelope.of(MessageTypes.HELLO, Json.obj("token", c.token(), "botId", bot.props.botId(),
                    "modVersion", ModInfo.modVersion())).encode(), false);
        }
    }

    private void receive(byte[] bytes) {
        Envelope env;
        try {
            env = Envelope.decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            bot.warn("dropped malformed companion message (" + bytes.length + " bytes): " + e.getMessage());
            return;
        }
        JsonObject payload = Json.parseObject(env.encode());
        bot.sendReliable(MessageTypes.PLUGIN, Json.obj("payload", payload));
        if (MessageTypes.WELCOME.equals(env.t())) {
            if (state != State.VERIFIED) {
                state = State.VERIFIED;
                JsonObject d = env.d() == null ? new JsonObject() : env.d().deepCopy();
                d.addProperty("state", EventKinds.COMPANION_VERIFIED);
                bot.event(EventKinds.COMPANION, Levels.INFO, "companion plugin verified this bot", d);
            }
        } else if (MessageTypes.REJECT.equals(env.t())) {
            state = State.REJECTED;
            String reason = env.d() == null ? null : Json.getString(env.d(), "reason", null);
            bot.event(EventKinds.COMPANION, Levels.WARN, "companion plugin rejected this bot"
                    + (reason == null ? "" : ": " + reason), Json.obj("state", EventKinds.COMPANION_REJECTED,
                    "reason", reason));
        }
    }

    /** Manager {@code plugin {payload}} → server. */
    public void fromManager(JsonObject d) {
        JsonObject payload = Json.getObj(d, "payload");
        if (payload == null) {
            bot.warn("'plugin' message without a payload object dropped");
            return;
        }
        send(Json.toJson(payload), true);
    }

    private void send(String json, boolean fromManager) {
        if (!bot.inGame() || bot.mc.getConnection() == null) {
            if (fromManager) {
                bot.warn("'plugin' message dropped: not on a server");
            }
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Protocol.MAX_C2S_PLUGIN_BYTES) {
            bot.warn("'plugin' message dropped: " + bytes.length + " bytes exceeds " + Protocol.MAX_C2S_PLUGIN_BYTES);
            return;
        }
        try {
            ClientPlayNetworking.send(new RawPayload(bytes));
        } catch (RuntimeException e) {
            bot.warn("companion message not sent: " + e);
        }
    }
}
