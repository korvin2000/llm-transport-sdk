package net.ai.gate.testing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.Model;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;
import net.ai.gate.spi.protocol.Frame;
import net.ai.gate.spi.protocol.StreamDecoder;
import net.ai.gate.spi.protocol.WireApi;
import org.jspecify.annotations.Nullable;

/// The codec of the fake protocol — a real, pure `WireApi` over a deliberately small JSON and SSE format, which
/// doubles as the reference for codec authors. It remembers what it encoded so the fake server can report the
/// adapted requests.
final class FakeWireApi implements WireApi {
    static final String ID = "fake-chat";

    /// Cap on distinct pending bodies: a `preview()` never sends, so nothing ever polls its entry back out, and an
    /// unbounded run of previews (or of requests nobody sends) would otherwise leak memory forever.
    private static final int MAX_TRACKED_BODIES = 256;

    /// Canonical body to its pending [ApiRequest]s, oldest first, insertion-order bounded to [#MAX_TRACKED_BODIES]
    /// distinct bodies. Bodies must stay byte-stable for equal requests — prompt caching and response-cache keys
    /// depend on it — so this never adds headers or body members to disambiguate them; instead, equal concurrent
    /// bodies queue up under the same key and [#request(String)] polls the oldest still pending for it. A retry
    /// resends the same body without a new [#encode] call, so it finds the deque already drained and is correctly
    /// not mistaken for a new request. Access is synchronized on this map itself.
    private final Map<String, Deque<ApiRequest>> encoded = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Deque<ApiRequest>> eldest) { return size() > MAX_TRACKED_BODIES; }
    };

    @Override public String id() { return ID; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) {
        var options = request.options();
        var messages = request.conversation().messages().stream().map(m -> switch (m) {
            case UserMessage u -> Json.object("role", "user", "content", u.content().stream().map(FakeWireApi::part).toList());
            case AssistantMessage a -> Json.object("role", "assistant", "content", a.content().stream().map(FakeWireApi::part).toList());
            case ToolResultMessage r -> Json.object("role", "tool", "results", r.results().stream()
                    .map(x -> Json.object("id", x.callId(), "text", x.text(), "error", x.isError())).toList());
        }).toList();
        var body = Json.object("model", request.model().id(), "stream", request.streaming(), "messages", messages,
                "tools", request.conversation().tools().stream().map(Tool::name).toList());
        if (request.conversation().system().isPresent()) body = body.with("system", request.conversation().system().get());
        if (options.maxTokens().isPresent()) body = body.with("max_tokens", options.maxTokens().getAsInt());
        if (options.temperature().isPresent()) body = body.with("temperature", options.temperature().getAsDouble());
        if (options.reasoning().isPresent()) body = body.with("reasoning", lower(options.reasoning().get()));
        if (options.cacheRetention().isPresent()) body = body.with("cache", lower(options.cacheRetention().get()));
        if (options.output().isPresent()) body = body.with("output", switch (options.output().get()) {
            case OutputFormat.PlainText _ -> "text";
            case OutputFormat.AnyJson _ -> "json";
            case OutputFormat.Schema s -> s.schema().asJson();
            case OutputFormat.Typed t -> ctx.json().schemaFor(t.type()).asJson();
        });
        var key = body.toJson();
        synchronized (encoded) { encoded.computeIfAbsent(key, _ -> new ArrayDeque<>()).addLast(request); }
        return HttpCall.post("chat", body);
    }

    /// Polls the oldest request still pending for this body; `null` for a retry (already polled) or a body that
    /// aged out of [#MAX_TRACKED_BODIES].
    @Nullable ApiRequest request(String body) {
        synchronized (encoded) {
            var pending = encoded.get(body);
            if (pending == null) return null;
            var request = pending.pollFirst();
            if (pending.isEmpty()) encoded.remove(body);
            return request;
        }
    }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) {
        var json = (JsonObject) reply.json();
        var parts = new ArrayList<Content>();
        if (json.get("content").orElse(null) instanceof JsonArray array)
            for (var part : array.values()) parts.add(part((JsonObject) part));
        return message(ctx.model(), json).content(parts).build();
    }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) {
        return new StreamDecoder() {
            private boolean done;

            @Override public List<ChatEvent> onFrame(Frame frame) {
                var data = (JsonObject) Json.parse(frame.data());
                int index = data.get("index").orElse(null) instanceof JsonNumber n ? (int) n.longValue() : 0;
                return switch (frame.event().orElse("")) {
                    case "start" -> List.of(ChatEvent.Started.of(data.string("id"), data.string("model")));
                    case "text" -> List.of(new ChatEvent.TextDelta(index, data.string("text")));
                    case "reasoning" -> List.of(new ChatEvent.ReasoningDelta(index, data.string("text")));
                    case "tool_start" -> List.of(new ChatEvent.ToolCallStart(index, data.string("id"), data.string("name")));
                    case "tool_delta" -> List.of(new ChatEvent.ToolCallDelta(index, data.string("fragment"), Json.object()));
                    case "part_end" -> List.of(new ChatEvent.PartEnd(index, part(data.object("part"))));
                    case "done" -> {
                        done = true;
                        yield List.of(ChatEvent.Done.of(message(ctx.model(), data).build()));
                    }
                    default -> List.of(new ChatEvent.Unknown(frame.event().orElse("message"), data));
                };
            }

            /// Without `done` the stream ended prematurely; the core reports that as `stream_interrupted`.
            @Override public List<ChatEvent> onEnd() { return List.of(); }
        };
    }

    /// `{"error": {"code": …, "message": …}}` on top of the default status mapping.
    @Override public LlmException.Details decodeError(HttpReply reply, DecodeContext ctx) {
        var defaults = WireApi.super.decodeError(reply, ctx);
        if (!(defaults.errorBody().orElse(null) instanceof JsonObject body)) return defaults;
        var error = body.object("error");
        if (!(error.get("code").orElse(null) instanceof JsonString code)) return defaults;
        return LlmException.Details.builder(ErrorCode.of(code.value()), error.string("message"))
                .httpStatus(reply.status()).retryAfter(defaults.retryAfter().orElse(null)).errorBody(body)
                .outcomeUnknown(defaults.outcomeUnknown()).build();
    }

    private AssistantMessage.Builder message(Model model, JsonObject json) {
        var usage = json.object("usage");
        var b = Usage.builder().raw(usage);
        if (usage.get("input").orElse(null) instanceof JsonNumber n) b.input(n.longValue());
        if (usage.get("output").orElse(null) instanceof JsonNumber n) b.output(n.longValue());
        if (usage.get("cacheRead").orElse(null) instanceof JsonNumber n) b.cacheRead(n.longValue());
        if (usage.get("cacheWrite").orElse(null) instanceof JsonNumber n) b.cacheWrite(n.longValue());
        return AssistantMessage.builder(model.ref(), ID).stopReason(StopReason.of(json.string("stop"))).usage(b.build())
                .responseId(json.get("id").orElse(null) instanceof JsonString s ? s.value() : null);
    }

    /// The wire form of a content part.
    static JsonObject part(Content content) {
        return switch (content) {
            case Content.Text t -> Json.object("type", "text", "text", t.text());
            case Content.Reasoning r -> Json.object("type", "reasoning", "text", r.text().orElse(null), "signature", r.signature().orElse(null));
            case ToolCall c -> Json.object("type", "tool_call", "id", c.id(), "name", c.name(), "arguments", c.argumentsJson());
            case Content.Refusal r -> Json.object("type", "refusal", "text", r.text());
            case Content.Image i -> Json.object("type", "image", "mediaType", i.mediaType());
            default -> Json.object("type", "unknown", "raw", content.toString());
        };
    }

    static Content part(JsonObject json) {
        return switch (json.string("type")) {
            case "text" -> Content.text(json.string("text"));
            case "reasoning" -> Content.Reasoning.of(text(json, "text"), text(json, "signature"), false, JsonNull.INSTANCE);
            case "tool_call" -> ToolCall.of(json.string("id"), json.string("name"), json.string("arguments"));
            case "refusal" -> Content.Refusal.of(json.string("text"));
            default -> Content.Unknown.of(json.string("type"), json);
        };
    }

    private static @Nullable String text(JsonObject json, String name) {
        return json.get(name).orElse(null) instanceof JsonString s ? s.value() : null;
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
