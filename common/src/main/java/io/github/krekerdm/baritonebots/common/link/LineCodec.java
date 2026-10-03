package io.github.krekerdm.baritonebots.common.link;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Newline-delimited UTF-8 framing for the link (SPEC §2). An instance buffers its input stream and must be
 * the only reader of it; {@link #writeLine} is static and stateless.
 */
public final class LineCodec {
    /** Thrown when a line exceeds the limit. The stream is then mid-line and the connection must be closed. */
    public static final class LineTooLongException extends IOException {
        public LineTooLongException(int maxBytes) {
            super("line exceeds " + maxBytes + " bytes");
        }
    }

    private final InputStream in;
    private final int maxBytes;
    private final byte[] buf = new byte[16 * 1024];
    private int pos;
    private int lim;
    private byte[] line = new byte[1024];

    public LineCodec(InputStream in, int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /**
     * Reads the next line without its terminator ({@code \n} or {@code \r\n}). A final line without
     * terminator is returned as is; returns {@code null} at end of stream.
     *
     * @throws LineTooLongException when the line has more than {@code maxBytes} bytes
     */
    public String readLine() throws IOException {
        int n = 0;
        boolean any = false;
        while (true) {
            if (pos >= lim) {
                int r = in.read(buf, 0, buf.length);
                if (r <= 0) {
                    pos = 0;
                    lim = 0;
                    return any ? decode(n) : null;
                }
                pos = 0;
                lim = r;
            }
            any = true;
            int i = pos;
            while (i < lim && buf[i] != '\n') {
                i++;
            }
            int chunk = i - pos;
            if (n + chunk > maxBytes + 1) {
                throw new LineTooLongException(maxBytes);
            }
            ensure(n + chunk);
            System.arraycopy(buf, pos, line, n, chunk);
            n += chunk;
            if (i < lim) {
                pos = i + 1;
                return decode(n);
            }
            pos = lim;
        }
    }

    private String decode(int n) throws LineTooLongException {
        if (n > 0 && line[n - 1] == '\r') {
            n--;
        }
        if (n > maxBytes) {
            throw new LineTooLongException(maxBytes);
        }
        return new String(line, 0, n, StandardCharsets.UTF_8);
    }

    private void ensure(int capacity) {
        if (capacity > line.length) {
            line = Arrays.copyOf(line, Math.max(capacity, Math.min(line.length * 2, maxBytes + 1)));
        }
    }

    /**
     * Writes {@code line} as UTF-8 plus {@code \n} in one call and flushes.
     *
     * @throws IllegalArgumentException when the line contains a line break or exceeds {@code maxBytes}
     */
    public static void writeLine(OutputStream out, String line, int maxBytes) throws IOException {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line contains a line break");
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("line exceeds " + maxBytes + " bytes");
        }
        byte[] framed = Arrays.copyOf(bytes, bytes.length + 1);
        framed[bytes.length] = '\n';
        out.write(framed);
        out.flush();
    }
}
