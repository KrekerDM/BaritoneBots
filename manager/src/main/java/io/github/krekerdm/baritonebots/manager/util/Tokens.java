package io.github.krekerdm.baritonebots.manager.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Random secrets, constant-time comparison and unique ids. */
public final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final AtomicLong COUNTER = new AtomicLong();

    private Tokens() {
    }

    /** Alphanumeric secret; 32 chars carry about 190 bits. */
    public static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALNUM[RANDOM.nextInt(ALNUM.length)];
        }
        return new String(out);
    }

    /** Compares secrets without leaking the matching prefix length through timing. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Id unique across manager restarts: prefix, millis in base 36, process counter. */
    public static String id(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + Long.toString(COUNTER.incrementAndGet(), 36);
    }
}
