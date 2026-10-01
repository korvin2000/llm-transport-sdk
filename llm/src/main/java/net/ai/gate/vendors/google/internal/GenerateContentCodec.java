package net.ai.gate.vendors.google.internal;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Message;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.chat.tool.FunctionTool;
import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ModelRef;
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
import net.ai.gate.vendors.google.GeminiOptions;

/// Gemini `models/{model}:generateContent` and `:streamGenerateContent?alt=sse` (`v1beta` in the preset's base URL).
/// Every streamed chunk's `usageMetadata` is a `UsageUpdate`; input tokens are counted by `:countTokens`.
///
/// Thought signatures may sit on any part. A signature on a thought part stays with that reasoning part; one on a
/// text or function-call part becomes a text-less signed `Reasoning` just before it, and is re-attached to the next
/// part (or the last one) on replay — so same-origin history round-trips while other models never see it. Function
/// calls without an id get one derived from the response id; the ids the API returns (Gemini 3) go back with the
/// call and its response. Generated images and audio (`inlineData`) become `Content.Image` / `Content.Audio` —
/// audio's format is the MIME subtype, e.g. `L16;codec=pcm;rate=24000` — and replay as `inlineData`. Gemini 3 and
/// the `-latest` aliases take a `thinkingLevel`, older models a `thinkingBudget`
/// ([function calling](https://ai.google.dev/gemini-api/docs/generate-content/function-calling)).
public final class GenerateContentCodec implements WireApi {
    public static final GenerateContentCodec INSTANCE = new GenerateContentCodec();
    private static final String ID = "google-generate-content", SYNTHESIZED = "call_ai-gate_";
    private static final Pattern LEVEL_MODELS = Pattern.compile(".*gemini-([3-9]|[1-9][0-9])[.-].*|.*-latest");
    private static final Map<String, String> HOSTED = Map.of("google_search", "googleSearch", "code_execution", "codeExecution",
            "url_context", "urlContext");

    private GenerateContentCodec() { }

    @Override public String id() { return ID; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) {
        var o = request.options();
        var extra = o.provider(GeminiOptions.class);
        var conversation = request.conversation();
        var body = new LinkedHashMap<String, Object>();
        body.put("contents", contents(conversation.messages()));
        conversation.system().ifPresent(s -> body.put("systemInstruction", Json.object("parts", List.of(Json.object("text", s)))));

        var declarations = new ArrayList<JsonObject>();
        var tools = new ArrayList<JsonObject>();
        for (var tool : conversation.tools()) {
            switch (tool) {
                case FunctionTool f -> {
                    var d = Json.object("name", f.name());
                    if (f.description().isPresent()) d = d.with("description", f.description().get());
                    declarations.add(d.with("parametersJsonSchema", f.parameters().asJson()));
                }
                case ProviderTool p -> tools.add(Json.object(HOSTED.getOrDefault(p.name(), p.name()), p.config()));
            }
        }
        if (!declarations.isEmpty()) tools.addFirst(Json.object("functionDeclarations", declarations));
        if (!tools.isEmpty()) body.put("tools", tools);
        o.toolChoice().ifPresent(c -> body.put("toolConfig", Json.object("functionCallingConfig", switch (c) {
            case ToolChoice.Auto _ -> Json.object("mode", "AUTO");
            case ToolChoice.None _ -> Json.object("mode", "NONE");
            case ToolChoice.Required _ -> Json.object("mode", "ANY");
            case ToolChoice.Only only -> Json.object("mode", "ANY", "allowedFunctionNames", List.of(only.toolName()));
        })));
        if (!o.parallelToolCalls().orElse(true))
            ctx.adapt(new Warning("option_dropped", "generateContent cannot forbid parallel tool calls; the setting was not sent"));

        var config = new LinkedHashMap<String, Object>();
        o.maxTokens().ifPresent(n -> config.put("maxOutputTokens", n));
        o.temperature().ifPresent(t -> config.put("temperature", t));
        o.topP().ifPresent(p -> config.put("topP", p));
        o.topK().ifPresent(k -> config.put("topK", k));
        o.seed().ifPresent(s -> config.put("seed", s));
        if (!o.stop().isEmpty()) config.put("stopSequences", o.stop());
        o.output().ifPresent(format -> {
            if (format instanceof OutputFormat.AnyJson) config.put("responseMimeType", "application/json");
            Codecs.schema(format, ctx.json()).ifPresent(s -> {
                config.put("responseMimeType", "application/json");
                config.put("responseJsonSchema", s.schema().asJson());
            });
        });
        var thinking = new LinkedHashMap<String, Object>();
        var budget = extra.map(GeminiOptions::thinkingBudget).filter(OptionalInt::isPresent).map(OptionalInt::getAsInt);
        o.reasoning().ifPresent(level -> {
            if (budget.isPresent()) return;
            if (LEVEL_MODELS.matcher(request.model().id()).matches()) thinking.put("thinkingLevel", switch (level) {
                case OFF, MINIMAL -> "MINIMAL";
                case LOW -> "LOW";
                case MEDIUM -> "MEDIUM";
                case HIGH, XHIGH, MAX -> "HIGH";
            });
            else thinking.put("thinkingBudget", switch (level) {
                case OFF -> 0;
                case MINIMAL -> 512;
                case LOW -> 2048;
                case MEDIUM -> 8192;
                case HIGH -> 24576;
                case XHIGH, MAX -> 32768;
            });
        });
        budget.ifPresent(b -> thinking.put("thinkingBudget", b));
        if (extra.map(GeminiOptions::includeThoughts).orElse(false) || o.reasoning().filter(l -> l != ReasoningLevel.OFF).isPresent())
            thinking.put("includeThoughts", true);
        if (!thinking.isEmpty()) config.put("thinkingConfig", thinking);
        if (!config.isEmpty()) body.put("generationConfig", config);
        extra.flatMap(GeminiOptions::safetySettings).ifPresent(s -> body.put("safetySettings", s));
        extra.flatMap(GeminiOptions::cachedContent).ifPresent(c -> body.put("cachedContent", c));

        var model = "models/" + request.model().id();
        return HttpCall.post(request.streaming() ? model + ":streamGenerateContent?alt=sse" : model + ":generateContent", Json.valueOf(body));
    }

