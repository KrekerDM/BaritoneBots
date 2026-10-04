package io.github.krekerdm.baritonebots.manager.autopilot;

import io.github.krekerdm.baritonebots.manager.world.WorldDoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Container labels → roles (SPEC §5.7e). Pure, unit-tested.
 * <p>
 * The sign text is split into words (letters and digits; {@code ё} counts as {@code е}, case is ignored). A dictionary
 * word matches the same word, or a longer word starting with it when it has at least 4 letters ({@code склад} →
 * {@code склада}, {@code инструмент} → {@code инструменты}); the longest matching dictionary word wins. Values are
 * roles ({@code storage}, {@code inbox}, {@code kit}, ...) or categories ({@code sorted:wood}, or just {@code wood}).
 * The result keeps every distinct role in sign order plus the first category. Without any sign word an item frame on
 * the container gives the category of the framed item.
 */
public final class SignRoles {
    static final int MIN_PREFIX = 4;

    private SignRoles() {
    }

    /** Roles a label asks for; empty when it asks for nothing. */
    public static List<String> roles(String signText, String frameItem, Map<String, String> dictionary,
                                     Function<String, String> categoryOf) {
        Map<String, String> dict = normalizeKeys(dictionary);
        List<String> roles = new ArrayList<>();
        String category = null;
        for (String w : words(signText)) {
            String value = lookup(dict, w);
            if (value == null) {
                continue;
            }
            String role = role(value);
            if (role.startsWith(WorldDoc.SORTED_PREFIX)) {
                if (category == null) {
                    category = role;
                }
            } else if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        if (roles.isEmpty() && category == null && frameItem != null && !frameItem.isBlank() && categoryOf != null) {
            String cat = categoryOf.apply(frameItem);
            if (cat != null && !cat.isBlank()) {
                category = WorldDoc.SORTED_PREFIX + cat;
            }
        }
        if (category != null) {
            roles.add(category);
        }
        return roles;
    }

    /** Words of a text, lower-cased, {@code ё} → {@code е}. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String w : norm(text).split("[^\\p{L}\\p{Nd}_]+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** The value of the longest dictionary word matching {@code word}, or null. */
    static String lookup(Map<String, String> dict, String word) {
        String exact = dict.get(word);
        if (exact != null) {
            return exact;
        }
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : dict.entrySet()) {
            String k = e.getKey();
            if (k.length() >= MIN_PREFIX && k.length() > bestLen && word.startsWith(k)) {
                best = e.getValue();
                bestLen = k.length();
            }
        }
        return best;
    }

    /** A dictionary value as a container role: bare category names become {@code sorted:<name>}. */
    static String role(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith(WorldDoc.SORTED_PREFIX) || WorldDoc.CONTAINER_ROLES.contains(v)) {
            return v;
        }
        return WorldDoc.SORTED_PREFIX + v;
    }

    private static Map<String, String> normalizeKeys(Map<String, String> dictionary) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dictionary != null) {
            dictionary.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !v.isBlank()) {
                    out.putIfAbsent(norm(k.trim()), v);
                }
            });
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
