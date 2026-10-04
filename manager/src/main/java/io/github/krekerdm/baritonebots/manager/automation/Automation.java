package io.github.krekerdm.baritonebots.manager.automation;

import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.events.ManagerEvent;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules and rules (SPEC §5.7b). Owns the minute timer (aligned to the wall-clock minute) and routes the
 * manager's inputs (events, bot status, container snapshots) to {@link RuleService}. Loop-owned.
 */
public final class Automation {
    private final Manager m;
    private final StepRunner runner;
    private final ScheduleService schedules;
    private final RuleService rules;
    private ScheduledFuture<?> timer;

    public Automation(Manager m) {
        this.m = m;
        this.runner = new StepRunner(m);
        this.schedules = new ScheduleService(m, runner);
        this.rules = new RuleService(m, runner);
    }

    public ScheduleService schedules() {
        return schedules;
    }

    public RuleService rules() {
        return rules;
    }

    public StepRunner runner() {
        return runner;
    }

    /** Starts the minute timer (loop). */
    public void start() {
        if (timer != null) {
            return;
        }
        long now = System.currentTimeMillis();
        long delay = 60_000 - now % 60_000 + 250;
        timer = m.loop.every(this::minute, delay, 60_000, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    /** Once a minute: schedules, then the polled rule conditions. */
    public void minute() {
        try {
            schedules.tick();
        } finally {
            rules.minute();
        }
    }

    public void onEvent(ManagerEvent e) {
        rules.onEvent(e);
    }

    public void onStatus(BotState b) {
        rules.onStatus(b);
    }

    public void onSnapshot(String serverId) {
        rules.onSnapshot(serverId);
    }
}
