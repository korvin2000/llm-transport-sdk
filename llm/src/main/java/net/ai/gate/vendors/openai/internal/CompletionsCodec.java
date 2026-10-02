package net.ai.gate.vendors.openai.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.chat.tool.FunctionTool;
import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import net.ai.gate.metadata.Charge;
import net.ai.gate.metadata.ResponseInfo;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiFeatures;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.Codecs;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;
import net.ai.gate.spi.protocol.Frame;
import net.ai.gate.spi.protocol.StreamDecoder;
import net.ai.gate.spi.protocol.WireApi;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat;

/// OpenAI Chat Completions (`POST chat/completions`) — also the dialect of most compatible providers and gateways,
/// whose differences are `OpenAiCompletionsCompat` flags, never base-URL sniffing. Reasoning arrives in
/// `reasoning_content`, `reasoning` or `reasoning_text`, whichever the server uses. Audio output (`message.audio`,
/// streamed as `delta.audio`) becomes `Content.Audio` with its transcript — `pcm16` when streamed, else the reply's
/// `format` or `wav` — and replays as the transcript, since audio ids expire. Usage arrives in the last chunk (with
/// `stream_options.include_usage`) as a `UsageUpdate` before the end; there is no counting endpoint. Replay keeps
/// text, tool calls with their ids and raw arguments, and reasoning only where `reasoningContentReplay` is set.
/// A gateway's own facts are kept where the reply states them: OpenRouter's charge (`usage.cost`, USD credits) as
/// `Usage.charge()`, and the upstream it routed to (`provider`) as `ResponseInfo.route()`.
public final class CompletionsCodec implements WireApi {
    public static final CompletionsCodec INSTANCE = new CompletionsCodec();
    private static final String ID = "openai-completions";
    private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning", "reasoning_text");
    private static final JsonObject EPHEMERAL = Json.object("type", "ephemeral");
    private static final Pattern MISTRAL_ID = Pattern.compile("[a-zA-Z0-9]{9}");
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Currency USD = Currency.getInstance("USD");

    private CompletionsCodec() { }

    @Override public String id() { return ID; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) {
        var compat = ctx.compat(OpenAiCompletionsCompat.defaults());
        var o = request.options();
        var conversation = request.conversation();
        var messages = new ArrayList<JsonObject>();
        var ids = toolCallIds(conversation, compat);
        conversation.system().ifPresent(s -> messages.add(Json.object("role", compat.developerRole() ? "developer" : "system", "content", s)));
        for (var message : conversation.messages()) {
            switch (message) {
                case UserMessage u -> messages.add(Json.object("role", "user", "content", content(u.content())));
                case AssistantMessage a -> messages.add(assistant(a, compat, ids));
                case ToolResultMessage r -> r.results().forEach(x ->
                        messages.add(Json.object("role", "tool", "tool_call_id", ids.apply(x.callId()), "content", x.text())));
            }
        }
        boolean marked = compat.cacheControl() == OpenAiCompletionsCompat.CacheControl.ANTHROPIC_STYLE
                && o.cacheRetention().orElse(CacheRetention.SHORT) != CacheRetention.NONE;
        if (marked && !messages.isEmpty()) {
            if (conversation.system().isPresent()) messages.set(0, cached(messages.getFirst()));
            messages.set(messages.size() - 1, cached(messages.getLast()));
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model().id());
        body.put("messages", messages);
        if (request.streaming()) {
            body.put("stream", true);
            if (compat.streamUsage()) body.put("stream_options", Json.object("include_usage", true));
        }
        o.maxTokens().ifPresent(n -> body.put(compat.maxTokensField(), n));
        if (Codecs.sampling(request, ctx, ResponsesCodec.reasoningRejectsSampling(request.model(), o.reasoning().orElse(null)))) {
            o.temperature().ifPresent(t -> body.put("temperature", t));
            o.topP().ifPresent(p -> body.put("top_p", p));
        }
        o.seed().ifPresent(s -> body.put("seed", s));
        if (!o.stop().isEmpty()) body.put("stop", o.stop());
        if (o.topK().isPresent()) ctx.adapt(new Warning("option_dropped", "topK is not part of Chat Completions; it was not sent"));

        var tools = new ArrayList<JsonObject>();
        for (var tool : conversation.tools()) {
            switch (tool) {
                case FunctionTool f -> {
                    var function = Json.object("name", f.name(), "parameters", f.parameters().asJson());
                    if (f.description().isPresent()) function = function.with("description", f.description().get());
                    if (f.strict() && compat.strictTools()) function = function.with("strict", true);
                    tools.add(Json.object("type", "function", "function", function));
                }
                case ProviderTool p -> tools.add(p.config());
            }
        }
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            o.toolChoice().ifPresent(c -> body.put("tool_choice", switch (c) {
                case ToolChoice.Auto _ -> "auto";
                case ToolChoice.None _ -> "none";
                case ToolChoice.Required _ -> "required";
                case ToolChoice.Only only -> Json.object("type", "function", "function", Json.object("name", only.toolName()));
            }));
            o.parallelToolCalls().ifPresent(p -> body.put("parallel_tool_calls", p));
        }

