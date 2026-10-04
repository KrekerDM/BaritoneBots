package io.github.krekerdm.baritonebots.plugin.rollback;

import io.github.krekerdm.baritonebots.plugin.PluginSettings;
import io.github.krekerdm.baritonebots.plugin.journal.JournalRecord;
import io.github.krekerdm.baritonebots.plugin.journal.JournalService;
import io.github.krekerdm.baritonebots.plugin.journal.TimeWindow;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Runs rollback requests from the admin command and from verified bots. {@code journal} mode reads the bot's records
 * (memory or files, off the server thread) and restores them in batches with a {@link RollbackJob}; {@code command}
 * mode runs the configured console command (e.g. Prism or CoreProtect) with {@code {player}} and {@code {minutes}}
 * filled in. One journal rollback at a time. Server thread only.
 */
public final class RollbackService {
    private final Plugin plugin;
    private RollbackJob job;
    private String jobTarget;
    /** True while the records of a journal rollback are being read. */
    private boolean reading;
    /** Set by {@link #cancel()} on disable; late journal reads then end as cancelled instead of starting a job. */
    private boolean closed;

    public RollbackService(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Largest window accepted in the current mode. */
    public static int maxMinutes(PluginSettings s) {
        return s.rollback().commandMode() ? TimeWindow.MAX_COMMAND_MINUTES
                : TimeWindow.maxJournalMinutes(s.journal().retentionDays());
    }

    /** True when the current settings can serve a rollback at all. */
    public static boolean available(PluginSettings s, JournalService journal) {
        return s.rollback().commandMode() ? !s.rollback().command().isEmpty() : journal != null;
    }

    /**
     * @param target  canonical bot name (validated against the {@code bots} list by the caller)
     * @param journal null when the journal is off
     * @param done    called exactly once, on the server thread
     */
    public void request(String target, int minutes, PluginSettings s, JournalService journal,
                        Consumer<RollbackResult> done) {
        PluginSettings.Rollback cfg = s.rollback();
        String via = cfg.commandMode() ? RollbackResult.VIA_COMMAND : RollbackResult.VIA_JOURNAL;
        if (!available(s, journal)) {
            done.accept(RollbackResult.failure(via, RollbackResult.UNAVAILABLE));
            return;
        }
        if (!TimeWindow.validMinutes(minutes, maxMinutes(s))) {
            done.accept(RollbackResult.failure(via, RollbackResult.BAD_MINUTES));
            return;
        }
        if (cfg.commandMode()) {
            done.accept(runCommand(cfg.command(), target, minutes));
            return;
        }
        if (closed || busy()) {
            done.accept(RollbackResult.failure(via, RollbackResult.BUSY));
            return;
        }
        reading = true;
        jobTarget = target;
        long since = TimeWindow.since(journal.now(), minutes);
        journal.records(target, since, cfg.maxBlocks()).whenComplete((read, error) -> {
            reading = false;
            if (closed) {
                done.accept(RollbackResult.failure(via, RollbackResult.CANCELLED));
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Rollback of " + target + ": journal read failed", error);
                jobTarget = null;
                done.accept(RollbackResult.failure(via, RollbackResult.ERROR));
                return;
            }
            if (read.truncated()) {
                jobTarget = null;
                done.accept(RollbackResult.failure(via, RollbackResult.TOO_LARGE));
                return;
            }
            List<JournalRecord> order = RollbackRules.undoOrder(read.records());
            if (order.isEmpty()) {
                jobTarget = null;
                done.accept(RollbackResult.journal(0, 0, 0, 0));
                return;
            }
            RollbackJob started = new RollbackJob(plugin, order, cfg.blocksPerTick(), result -> {
                job = null;
                jobTarget = null;
                done.accept(result);
            });
            job = started;
            started.start();
        });
    }

    private RollbackResult runCommand(String template, String target, int minutes) {
        // The target passed the vanilla name rule, so it cannot smuggle spaces or extra arguments into the command.
        String command = template.replace("{player}", target).replace("{minutes}", Integer.toString(minutes));
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!ok) {
                plugin.getLogger().warning("Rollback command returned false: /" + command);
            }
            return RollbackResult.command(command, ok);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Rollback command failed: /" + command, e);
            return RollbackResult.command(command, false);
        }
    }

    public boolean busy() {
        return reading || job != null;
    }

    /** "name 120/500" while a journal rollback runs, else null. */
    public String describeRunning() {
        if (job != null) {
            return jobTarget + " " + job.progress() + "/" + job.total();
        }
        return reading ? jobTarget + " (reading)" : null;
    }

    /** Plugin disable: stops a running job; its callback reports {@code cancelled}. */
    public void cancel() {
        closed = true;
        RollbackJob j = job;
        if (j != null) {
            j.cancel();
        }
        job = null;
        reading = false;
        jobTarget = null;
    }
}
