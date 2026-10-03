package io.github.krekerdm.baritonebots.manager.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal method + path-pattern router; {@code {name}} segments capture one decoded path segment. */
final class Router {
    @FunctionalInterface
    interface Handler {
        Object handle(Req r) throws Exception;
    }

    private record Route(String method, String[] parts, Handler handler) {
    }

    /** {@code handler == null} means the path exists but not for this method (405). */
    record Match(Handler handler, Map<String, String> params) {
    }

    private final List<Route> routes = new ArrayList<>();

    void add(String method, String pattern, Handler h) {
        routes.add(new Route(method, split(pattern), h));
    }

    void get(String pattern, Handler h) {
        add("GET", pattern, h);
    }

    void post(String pattern, Handler h) {
        add("POST", pattern, h);
    }

    void put(String pattern, Handler h) {
        add("PUT", pattern, h);
    }

    void delete(String pattern, Handler h) {
        add("DELETE", pattern, h);
    }

    /** Returns null when no route has this path. */
    Match find(String method, String rawPath) {
        String[] segs = split(rawPath);
        boolean pathMatched = false;
        for (Route r : routes) {
            Map<String, String> params = match(r.parts(), segs);
            if (params == null) {
                continue;
            }
            if (r.method().equals(method)) {
                return new Match(r.handler(), params);
            }
            pathMatched = true;
        }
        return pathMatched ? new Match(null, Map.of()) : null;
    }

    private static Map<String, String> match(String[] pattern, String[] segs) {
        if (pattern.length != segs.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            String s = decode(segs[i]);
            if (p.startsWith("{") && p.endsWith("}")) {
                if (s.isEmpty()) {
                    return null;
                }
                params.put(p.substring(1, p.length() - 1), s);
            } else if (!p.equals(s)) {
                return null;
            }
        }
        return params;
    }

    static String decode(String seg) {
        try {
            return URLDecoder.decode(seg.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return seg;
        }
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? new String[0] : p.split("/", -1);
    }
}
