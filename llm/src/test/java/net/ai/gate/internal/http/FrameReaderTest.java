package net.ai.gate.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.ai.gate.spi.protocol.Frame;
import net.ai.gate.spi.protocol.StreamFormat;
import org.junit.jupiter.api.Test;

/// Whole events however the network splits the bytes — across lines, fields and multi-byte characters.
class FrameReaderTest {
    /// Returns at most `size` bytes per read, as a slow network would.
    private static InputStream trickle(String text, int size) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, size)); }
        };
    }

    private static List<Frame> frames(InputStream in, StreamFormat format) {
        var frames = new ArrayList<Frame>();
        new FrameReader(in, format).forEachRemaining(frames::add);
        return frames;
    }

    @Test
    void sseEventsSurviveArbitrarySplits() {
        var text = ": keep-alive\n\nevent: delta\ndata: {\"text\":\"ünï\"}\n\r\ndata: line one\r\ndata:line two\r\n\r\nid: 7\ndata: last";
        var expected = List.of(Frame.of("delta", "{\"text\":\"ünï\"}"), Frame.of(null, "line one\nline two"), Frame.of(null, "last", "7"));
        for (int size = 1; size <= 5; size++) assertEquals(expected, frames(trickle(text, size), StreamFormat.SSE), "chunk size " + size);
    }

    @Test
    void eventsAndLinesAreBounded() {
        var line = "data: " + "x".repeat(FrameReader.MAX_EVENT_CHARS / 2 + 10) + "\n";
        var event = line + line + "\n";
        var error = org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                () -> frames(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), StreamFormat.SSE));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("event exceeds"), error.getMessage());
        var longLine = "y".repeat(FrameReader.MAX_LINE_BYTES + 1);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                () -> frames(new ByteArrayInputStream(longLine.getBytes(StandardCharsets.UTF_8)), StreamFormat.NDJSON));
    }

    @Test
    void ndjsonYieldsOneFramePerNonBlankLine() {
        assertEquals(List.of(Frame.of("{\"a\":1}"), Frame.of("{\"b\":2}")), frames(trickle("{\"a\":1}\n\n{\"b\":2}", 3), StreamFormat.NDJSON));
    }
}
