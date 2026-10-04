package io.github.krekerdm.baritonebots.manager.planner;

import java.util.HashMap;
import java.util.Map;

/**
 * Role hysteresis (SPEC §5.7): a bot keeps the role of its last work item for at least
 * {@code planner.roleSwitchCooldownSec}. A bot without a role may take any. Pure (time is passed in), loop-owned.
 */
public final class RoleTracker {
    private record Entry(String role, long since) {
    }

    private final Map<String, Entry> roles = new HashMap<>();

    public String role(String botId) {
        Entry e = roles.get(botId);
        return e == null ? null : e.role();
    }

    public long since(String botId) {
        Entry e = roles.get(botId);
        return e == null ? 0 : e.since();
    }

    /** Would taking {@code role} be a role change (no role yet = no change)? */
    public boolean isChange(String botId, String role) {
        String cur = role(botId);
        return role != null && cur != null && !cur.equals(role);
    }

    /** May the bot switch to {@code role} now? A {@code null} role (role-neutral work) is always allowed. */
    public boolean allows(String botId, String role, long now, long cooldownMs) {
        Entry e = roles.get(botId);
        return role == null || e == null || e.role().equals(role) || now - e.since() >= cooldownMs;
    }

    /** Records the role of a new assignment; the timer only restarts when the role actually changes. */
    public void assign(String botId, String role, long now) {
        Entry e = roles.get(botId);
        if (e == null || !e.role().equals(role)) {
            roles.put(botId, new Entry(role, now));
        }
    }

    public void forget(String botId) {
        roles.remove(botId);
    }
}
