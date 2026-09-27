package net.ai.gate.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonBoolean;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.Usage;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.spi.http.TransportOptions;
import net.ai.gate.spi.protocol.ApiRequest;
import org.jspecify.annotations.Nullable;

/// The in-process server of a [FakeProvider], bound to it as its transport. Answers each call with the next scripted
/// item: a reply (JSON, or SSE split into 7-byte pieces so framing across chunks and UTF-8 boundaries is exercised),
/// a failure mapped to its HTTP status or connection error, a dynamic answer, a non-JSON body, or a stalled body.
/// Thread-safe.
final class FakeServer implements HttpTransport {
    sealed interface Script permits Reply, Failure, Dynamic, Malformed, Stalled { }
    record Reply(ScriptedReply reply) implements Script { }
    record Failure(LlmException error) implements Script { }
    record Dynamic(Function<ApiRequest, AssistantMessage> handler) implements Script { }
    record Malformed() implements Script { }
    record Stalled(StallBody body) implements Script { }

    private static final int PIECE_BYTES = 7;

    private final FakeWireApi api;
    private final ArrayDeque<Script> script = new ArrayDeque<>();
    private final List<ApiRequest> requests = new ArrayList<>();
    private final AtomicInteger sends = new AtomicInteger();
    private volatile int tokensPerSecond;

    FakeServer(FakeWireApi api) { this.api = api; }

    synchronized void add(Script item) { script.add(item); }
    synchronized List<ApiRequest> requests() { return List.copyOf(requests); }
    synchronized int remaining() { return script.size(); }
    /// Chat requests actually received, retries included — unlike [#requests()], which lists each prepared request
    /// (encoded once) rather than each attempt to send it.
    int sends() { return sends.get(); }
    void pacing(int value) { tokensPerSecond = value; }

