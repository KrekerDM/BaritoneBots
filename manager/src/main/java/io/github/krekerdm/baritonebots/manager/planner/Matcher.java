package io.github.krekerdm.baritonebots.manager.planner;

import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Matches idle bots to work items (SPEC §5.7): score = priority − distance/64 − (role change ? 2 : 0); greedy over all
 * pairs, best score first, respecting each item's remaining capacity, the bots' allowed roles and the role cooldown.
 * Ties break on bot id, then item key, so the result is deterministic. Pure.
 */
public final class Matcher {
    public static final double ROLE_CHANGE_PENALTY = 2.0;
    public static final double DISTANCE_DIVISOR = 64.0;

    /**
     * An idle bot.
     *
     * @param roles allowed roles (empty = any)
     */
    public record Bot(String id, String dim, Pos pos, List<String> roles) {
        public Bot {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public boolean allows(String role) {
            return roles.isEmpty() || roles.contains(role);
        }
    }

    /** An item with how many more bots it can take right now. */
    public record Offer(WorkItem item, int slots) {
    }

    public record Match(String botId, WorkItem item, double score, boolean roleChange) {
    }

    private Matcher() {
    }

    /**
     * @param eligible extra per-pair check (project membership, tools, ...)
     */
    public static List<Match> match(List<Bot> bots, List<Offer> offers, RoleTracker roles, long now, long cooldownMs,
                                    BiPredicate<Bot, WorkItem> eligible) {
        List<Match> pairs = new ArrayList<>();
        for (Bot b : bots) {
            for (Offer o : offers) {
                WorkItem w = o.item();
                if (o.slots() <= 0 || !b.allows(w.role()) || !roles.allows(b.id(), w.role(), now, cooldownMs)) {
                    continue;
                }
                if (w.location() != null && w.dim() != null && b.dim() != null
                        && !Dims.normalize(w.dim()).equals(Dims.normalize(b.dim()))) {
                    continue;
                }
                if (eligible != null && !eligible.test(b, w)) {
                    continue;
                }
                boolean change = roles.isChange(b.id(), w.role());
                pairs.add(new Match(b.id(), w, score(b, w, change), change));
            }
        }
        pairs.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(Match::botId).thenComparing(m -> m.item().key()));
        Map<String, Integer> left = new HashMap<>();
        offers.forEach(o -> left.merge(o.item().key(), o.slots(), Integer::sum));
        Set<String> taken = new HashSet<>();
        List<Match> out = new ArrayList<>();
        for (Match m : pairs) {
            String key = m.item().key();
            if (taken.contains(m.botId()) || left.getOrDefault(key, 0) <= 0) {
                continue;
            }
            taken.add(m.botId());
            left.merge(key, -1, Integer::sum);
            out.add(m);
        }
        return out;
    }

    public static double score(Bot b, WorkItem w, boolean roleChange) {
        double dist = 0;
        if (b.pos() != null && w.location() != null) {
            dist = b.pos().distance(w.location());
        }
        return w.priority() - dist / DISTANCE_DIVISOR - (roleChange ? ROLE_CHANGE_PENALTY : 0);
    }
}
