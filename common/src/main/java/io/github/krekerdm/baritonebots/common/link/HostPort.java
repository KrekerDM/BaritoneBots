package io.github.krekerdm.baritonebots.common.link;

/** {@code host:port} pair as used by {@code baritonebots.link} and server addresses; IPv6 needs brackets. */
public record HostPort(String host, int port) {
    public HostPort {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /**
     * Parses {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}; {@code defaultPort} is used
     * when no port is given.
     */
    public static HostPort parse(String s, int defaultPort) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("address is empty");
        }
        String t = s.trim();
        if (t.startsWith("[")) {
            int close = t.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("unterminated IPv6 address: " + s);
            }
            String host = t.substring(1, close);
            String rest = t.substring(close + 1);
            if (rest.isEmpty()) {
                return new HostPort(host, defaultPort);
            }
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("bad address: " + s);
            }
            return new HostPort(host, parsePort(rest.substring(1), s));
        }
        int colon = t.lastIndexOf(':');
        if (colon < 0) {
            return new HostPort(t, defaultPort);
        }
        if (t.indexOf(':') != colon) {
            // bare IPv6 literal without brackets
            return new HostPort(t, defaultPort);
        }
        return new HostPort(t.substring(0, colon), parsePort(t.substring(colon + 1), s));
    }

    private static int parsePort(String p, String whole) {
        try {
            return Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port in address: " + whole, e);
        }
    }

    @Override
    public String toString() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
