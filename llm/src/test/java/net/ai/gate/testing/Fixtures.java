package net.ai.gate.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import net.ai.gate.Llm;
import net.ai.gate.Provider;
import net.ai.gate.auth.Environment;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.spi.http.TransportOptions;

/// Test-only transports speaking the fake wire format, for phases the scripted server cannot stage: replies whose
/// bodies block until released, and replies chosen per request (by header, by count).
public final class Fixtures {
    private Fixtures() { }

    public static Llm runtime(Provider... providers) {
        var builder = Llm.builder().environment(Environment.none()).catalog(c -> c.offline());
        for (var p : providers) builder.provider(p);
        return builder.build();
    }

    /// A transport answering every call with `answer`.
    public static HttpTransport transport(Function<HttpCall, HttpReply> answer) { return (call, options) -> answer.apply(call); }

    /// A transport that lets the test see the options too.
    public interface Answer { HttpReply apply(HttpCall call, TransportOptions options) throws IOException; }

    public static HttpTransport transport(Answer answer) { return answer::apply; }

    /// A complete fake-format reply with one text part.
    public static HttpReply textReply(String text) {
        return json(200, "{\"id\":\"r1\",\"model\":\"fake\",\"stop\":\"stop\",\"usage\":{\"input\":1,\"output\":1},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}");
    }

    public static HttpReply error(int status, String code, String message) {
        return json(status, "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }

    public static HttpReply json(int status, String body) {
        return HttpReply.of(status, Map.of("content-type", List.of("application/json")), body.getBytes(StandardCharsets.UTF_8));
    }

    /// Headers now, body bytes only after `release()`; closing the body fails a pending or later read.
    public static final class Stalled {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean closed;

        public void release() { latch.countDown(); }

        public HttpReply reply(int status, String bodyAfterRelease) {
            var bytes = bodyAfterRelease.getBytes(StandardCharsets.UTF_8);
            return HttpReply.of(status, Map.of("content-type", List.of("application/json")), new InputStream() {
                private int position;

                @Override public int read() throws IOException {
                    var one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    try {
                        while (!latch.await(20, TimeUnit.MILLISECONDS)) if (closed) throw new IOException("The response body was closed");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("interrupted while stalled");
                    }
                    if (closed) throw new IOException("The response body was closed");
                    if (position >= bytes.length) return -1;
                    int n = Math.min(length, bytes.length - position);
                    System.arraycopy(bytes, position, buffer, offset, n);
                    position += n;
                    return n;
                }

                @Override public void close() { closed = true; }
            });
        }
    }
}
