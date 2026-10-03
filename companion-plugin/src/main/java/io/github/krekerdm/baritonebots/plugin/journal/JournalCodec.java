package io.github.krekerdm.baritonebots.plugin.journal;

import java.util.ArrayList;
import java.util.List;

/**
 * One record per line, tab-separated:
 * {@code 1 <time> <B|P> <actor> <world> <x> <y> <z> <before> <after>}.
 * The leading {@code 1} is the format version. Text fields escape {@code \\}, tab, CR and LF, so a raw tab is
 * always a separator and a raw newline always ends a record.
 */
public final class JournalCodec {
    public static final String VERSION = "1";
    private static final int FIELDS = 10;

    private JournalCodec() {
    }

    public static String encode(JournalRecord r) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(VERSION).append('\t')
                .append(r.time()).append('\t')
                .append(r.action().code()).append('\t');
        escape(sb, r.actor()).append('\t');
        escape(sb, r.world()).append('\t');
        sb.append(r.x()).append('\t').append(r.y()).append('\t').append(r.z()).append('\t');
        escape(sb, r.before()).append('\t');
        escape(sb, r.after());
        return sb.toString();
    }

    /** @throws IllegalArgumentException for a line that is not a valid version-1 record */
    public static JournalRecord decode(String line) {
        if (line == null) {
            throw new IllegalArgumentException("null line");
        }
        List<String> f = split(line);
        if (f.size() != FIELDS) {
            throw new IllegalArgumentException("expected " + FIELDS + " fields, got " + f.size());
        }
        if (!VERSION.equals(f.get(0))) {
            throw new IllegalArgumentException("unsupported record version '" + f.get(0) + "'");
        }
        String action = f.get(2);
        if (action.length() != 1) {
            throw new IllegalArgumentException("bad action '" + action + "'");
        }
        try {
            return new JournalRecord(
                    Long.parseLong(f.get(1)),
                    JournalRecord.Action.ofCode(action.charAt(0)),
                    unescape(f.get(3)),
                    unescape(f.get(4)),
                    Integer.parseInt(f.get(5)),
                    Integer.parseInt(f.get(6)),
                    Integer.parseInt(f.get(7)),
                    unescape(f.get(8)),
                    unescape(f.get(9)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number: " + e.getMessage(), e);
        }
    }

    private static List<String> split(String line) {
        List<String> out = new ArrayList<>(FIELDS);
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\t') {
                out.add(line.substring(start, i));
                start = i + 1;
            }
        }
        out.add(line.substring(start));
        return out;
    }

    private static StringBuilder escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) {
                throw new IllegalArgumentException("dangling escape");
            }
            char n = s.charAt(++i);
            switch (n) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> throw new IllegalArgumentException("unknown escape \\" + n);
            }
        }
        return sb.toString();
    }
}