        o.reasoning().ifPresent(level -> reasoning(level, compat, body, ctx));
        o.output().ifPresent(format -> {
            if (format instanceof OutputFormat.AnyJson) body.put("response_format", Json.object("type", "json_object"));
            Codecs.schema(format, ctx.json()).ifPresent(s -> body.put("response_format", Json.object("type", "json_schema",
                    "json_schema", Json.object("name", s.name(), "schema", s.schema().asJson(), "strict", s.strict()))));
        });

        var call = HttpCall.post("chat/completions", Json.valueOf(body));
        var session = o.sessionId().orElse(null);
        return session == null ? call : switch (compat.sessionHeader()) {
            case NONE -> call;
            case OPENAI -> call.withHeader("session_id", session);
            case OPENROUTER -> call.withHeader("x-session-id", session);
        };
    }

    private static void reasoning(ReasoningLevel level, OpenAiCompletionsCompat compat, Map<String, Object> body, EncodeContext ctx) {
        boolean on = level != ReasoningLevel.OFF;
        switch (compat.reasoningFormat()) {
            case OPENAI -> body.put("reasoning_effort", Codecs.effort(level));
            case OPENROUTER -> body.put("reasoning", Json.object("effort", Codecs.effort(level)));
            case DEEPSEEK, ZAI -> body.put("thinking", Json.object("type", on ? "enabled" : "disabled"));
            case QWEN -> body.put("enable_thinking", on);
            case TOGETHER -> body.put("reasoning", Json.object("enabled", on));
            case CHAT_TEMPLATE -> body.put("chat_template_kwargs", Json.object("enable_thinking", on));
            case NONE -> ctx.adapt(new Warning("option_dropped", "reasoning " + level + " was not sent: the endpoint has no reasoning control"));
        }
    }

    /// A string for plain text, else typed parts.
    private static Object content(List<Content> parts) {
        if (parts.size() == 1 && parts.getFirst() instanceof Content.Text t) return t.text();
        return parts.stream().map(part -> switch (part) {
            case Content.Text t -> Json.object("type", "text", "text", t.text());
            case Content.Image i -> Json.object("type", "image_url", "image_url", i.detail()
                    .map(d -> Json.object("url", Codecs.url(i.source(), i.mediaType()), "detail", d))
                    .orElseGet(() -> Json.object("url", Codecs.url(i.source(), i.mediaType()))));
            case Content.Document d -> Json.object("type", "file", "file", d.source() instanceof Content.Source.Ref r
                    ? Json.object("file_id", r.fileId())
                    : Json.object("filename", d.title().orElse("document"), "file_data", Codecs.url(d.source(), d.mediaType())));
            case Content.Audio a -> Json.object("type", "input_audio", "input_audio", Json.object("data", Codecs.base64(new Content.Source.Inline(a.data())), "format", a.format()));
            default -> throw new IllegalArgumentException("A user turn cannot carry " + part);
        }).toList();
    }

    /// Mistral takes only ids of exactly nine letters and digits: other ids become a base-62 hash — the same for a call
    /// and its result, distinct within the request — while ids already in that form stay.
    private static UnaryOperator<String> toolCallIds(Conversation conversation, OpenAiCompletionsCompat compat) {
        if (compat.toolCallIdFormat() != OpenAiCompletionsCompat.ToolCallIdFormat.MISTRAL) return UnaryOperator.identity();
        var all = conversation.messages().stream().flatMap(m -> switch (m) {
            case AssistantMessage a -> a.toolCalls().stream().map(ToolCall::id);
            case ToolResultMessage r -> r.results().stream().map(ToolResult::callId);
            case UserMessage _ -> Stream.<String>empty();
        }).toList();
        var ids = new HashMap<String, String>();
        all.stream().filter(id -> MISTRAL_ID.matcher(id).matches()).forEach(id -> ids.put(id, id));
        var taken = new HashSet<>(ids.values());
        for (var id : all)
            ids.computeIfAbsent(id, k -> Stream.iterate(0, n -> n + 1).map(n -> base62(k + "#" + n)).filter(taken::add).findFirst().orElseThrow());
        return id -> ids.getOrDefault(id, id);
    }

    /// Nine base-62 digits of a name-based UUID of `seed`.
    private static String base62(String seed) {
        long value = Long.remainderUnsigned(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits(), 13_537_086_546_263_552L);
        var digits = new char[9];
        for (int i = 8; i >= 0; i--, value /= 62) digits[i] = BASE62.charAt((int) (value % 62));
        return new String(digits);
    }

    private static JsonObject assistant(AssistantMessage a, OpenAiCompletionsCompat compat, UnaryOperator<String> ids) {
        var text = new StringBuilder();
        var reasoning = new StringBuilder();
        var calls = new ArrayList<JsonObject>();
        for (var part : a.content()) {
            switch (part) {
                case Content.Text t -> text.append(t.text());
                case Content.Refusal r -> text.append(r.text());
                case Content.Reasoning r -> r.text().ifPresent(reasoning::append);
                case ToolCall c -> calls.add(Json.object("id", ids.apply(c.id()), "type", "function",
                        "function", Json.object("name", c.name(), "arguments", c.argumentsJson().isBlank() ? "{}" : c.argumentsJson())));
                case Content.Audio audio -> audio.transcript().ifPresent(text::append);
                default -> { }   // images and unknown parts have no assistant form here
            }
        }
        var message = Json.object("role", "assistant", "content", text.isEmpty() && !calls.isEmpty() ? null : text.toString());
        if (!calls.isEmpty()) message = message.with("tool_calls", calls);
        if (compat.reasoningContentReplay() && !reasoning.isEmpty()) message = message.with("reasoning_content", reasoning.toString());
        return message;
    }

    /// Anthropic-style `cache_control` on the last part of a message.
    private static JsonObject cached(JsonObject message) {
        var content = message.get("content").orElse(null);
        List<JsonValue> parts = content instanceof JsonString s ? List.of(Json.object("type", "text", "text", s.value()))
                : message.array("content");
        if (parts.isEmpty() || !(parts.getLast() instanceof JsonObject last)) return message;
        var marked = new ArrayList<>(parts);
        marked.set(marked.size() - 1, last.with("cache_control", EPHEMERAL));
        return message.with("content", marked);
    }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) {
        var json = (JsonObject) reply.json();
        var choice = json.objects("choices").stream().findFirst().orElse(Json.object());
        var message = choice.object("message");
        var b = AssistantMessage.builder(ctx.model().ref(), ID);
        reasoningOf(message).ifPresent(r -> b.add(Content.reasoning(r)));
        message.optString("content").filter(s -> !s.isEmpty()).ifPresent(b::text);
        message.optString("refusal").ifPresent(r -> b.add(Content.Refusal.of(r)));
        if (message.object("audio").optString("data").isPresent()) {
            var audio = message.object("audio");
            b.add(Content.Audio.of(Base64.getDecoder().decode(audio.string("data")), audio.optString("format").orElse("wav"),
                    audio.optString("transcript").orElse(null)));
        }
        int n = 0;
        for (var call : message.objects("tool_calls")) {
            var function = call.object("function");
            b.add(ToolCall.of(Codecs.callId(call.optString("id").orElse(null), n), function.string("name"), function.optString("arguments").orElse("")));
            n++;
        }
        return b.stopReason(stop(choice.optString("finish_reason").orElse(null))).usage(usage(json.object("usage")))
                .responseId(json.optString("id").orElse(null)).responseModel(json.optString("model").orElse(null))
                .info(routed(json.optString("provider").orElse(null))).build();
    }

    /// Call facts the core completes: only the route a gateway states, else none.
    static ResponseInfo routed(@Nullable String route) {
        return route == null || route.isBlank() ? ResponseInfo.empty() : ResponseInfo.empty().toBuilder().route(route).build();
    }

    private static Optional<String> reasoningOf(JsonObject message) {
        return REASONING_FIELDS.stream().flatMap(f -> message.optString(f).stream()).filter(s -> !s.isEmpty()).findFirst();
    }

    static StopReason stop(@Nullable String finish) {
        return finish == null ? StopReason.STOP : switch (finish) {
            case "stop", "end_turn" -> StopReason.STOP;
            case "length" -> StopReason.LENGTH;
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.CONTENT_FILTER;
            default -> StopReason.of(finish);
        };
    }

    /// `prompt_tokens` includes cache reads (and gateway cache writes); DeepSeek reports `prompt_cache_hit_tokens`.
    static Usage usage(JsonObject u) {
        var b = Usage.builder().raw(u);
        var details = u.object("prompt_tokens_details");
        u.optLong("prompt_tokens").ifPresent(p -> input(b, p, details.optLong("cached_tokens").isPresent() ? details.optLong("cached_tokens")
                : u.optLong("prompt_cache_hit_tokens"), details.optLong("cache_write_tokens")));
        u.optLong("completion_tokens").ifPresent(b::output);
        u.object("completion_tokens_details").optLong("reasoning_tokens").ifPresent(b::reasoning);
        u.optLong("total_tokens").ifPresent(b::total);
        charge(b, u);
        return b.build();
    }

    /// OpenRouter's charge: `cost` is what the account was charged, `cost_details.upstream_inference_cost` the upstream
    /// provider's own charge, both in USD; a missing or negative amount is no charge stated.
    static void charge(Usage.Builder b, JsonObject u) {
        if (!(u.get("cost").orElse(null) instanceof JsonNumber cost) || cost.value().signum() < 0) return;
        var upstream = u.object("cost_details").get("upstream_inference_cost").orElse(null) instanceof JsonNumber n && n.value().signum() >= 0 ? n.value() : null;
        b.charge(new Charge(USD, cost.value(), upstream));
    }

    /// The OpenAI family's input accounting: `total` includes cache reads and writes. Writes have no bucket of their
    /// own — they are input — so they are `0` where the cache read is reported, unless a gateway states them; a counter
    /// the reply leaves out stays absent.
    static void input(Usage.Builder b, long total, OptionalLong read, OptionalLong written) {
        b.input(Math.max(0, total - read.orElse(0) - written.orElse(0)));
        read.ifPresent(b::cacheRead);
        if (written.isPresent()) b.cacheWrite(written.getAsLong());
        else if (read.isPresent()) b.cacheWrite(0);
    }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) {
        return new StreamDecoder() {
            private final Map<String, Integer> parts = new HashMap<>();
            private final StringBuilder refusal = new StringBuilder(), transcript = new StringBuilder();
            private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
            private @Nullable String finish, id, model, route;
            private Usage usage = Usage.empty();
            private boolean started, done;

            @Override public List<ChatEvent> onFrame(Frame frame) {
                if (frame.data().strip().equals("[DONE]")) return finish();
                var chunk = Codecs.json(frame);
                if (chunk.get("error").orElse(null) instanceof JsonObject error)
                    throw Codecs.streamError(error.optString("type").or(() -> error.optString("code")).orElse("error"), error.optString("message").orElse(""));
                var events = new ArrayList<ChatEvent>();
                if (!started) {
                    started = true;
                    id = chunk.optString("id").orElse(null);
                    model = chunk.optString("model").orElse(null);
                    route = chunk.optString("provider").filter(p -> !p.isBlank()).orElse(null);
                    events.add(ChatEvent.Started.of(id, model, route));
                } else if (route == null) {
                    // a gateway may name its upstream only in a later chunk: restate the start so a cut-off reply keeps it
                    route = chunk.optString("provider").filter(p -> !p.isBlank()).orElse(null);
                    if (route != null) events.add(ChatEvent.Started.of(id, model, route));
                }
                if (chunk.get("usage").orElse(null) instanceof JsonObject u) {
                    usage = usage(u);
                    events.add(new ChatEvent.UsageUpdate(usage));
                }
                for (var choice : chunk.objects("choices")) {
                    var delta = choice.object("delta");
                    reasoningOf(delta).ifPresent(r -> events.add(new ChatEvent.ReasoningDelta(index("reasoning"), r)));
                    delta.optString("content").filter(s -> !s.isEmpty()).ifPresent(t -> events.add(new ChatEvent.TextDelta(index("text"), t)));
                    delta.optString("refusal").ifPresent(refusal::append);
                    if (delta.get("audio").orElse(null) instanceof JsonObject a) {
                        int at = index("audio");
                        a.optString("data").ifPresent(d -> audio.writeBytes(Base64.getDecoder().decode(d)));
                        a.optString("transcript").filter(t -> !t.isEmpty()).ifPresent(t -> {
                            transcript.append(t);
                            events.add(new ChatEvent.TextDelta(at, t));   // shown live; the audio part replaces it at the end
                        });
                    }
                    for (var call : delta.objects("tool_calls")) {
                        var key = "tool" + call.optLong("index").orElse(0);
                        var function = call.object("function");
                        if (!parts.containsKey(key)) {
                            int at = index(key);
                            events.add(new ChatEvent.ToolCallStart(at, Codecs.callId(call.optString("id").orElse(null), at),
                                    function.optString("name").orElse("unknown")));
                        }
                        function.optString("arguments").filter(s -> !s.isEmpty())
                                .ifPresent(a -> events.add(new ChatEvent.ToolCallDelta(index(key), a, Json.object())));
                    }
                    choice.optString("finish_reason").ifPresent(f -> finish = f);
                }
                return events;
            }

            /// Servers that close without `[DONE]` still end cleanly once a finish reason arrived.
            @Override public List<ChatEvent> onEnd() { return finish != null ? finish() : List.of(); }

            private List<ChatEvent> finish() {
                if (done) return List.of();
                done = true;
                var events = new ArrayList<ChatEvent>();
                if (!refusal.isEmpty()) events.add(new ChatEvent.PartEnd(index("refusal"), Content.Refusal.of(refusal.toString())));
                if (parts.containsKey("audio"))
                    events.add(new ChatEvent.PartEnd(index("audio"), Content.Audio.of(audio.toByteArray(), "pcm16", transcript.isEmpty() ? null : transcript.toString())));
                events.add(ChatEvent.Done.of(AssistantMessage.builder(ctx.model().ref(), ID).stopReason(stop(finish)).usage(usage)
                        .responseId(id).responseModel(model).info(routed(route)).build()));
                return events;
            }

            private int index(String key) { return parts.computeIfAbsent(key, _ -> parts.size()); }
        };
    }

    /// Chat Completions limits tool-call ids to 40 characters.
    @Override public String normalizeToolCallId(String foreignId) { return Codecs.toolCallId(foreignId, 40); }

    /// Anthropic-style markers are placed automatically (system, last message): explicit breakpoints are not honoured.
    @Override public ApiFeatures features(DecodeContext ctx) {
        var compat = ctx.compat(OpenAiCompletionsCompat.defaults());
        return new ApiFeatures(ID, ApiFeatures.OutputCap.ENFORCED, 1, ApiFeatures.PromptCache.AUTOMATIC, 0, Set.of(CacheRetention.SHORT),
                false, true, true, Set.of("input", "output", "reasoning", "cache_read", "cache_write"), compat.streamUsage(),
                compat.reasoningContentReplay(), false, false);
    }
}
