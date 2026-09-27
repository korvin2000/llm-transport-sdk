package net.ai.gate.spi.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import net.ai.gate.json.Json;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// An HTTP response whose body is consumed once: either as a stream through [#body()] or whole through [#bytes()].
/// Header names are case-insensitive. `close()` releases the connection, or aborts it when the body is unread.
/// Not thread-safe, except `close()`.
public final class HttpReply implements AutoCloseable {
    /// Upper bound for bodies read whole; streams are bounded by their framing instead.
    public static final int MAX_BODY_BYTES = 64 << 20;

    private final int status;
    private final Map<String, List<String>> headers;
    private final InputStream body;
    private byte @Nullable [] bytes;
    private boolean streamTaken;

    private HttpReply(int status, Map<String, List<String>> headers, InputStream body) {
        this.status = status;
        var sorted = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((k, v) -> sorted.put(k, List.copyOf(v)));
        this.headers = Collections.unmodifiableMap(sorted);
        this.body = body;
    }

    /// For transports: `body` is owned by the reply from now on.
    public static HttpReply of(int status, Map<String, List<String>> headers, InputStream body) { return new HttpReply(status, headers, body); }

    public static HttpReply of(int status, Map<String, List<String>> headers, byte[] body) {
        return new HttpReply(status, headers, new ByteArrayInputStream(body));
    }

    public int status() { return status; }
    public boolean successful() { return status >= 200 && status < 300; }
    public Map<String, List<String>> headers() { return headers; }
    public Optional<String> header(String name) { return Optional.ofNullable(headers.get(name)).flatMap(v -> v.stream().findFirst()); }

    /// The body as a stream, for framing.
    /// @throws IllegalStateException when the body was already taken or read
    public synchronized InputStream body() {
        if (streamTaken || bytes != null) throw new IllegalStateException("The reply body was already consumed");
        streamTaken = true;
        return body;
    }

    /// The whole body, read once and kept; bounded by [#MAX_BODY_BYTES].
    public synchronized byte[] bytes() {
        if (bytes == null) {
            if (streamTaken) throw new IllegalStateException("The reply body is being streamed");
            try (body) {
                var read = body.readNBytes(MAX_BODY_BYTES + 1);
                if (read.length > MAX_BODY_BYTES) throw new IOException("Body exceeds " + MAX_BODY_BYTES + " bytes");
                bytes = read;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bytes.clone();
    }

    public String text() { return new String(bytes(), StandardCharsets.UTF_8); }

    /// @throws IllegalArgumentException when the body is not JSON
    public JsonValue json() { return Json.parse(text()); }

    @Override public void close() {
        try { body.close(); } catch (IOException ignored) { /* releasing: nothing left to report */ }
    }

    @Override public String toString() { return "HttpReply[" + status + "]"; }
}
