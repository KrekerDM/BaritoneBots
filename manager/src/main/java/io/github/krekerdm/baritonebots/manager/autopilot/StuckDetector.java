package io.github.krekerdm.baritonebots.manager.autopilot;

import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stuck detection (SPEC §5.7b): a running task whose bot has not moved a block, whose step / progress has not
 * changed and whose Baritone goal has not changed for {@code stuckSec}. Tasks that legitimately stand still
 * ({@code guard}, {@code follow}, {@code idle}, {@code eat}) and paused tasks are never stuck. Pure: fed with
 * status samples by the autopilot.
 */
public final class StuckDetector {
    static final Set<String> EXEMPT = Set.of(TaskTypes.GUARD, TaskTypes.FOLLOW, TaskTypes.IDLE, TaskTypes.EAT);
    static final double MOVE_BLOCKS = 1.0;
    static final double PROGRESS_EPS = 0.01;

    /** One status sample of a running task. */
    public record Sample(String taskId, String type, boolean paused, double x, double y, double z, String step,
                         double progress, String goal) {
    }

    private record Track(String taskId, double x, double y, double z, String step, double progress, String goal,
                         long since) {
    }

    private final Map<String, Track> tracks = new HashMap<>();

    /**
     * @param sample  {@code null} when no task runs
     * @param stuckMs 0 = off
     * @return true once when the bot is stuck (the timer then restarts)
     */
    public boolean update(String botId, Sample sample, long now, long stuckMs) {
        if (stuckMs <= 0 || sample == null || sample.taskId() == null || EXEMPT.contains(sample.type())) {
            tracks.remove(botId);
            return false;
        }
        Track t = tracks.get(botId);
        if (t == null || !t.taskId().equals(sample.taskId()) || sample.paused() || moved(t, sample) || changed(t, sample)) {
            tracks.put(botId, track(sample, now));
            return false;
        }
        if (now - t.since() >= stuckMs) {
            tracks.put(botId, track(sample, now));
            return true;
        }
        return false;
    }

    public void forget(String botId) {
        tracks.remove(botId);
    }

    /** Milliseconds without progress so far (0 when not tracked). */
    public long stillFor(String botId, long now) {
        Track t = tracks.get(botId);
        return t == null ? 0 : now - t.since();
    }

    private static Track track(Sample s, long now) {
        return new Track(s.taskId(), s.x(), s.y(), s.z(), s.step(), s.progress(), s.goal(), now);
    }

    private static boolean moved(Track t, Sample s) {
        double dx = s.x() - t.x();
        double dy = s.y() - t.y();
        double dz = s.z() - t.z();
        return dx * dx + dy * dy + dz * dz >= MOVE_BLOCKS * MOVE_BLOCKS;
    }

    private static boolean changed(Track t, Sample s) {
        return !Objects.equals(t.step(), s.step()) || Math.abs(t.progress() - s.progress()) >= PROGRESS_EPS
                || !Objects.equals(t.goal(), s.goal());
    }
}
