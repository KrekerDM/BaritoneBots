package io.github.krekerdm.baritonebots.plugin;

import io.github.krekerdm.baritonebots.plugin.journal.JournalRecord;
import io.github.krekerdm.baritonebots.plugin.journal.JournalService;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.net.InetAddress;

/** Name protection, perk upkeep and the block journal. */
final class BotListener implements Listener {
    private static final String AIR = "minecraft:air";
    private static final String WATER = "minecraft:water";

    private final BaritoneBotsPlugin plugin;

    BotListener(BaritoneBotsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Async thread: a name from {@code bots} may join only from {@code protect-names.allowed-ips}. Host-name rules
     * may resolve DNS here, which is why this is the async pre-login event and not the login event.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        PluginSettings s = plugin.settings();
        if (!s.protectNames() || s.botName(e.getName()) == null) {
            return;
        }
        InetAddress address = e.getAddress();
        if (s.allowedAddresses().matches(address, true)) {
            return;
        }
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.messages().component("kick.protected-name"));
        plugin.getLogger().warning("Refused " + e.getName() + " from " + (address == null ? "?" : address.getHostAddress())
                + ": the name belongs to a bot and the address is not in protect-names.allowed-ips");
    }

    // ------------------------------------------------------------------ perks

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // Sessions end on quit, so a joining bot is verified by its next hello; this only covers a stale session.
        plugin.reapplyDistances(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                plugin.reapplyDistances(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                plugin.reapplyDistances(p);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.onQuit(e.getPlayer());
    }

    // ------------------------------------------------------------------ journal

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        JournalService journal = journalFor(e.getPlayer());
        if (journal == null) {
            return;
        }
        Block b = e.getBlock();
        BlockData data = b.getBlockData();
        // The event fires before the block is removed; a waterlogged block leaves its water behind.
        String after = data instanceof Waterlogged w && w.isWaterlogged() ? WATER : AIR;
        journal.record(new JournalRecord(System.currentTimeMillis(), JournalRecord.Action.BREAK, e.getPlayer().getName(),
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), data.getAsString(), after));
    }

    /**
     * Also receives {@link BlockMultiPlaceEvent} (beds and other multi-block items share this handler list), whose
     * replaced states cover every block that was set. At MONITOR the new blocks are already in the world.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        JournalService journal = journalFor(e.getPlayer());
        if (journal == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String actor = e.getPlayer().getName();
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState before : multi.getReplacedBlockStates()) {
                Block b = before.getBlock();
                journal.record(new JournalRecord(now, JournalRecord.Action.PLACE, actor, b.getWorld().getName(),
                        b.getX(), b.getY(), b.getZ(), before.getBlockData().getAsString(), b.getBlockData().getAsString()));
            }
            return;
        }
        Block b = e.getBlockPlaced();
        journal.record(new JournalRecord(now, JournalRecord.Action.PLACE, actor, b.getWorld().getName(),
                b.getX(), b.getY(), b.getZ(), e.getBlockReplacedState().getBlockData().getAsString(),
                b.getBlockData().getAsString()));
    }

    private JournalService journalFor(Player p) {
        JournalService journal = plugin.journal();
        return journal != null && plugin.registry().isVerified(p.getUniqueId()) ? journal : null;
    }
}
