package io.github.krekerdm.baritonebots.common.msg;

import io.github.krekerdm.baritonebots.common.geom.Vec3d;

import java.util.List;
import java.util.Map;

/**
 * Periodic bot snapshot (SPEC §2.5). {@code armor} has 4 entries head..feet, {@code null} for empty slots;
 * {@code items} sums the whole inventory by item id; optional fields are {@code null} when unknown.
 */
public record BotStatus(String botId, String username, String state, String server, String dim, Vec3d pos,
                        float yaw, float pitch, float health, float maxHealth, int food, float saturation,
                        int xpLevel, List<String> armor, String mainHand, String offhand, int freeSlots,
                        Map<String, Integer> items, TaskInfo task, BaritoneInfo baritone, Perf perf,
                        long uptimeSec, long time) {
    public static final String STARTING = "starting";
    public static final String MENU = "menu";
    public static final String CONNECTING = "connecting";
    public static final String LOGGING_IN = "logging_in";
    public static final String ONLINE = "online";
    public static final String DEAD = "dead";
    public static final String DISCONNECTED = "disconnected";
    public static final List<String> STATES = List.of(STARTING, MENU, CONNECTING, LOGGING_IN, ONLINE, DEAD, DISCONNECTED);

    public BotStatus {
        armor = Copies.listWithNulls(armor);
        items = Copies.map(items);
    }

    /** The running task; {@code progress} is 0..1 or -1 when unknown. */
    public record TaskInfo(String id, String type, String label, String state, String step, double progress) {
        public static final String RUNNING = "running";
        public static final String PAUSED = "paused";
    }

    /** Baritone state: controlling process name, whether it is pathing, goal text and ETA in seconds. */
    public record BaritoneInfo(String process, boolean pathing, String goal, Double eta) {
    }

    /** Client JVM and game performance; {@code cpu} is the process CPU load 0..1. */
    public record Perf(int heapUsedMb, int heapMaxMb, double cpu, int fps, int pingMs) {
    }
}
