package net.ai.gate.vendors;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import net.ai.gate.Llm;
import net.ai.gate.Provider;
import net.ai.gate.auth.Environment;
import net.ai.gate.json.JsonObject;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.spi.http.TransportOptions;

/// A scripted provider endpoint for codec tests: records every call and answers with queued replies, so a real
/// preset runs through the real core with only the network replaced.
public final class WireScript implements HttpTransport {
    public static final String KEY = "test-key-0123456789";

    private final List<HttpCall> calls = new CopyOnWriteArrayList<>();
    private final Queue<HttpReply> replies = new ConcurrentLinkedQueue<>();

    public WireScript json(String body) { return reply(200, "application/json", body); }

    public WireScript error(int status, String body) { return reply(status, "application/json", body); }

    /// One SSE event per data string; raw `[DONE]` markers pass as they are.
    public WireScript sse(String... data) {
        return reply(200, "text/event-stream", Arrays.stream(data).map(d -> "data: " + d + "\n\n").collect(Collectors.joining()));
    }

    /// Events as the ChatGPT Codex backend sends them: `event:` and `data:` fields, and no content type at all.
    public WireScript untypedEvents(String... data) {
        var body = Arrays.stream(data).map(d -> "event: " + d.replaceFirst("(?s).*?\"type\":\"([^\"]+)\".*", "$1") + "\ndata: " + d + "\n\n").collect(Collectors.joining());
        replies.add(HttpReply.of(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /// A JSON body without a content type.
    public WireScript untypedJson(String body) {
        replies.add(HttpReply.of(200, Map.of(), body.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    private WireScript reply(int status, String type, String body) {
        replies.add(HttpReply.of(status, Map.of("content-type", List.of(type)), body.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    @Override public HttpReply send(HttpCall call, TransportOptions options) {
        calls.add(call);
        var reply = replies.poll();
        if (reply == null) throw new AssertionError("No scripted reply for " + call);
        return reply;
    }

    public List<HttpCall> calls() { return calls; }

    public JsonObject body(int index) { return (JsonObject) calls.get(index).body().orElseThrow(); }

    public JsonObject lastBody() { return body(calls.size() - 1); }

    /// A runtime with `provider` bound to this endpoint and a test key in `keyVariable`.
    public Llm runtime(Provider provider, String keyVariable) {
        return Llm.builder().provider(provider.toBuilder().transport(this).build())
                .environment(Environment.of(Map.of(keyVariable, KEY))).catalog(c -> c.offline()).build();
    }
}
