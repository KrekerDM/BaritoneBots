package io.github.krekerdm.baritonebots.common.link;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineCodecTest {

    private static LineCodec codec(String s, int max) {
        return new LineCodec(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), max);
    }

    @Test
    void readsLinesWithCrLfUnicodeAndTrailingPartial() throws IOException {
        LineCodec c = codec("first\r\nвторой\n\nlast", 100);
        assertEquals("first", c.readLine());
        assertEquals("второй", c.readLine());
        assertEquals("", c.readLine());
        assertEquals("last", c.readLine());
        assertNull(c.readLine());
        assertNull(c.readLine());
    }

    @Test
    void readsLinesLongerThanTheInternalBuffer() throws IOException {
        String big = "x".repeat(40_000);
        LineCodec c = codec(big + "\n" + big + "y\n", 50_000);
        assertEquals(big, c.readLine());
        assertEquals(big + "y", c.readLine());
        assertNull(c.readLine());
    }

    @Test
    void enforcesTheLimit() throws IOException {
        LineCodec exact = codec("12345\n123456\n", 5);
        assertEquals("12345", exact.readLine());
        assertThrows(LineCodec.LineTooLongException.class, exact::readLine);

        LineCodec withCr = codec("12345\r\n", 5);
        assertEquals("12345", withCr.readLine(), "the CR of a CRLF does not count against the limit");

        LineCodec unterminated = codec("1234567", 5);
        assertThrows(LineCodec.LineTooLongException.class, unterminated::readLine);
    }

    @Test
    void writesFramedLines() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LineCodec.writeLine(out, "{\"t\":\"ок\"}", 100);
        assertArrayEquals("{\"t\":\"ок\"}\n".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> LineCodec.writeLine(out, "a\nb", 100));
        assertThrows(IllegalArgumentException.class, () -> LineCodec.writeLine(out, "abcdef", 5));
    }

    @Test
    void hostPort() {
        assertEquals(new HostPort("127.0.0.1", 25590), HostPort.parse("127.0.0.1:25590", 1));
        assertEquals(new HostPort("play.example.net", 25565), HostPort.parse("play.example.net", 25565));
        assertEquals(new HostPort("::1", 25590), HostPort.parse("[::1]:25590", 1));
        assertEquals(new HostPort("::1", 7), HostPort.parse("::1", 7));
        assertEquals("[::1]:25590", new HostPort("::1", 25590).toString());
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse("host:notaport", 1));
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse("host:70000", 1));
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse(" ", 1));
    }
}
