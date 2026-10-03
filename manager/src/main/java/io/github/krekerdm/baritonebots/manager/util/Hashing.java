package io.github.krekerdm.baritonebots.manager.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Hex digests for download verification. */
public final class Hashing {
    private Hashing() {
    }

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK without " + algorithm, e);
        }
    }

    public static String file(Path path, String algorithm) throws IOException {
        MessageDigest md = digest(algorithm);
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static String bytes(byte[] data, String algorithm) {
        return HexFormat.of().formatHex(digest(algorithm).digest(data));
    }

    /** True when the expected digest is blank (unknown) or equals the actual one, ignoring case. */
    public static boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.trim().equalsIgnoreCase(actual);
    }
}
