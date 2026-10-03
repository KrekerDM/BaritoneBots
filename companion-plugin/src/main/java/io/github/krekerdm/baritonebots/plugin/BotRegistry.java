package io.github.krekerdm.baritonebots.plugin;

import org.bukkit.permissions.PermissionAttachment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verified bot sessions and per-connection handshake state. Server thread only. */
public final class BotRegistry {
    /** Failed hellos after which a connection is ignored until it reconnects. */
    public static final int MAX_FAILURES = 5;

    private final Map<UUID, Session> verified = new HashMap<>();
    private final Map<UUID, Integer> failures = new HashMap<>();
    private final Set<UUID> silenced = new HashSet<>();

    public Session get(UUID player) {
        return verified.get(player);
    }

    public boolean isVerified(UUID player) {
        return verified.containsKey(player);
    }

    public void put(Session s) {
        verified.put(s.player(), s);
        failures.remove(s.player());
    }

    public Session remove(UUID player) {
        return verified.remove(player);
    }

    public Collection<Session> sessions() {
        return List.copyOf(verified.values());
    }

    public Session byName(String name) {
        for (Session s : verified.values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    /** @return the failure count after this one */
    public int fail(UUID player) {
        return failures.merge(player, 1, Integer::sum);
    }

    public boolean tooManyFailures(UUID player) {
        return failures.getOrDefault(player, 0) >= MAX_FAILURES;
    }

    /**
     * Marks a connection whose non-handshake messages are ignored without a reply (a player that is not a bot).
     *
     * @return true the first time
     */
    public boolean silence(UUID player) {
        return silenced.add(player);
    }

    /** Forget everything about a connection (quit). */
    public Session disconnect(UUID player) {
        failures.remove(player);
        silenced.remove(player);
        return verified.remove(player);
    }

    public List<Session> clear() {
        List<Session> all = new ArrayList<>(verified.values());
        verified.clear();
        failures.clear();
        silenced.clear();
        return all;
    }

    /** One verified bot connection. */
    public static final class Session {
        private final UUID player;
        private final String name;
        private final String botId;
        private final String modVersion;
        private final long verifiedAt;
        private PermissionAttachment attachment;

        public Session(UUID player, String name, String botId, String modVersion, long verifiedAt) {
            this.player = player;
            this.name = name;
            this.botId = botId;
            this.modVersion = modVersion;
            this.verifiedAt = verifiedAt;
        }

        public UUID player() {
            return player;
        }

        public String name() {
            return name;
        }

        public String botId() {
            return botId;
        }

        public String modVersion() {
            return modVersion;
        }

        public long verifiedAt() {
            return verifiedAt;
        }

        public PermissionAttachment attachment() {
            return attachment;
        }

        public void attachment(PermissionAttachment a) {
            this.attachment = a;
        }
    }
}
