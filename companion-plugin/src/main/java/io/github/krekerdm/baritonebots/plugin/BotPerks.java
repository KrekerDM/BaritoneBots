package io.github.krekerdm.baritonebots.plugin;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.logging.Logger;

/** Per-player distances and permission nodes for verified bots. Server thread only. */
final class BotPerks {
    private final Plugin plugin;
    private final Logger log;

    BotPerks(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /**
     * Applies the configured distances. Paper keeps them on the reused ServerPlayer across respawns, but they are
     * re-applied on respawn and world change anyway because other plugins may reset them there.
     */
    void applyDistances(Player p, PluginSettings.Distances d) {
        if (!d.enabled()) {
            return;
        }
        try {
            // Simulation first: Paper's effective load distance is max(simulation + 1, view + 1).
            if (d.simulation() != -1) {
                p.setSimulationDistance(d.simulation());
            }
            if (d.view() != -1) {
                p.setViewDistance(d.view());
            }
            if (d.send() != -1) {
                p.setSendViewDistance(d.send());
            }
            p.setAffectsSpawning(d.affectsSpawning());
        } catch (IllegalArgumentException e) {
            log.warning("Could not set distances for " + p.getName() + ": " + e.getMessage());
        }
    }

    /** Back to the world's values, for a bot that lost its verification while online. */
    void resetDistances(Player p) {
        try {
            p.setViewDistance(-1);
            p.setSimulationDistance(-1);
            p.setSendViewDistance(-1);
            p.setAffectsSpawning(true);
        } catch (IllegalArgumentException e) {
            log.warning("Could not reset distances for " + p.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Replaces the session's attachment with one holding {@code nodes}; {@code -node} denies. LuckPerms keeps
     * attachments from other plugins as transient nodes, so this also works with LuckPerms installed.
     */
    void grantPermissions(Player p, BotRegistry.Session s, List<String> nodes) {
        revokePermissions(p, s);
        if (nodes.isEmpty()) {
            return;
        }
        PermissionAttachment a = p.addAttachment(plugin);
        for (String node : nodes) {
            if (node.startsWith("-")) {
                a.setPermission(node.substring(1), false);
            } else {
                a.setPermission(node, true);
            }
        }
        s.attachment(a);
    }

    void revokePermissions(Player p, BotRegistry.Session s) {
        PermissionAttachment a = s.attachment();
        if (a == null) {
            return;
        }
        s.attachment(null);
        if (p != null && p.isOnline()) {
            try {
                p.removeAttachment(a);
            } catch (IllegalArgumentException alreadyGone) {
                // The permissible was replaced (e.g. by a permissions plugin re-injecting); nothing left to remove.
            }
        }
    }
}
