package io.github.krekerdm.baritonebots.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Russian and English texts from {@code lang/<language>.properties} (UTF-8, MiniMessage markup, placeholders as
 * {@code <name>} tags). A key missing in the chosen language falls back to English, then to the key itself.
 */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final String language;
    private final Properties chosen;
    private final Properties english;

    private Messages(String language, Properties chosen, Properties english) {
        this.language = language;
        this.chosen = chosen;
        this.english = english;
    }

    public static Messages load(String language) {
        Properties en = loadBundle("en");
        Properties chosen = "en".equals(language) ? en : loadBundle(language);
        return new Messages(language, chosen, en);
    }

    private static Properties loadBundle(String lang) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream("/lang/" + lang + ".properties")) {
            if (in == null) {
                return p;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the bundled lang/" + lang + ".properties", e);
        }
        return p;
    }

    public String language() {
        return language;
    }

    public String raw(String key) {
        String v = chosen.getProperty(key);
        if (v == null) {
            v = english.getProperty(key);
        }
        return v == null ? key : v;
    }

    /** Chat component with the shared prefix. */
    public Component chat(String key, TagResolver... args) {
        return MM.deserialize(raw("prefix"), args).append(component(key, args));
    }

    /** Component without prefix (status lines, list entries). */
    public Component component(String key, TagResolver... args) {
        return MM.deserialize(raw(key), args);
    }

    /** Plain text for logs, kick messages to non-chat surfaces and notices sent to bots. */
    public String plain(String key, TagResolver... args) {
        return PlainTextComponentSerializer.plainText().serialize(component(key, args));
    }

    /** Placeholder whose value is inserted literally (never parsed as markup). */
    public static TagResolver arg(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver arg(String name, ComponentLike value) {
        return Placeholder.component(name, value);
    }
}
