package io.github.krekerdm.baritonebots.plugin;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressRulesTest {
    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void literalsAndNetworks() throws Exception {
        List<String> warnings = new ArrayList<>();
        AddressRules rules = AddressRules.parse(List.of("203.0.113.7", "192.168.1.0/24", "2001:db8::/32", " "),
                warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(3, rules.size());

        assertTrue(rules.matches(ip("203.0.113.7"), false));
        assertFalse(rules.matches(ip("203.0.113.8"), false));
        assertTrue(rules.matches(ip("192.168.1.250"), false));
        assertFalse(rules.matches(ip("192.168.2.1"), false));
        assertTrue(rules.matches(ip("2001:db8:1::5"), false));
        assertFalse(rules.matches(ip("2001:db9::5"), false));
        assertTrue(rules.matches(ip("::ffff:192.168.1.3"), false), "IPv4-mapped IPv6 compares as IPv4");
        assertFalse(rules.matches(null, false));
    }

    @Test
    void invalidEntriesAreSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        AddressRules rules = AddressRules.parse(List.of("300.1.1.1", "10.0.0.0/40", "10.0.0.0/x", "bad_host!",
                "1.2.3.4"), warnings);
        assertEquals(1, rules.size());
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void hostNamesNeverResolveOnTheMainThread() throws Exception {
        List<String> warnings = new ArrayList<>();
        AddressRules rules = AddressRules.parse(List.of("mybots.ddns.example"), warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(1, rules.size());
        // Never resolved and resolving not allowed: no match, no DNS lookup.
        assertFalse(rules.matches(ip("203.0.113.7"), false));
    }

    @Test
    void literalParsing() {
        assertEquals(4, AddressRules.literal("10.1.2.3").length);
        assertEquals(16, AddressRules.literal("[2001:db8::1]").length);
        assertEquals(4, AddressRules.literal("::ffff:10.1.2.3").length);
        assertNull(AddressRules.literal("example.org"));
        assertNull(AddressRules.literal("256.1.1.1"));
        assertTrue(AddressRules.empty().isEmpty());
    }
}