    /// Turns as `contents`; also the payload of cached contents.
    static List<JsonObject> contents(List<Message> messages) {
        var contents = new ArrayList<JsonObject>();
        for (var message : messages) {
            var parts = new ArrayList<JsonObject>();
            switch (message) {
                case UserMessage u -> u.content().forEach(p -> parts.add(media(p)));
                case AssistantMessage a -> model(a, parts);
                case ToolResultMessage r -> r.results().forEach(x -> {
                    parts.add(Json.object("functionResponse", withId(Json.object("name", x.toolName(),
                            "response", Json.object(x.isError() ? "error" : "output", x.text())), x.callId())));
                    x.content().stream().filter(c -> !(c instanceof Content.Text)).forEach(c -> parts.add(media(c)));
                });
            }
            if (!parts.isEmpty()) contents.add(Json.object("role", message instanceof AssistantMessage ? "model" : "user", "parts", parts));
        }
        return contents;
    }

    private static JsonObject media(Content part) {
        return switch (part) {
            case Content.Text t -> Json.object("text", t.text());
            case Content.Image i -> data(i.source(), i.mediaType());
            case Content.Document d -> data(d.source(), d.mediaType());
            case Content.Audio a -> Json.object("inlineData", Json.object("mimeType", "audio/" + a.format(),
                    "data", Codecs.base64(new Content.Source.Inline(a.data()))));
            default -> throw new IllegalArgumentException("generateContent cannot take " + part + " in a user turn");
        };
    }

    private static JsonObject data(Content.Source source, String mediaType) {
        return switch (source) {
            case Content.Source.Remote r -> Json.object("fileData", Json.object("mimeType", mediaType, "fileUri", r.url().toString()));
            case Content.Source.Ref r -> Json.object("fileData", Json.object("mimeType", mediaType, "fileUri", r.fileId()));
            default -> Json.object("inlineData", Json.object("mimeType", mediaType, "data", Codecs.base64(source)));
        };
    }

    /// A text-less signed reasoning part hands its signature to the next part, or to the last one at the end.
    private static void model(AssistantMessage a, List<JsonObject> parts) {
        String pending = null;
        for (var part : a.content()) {
            JsonObject wire = switch (part) {
                case Content.Reasoning r when r.text().isPresent() -> Json.object("text", r.text().get(), "thought", true);
                case Content.Reasoning r -> {
                    if (r.signature().isPresent()) pending = r.signature().get();
                    yield null;
                }
                case Content.Text t -> Json.object("text", t.text());
                case Content.Refusal r -> Json.object("text", r.text());
                case ToolCall c -> Json.object("functionCall", withId(Json.object("name", c.name(), "args", args(c)), c.id()));
                case Content.Image _, Content.Audio _ -> media(part);
                case Content.Unknown u when u.raw() instanceof JsonObject raw -> raw;
                default -> null;
            };
            if (wire == null) continue;
            if (part instanceof Content.Reasoning r && r.signature().isPresent()) wire = wire.with("thoughtSignature", r.signature().get());
            else if (pending != null) wire = wire.with("thoughtSignature", pending);
            if (!(part instanceof Content.Reasoning)) pending = null;
            parts.add(wire);
        }
        if (pending != null && !parts.isEmpty()) parts.set(parts.size() - 1, parts.getLast().with("thoughtSignature", pending));
    }

