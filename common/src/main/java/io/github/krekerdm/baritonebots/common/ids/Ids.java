package io.github.krekerdm.baritonebots.common.ids;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item / block / block-state id helpers (SPEC §1). Ids are {@code namespace:path}, optionally followed by a
 * block-state suffix {@code [k=v,...]}; tags start with {@code #}.
 */
public final class Ids {
    public static final String MINECRAFT = "minecraft";
    public static final String AIR = "minecraft:air";

    private Ids() {
    }

    /**
     * {@code "Stone"} → {@code "minecraft:stone"}. Lower-cases and trims the id part, adds the
     * {@code minecraft:} namespace when missing, keeps a {@code [...]} block-state suffix and a leading
     * {@code #} (tag) intact. {@code null} stays {@code null}.
     */
    public static String normalize(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim();
        if (s.isEmpty()) {
            return s;
        }
        String prefix = "";
        if (s.startsWith("#")) {
            prefix = "#";
            s = s.substring(1).trim();
        }
        int bracket = s.indexOf('[');
        String base = (bracket >= 0 ? s.substring(0, bracket) : s).trim().toLowerCase(Locale.ROOT);
        String state = bracket >= 0 ? s.substring(bracket) : "";
        if (base.indexOf(':') < 0) {
            base = MINECRAFT + ":" + base;
        }
        return prefix + base + state;
    }

    /**
     * Normalises a glob: like {@link #normalize} except that a lone {@code "*"} stays {@code "*"}
     * (any namespace) instead of becoming {@code minecraft:*}.
     */
    public static String normalizeGlob(String glob) {
        if (glob == null) {
            return null;
        }
        String s = glob.trim();
        return s.equals("*") ? s : normalize(s);
    }

    /** {@code minecraft:oak_stairs[facing=north]} → {@code minecraft:oak_stairs}. */
    public static String stripState(String id) {
        if (id == null) {
            return null;
        }
        int bracket = id.indexOf('[');
        return bracket >= 0 ? id.substring(0, bracket) : id;
    }

    /** Namespace part of a normalised id ({@code minecraft} when absent). */
    public static String namespace(String id) {
        String s = stripState(normalize(id));
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        int colon = s.indexOf(':');
        return colon < 0 ? MINECRAFT : s.substring(0, colon);
    }

    /** Path part of a normalised id: {@code minecraft:stone} → {@code stone}. */
    public static String path(String id) {
        String s = stripState(normalize(id));
        if (s == null) {
            return "";
        }
        return s.substring(s.indexOf(':') + 1);
    }

    /** True for air, cave air and void air (with or without block state). */
    public static boolean isAir(String id) {
        if (id == null) {
            return true;
        }
        String s = stripState(normalize(id));
        return s.equals(AIR) || s.equals("minecraft:cave_air") || s.equals("minecraft:void_air");
    }

    /** Block-state properties of {@code id[k=v,...]} in written order; empty map when there is no suffix. */
    public static Map<String, String> properties(String state) {
        Map<String, String> out = new LinkedHashMap<>();
        if (state == null) {
            return out;
        }
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        if (open < 0 || close < open) {
            return out;
        }
        String body = state.substring(open + 1, close).trim();
        if (body.isEmpty()) {
            return out;
        }
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** Builds {@code id[k=v,...]} with properties sorted by name (no suffix when {@code props} is empty). */
    public static String withProperties(String id, Map<String, String> props) {
        String base = stripState(normalize(id));
        if (props == null || props.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey().toLowerCase(Locale.ROOT)).append('=').append(e.getValue().toLowerCase(Locale.ROOT));
        }
        return sb.append(']').toString();
    }

    /**
     * Canonical block-state string: normalised id, properties sorted by name, no spaces.
     * Two spellings of the same state compare equal after this.
     */
    public static String canonicalState(String state) {
        if (state == null) {
            return null;
        }
        String n = normalize(state);
        if (n.indexOf('[') < 0) {
            return n;
        }
        if (n.lastIndexOf(']') < n.indexOf('[')) {
            return n;
        }
        return withProperties(n, properties(n));
    }

    /**
     * Glob match after normalising both sides; {@code *} matches any run of characters, all else is literal.
     * When the glob has no block-state suffix, the id's state suffix is ignored.
     */
    public static boolean matches(String glob, String id) {
        if (glob == null || id == null) {
            return false;
        }
        String g = normalizeGlob(glob);
        String s = normalize(id);
        if (g.isEmpty() || s.isEmpty()) {
            return false;
        }
        if (g.indexOf('[') < 0) {
            s = stripState(s);
        }
        return wildcard(g, s);
    }

    /** True when any glob in {@code globs} matches {@code id}; false for an empty or {@code null} list. */
    public static boolean matchesAny(Collection<String> globs, String id) {
        if (globs == null || id == null) {
            return false;
        }
        for (String g : globs) {
            if (matches(g, id)) {
                return true;
            }
        }
        return false;
    }

    /** Iterative '*' matcher with single-point backtracking: linear in practice, no regex compilation. */
    private static boolean wildcard(String pattern, String text) {
        int p = 0;
        int t = 0;
        int starP = -1;
        int starT = -1;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                starP = p++;
                starT = t;
            } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (starP >= 0) {
                p = starP + 1;
                t = ++starT;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
