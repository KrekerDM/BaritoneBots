package io.github.krekerdm.baritonebots.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Companion token generation and comparison. */
public final class Tokens {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** 32 symbols of a 62-letter alphabet, about 190 bits: same shape as the manager's link secret. */
    public static final int LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Constant-time comparison. Both sides are hashed first so neither the length nor a common prefix of the
     * configured token leaks through timing. A blank configured token never matches.
     */
    public static boolean matches(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(expected), sha256(given));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