    @Override public HttpReply send(HttpCall call, TransportOptions options) throws IOException {
        if (!"POST".equals(call.method()) || !call.uri().getPath().endsWith("/chat"))
            return HttpReply.of(call.uri().getPath().endsWith("/v1") ? 200 : 404, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        var body = (JsonObject) call.body().orElseThrow();
        // A genuine retry resends this exact body without a new encode() call, so FakeWireApi's deque for it is
        // already drained and this correctly returns null instead of double-counting the original request.
        var request = api.request(body.toJson());
        int number = sends.incrementAndGet();
        Script next;
        synchronized (this) {
            if (request != null) requests.add(request);
            next = script.poll();
        }
        if (next == null) return error(400, "invalid_request", "FakeProvider has no scripted reply left for request " + number, null);
        boolean stream = body.get("stream").orElse(null) == JsonBoolean.TRUE;
        return switch (next) {
            case Failure f -> failure(f.error());
            case Reply r -> reply(r.reply(), request, number, stream, tokensPerSecond);
            case Dynamic d -> {
                var message = d.handler().apply(Objects.requireNonNull(request, "unknown request"));
                var scripted = ScriptedReply.builder().stopReason(message.stopReason());
                message.content().forEach(scripted::part);
                if (message.usage().input().isPresent() && message.usage().output().isPresent())
                    scripted.usage(message.usage().input().getAsLong(), message.usage().output().getAsLong());
                yield reply(scripted.build(), request, number, stream, tokensPerSecond);
            }
            case Malformed _ -> malformed(stream);
            case Stalled s -> stalled(s.body(), request, number, stream);
        };
    }

    /// A frame whose data is not JSON (stream) or a non-JSON body (non-stream), status 200: decoding fails and the
    /// core reports `malformed_response`.
    private static HttpReply malformed(boolean stream) {
        return stream
                ? HttpReply.of(200, Map.of("content-type", List.of("text/event-stream")),
                        "event: text\ndata: not valid json\n\n".getBytes(StandardCharsets.UTF_8))
                : HttpReply.of(200, Map.of("content-type", List.of("application/json")), "not valid json".getBytes(StandardCharsets.UTF_8));
    }

    /// 200 headers at once, then a body that blocks in `read()` until `body` is released (serving a small reply
    /// saying "released"), closed (the caller cancelled or otherwise dropped the connection), or the reading thread
    /// is interrupted. The eventual payload is rendered up front, on this (the sending) thread, from the same
    /// [#reply] logic as a normal answer, so it is a valid wire reply for whichever of stream or non-stream this
    /// call is.
    private HttpReply stalled(StallBody body, @Nullable ApiRequest request, int number, boolean stream) {
        Map<String, List<String>> headers;
        byte[] payload;
        try (var released = reply(ScriptedReply.text("released"), request, number, stream, 0)) {
            headers = released.headers();
            payload = released.bytes();
        }
        body.arm(payload);
        return HttpReply.of(200, headers, body);
    }

    private HttpReply failure(LlmException e) throws IOException {
        if (e.is(ErrorCode.CONNECT_FAILED)) throw new ConnectException(e.getMessage());
        if (e.is(ErrorCode.OUTCOME_UNKNOWN) || e.is(ErrorCode.STREAM_INTERRUPTED)) throw new IOException(e.getMessage());
        int status = e.httpStatus().orElseGet(() -> switch (e.code().value()) {
            case "rate_limited" -> 429;
            case "quota_exhausted" -> 402;
            case "overloaded" -> 529;
            case "server_error" -> 500;
            case "invalid_credentials", "login_required" -> 401;
            case "permission_denied" -> 403;
            case "model_not_found" -> 404;
            case "deadline_exceeded" -> 408;
            default -> 400;
        });
        return error(status, e.code().value(), e.getMessage(), e.details().retryAfter().map(d -> String.valueOf(d.toMillis())).orElse(null));
    }

    private static HttpReply error(int status, String code, String message, @Nullable String retryAfterMillis) {
        var headers = retryAfterMillis == null ? Map.<String, List<String>>of() : Map.of("retry-after-ms", List.of(retryAfterMillis));
        return HttpReply.of(status, headers, Json.object("error", Json.object("code", code, "message", message)).toJson().getBytes(StandardCharsets.UTF_8));
    }

    /// `pace` is passed explicitly (rather than always reading [#tokensPerSecond]) so [#stalled] can render the
    /// eventual payload of a stalled call without it being paced.
    private HttpReply reply(ScriptedReply reply, @Nullable ApiRequest request, int number, boolean stream, int pace) {
        var usage = reply.usage().orElseGet(() -> estimate(request, reply.parts()));
        var usageJson = Json.object("input", usage.input().orElse(0), "output", usage.output().orElse(0),
                "cacheRead", usage.cacheRead().orElse(0), "cacheWrite", usage.cacheWrite().orElse(0));
        var id = "fake-resp-" + number;
        var model = request == null ? "fake" : request.model().id();
        if (!stream) {
            var json = Json.object("id", id, "model", model, "stop", reply.stopReason().raw(), "usage", usageJson,
                    "content", reply.parts().stream().map(FakeWireApi::part).toList());
            var text = json.toJson();
            // Cut in the middle of the JSON: unbalanced braces the decoder cannot parse, reported as malformed_response.
            if (reply.truncated()) text = text.substring(0, text.length() / 2);
            return HttpReply.of(200, Map.of("content-type", List.of("application/json")), text.getBytes(StandardCharsets.UTF_8));
        }
        var frames = new ArrayList<String>();
        frames.add(frame("start", Json.object("id", id, "model", model)));
        for (int i = 0; i < reply.parts().size(); i++) {
            var part = reply.parts().get(i);
            int index = i;
            switch (part) {
                case Content.Text t -> chunks(t.text()).forEach(c -> frames.add(frame("text", Json.object("index", index, "text", c))));
                case Content.Reasoning r ->
                        chunks(r.text().orElse("")).forEach(c -> frames.add(frame("reasoning", Json.object("index", index, "text", c))));
                case ToolCall c -> {
                    frames.add(frame("tool_start", Json.object("index", index, "id", c.id(), "name", c.name())));
                    chunks(c.argumentsJson()).forEach(f -> frames.add(frame("tool_delta", Json.object("index", index, "fragment", f))));
                }
                default -> { }
            }
            frames.add(frame("part_end", Json.object("index", index, "part", FakeWireApi.part(part))));
        }
        // Omitting the terminal `done` frame leaves the stream without its terminal evidence; the core reports
        // that as stream_interrupted, with everything delivered so far kept as the partial reply.
        if (!reply.truncated()) frames.add(frame("done", Json.object("id", id, "stop", reply.stopReason().raw(), "usage", usageJson)));
        return HttpReply.of(200, Map.of("content-type", List.of("text/event-stream")), new PacedBody(frames, pace));
    }

    private static String frame(String event, JsonObject data) { return "event: " + event + "\ndata: " + data.toJson() + "\n\n"; }

    private static List<String> chunks(String text) {
        var chunks = new ArrayList<String>();
        for (int i = 0; i < text.length(); i += 4) chunks.add(text.substring(i, Math.min(text.length(), i + 4)));
        return chunks;
    }

    /// Roughly four characters per token, as a stand-in for provider-reported usage.
    private static Usage estimate(@Nullable ApiRequest request, List<Content> parts) {
        long in = request == null ? 0 : request.conversation().system().map(String::length).orElse(0)
                + request.conversation().messages().stream().mapToLong(m -> m.toString().length()).sum();
        long out = parts.stream().mapToLong(p -> switch (p) {
            case Content.Text t -> t.text().length();
            case Content.Reasoning r -> r.text().map(String::length).orElse(0);
            case ToolCall c -> c.argumentsJson().length() + c.name().length();
            default -> 0;
        }).sum();
        return Usage.builder().input(Math.max(1, in / 4)).output(Math.max(1, out / 4)).cacheRead(0).cacheWrite(0).build();
    }

    /// Serves frames in small pieces, optionally paced; `close()` makes pending reads fail as an aborted body does.
    private static final class PacedBody extends InputStream {
        private final List<byte[]> pieces = new ArrayList<>();
        private final Set<Integer> frameStarts = new HashSet<>();
        private final long delayMillis;
        private int piece, position;
        private volatile boolean closed;

        PacedBody(List<String> frames, int tokensPerSecond) {
            delayMillis = tokensPerSecond > 0 ? Math.max(1, 1000L / tokensPerSecond) : 0;
            for (var frame : frames) {
                frameStarts.add(pieces.size());
                var bytes = frame.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < bytes.length; i += PIECE_BYTES)
                    pieces.add(Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + PIECE_BYTES)));
            }
        }

        @Override public int read() throws IOException {
            var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("The response body was closed");
            if (piece >= pieces.size()) return -1;
            if (position == 0 && delayMillis > 0 && frameStarts.contains(piece)) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted");
                }
                if (closed) throw new IOException("The response body was closed");
            }
            var current = pieces.get(piece);
            int n = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, n);
            position += n;
            if (position == current.length) {
                piece++;
                position = 0;
            }
            return n;
        }

        @Override public void close() { closed = true; }
    }

    /// The body of a [FakeProvider#stall()] call: `read()` blocks until [#release()] serves the armed payload,
    /// [#close()] fails it as an aborted body would, or the reading thread is interrupted. Thread-safe.
    static final class StallBody extends InputStream {
        private final Object lock = new Object();
        private byte[] payload = new byte[0];
        private boolean released, closed;
        private int position;

        /// The bytes served once released; called once, before this body is exposed to a reader.
        void arm(byte[] payload) { this.payload = payload; }

        void release() { synchronized (lock) { released = true; lock.notifyAll(); } }
        boolean isReleased() { synchronized (lock) { return released; } }

        @Override public int read() throws IOException {
            var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            synchronized (lock) {
                if (closed) throw new IOException("The response body was closed");
                while (!released) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("interrupted");
                    }
                    if (closed) throw new IOException("The response body was closed");
                }
            }
            if (position >= payload.length) return -1;
            int n = Math.min(length, payload.length - position);
            System.arraycopy(payload, position, buffer, offset, n);
            position += n;
            return n;
        }

        @Override public void close() { synchronized (lock) { closed = true; lock.notifyAll(); } }
    }
}