    /// Ids synthesized here stay local; others are the API's own.
    private static JsonObject withId(JsonObject function, String id) { return id.startsWith(SYNTHESIZED) ? function : function.with("id", id); }

    private static JsonObject args(ToolCall call) {
        try {
            return call.arguments();
        } catch (RuntimeException e) {
            return Json.object();   // truncated arguments in a stored history must not fail every later call
        }
    }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) {
        var json = (JsonObject) reply.json();
        var responseId = json.optString("responseId").orElse(null);
        var parts = new ArrayList<Content>();
        var candidate = json.objects("candidates").stream().findFirst().orElse(Json.object());
        for (var part : candidate.object("content").objects("parts")) parts.addAll(parts(part, responseId, parts.size()));
        return message(ctx.model().ref(), json, candidate, parts.stream().anyMatch(ToolCall.class::isInstance)).content(parts).build();
    }

    /// One wire part as content: a signature off a thought part becomes its own signed reasoning part first.
    private static List<Content> parts(JsonObject part, @Nullable String responseId, int position) {
        var signature = part.optString("thoughtSignature").orElse(null);
        if (part.bool("thought")) return List.of(Content.Reasoning.of(part.optString("text").orElse(null), signature, false, JsonNull.INSTANCE));
        Content content;
        if (part.get("functionCall").orElse(null) instanceof JsonObject call)
            content = ToolCall.of(call.optString("id").filter(id -> !id.isBlank()).orElseGet(() -> callId(responseId, position)), call.string("name"), call.object("args"));
        else if (part.optString("text").isPresent()) content = Content.text(part.string("text"));
        else if (part.get("inlineData").orElse(null) instanceof JsonObject data && data.optString("data").isPresent()) content = inline(data, part);
        else content = Content.Unknown.of(part.members().keySet().stream().filter(k -> !k.equals("thoughtSignature")).findFirst().orElse("part"), part);
        return signature == null ? List.of(content) : List.of(Content.Reasoning.of(null, signature, false, JsonNull.INSTANCE), content);
    }

    private static String callId(@Nullable String responseId, int position) {
        return SYNTHESIZED + Integer.toHexString(String.valueOf(responseId).hashCode()) + "_" + position;
    }

    /// Generated images and audio; other media stays an unknown part.
    private static Content inline(JsonObject data, JsonObject part) {
        var type = data.optString("mimeType").orElse("");
        var bytes = Base64.getDecoder().decode(data.string("data"));
        return type.startsWith("image/") ? Content.Image.of(new Content.Source.Inline(bytes), type, null)
                : type.startsWith("audio/") ? Content.Audio.of(bytes, type.substring("audio/".length()), null)
                : Content.Unknown.of("inlineData", part);
    }

    private static AssistantMessage.Builder message(ModelRef model, JsonObject chunk, JsonObject candidate, boolean calls) {
        var finish = candidate.optString("finishReason").orElse(null);
        var blocked = chunk.object("promptFeedback").optString("blockReason").isPresent();
        var stop = blocked ? StopReason.CONTENT_FILTER : finish == null ? StopReason.STOP : switch (finish) {
            case "STOP" -> calls ? StopReason.TOOL_USE : StopReason.STOP;
            case "MAX_TOKENS" -> StopReason.LENGTH;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> StopReason.CONTENT_FILTER;
            default -> StopReason.of(finish.toLowerCase(Locale.ROOT));
        };
        return AssistantMessage.builder(model, ID).stopReason(stop).usage(usage(chunk.object("usageMetadata")))
                .responseId(chunk.optString("responseId").orElse(null)).responseModel(chunk.optString("modelVersion").orElse(null));
    }

    /// `promptTokenCount` includes cached tokens; `thoughtsTokenCount` is not part of `candidatesTokenCount`. The API
    /// omits zero-valued counters (proto3 JSON), so an absent cache count is `0`, and it has no cache-write bucket.
    static Usage usage(JsonObject u) {
        if (u.isEmpty()) return Usage.empty();
        var b = Usage.builder().raw(u);
        long cached = u.optLong("cachedContentTokenCount").orElse(0), thoughts = u.optLong("thoughtsTokenCount").orElse(0);
        u.optLong("promptTokenCount").ifPresent(p -> b.input(Math.max(0, p - cached) + u.optLong("toolUsePromptTokenCount").orElse(0))
                .cacheRead(cached).cacheWrite(0));
        u.optLong("candidatesTokenCount").ifPresent(c -> b.output(c + thoughts));
        if (thoughts > 0) b.reasoning(thoughts);
        u.optLong("totalTokenCount").ifPresent(b::total);
        return b.build();
    }

    /// Each frame is a partial response; the stream has no terminal event, so a finish reason makes the end clean.
    @Override public StreamDecoder streamDecoder(DecodeContext ctx) {
        return new StreamDecoder() {
            private final TreeMap<Integer, String> signatures = new TreeMap<>();   // signed thought part → its signature
            private final Map<Integer, StringBuilder> thoughts = new HashMap<>();
            private @Nullable String responseId, kind;
            private JsonObject last = Json.object(), candidate = Json.object();
            private int index = -1, position;
            private boolean started, calls;

            @Override public List<ChatEvent> onFrame(Frame frame) {
                var chunk = Codecs.json(frame);
                if (chunk.get("error").orElse(null) instanceof JsonObject error)
                    throw Codecs.streamError(error.optString("status").orElse("error"), error.optString("message").orElse("no details"));
                var events = new ArrayList<ChatEvent>();
                if (!started) {
                    started = true;
                    responseId = chunk.optString("responseId").orElse(null);
                    events.add(ChatEvent.Started.of(responseId, chunk.optString("modelVersion").orElse(null)));
                }
                var c = chunk.objects("candidates").stream().findFirst().orElse(Json.object());
                for (var part : c.object("content").objects("parts")) {
                    for (var content : parts(part, responseId, position++)) {
                        switch (content) {
                            case Content.Reasoning r when r.text().isPresent() -> {
                                events.add(new ChatEvent.ReasoningDelta(next("thought"), r.text().get()));
                                thoughts.computeIfAbsent(index, _ -> new StringBuilder()).append(r.text().get());
                                r.signature().ifPresent(s -> signatures.put(index, s));
                            }
                            case ToolCall call -> {
                                calls = true;
                                events.add(new ChatEvent.ToolCallStart(next("call"), call.id(), call.name()));
                                events.add(new ChatEvent.PartEnd(index, call));
                                kind = null;   // every call is its own part
                            }
                            case Content.Text t -> events.add(new ChatEvent.TextDelta(next("text"), t.text()));
                            default -> {
                                events.add(new ChatEvent.PartEnd(next("other"), content));
                                kind = null;
                            }
                        }
                    }
                }
                last = chunk;
                if (!chunk.object("usageMetadata").isEmpty()) events.add(new ChatEvent.UsageUpdate(usage(chunk.object("usageMetadata"))));
                if (c.optString("finishReason").isPresent() || chunk.object("promptFeedback").optString("blockReason").isPresent()) candidate = c;
                return events;
            }

            @Override public List<ChatEvent> onEnd() {
                if (candidate.isEmpty() && last.object("promptFeedback").isEmpty()) return List.of();
                var events = new ArrayList<ChatEvent>();
                signatures.forEach((i, s) -> events.add(new ChatEvent.PartEnd(i, Content.Reasoning.of(thoughts.get(i).toString(), s, false, JsonNull.INSTANCE))));
                events.add(ChatEvent.Done.of(message(ctx.model().ref(), last, candidate, calls).build()));
                return events;
            }

            /// The index of the current part of `kind`, opening a new one when the kind changes.
            private int next(String k) {
                if (!k.equals(kind)) {
                    index++;
                    kind = k;
                }
                return index;
            }
        };
    }

    @Override public ApiFeatures features(DecodeContext ctx) {
        return new ApiFeatures(ID, ApiFeatures.OutputCap.ENFORCED, 1, ApiFeatures.PromptCache.NAMED_RESOURCE, 0, Set.of(), false, true, false,
                Set.of("input", "output", "reasoning", "cache_read", "cache_write"), true, true, false, true);
    }

    /// `models/{model}:countTokens` over the whole request (`generateContentRequest`).
    @Override public Optional<HttpCall> countRequest(HttpCall request) {
        var path = request.uri().getPath();
        int colon = path.lastIndexOf(':');
        if (colon < 0 || !(request.body().orElse(null) instanceof JsonObject body)) return Optional.empty();
        var model = path.substring(0, colon);
        return Optional.of(HttpCall.post(model + ":countTokens", Json.object("generateContentRequest", body.with("model", model)))
                .withHeaders(request.headers()));
    }

    @Override public OptionalLong countReply(HttpReply reply) {
        return reply.json() instanceof JsonObject json ? json.optLong("totalTokens") : OptionalLong.empty();
    }
}
