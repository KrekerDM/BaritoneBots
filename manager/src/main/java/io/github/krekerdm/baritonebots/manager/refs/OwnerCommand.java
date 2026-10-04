package io.github.krekerdm.baritonebots.manager.refs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses an owner command (the chat text after the prefix, SPEC §5.7e). Pure, unit-tested.
 * <p>
 * The first word is the verb (English or Russian alias, case-insensitive; the two-word Russian phrases «ко мне» and
 * «за мной» count as one), the rest are arguments in their original case. A word starting with {@code @} picks the
 * bots: {@code @all} / {@code @все}, {@code @any} / {@code @любой}, or a bot id / name. Unknown verbs keep their
 * text in {@link #verb()} with {@link #known()} false; an empty command is {@code help}.
 */
public record OwnerCommand(String verb, List<String> args, String target, boolean known) {
    public static final String HERE = "here";
    public static final String HOME = "home";
    public static final String POS1 = "pos1";
    public static final String POS2 = "pos2";
    public static final String AREA = "area";
    public static final String CHEST = "chest";
    public static final String COME = "come";
    public static final String FOLLOW = "follow";
    public static final String STOP = "stop";
    public static final String BUILD = "build";
    public static final String FARM = "farm";
    public static final String RANCH = "ranch";
    public static final String TRASH = "trash";
    public static final String PROGRESS = "progress";
    public static final String OBTAIN = "obtain";
    public static final String HELP = "help";
    public static final String TARGET_ALL = "all";
    public static final String TARGET_ANY = "any";

    static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("here", HERE), Map.entry("тут", HERE), Map.entry("здесь", HERE), Map.entry("точка", HERE),
            Map.entry("home", HOME), Map.entry("sethome", HOME), Map.entry("дом", HOME),
            Map.entry("pos1", POS1), Map.entry("поз1", POS1), Map.entry("угол1", POS1),
            Map.entry("pos2", POS2), Map.entry("поз2", POS2), Map.entry("угол2", POS2),
            Map.entry("area", AREA), Map.entry("область", AREA),
            Map.entry("chest", CHEST), Map.entry("container", CHEST), Map.entry("сундук", CHEST),
            Map.entry("come", COME), Map.entry("сюда", COME), Map.entry("иди", COME), Map.entry("комне", COME),
            Map.entry("follow", FOLLOW), Map.entry("следуй", FOLLOW), Map.entry("замной", FOLLOW),
            Map.entry("stop", STOP), Map.entry("стоп", STOP), Map.entry("стой", STOP), Map.entry("хватит", STOP),
            Map.entry("build", BUILD), Map.entry("строй", BUILD), Map.entry("построй", BUILD),
            Map.entry("farm", FARM), Map.entry("ферма", FARM),
            Map.entry("ranch", RANCH), Map.entry("ранчо", RANCH), Map.entry("загон", RANCH),
            Map.entry("trash", TRASH), Map.entry("мусор", TRASH), Map.entry("выброси", TRASH),
            Map.entry("progress", PROGRESS), Map.entry("прогресс", PROGRESS), Map.entry("развитие", PROGRESS),
            Map.entry("obtain", OBTAIN), Map.entry("get", OBTAIN), Map.entry("добудь", OBTAIN),
            Map.entry("достань", OBTAIN), Map.entry("добыть", OBTAIN),
            Map.entry("help", HELP), Map.entry("помощь", HELP), Map.entry("?", HELP));

    /** Tier words of {@code progress}. */
    static final Map<String, String> TIERS = Map.of("wood", "wood", "stone", "stone", "iron", "iron",
            "diamond", "diamond", "дерево", "wood", "камень", "stone", "железо", "iron", "алмаз", "diamond",
            "алмазы", "diamond", "железка", "iron");

    public OwnerCommand {
        args = List.copyOf(args);
    }

    public static OwnerCommand parse(String text) {
        String t = text == null ? "" : text.strip();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String phrase : List.of("ко мне", "за мной")) {
            if (lower.equals(phrase) || lower.startsWith(phrase + " ")) {
                t = phrase.replace(" ", "") + t.substring(phrase.length());
                break;
            }
        }
        List<String> words = new ArrayList<>();
        String target = null;
        for (String w : t.split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (w.startsWith("@") && w.length() > 1) {
                String tg = w.substring(1);
                String tl = tg.toLowerCase(Locale.ROOT);
                target = switch (tl) {
                    case "all", "все", "всем" -> TARGET_ALL;
                    case "any", "любой" -> TARGET_ANY;
                    default -> tg;
                };
                continue;
            }
            words.add(w);
        }
        if (words.isEmpty()) {
            return new OwnerCommand(HELP, List.of(), target, true);
        }
        String first = words.getFirst().toLowerCase(Locale.ROOT);
        String verb = ALIASES.get(first);
        List<String> rest = words.subList(1, words.size());
        return verb == null ? new OwnerCommand(first, rest, target, false) : new OwnerCommand(verb, rest, target, true);
    }

    /** {@code progress} tier from an English or Russian word, or null. */
    public static String tier(String word) {
        return word == null ? null : TIERS.get(word.toLowerCase(Locale.ROOT));
    }

    /** First argument, or null. */
    public String arg(int i) {
        return i < args.size() ? args.get(i) : null;
    }
}
