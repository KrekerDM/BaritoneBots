package io.github.krekerdm.baritonebots.mod.behaviour;

import io.github.krekerdm.baritonebots.common.msg.BotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Recognises owner commands in chat lines (SPEC §5.7e). Pure: no Minecraft classes, unit-tested.
 * <p>
 * Signed player chat already names its sender ({@link #fromPlayer}); system lines (chat plugins, whispers rendered
 * as text) are matched against {@link BotConfig.Owner#patterns()} — regexes with the named groups {@code name} and
 * {@code msg} — in order ({@link #fromSystem}); the first matching pattern decides who spoke. A line counts when the
 * name is the owner (case-insensitive) and the message starts with the prefix followed by a space or the end; the
 * command text is what follows the prefix. A pattern containing the empty group {@code (?<dm>)} marks a private
 * message format ({@code via = whisper}).
 */
public final class OwnerChat {
    /** A recognised command: {@code text} without the prefix (trimmed), {@code player} as written in the line. */
    public record Command(String player, String text, String via) {
    }

    public static final String VIA_CHAT = "chat";
    public static final String VIA_WHISPER = "whisper";
    public static final String VIA_SYSTEM = "system";

    private final String owner;
    private final String prefix;
    private final List<Pattern> patterns;
    private final List<String> invalid = new ArrayList<>();

    public OwnerChat(BotConfig.Owner cfg) {
        this.owner = cfg.player();
        this.prefix = cfg.prefix();
        List<Pattern> out = new ArrayList<>();
        for (String raw : cfg.patterns()) {
            try {
                Pattern p = Pattern.compile(raw);
                if (raw.contains("(?<name>") && raw.contains("(?<msg>")) {
                    out.add(p);
                } else {
                    invalid.add(raw + " (needs the groups 'name' and 'msg')");
                }
            } catch (PatternSyntaxException e) {
                invalid.add(raw + " (" + e.getDescription() + ")");
            }
        }
        this.patterns = List.copyOf(out);
    }

    /** Patterns that could not be used (for one warning). */
    public List<String> invalidPatterns() {
        return List.copyOf(invalid);
    }

    public boolean enabled() {
        return !prefix.isEmpty();
    }

    /**
     * No owner configured yet: any player's prefixed line is passed on, so the manager can offer that player as the
     * owner (SPEC §5.7e, owner detection); the manager runs nothing until the owner is confirmed.
     */
    public boolean ownerUnknown() {
        return owner.isEmpty();
    }

    private boolean isOwner(String name) {
        return owner.isEmpty() || name.equalsIgnoreCase(owner);
    }

    /** A chat message whose sender is known (signed or unsigned player chat). */
    public Command fromPlayer(String sender, String body, boolean whisper) {
        if (!enabled() || sender == null || body == null || !isOwner(sender)) {
            return null;
        }
        String text = stripPrefix(body.strip());
        return text == null ? null : new Command(sender, text, whisper ? VIA_WHISPER : VIA_CHAT);
    }

    /** A system line: the first pattern that matches decides the speaker and the message. */
    public Command fromSystem(String line) {
        if (!enabled() || line == null || line.isBlank()) {
            return null;
        }
        String plain = line.strip();
        for (Pattern p : patterns) {
            Matcher m = p.matcher(plain);
            if (!m.find()) {
                continue;
            }
            String name = m.group("name");
            String msg = m.group("msg");
            if (name == null || msg == null) {
                continue;
            }
            if (!isOwner(name)) {
                return null; // somebody else spoke; a later, looser pattern must not reinterpret the line
            }
            String text = stripPrefix(msg.strip());
            boolean dm = p.pattern().contains("(?<dm>") && m.group("dm") != null;
            return text == null ? null : new Command(name, text, dm ? VIA_WHISPER : VIA_SYSTEM);
        }
        return null;
    }

    /** Text after the prefix, or null when the message does not start with it. */
    String stripPrefix(String msg) {
        if (msg.length() < prefix.length() || !msg.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String rest = msg.substring(prefix.length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return null; // "!bx" is not "!b x"
        }
        return rest.strip();
    }
}
