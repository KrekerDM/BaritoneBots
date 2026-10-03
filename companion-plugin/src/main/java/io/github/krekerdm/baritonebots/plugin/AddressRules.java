package io.github.krekerdm.baritonebots.plugin;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@code protect-names.allowed-ips} list: literal IPs, CIDR ranges and host names (for a bot PC behind a
 * dynamic home IP with a DDNS name). Literal and CIDR entries never touch DNS. Host names are resolved only when
 * the caller allows blocking ({@code mayResolve}), i.e. on the async pre-login thread; the main thread uses the
 * addresses cached by the last resolution.
 */
public final class AddressRules {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*");
    static final long HOST_CACHE_MS = 60_000;

    private final List<Rule> rules;

    private AddressRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static AddressRules empty() {
        return new AddressRules(List.of());
    }

    /** Parses entries; invalid ones are skipped and described in {@code warnings}. */
    public static AddressRules parse(List<String> entries, List<String> warnings) {
        List<Rule> out = new ArrayList<>();
        for (String raw : entries) {
            String e = raw == null ? "" : raw.trim();
            if (e.isEmpty()) {
                continue;
            }
            try {
                out.add(parseRule(e));
            } catch (IllegalArgumentException ex) {
                warnings.add("allowed-ips entry '" + e + "' ignored: " + ex.getMessage());
            }
        }
        return new AddressRules(out);
    }

    private static Rule parseRule(String e) {
        int slash = e.indexOf('/');
        if (slash >= 0) {
            byte[] net = literal(e.substring(0, slash));
            if (net == null) {
                throw new IllegalArgumentException("not an IP network");
            }
            int bits;
            try {
                bits = Integer.parseInt(e.substring(slash + 1).trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("bad prefix length");
            }
            if (bits < 0 || bits > net.length * 8) {
                throw new IllegalArgumentException("prefix length out of range");
            }
            return new Cidr(net, bits, e);
        }
        byte[] lit = literal(e);
        if (lit != null) {
            return new Cidr(lit, lit.length * 8, e);
        }
        if (IPV4.matcher(e).matches() || e.indexOf(':') >= 0) {
            throw new IllegalArgumentException("not a valid IP address");
        }
        if (!HOST.matcher(e).matches()) {
            throw new IllegalArgumentException("not an IP, CIDR or host name");
        }
        return new Host(e.toLowerCase(Locale.ROOT));
    }

    /** Bytes of a literal IPv4/IPv6 address (IPv4-mapped IPv6 folded to IPv4), or null when {@code s} is not one. */
    static byte[] literal(String s) {
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            t = t.substring(1, t.length() - 1);
        }
        boolean v4 = IPV4.matcher(t).matches();
        boolean v6 = t.indexOf(':') >= 0 && t.matches("[0-9A-Fa-f:.%a-zA-Z]+");
        if (!v4 && !v6) {
            return null;
        }
        if (v4) {
            for (String part : t.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            // A literal never triggers a DNS lookup in getByName.
            return normalize(InetAddress.getByName(t)).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Folds {@code ::ffff:a.b.c.d} to the IPv4 address so dual-stack sockets compare equal to IPv4 rules. */
    static InetAddress normalize(InetAddress a) {
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = b[10] == (byte) 0xff && b[11] == (byte) 0xff;
            for (int i = 0; i < 10 && mapped; i++) {
                mapped = b[i] == 0;
            }
            if (mapped) {
                try {
                    return Inet4Address.getByAddress(Arrays.copyOfRange(b, 12, 16));
                } catch (UnknownHostException e) {
                    return a;
                }
            }
        }
        return a;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public int size() {
        return rules.size();
    }

    /** @param mayResolve true on threads where a blocking DNS lookup is acceptable */
    public boolean matches(InetAddress address, boolean mayResolve) {
        if (address == null) {
            return false;
        }
        byte[] b = normalize(address).getAddress();
        for (Rule r : rules) {
            if (r.matches(b, mayResolve)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return rules.stream().map(Rule::text).collect(Collectors.joining(", "));
    }

    private sealed interface Rule permits Cidr, Host {
        boolean matches(byte[] address, boolean mayResolve);

        String text();
    }

    private record Cidr(byte[] network, int bits, String text) implements Rule {
        @Override
        public boolean matches(byte[] a, boolean mayResolve) {
            if (a.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (a[full] & mask) == (network[full] & mask);
        }
    }

    private static final class Host implements Rule {
        private final String name;
        private volatile Set<String> cached = Set.of();
        private volatile long resolvedAt = Long.MIN_VALUE;

        Host(String name) {
            this.name = name;
        }

        @Override
        public boolean matches(byte[] address, boolean mayResolve) {
            long now = System.currentTimeMillis();
            if (mayResolve && (resolvedAt == Long.MIN_VALUE || now - resolvedAt > HOST_CACHE_MS)) {
                try {
                    cached = Arrays.stream(InetAddress.getAllByName(name))
                            .map(a -> Arrays.toString(normalize(a).getAddress()))
                            .collect(Collectors.toUnmodifiableSet());
                    resolvedAt = now;
                } catch (UnknownHostException e) {
                    // Keep the previous addresses: a DNS hiccup must not lock the bots out.
                    resolvedAt = now;
                }
            }
            return cached.contains(Arrays.toString(address));
        }

        @Override
        public String text() {
            return name;
        }
    }
}
