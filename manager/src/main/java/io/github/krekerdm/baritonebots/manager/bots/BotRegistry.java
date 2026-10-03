package io.github.krekerdm.baritonebots.manager.bots;

import io.github.krekerdm.baritonebots.manager.config.ManagerConfig;
import io.github.krekerdm.baritonebots.manager.http.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** All configured bots with their live state, keyed case-insensitively by id. Owned by the manager loop. */
public final class BotRegistry {
    private final Map<String, BotState> bots = new LinkedHashMap<>();

    private static String key(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    /**
     * Brings the registry in line with the config: new bots are created, existing ones get the new definition.
     *
     * @return states whose bot was removed from the config (the caller stops them)
     */
    public List<BotState> sync(ManagerConfig cfg) {
        Set<String> seen = new HashSet<>();
        Map<String, BotState> next = new LinkedHashMap<>();
        for (ManagerConfig.BotDef def : cfg.bots()) {
            String k = key(def.id());
            seen.add(k);
            BotState s = bots.get(k);
            if (s == null) {
                s = new BotState(def);
            } else {
                s.def = def;
            }
            next.put(k, s);
        }
        List<BotState> removed = new ArrayList<>();
        bots.forEach((k, s) -> {
            if (!seen.contains(k)) {
                removed.add(s);
            }
        });
        bots.clear();
        bots.putAll(next);
        return removed;
    }

    public BotState get(String id) {
        return bots.get(key(id));
    }

    /** @throws ApiException 404 when no such bot exists */
    public BotState require(String id) {
        BotState s = get(id);
        if (s == null) {
            throw ApiException.notFound("bot '" + id + "'");
        }
        return s;
    }

    public Collection<BotState> all() {
        return List.copyOf(bots.values());
    }
}
