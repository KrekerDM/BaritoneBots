package io.github.krekerdm.baritonebots.manager.events;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translations. The manager's own strings (task/step/role/settings labels, event messages, error codes) live in
 * {@code /i18n-catalog/<lang>.json}; the panel's UI strings in {@code /panel/i18n/<lang>.json}. Both may be flat
 * ({@code "a.b": "x"}) or nested; {@link #merged(String)} returns one flat map with the catalog on top.
 * Placeholders are {@code {name}}. Thread-safe (immutable data after first load).
 */
public final class I18n {
    public static final List<String> LANGS = List.of("ru", "en");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final Map<String, JsonObject> catalogs = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> mergedCache = new ConcurrentHashMap<>();

    public static String normalizeLang(String lang) {
        String l = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
        return LANGS.contains(l) ? l : "en";
    }

    /** Panel file + manager catalog, flat. */
    public JsonObject merged(String lang) {
        String l = normalizeLang(lang);
        return mergedCache.computeIfAbsent(l, k -> {
            JsonObject out = new JsonObject();
            flatten(load("panel/i18n/" + k + ".json"), "", out);
            catalog(k).entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
            return out;
        }).deepCopy();
    }

    private JsonObject catalog(String lang) {
        return catalogs.computeIfAbsent(lang, k -> {
            JsonObject out = new JsonObject();
            flatten(load("i18n-catalog/" + k + ".json"), "", out);
            return out;
        });
    }

    /** Catalog text in {@code lang} (English, then the key itself as fallbacks) with placeholders filled. */
    public String t(String lang, String key, Map<String, ?> args) {
        String l = normalizeLang(lang);
        String text = Json.getString(catalog(l), key, null);
        if (text == null && !"en".equals(l)) {
            text = Json.getString(catalog("en"), key, null);
        }
        if (text == null) {
            text = key;
        }
        return format(text, args);
    }

    public boolean has(String lang, String key) {
        return catalog(normalizeLang(lang)).has(key);
    }

    static String format(String text, Map<String, ?> args) {
        if (args == null || args.isEmpty()) {
            return text;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = args.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static JsonObject load(String resource) {
        try (InputStream in = I18n.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return new JsonObject();
            }
            JsonElement e = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot read %s: %s", resource, e.getMessage());
            return new JsonObject();
        }
    }

    private static void flatten(JsonObject src, String prefix, JsonObject out) {
        for (Map.Entry<String, JsonElement> e : src.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                flatten(v.getAsJsonObject(), key, out);
            } else if (v.isJsonPrimitive()) {
                out.addProperty(key, v.getAsString());
            }
        }
    }
}
