package io.github.krekerdm.baritonebots.manager.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.krekerdm.baritonebots.manager.Manager;
import io.github.krekerdm.baritonebots.manager.bots.BotState;
import io.github.krekerdm.baritonebots.manager.config.ValidationException;
import io.github.krekerdm.baritonebots.manager.planner.Assignment;
import io.github.krekerdm.baritonebots.manager.tasks.QueueEntry;
import io.github.krekerdm.baritonebots.manager.tasks.TaskCatalog;
import io.github.krekerdm.baritonebots.manager.tasks.TaskQueue;
import io.github.krekerdm.baritonebots.manager.tasks.Validators;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Queues the steps of a schedule or rule on bots (SPEC §5.7b). Origins are {@code schedule:<id>} / {@code rule:<id>}:
 * user intent like a manual task, but "soft" ({@link io.github.krekerdm.baritonebots.manager.planner.Planner#isSoftOrigin}):
 * with priority {@code normal} the entries are appended and run when the bot's current planner batch ends (planner
 * work is not cancelled); with {@code high} the bot's planner assignment is released (its entries cancelled) and the
 * steps go to the queue front. A bot that still has entries of the same origin is skipped (no pile-up). Loop-owned.
 */
public final class StepRunner {
    public static final String ORIGIN_SCHEDULE = "schedule:";
    public static final String ORIGIN_RULE = "rule:";
    public static final String ANY = "any";
    public static final String ALL = "all";

    private final Manager m;

    public StepRunner(Manager m) {
        this.m = m;
    }

    /** {@code botIds}: a list of ids, {@code "any"} (one free bot) or {@code "all"}. */
    public record Target(String mode, List<String> ids) {
        public static Target of(JsonElement botIds) {
            if (botIds == null || botIds.isJsonNull()) {
                return new Target(ANY, List.of());
            }
            if (botIds.isJsonArray()) {
                List<String> ids = new ArrayList<>();
                botIds.getAsJsonArray().forEach(e -> ids.add(e.getAsString()));
                return new Target("list", List.copyOf(ids));
            }
            String s = botIds.getAsString().trim().toLowerCase(Locale.ROOT);
            return new Target(ALL.equals(s) ? ALL : ANY, List.of());
        }

        boolean includes(String botId) {
            return !"list".equals(mode) || ids.stream().anyMatch(x -> x.equalsIgnoreCase(botId));
        }
    }

    /**
     * Checks {@code botIds} shape and ids; returns an error code or null.
     */
    public static String checkTarget(JsonElement botIds, java.util.Set<String> knownBotIdsLower) {
        if (botIds == null || botIds.isJsonNull()) {
            return null;
        }
        if (botIds.isJsonPrimitive() && botIds.getAsJsonPrimitive().isString()) {
            String s = botIds.getAsString().trim().toLowerCase(Locale.ROOT);
            return ANY.equals(s) || ALL.equals(s) ? null : "enum";
        }
        if (!botIds.isJsonArray() || botIds.getAsJsonArray().isEmpty()) {
            return "type";
        }
        for (JsonElement e : botIds.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !knownBotIdsLower.contains(e.getAsString().toLowerCase(Locale.ROOT))) {
                return "not_found";
            }
        }
        return null;
    }

    /**
     * Validates a step list (TaskTemplates / manager steps, as in scenarios).
     *
     * @return null when fine, else {@code "<subpath>:<code>"} relative to the list (e.g. {@code [0].type:unknown_type})
     */
    public static String checkSteps(TaskCatalog catalog, JsonElement steps) {
        if (steps == null || !steps.isJsonArray() || steps.getAsJsonArray().isEmpty()) {
            return steps == null || steps.isJsonNull() || steps.isJsonArray() ? ":required" : ":type";
        }
        JsonArray a = steps.getAsJsonArray();
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).isJsonObject()) {
                return "[" + i + "]:type";
            }
            try {
                Validators.template(catalog, a.get(i).getAsJsonObject(), "[" + i + "]");
            } catch (ValidationException e) {
                var first = e.fields().entrySet().iterator().next();
                return first.getKey() + ":" + first.getValue();
            }
        }
        return null;
    }

    /**
     * Bots to run on: {@code trigger} (when given and allowed), the listed bots, every bot ({@code all}) or the best
     * free bot ({@code any}: idle first, then without planner work, then the shortest queue). Only enabled bots of
     * {@code serverId} (any server when null); {@code any} / {@code all} only pick online, living bots.
     */
    public List<BotState> pick(Target t, String serverId, BotState trigger) {
        return pick(t, serverId, trigger, null);
    }

    /** {@link #pick(Target, String, BotState)} without the bots {@code skip} matches (null = none). */
    public List<BotState> pick(Target t, String serverId, BotState trigger, java.util.function.Predicate<BotState> skip) {
        if (trigger != null) {
            return t.includes(trigger.id) && onServer(trigger, serverId) ? List.of(trigger) : List.of();
        }
        List<BotState> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (BotState b : m.bots.all()) {
            if (!b.def.enabled() || !onServer(b, serverId) || !t.includes(b.id) || skip != null && skip.test(b)) {
                continue;
            }
            if ("list".equals(t.mode()) || b.online() && !b.dead) {
                out.add(b);
            }
        }
        if (!ANY.equals(t.mode()) || out.size() <= 1) {
            return out;
        }
        out.sort(Comparator.comparingInt((BotState b) -> m.planner.isIdle(b, now) ? 0 : 1)
                .thenComparingInt(b -> m.planner.assignmentOf(b.id) == null ? 0 : 1)
                .thenComparingInt(b -> b.queue.size() + (b.queue.current() == null ? 0 : 1)));
        return List.of(out.getFirst());
    }

    private static boolean onServer(BotState b, String serverId) {
        return serverId == null || serverId.equalsIgnoreCase(String.valueOf(b.def.serverId()));
    }

    /**
     * Queues {@code steps} on {@code b} with {@code origin}.
     *
     * @return entries queued, 0 when skipped because entries of the same origin are still queued
     * @throws IllegalArgumentException when a step is invalid or unsupported
     */
    public int run(BotState b, JsonElement steps, String origin, boolean high, String label) {
        if (b.queue.anyMatch(x -> x.hasOrigin(origin))) {
            return 0;
        }
        String bad = checkSteps(m.catalog, steps);
        if (bad != null) {
            throw new IllegalArgumentException("invalid steps (" + bad + ")");
        }
        List<QueueEntry> entries = new ArrayList<>();
        JsonArray a = steps.getAsJsonArray();
        for (int i = 0; i < a.size(); i++) {
            var tpl = Validators.template(m.catalog, a.get(i).getAsJsonObject(), "[" + i + "]");
            String type = tpl.get("type").getAsString();
            if (!m.catalog.isSupported(type)) {
                throw new IllegalArgumentException("step '" + type + "' is not supported");
            }
            if (label != null && !tpl.has("label")) {
                tpl.addProperty("label", label);
            }
            entries.add(QueueEntry.fromTemplate(tpl, origin));
        }
        if (high) {
            Assignment as = m.planner.assignmentOf(b.id);
            if (as != null) {
                m.planner.release(as, "manual", true);
            }
        }
        m.dispatcher.add(b, entries, high ? TaskQueue.Mode.FRONT : TaskQueue.Mode.APPEND);
        return entries.size();
    }
}
