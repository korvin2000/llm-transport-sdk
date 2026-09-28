package net.ai.gate.vendors.openai.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.AssistantMessage;
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
import net.ai.gate.error.ProviderException;
import net.ai.gate.error.RateLimitedException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonValue;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Model;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.model.SupportLevel;
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
import net.ai.gate.vendors.openai.OpenAiResponsesCompat;
import net.ai.gate.vendors.openai.OpenAiResponsesOptions;

/// OpenAI Responses API (`POST responses`), stateless by default (`store: false`): reasoning items travel with their
/// encrypted content, so same-origin reasoning replays without server state. Stream events carry deltas; each
/// `response.output_item.done` carries the authoritative part, decoded exactly as a whole reply is. Generated images
/// (`image_generation_call`) become inline images that replay as their item. Dialects (the ChatGPT Codex backend)
/// are `OpenAiResponsesCompat` flags.
///
/// Reasoning models take `temperature` and `top_p` only with reasoning off; the catalog's `TEMPERATURE` support
/// decides first. GPT-5.6 and later have no `prompt_cache_retention`
/// ([prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)); asking them for `LONG` retention
/// is an adaptation.
///
/// Usage arrives only with the final response. `input_tokens_details.cached_tokens` is the cache read; the API has no
/// separate write bucket (writes are input), so writes are `0` where cache details are reported and absent where they
/// are not. Input tokens are counted by `responses/input_tokens`. Replay keeps text, `call_id`s (not the function
/// item `id`), reasoning items with their encrypted content (without `status`) and generated images.
public final class ResponsesCodec implements WireApi {
    public static final ResponsesCodec INSTANCE = new ResponsesCodec();
    private static final String ID = "openai-responses";
    private static final String ENCRYPTED_REASONING = "reasoning.encrypted_content", IMAGE = "image_generation_call";
    private static final Pattern REASONING_FAMILY = Pattern.compile("(.*/)?(o[1-9]|gpt-[5-9]).*");
    private static final Pattern NO_CACHE_RETENTION = Pattern.compile("(.*/)?gpt-(5\\.([6-9]|\\d{2,})|[6-9]).*");
    private static final int MIN_OUTPUT = 16;

    private ResponsesCodec() { }

    @Override public String id() { return ID; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) {
        var compat = ctx.compat(OpenAiResponsesCompat.defaults());
        var o = request.options();
        var extra = o.provider(OpenAiResponsesOptions.class);
        var conversation = request.conversation();
        var level = o.reasoning().orElse(null);
        var input = new ArrayList<JsonValue>();
        for (var message : conversation.messages()) {
            switch (message) {
                case UserMessage u -> input.add(Json.object("role", "user", "content", u.content().stream().map(ResponsesCodec::inputPart).toList()));
                case AssistantMessage a -> assistant(a, input);
                case ToolResultMessage r -> r.results().forEach(x -> input.add(Json.object("type", "function_call_output",
                        "call_id", x.callId(), "output", output(x))));
            }
        }
        boolean store = extra.flatMap(OpenAiResponsesOptions::store).orElse(false);

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model().id());
        conversation.system().or(compat::defaultInstructions).ifPresent(s -> body.put("instructions", s));
        body.put("input", input);
        if (request.streaming() || compat.streamingOnly()) body.put("stream", true);
        body.put("store", store);
        if (compat.maxOutputTokens()) o.maxTokens().ifPresent(n -> {
            body.put("max_output_tokens", Math.max(MIN_OUTPUT, n));
            ctx.outputLimit(Math.max(MIN_OUTPUT, n));
        });
        else if (o.maxTokens().isPresent()) ctx.adapt(new Warning("option_dropped", "the endpoint takes no max_output_tokens; maxTokens was not sent"));
        if (Codecs.sampling(request, ctx, reasoningRejectsSampling(request.model(), level))) {
            o.temperature().ifPresent(t -> body.put("temperature", t));
            o.topP().ifPresent(p -> body.put("top_p", p));
        }
        if (o.topK().isPresent()) dropped(ctx, "topK");
        if (o.seed().isPresent()) dropped(ctx, "seed");
        if (!o.stop().isEmpty()) dropped(ctx, "stop");

