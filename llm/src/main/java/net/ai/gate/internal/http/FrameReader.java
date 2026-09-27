package net.ai.gate.internal.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

import net.ai.gate.spi.protocol.Frame;
import net.ai.gate.spi.protocol.StreamFormat;
import org.jspecify.annotations.Nullable;

/// Whole SSE events or NDJSON lines from a byte stream, however the network splits it. Lines are decoded as UTF-8
/// only once complete, so multi-byte characters split across chunks survive. Memory is bounded by [#MAX_LINE_BYTES]
/// per line and [#MAX_EVENT_CHARS] per event, whatever the number of `data:` lines. Confined to the consuming
/// thread, except [#lastActivity()], which a watchdog reads.
public final class FrameReader implements Iterator<Frame> {
    static final int MAX_LINE_BYTES = 8 << 20;
    static final int MAX_EVENT_CHARS = 8 << 20;

    private final InputStream in;
    private final StreamFormat format;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private volatile long lastActivity = System.nanoTime();
    private @Nullable Frame next;
    private boolean eof, skipLf;

    public FrameReader(InputStream body, StreamFormat format) {
        this.format = format;
        this.in = new BufferedInputStream(new FilterInputStream(body) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) lastActivity = System.nanoTime();
                return n;
            }
        });
    }

    /// `System.nanoTime()` of the last received bytes; keep-alives count.
    public long lastActivity() { return lastActivity; }

    /// @throws UncheckedIOException when reading fails or a bound is exceeded
    @Override public boolean hasNext() {
        if (next == null && !eof) {
            try {
                next = format == StreamFormat.SSE ? readEvent() : readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return next != null;
    }

    @Override public Frame next() {
        if (!hasNext()) throw new NoSuchElementException();
        var frame = next;
        next = null;
        return frame;
    }

    private @Nullable Frame readLine() throws IOException {
        for (var text = line(); text != null; text = line()) if (!text.isBlank()) return Frame.of(text);
        eof = true;
        return null;
    }

    private @Nullable Frame readEvent() throws IOException {
        String event = null, id = null;
        StringBuilder data = null;
        while (true) {
            var text = line();
            if (text == null) {
                eof = true;
                return data == null ? null : Frame.of(event, data.toString(), id);
            }
            if (text.isEmpty()) {
                if (data != null) return Frame.of(event, data.toString(), id);
                event = null;
                continue;
            }
            if (text.startsWith(":")) continue;
            int colon = text.indexOf(':');
            var field = colon < 0 ? text : text.substring(0, colon);
            var value = colon < 0 ? "" : text.substring(colon + (text.startsWith(" ", colon + 1) ? 2 : 1));
            switch (field) {
                case "data" -> {
                    if (data == null) data = new StringBuilder(); else data.append('\n');
                    if (data.length() + value.length() > MAX_EVENT_CHARS) throw new IOException("Stream event exceeds " + MAX_EVENT_CHARS + " characters");
                    data.append(value);
                }
                case "event" -> event = value;
                case "id" -> id = value;
                default -> { } // retry and unknown fields carry nothing for the SDK
            }
        }
    }

    /// The next line without its terminator (`\n`, `\r\n` or `\r`); `null` at the end of the stream.
    private @Nullable String line() throws IOException {
        line.reset();
        while (true) {
            int b = in.read();
            if (skipLf) {
                skipLf = false;
                if (b == '\n') continue;
            }
            if (b < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            if (b == '\n') return line.toString(StandardCharsets.UTF_8);
            if (b == '\r') {
                skipLf = true;
                return line.toString(StandardCharsets.UTF_8);
            }
            if (line.size() >= MAX_LINE_BYTES) throw new IOException("Stream line exceeds " + MAX_LINE_BYTES + " bytes");
            line.write(b);
        }
    }
}