        var tools = conversation.tools().stream().map(tool -> switch (tool) {
            case FunctionTool f -> {
                var function = Json.object("type", "function", "name", f.name());
                if (f.description().isPresent()) function = function.with("description", f.description().get());
                yield function.with("parameters", f.parameters().asJson()).with("strict", f.strict());   // the API defaults to strict
            }
            case ProviderTool p -> p.config();
        }).toList();
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            o.toolChoice().ifPresent(c -> body.put("tool_choice", switch (c) {
                case ToolChoice.Auto _ -> "auto";
                case ToolChoice.None _ -> "none";
                case ToolChoice.Required _ -> "required";
                case ToolChoice.Only only -> Json.object("type", "function", "name", only.toolName());
            }));
            o.parallelToolCalls().ifPresent(p -> body.put("parallel_tool_calls", p));
        }

        var include = new ArrayList<>(extra.map(OpenAiResponsesOptions::include).orElse(List.of()));
        var summary = extra.flatMap(OpenAiResponsesOptions::reasoningSummary).map(s -> s.name().toLowerCase(Locale.ROOT));
        if (level != null || summary.isPresent()) {
            var reasoning = Json.object();
            if (level != null) reasoning = reasoning.with("effort", Codecs.effort(level));
            if (level != ReasoningLevel.OFF) reasoning = reasoning.with("summary", summary.orElse("auto"));
            body.put("reasoning", reasoning);
        }
        // without server state, reasoning replays only with its encrypted content — also when the model reasons by default
        boolean reasons = level != null ? level != ReasoningLevel.OFF : request.model().capabilities().support(Capability.REASONING) == SupportLevel.SUPPORTED;
        if (!store && reasons && !include.contains(ENCRYPTED_REASONING)) include.add(ENCRYPTED_REASONING);
        if (!include.isEmpty()) body.put("include", include);

        o.output().ifPresent(format -> {
            if (format instanceof OutputFormat.AnyJson) body.put("text", Json.object("format", Json.object("type", "json_object")));
            Codecs.schema(format, ctx.json()).ifPresent(s -> body.put("text", Json.object("format", Json.object("type", "json_schema",
                    "name", s.name(), "schema", s.schema().asJson(), "strict", s.strict()))));
        });

        var retention = o.cacheRetention().orElse(CacheRetention.SHORT);
        if (retention != CacheRetention.NONE)
            extra.flatMap(OpenAiResponsesOptions::promptCacheKey).or(o::sessionId).ifPresent(key -> body.put("prompt_cache_key", key));
        if (retention == CacheRetention.LONG) {
            if (NO_CACHE_RETENTION.matcher(request.model().id()).matches())
                ctx.adapt(new Warning("cache_hint_ignored", request.model().id() + " has no extended prompt-cache retention; the default applies"));
            else body.put("prompt_cache_retention", "24h");
        }
        extra.flatMap(OpenAiResponsesOptions::serviceTier).ifPresent(t -> body.put("service_tier", t));
        extra.flatMap(OpenAiResponsesOptions::previousResponseId).ifPresent(id -> body.put("previous_response_id", id));
        extra.flatMap(OpenAiResponsesOptions::safetyIdentifier).ifPresent(id -> body.put("safety_identifier", id));
        var call = HttpCall.post("responses", Json.valueOf(body));
        if (compat.streamingOnly()) call = call.withHeader("Accept", "text/event-stream");
        var session = o.sessionId().orElse(null);
        return session == null || !compat.sessionHeaders() ? call : call.withHeader("session-id", session).withHeader("x-client-request-id", session);
    }

    /// OpenAI reasoning models take sampling parameters only with reasoning off; unless it is set, they are assumed to
    /// reason when the catalog does not say they accept them.
    static boolean reasoningRejectsSampling(Model model, @Nullable ReasoningLevel level) {
        return REASONING_FAMILY.matcher(model.id()).matches()
                && (level != null ? level != ReasoningLevel.OFF : model.capabilities().support(Capability.TEMPERATURE) != SupportLevel.SUPPORTED);
    }

    private static void dropped(EncodeContext ctx, String option) {
        ctx.adapt(new Warning("option_dropped", option + " is not part of the Responses API; it was not sent"));
    }

    private static JsonObject inputPart(Content part) {
        return switch (part) {
            case Content.Text t -> Json.object("type", "input_text", "text", t.text());
            case Content.Image i -> i.source() instanceof Content.Source.Ref r
                    ? Json.object("type", "input_image", "file_id", r.fileId(), "detail", i.detail().orElse("auto"))
                    : Json.object("type", "input_image", "image_url", Codecs.url(i.source(), i.mediaType()), "detail", i.detail().orElse("auto"));
            case Content.Document d -> switch (d.source()) {
                case Content.Source.Ref r -> Json.object("type", "input_file", "file_id", r.fileId());
                case Content.Source.Remote r -> Json.object("type", "input_file", "file_url", r.url().toString());
                default -> Json.object("type", "input_file", "filename", d.title().orElse("document"), "file_data", Codecs.url(d.source(), d.mediaType()));
            };
            default -> throw new IllegalArgumentException("The Responses API cannot take " + part + " as input");
        };
    }

    /// A string when the result is text only.
    private static Object output(ToolResult result) {
        if (result.content().stream().allMatch(Content.Text.class::isInstance)) return result.text();
        return result.content().stream().map(ResponsesCodec::inputPart).toList();
    }

    /// Consecutive text becomes one assistant message; reasoning, calls and unknown items keep their order.
    private static void assistant(AssistantMessage a, List<JsonValue> input) {
        var text = new StringBuilder();
        Runnable flush = () -> {
            if (!text.isEmpty()) input.add(Json.object("role", "assistant", "content", text.toString()));
            text.setLength(0);
        };
        for (var part : a.content()) {
            switch (part) {
                case Content.Text t -> text.append(t.text());
                case Content.Refusal r -> text.append(r.text());
                case Content.Reasoning r when r.providerData() instanceof JsonObject item && "reasoning".equals(item.optString("type").orElse(null)) -> {
                    flush.run();
                    input.add(r.signature().map(s -> item.with("encrypted_content", s)).orElse(item));
                }
                case ToolCall c -> {
                    flush.run();
                    input.add(Json.object("type", "function_call", "call_id", c.id(), "name", c.name(),
                            "arguments", c.argumentsJson().isBlank() ? "{}" : c.argumentsJson()));
                }
                case Content.Unknown u when u.raw() instanceof JsonObject item -> {
                    flush.run();
                    input.add(item);
                }
                case Content.Image i when i.providerData() instanceof JsonObject item && IMAGE.equals(item.optString("type").orElse(null)) -> {
                    flush.run();
                    input.add(Json.object("type", IMAGE, "id", item.string("id"), "status", item.optString("status").orElse("completed"),
                            "result", Codecs.base64(i.source())));
                }
                default -> { }   // foreign reasoning and media never reach here
            }
        }
        flush.run();
    }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) {
        var response = (JsonObject) reply.json();
        var b = message(ctx, response);
        for (var item : response.objects("output")) {
            var part = part(item);
            if (part != null) b.add(part);
        }
        var message = b.build();
        if (message.stopReason() == StopReason.ERROR) {
            var error = response.object("error");
            var failure = Codecs.streamError(error.optString("code").orElse("response_failed"),
                    message.errorMessage().orElse("The response failed"));
            var details = failure.details().toBuilder().partial(message).build();
            throw failure instanceof RateLimitedException ? new RateLimitedException(details) : new ProviderException(details);
        }
        return message;
    }

    /// Stop reason, usage and ids of a (final) response object.
    private static AssistantMessage.Builder message(DecodeContext ctx, JsonObject response) {
        boolean calls = response.objects("output").stream().anyMatch(i -> "function_call".equals(i.optString("type").orElse(null)));
        var reason = response.object("incomplete_details").optString("reason").orElse(null);
        var stop = switch (response.optString("status").orElse("completed")) {
            case "incomplete" -> "max_output_tokens".equals(reason) ? StopReason.LENGTH
                    : "content_filter".equals(reason) ? StopReason.CONTENT_FILTER : StopReason.of(reason == null ? "incomplete" : reason);
            case "failed", "cancelled" -> StopReason.ERROR;
            default -> calls ? StopReason.TOOL_USE : StopReason.STOP;
        };
        var b = AssistantMessage.builder(ctx.model().ref(), ID).stopReason(stop).usage(usage(response.object("usage")))
                .responseId(response.optString("id").orElse(null)).responseModel(response.optString("model").orElse(null));
        if (stop == StopReason.ERROR) b.errorMessage(response.object("error").optString("message").orElse("The response " + response.optString("status").orElse("")));
        return b;
    }

    /// One output item as a content part; `null` for items without content.
    private static @Nullable Content part(JsonObject item) {
        return switch (item.optString("type").orElse("")) {
            case "message" -> {
                var parts = item.objects("content");
                var refusal = parts.stream().flatMap(p -> p.optString("refusal").stream()).collect(Collectors.joining());
                if (!refusal.isEmpty()) yield Content.Refusal.of(refusal);
                var text = new StringBuilder();
                var citations = new ArrayList<Content.Citation>();
                for (var p : parts) {
                    int offset = text.length();
                    text.append(p.optString("text").orElse(""));
                    for (var a : p.objects("annotations"))
                        if ("url_citation".equals(a.optString("type").orElse(null)) && a.optString("url").isPresent())
                            citations.add(new Content.Citation(a.optString("title").orElse(""), URI.create(a.string("url")),
                                    offset + (int) a.optLong("start_index").orElse(0), offset + (int) a.optLong("end_index").orElse(0)));
                }
                yield Content.Text.of(text.toString(), citations);
            }
            case "reasoning" -> {
                var summary = item.objects("summary").stream().flatMap(s -> s.optString("text").stream()).collect(Collectors.joining("\n\n"));
                var text = summary.isEmpty() ? item.objects("content").stream().flatMap(s -> s.optString("text").stream()).collect(Collectors.joining()) : summary;
                yield Content.Reasoning.of(text.isEmpty() ? null : text, item.optString("encrypted_content").orElse(null), false,
                        item.without("encrypted_content").without("status"));
            }
            case "function_call" -> ToolCall.of(item.string("call_id"), item.string("name"), item.optString("arguments").orElse(""));
            case IMAGE -> item.optString("result").<Content>map(data -> Content.Image.of(new Content.Source.Inline(Base64.getDecoder().decode(data)),
                    "image/" + item.optString("output_format").orElse("png"), null, item.without("result"))).orElseGet(() -> Content.Unknown.of(IMAGE, item));
            case "" -> null;
            default -> Content.Unknown.of(item.string("type"), item);
        };
    }

    /// `input_tokens` includes cache reads (and a gateway's cache writes).
    private static Usage usage(JsonObject u) {
        if (u.isEmpty()) return Usage.empty();
        var b = Usage.builder().raw(u);
        u.optLong("input_tokens").ifPresent(i -> CompletionsCodec.input(b, i, u.object("input_tokens_details").optLong("cached_tokens"),
                u.object("input_tokens_details").optLong("cache_write_tokens")));
        u.optLong("output_tokens").ifPresent(b::output);
        u.object("output_tokens_details").optLong("reasoning_tokens").ifPresent(b::reasoning);
        u.optLong("total_tokens").ifPresent(b::total);
        return b.build();
    }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) {
        return new StreamDecoder() {
            @Override public List<ChatEvent> onFrame(Frame frame) {
                var event = Codecs.json(frame);
                int index = (int) event.optLong("output_index").orElse(0);
                return switch (event.optString("type").orElse(frame.event().orElse(""))) {
                    case "response.created" -> List.of(ChatEvent.Started.of(event.object("response").optString("id").orElse(null),
                            event.object("response").optString("model").orElse(null)));
                    case "response.output_item.added" -> {
                        var item = event.object("item");
                        yield "function_call".equals(item.optString("type").orElse(null))
                                ? List.of(new ChatEvent.ToolCallStart(index, item.string("call_id"), item.string("name"))) : List.of();
                    }
                    case "response.output_text.delta" -> List.of(new ChatEvent.TextDelta(index, event.string("delta")));
                    case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                            List.of(new ChatEvent.ReasoningDelta(index, event.string("delta")));
                    case "response.function_call_arguments.delta" -> List.of(new ChatEvent.ToolCallDelta(index, event.string("delta"), Json.object()));
                    case "response.output_item.done" -> {
                        var part = part(event.object("item"));
                        yield part == null ? List.of() : List.of(new ChatEvent.PartEnd(index, part));
                    }
                    case "response.completed", "response.incomplete" -> List.of(ChatEvent.Done.of(message(ctx, event.object("response")).build()));
                    case "response.failed" -> {
                        var error = event.object("response").object("error");
                        throw Codecs.streamError(error.optString("code").orElse("response_failed"), error.optString("message").orElse("no details"));
                    }
                    case "error" -> throw Codecs.streamError(event.optString("code").orElse("error"), event.optString("message").orElse("no details"));
                    default -> List.of();
                };
            }

            /// Without `response.completed` the stream ended prematurely; the core reports `stream_interrupted`.
            @Override public List<ChatEvent> onEnd() { return List.of(); }
        };
    }

    @Override public String normalizeToolCallId(String foreignId) { return Codecs.toolCallId(foreignId, 64); }

    @Override public ApiFeatures features(DecodeContext ctx) {
        var compat = ctx.compat(OpenAiResponsesCompat.defaults());
        return new ApiFeatures(ID, compat.maxOutputTokens() ? ApiFeatures.OutputCap.ENFORCED : ApiFeatures.OutputCap.UNSUPPORTED, MIN_OUTPUT,
                ApiFeatures.PromptCache.AUTOMATIC, 0,
                NO_CACHE_RETENTION.matcher(ctx.model().id()).matches() ? Set.of(CacheRetention.SHORT) : Set.of(CacheRetention.SHORT, CacheRetention.LONG),
                compat.streamingOnly(), true, true, Set.of("input", "output", "reasoning", "cache_read", "cache_write"), false, true, true, true);
    }

    /// `responses/input_tokens` takes the members that make up the prompt.
    @Override public Optional<HttpCall> countRequest(HttpCall request) {
        if (!(request.body().orElse(null) instanceof JsonObject body)) return Optional.empty();
        var counted = new LinkedHashMap<String, Object>();
        for (var member : List.of("model", "instructions", "input", "tools", "tool_choice", "parallel_tool_calls", "reasoning", "text", "previous_response_id"))
            body.get(member).ifPresent(v -> counted.put(member, v));
        return Optional.of(HttpCall.post("responses/input_tokens", Json.valueOf(counted)).withHeaders(request.headers()));
    }

    @Override public OptionalLong countReply(HttpReply reply) {
        return reply.json() instanceof JsonObject json ? json.optLong("input_tokens") : OptionalLong.empty();
    }
}
