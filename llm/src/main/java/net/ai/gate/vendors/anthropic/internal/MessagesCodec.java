package net.ai.gate.vendors.anthropic.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.CacheBreakpoint;
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
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
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
import net.ai.gate.vendors.anthropic.AnthropicCompat;
import net.ai.gate.vendors.anthropic.AnthropicOptions;

/// Anthropic Messages (`POST messages`), revision `2023-06-01` (the `anthropic-version` header).
///
/// Reasoning: Claude models after the 4.5 generation think adaptively (`thinking.type: adaptive` with
/// `output_config.effort`); earlier ones, compatible endpoints and an explicit `AnthropicOptions.thinkingBudget`
/// use a token budget, with `max_tokens` raised above it. Budget thinking cannot force a tool, and neither can
/// Opus 5.5, Fable 5.1 or Mythos 5.1: `required`/`only` become `auto` there. Raising `max_tokens` above a budget is
/// an adaptation (`strict` fails), and the effective limit is reported. Prompt caching marks at most four blocks —
/// the explicit breakpoints, each with its own TTL, or automatically the last tool, the system prompt and the last
/// message — and a 1-hour marker may not follow a 5-minute one.
///
/// Usage: `message_start` and `message_delta` usage arrive as `UsageUpdate`s; cache writes are split into the 5-minute
/// and 1-hour classes when `cache_creation` reports them; absent counters stay absent; where `usage.iterations`
/// reports the summarisation and message calls of a compaction, the buckets are their sums. Input tokens are
/// counted by `messages/count_tokens`. Replay keeps text, signed and redacted thinking, tool use, compaction and
/// unknown blocks; citations replay as plain text.
///
/// Compaction on demand (`compaction: {type: summarize}` under the `compact-2026-09-04` beta) answers with one signed
/// `compaction` block and `stop_reason: compaction`; it replays first in `messages`, verbatim, under the same beta.
/// A block without a signature comes from threshold compaction (`compact-2026-01-12`) and replays under that beta.
/// See [compaction on demand](https://platform.claude.com/docs/en/build-with-claude/compaction-on-demand).
///
/// Citations cover their whole text block (`0..length`); document citations have no URL and point at
/// `document:<index>`, search results without one at `search-result:<index>`. Cited text replays as plain text.
/// See [extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking) and
/// [citations](https://platform.claude.com/docs/en/build-with-claude/citations).
public final class MessagesCodec implements WireApi {
    public static final MessagesCodec INSTANCE = new MessagesCodec();
    private static final String ID = "anthropic-messages", REVISION = "2023-06-01";
    private static final Pattern BUDGET_MODELS = Pattern.compile("claude-3.*|claude-(opus|sonnet|haiku)-4(-[0-5])?(-\\d{8})?");
    private static final Pattern NO_FORCED_TOOLS = Pattern.compile("claude-(opus-5-5|fable-5-1|mythos-5-1).*");
    private static final int MAX_CACHE_MARKERS = 4, ANSWER_ROOM = 4096, FALLBACK_MAX_TOKENS = 8192;
    private static final String COMPACT_BETA = "compact-2026-09-04", COMPACT_THRESHOLD_BETA = "compact-2026-01-12";
    /// Hosted tool name → wire type and name, with the beta it needs.
    private static final Map<String, List<String>> HOSTED = Map.of(
            "web_search", List.of("web_search_20250305", "web_search", ""),
            "web_fetch", List.of("web_fetch_20250910", "web_fetch", ""),
            "code_execution", List.of("code_execution_20250825", "code_execution", ""),
            "bash", List.of("bash_20250124", "bash", ""),
            "text_editor", List.of("text_editor_20250728", "str_replace_based_edit_tool", ""),
            "computer", List.of("computer_20251124", "computer", "computer-use-2025-11-24"));

    private MessagesCodec() { }

    @Override public String id() { return ID; }

    @Override public String revision() { return REVISION; }

    /// Blocks stay mutable until cache markers are placed.
    private record Turn(String role, List<JsonObject> blocks) { }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) { return request(request, ctx, false); }

    /// `POST messages` with `compaction: {type: summarize}` under the `compact-2026-09-04` beta. What the API rejects
    /// on a summarisation call — stop sequences, a forced tool, an output format, streaming — is left out with a
    /// warning. Empty for endpoints without beta headers.
    @Override public Optional<HttpCall> compactRequest(ApiRequest request, EncodeContext ctx) {
        return ctx.compat(AnthropicCompat.defaults()).betaHeaders() ? Optional.of(request(request, ctx, true)) : Optional.empty();
    }

    private HttpCall request(ApiRequest request, EncodeContext ctx, boolean compaction) {
        var compat = ctx.compat(AnthropicCompat.defaults());
        var o = request.options();
        var extra = o.provider(AnthropicOptions.class);
        var conversation = request.conversation();
        var betas = new LinkedHashSet<>(extra.map(AnthropicOptions::betas).orElse(List.of()));

        var system = new ArrayList<JsonObject>();
        conversation.system().filter(s -> !s.isEmpty()).ifPresent(s -> system.add(Json.object("type", "text", "text", s)));
        var turns = new ArrayList<Turn>();
        for (var message : conversation.messages()) {
            turns.add(switch (message) {
                case UserMessage u -> new Turn("user", new ArrayList<>(u.content().stream().map(MessagesCodec::block).filter(Objects::nonNull).toList()));
                case AssistantMessage a -> new Turn("assistant", assistant(a));
                case ToolResultMessage r -> new Turn("user", new ArrayList<>(r.results().stream().map(MessagesCodec::result).toList()));
            });
        }
        for (var message : conversation.messages())   // a summary replays under the beta that produced it
            if (message instanceof AssistantMessage a) a.compaction().ifPresent(s -> betas.add(
                    s.providerData() instanceof JsonObject raw && raw.optString("signature").isPresent() ? COMPACT_BETA : COMPACT_THRESHOLD_BETA));
        var tools = new ArrayList<JsonObject>();
        for (var tool : conversation.tools()) {
            switch (tool) {
                case FunctionTool f -> {
                    var t = Json.object("name", f.name());
                    if (f.description().isPresent()) t = t.with("description", f.description().get());
                    tools.add(t.with("input_schema", f.parameters().asJson()));
                }
                case ProviderTool p -> {
                    var hosted = HOSTED.get(p.name());
                    if (hosted == null) { tools.add(p.config()); continue; }
                    var t = Json.object("type", hosted.get(0), "name", hosted.get(1));
                    for (var member : p.config().members().entrySet()) t = t.with(member.getKey(), member.getValue());
                    tools.add(t);
                    if (!hosted.get(2).isEmpty()) betas.add(hosted.get(2));
                }
            }
        }
        cacheMarkers(o.cacheRetention().orElse(CacheRetention.SHORT), conversation.cacheBreakpointsWithRetention(), system, tools, turns, compat, ctx);

        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model().id());
        int maxTokens = o.maxTokens().orElseGet(() -> ctx.defaultMaxTokens().orElse(FALLBACK_MAX_TOKENS));
        body.put("max_tokens", maxTokens);
        ctx.outputLimit(maxTokens);
        if (!system.isEmpty()) body.put("system", system);
        body.put("messages", turns.stream().map(t -> Json.object("role", t.role(), "content", t.blocks())).toList());
        if (request.streaming() && !compaction) body.put("stream", true);
        if (!o.stop().isEmpty()) {
            if (compaction) ctx.warn(new Warning("option_dropped", "stop sequences do not apply to a compaction request; they were not sent"));
            else body.put("stop_sequences", o.stop());
        }

        var outputConfig = new LinkedHashMap<String, Object>();
        var level = o.reasoning().filter(l -> l != ReasoningLevel.OFF).orElse(null);
        var budget = extra.map(AnthropicOptions::thinkingBudget).filter(OptionalInt::isPresent).map(OptionalInt::getAsInt);
        boolean thinking = level != null || budget.isPresent();
        boolean adaptive = thinking && budget.isEmpty() && request.model().id().startsWith("claude-") && !BUDGET_MODELS.matcher(request.model().id()).matches();
        if (adaptive) {
            body.put("thinking", Json.object("type", "adaptive"));
            outputConfig.put("effort", switch (level) {
                case MINIMAL, LOW -> "low";
                case MEDIUM -> "medium";
                case XHIGH -> "xhigh";
                case MAX -> "max";
                case null, default -> "high";
            });
        } else if (thinking) {
            int tokens = budget.orElseGet(() -> switch (level) {
                case MINIMAL -> 1024;
                case LOW -> 4096;
                case MEDIUM -> 8192;
                case XHIGH -> 32768;
                case MAX -> 65536;
                case null, default -> 16384;
            });
            if (maxTokens <= tokens) {
                long cap = request.model().maxOutputTokens().orElse(Long.MAX_VALUE);
                int raised = (int) Math.min(cap, (long) tokens + ANSWER_ROOM);
                if (raised <= tokens) tokens = Math.max(1024, raised - ANSWER_ROOM);
                ctx.adapt(new Warning("option_adapted", "max_tokens " + maxTokens + " → " + raised + " to leave room above the thinking budget of " + tokens));
                body.put("max_tokens", raised);
                ctx.outputLimit(raised);
            }
            body.put("thinking", Json.object("type", "enabled", "budget_tokens", tokens));
        }
        if (Codecs.sampling(request, ctx, thinking)) {
            o.temperature().ifPresent(t -> {
                if (t > 1) ctx.adapt(new Warning("option_adapted", "temperature " + t + " → 1 (the Messages API accepts [0, 1])"));
                body.put("temperature", Math.min(1, t));
            });
            o.topP().ifPresent(p -> body.put("top_p", p));
            o.topK().ifPresent(k -> body.put("top_k", k));
        }
        if (o.seed().isPresent()) ctx.adapt(new Warning("option_dropped", "seed is not part of the Messages API; it was not sent"));

        if (!tools.isEmpty()) {
            body.put("tools", tools);
            var requested = o.toolChoice().orElse(null);
            if (compaction && (requested instanceof ToolChoice.Required || requested instanceof ToolChoice.Only)) {
                ctx.warn(new Warning("option_dropped", "a forced tool does not apply to a compaction request; tool_choice was not sent"));
                requested = null;
            }
            if ((requested instanceof ToolChoice.Required || requested instanceof ToolChoice.Only)
                    && (thinking && !adaptive || NO_FORCED_TOOLS.matcher(request.model().id()).matches())) {
                ctx.adapt(new Warning("option_adapted", "tool_choice " + (requested instanceof ToolChoice.Only only ? only.toolName() : "required") + " → auto: " + request.model().id() + " cannot force a tool"
                        + (thinking && !adaptive ? " while thinking with a budget" : "")));
                requested = ToolChoice.auto();
            }
            var choice = Optional.ofNullable(requested).map(c -> switch (c) {
                case ToolChoice.Auto _ -> Json.object("type", "auto");
                case ToolChoice.None _ -> Json.object("type", "none");
                case ToolChoice.Required _ -> Json.object("type", "any");
                case ToolChoice.Only only -> Json.object("type", "tool", "name", only.toolName());
            });
            if (!o.parallelToolCalls().orElse(true))
                choice = Optional.of(choice.orElse(Json.object("type", "auto")).with("disable_parallel_tool_use", true));
            choice.ifPresent(c -> body.put("tool_choice", c));
        }
        o.output().ifPresent(format -> {
            if (compaction) {
                if (!(format instanceof OutputFormat.PlainText))
                    ctx.warn(new Warning("option_dropped", "an output format does not apply to a compaction request; it was not sent"));
                return;
            }
            if (format instanceof OutputFormat.AnyJson)
                ctx.adapt(new Warning("option_dropped", "the Messages API has no JSON mode; use a schema for structured output"));
            Codecs.schema(format, ctx.json()).ifPresent(s -> outputConfig.put("format", Json.object("type", "json_schema", "schema", s.schema().asJson())));
        });
        if (!outputConfig.isEmpty()) body.put("output_config", outputConfig);
        if (compaction) {
            var summarize = Json.object("type", "summarize");
            body.put("compaction", extra.flatMap(AnthropicOptions::compactionInstructions).map(i -> summarize.with("instructions", i)).orElse(summarize));
            betas.add(COMPACT_BETA);
        }
        extra.flatMap(AnthropicOptions::metadataUserId).ifPresent(id -> body.put("metadata", Json.object("user_id", id)));

        var version = extra.flatMap(AnthropicOptions::apiVersion).orElse(REVISION);
        if (!version.equals(REVISION)) ctx.warn(new Warning("untested_api_version", "anthropic-version " + version + " is not the tested " + REVISION));
        var call = HttpCall.post("messages", Json.valueOf(body)).withHeader("anthropic-version", version);
        if (betas.isEmpty()) return call;
        if (compat.betaHeaders()) return call.withHeader("anthropic-beta", String.join(",", betas));
        ctx.adapt(new Warning("option_dropped", "the endpoint accepts no anthropic-beta headers: " + betas + " were not sent"));
        return call;
    }

    /// Explicit breakpoints — `0` marks the end of system and tools — each with its retention or the call's, or
    /// automatic placement with the call's; at most four markers, 1-hour ones before 5-minute ones. Every fallback
    /// is an adaptation: it changes what the cache costs.
    private static void cacheMarkers(CacheRetention callRetention, List<CacheBreakpoint> breakpoints, List<JsonObject> system,
                                     List<JsonObject> tools, List<Turn> turns, AnthropicCompat compat, EncodeContext ctx) {
        record Target(List<JsonObject> blocks, CacheRetention retention) { }
        var targets = new ArrayList<Target>();
        if (breakpoints.isEmpty()) {
            targets.add(new Target(tools, callRetention));
            targets.add(new Target(system, callRetention));
            if (!turns.isEmpty()) targets.add(new Target(turns.getLast().blocks(), callRetention));
        } else {
            for (var b : breakpoints) targets.add(new Target(b.index() == 0 ? (system.isEmpty() ? tools : system) : turns.get(b.index() - 1).blocks(),
                    b.retention() != null ? b.retention() : callRetention));
        }
        int placed = 0;
        boolean shortPlaced = false;
        for (var target : targets) {
            var retention = target.retention();
            if (retention == CacheRetention.NONE) continue;
            if (placed == MAX_CACHE_MARKERS) {
                ctx.adapt(new Warning("cache_hint_ignored", "the Messages API accepts at most " + MAX_CACHE_MARKERS + " cache markers; the rest were not sent"));
                return;
            }
            if (retention == CacheRetention.LONG && !compat.cacheTtl()) {
                ctx.adapt(new Warning("cache_hint_ignored", "the endpoint has no extended cache TTL; the default applies"));
                retention = CacheRetention.SHORT;
            } else if (retention == CacheRetention.LONG && shortPlaced) {
                ctx.adapt(new Warning("cache_hint_ignored", "a 1-hour cache marker cannot follow a 5-minute one; it was sent with the default TTL"));
                retention = CacheRetention.SHORT;
            }
            var marker = retention == CacheRetention.LONG ? Json.object("type", "ephemeral", "ttl", "1h") : Json.object("type", "ephemeral");
            if (mark(target.blocks(), marker)) {
                placed++;
                shortPlaced |= retention == CacheRetention.SHORT;
            }
        }
    }

    /// Marks the last block that accepts `cache_control` (thinking blocks do not).
    private static boolean mark(List<JsonObject> blocks, JsonObject marker) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            var type = blocks.get(i).optString("type").orElse("");
            if (type.endsWith("thinking")) continue;
            blocks.set(i, blocks.get(i).with("cache_control", marker));
            return true;
        }
        return false;
    }

    private static @Nullable JsonObject block(Content part) {
        return switch (part) {
            case Content.Text t -> t.text().isEmpty() ? null : Json.object("type", "text", "text", t.text());
            case Content.Image i -> Json.object("type", "image", "source", source(i.source(), i.mediaType()));
            case Content.Document d -> {
                var doc = d.mediaType().startsWith("text/") && (d.source() instanceof Content.Source.Inline || d.source() instanceof Content.Source.Local)
                        ? Json.object("type", "document", "source", Json.object("type", "text", "media_type", "text/plain",
                                "data", new String(Codecs.bytes(d.source()), StandardCharsets.UTF_8)))
                        : Json.object("type", "document", "source", source(d.source(), d.mediaType()));
                yield d.title().map(title -> doc.with("title", title)).orElse(doc);
            }
            default -> throw new IllegalArgumentException("The Messages API cannot take " + part + " in a user turn");
        };
    }

    private static JsonObject source(Content.Source source, String mediaType) {
        return switch (source) {
            case Content.Source.Remote r -> Json.object("type", "url", "url", r.url().toString());
            case Content.Source.Ref r -> Json.object("type", "file", "file_id", r.fileId());
            default -> Json.object("type", "base64", "media_type", mediaType, "data", Codecs.base64(source));
        };
    }

    private static ArrayList<JsonObject> assistant(AssistantMessage a) {
        var blocks = new ArrayList<JsonObject>();
        for (var part : a.content()) {
            switch (part) {
                case Content.Text t when !t.text().isEmpty() -> blocks.add(Json.object("type", "text", "text", t.text()));
                case Content.Refusal r -> blocks.add(Json.object("type", "text", "text", r.text()));
                case Content.Reasoning r when r.redacted() && r.signature().isPresent() ->
                        blocks.add(Json.object("type", "redacted_thinking", "data", r.signature().get()));
                case Content.Reasoning r when r.signature().isPresent() ->
                        blocks.add(Json.object("type", "thinking", "thinking", r.text().orElse(""), "signature", r.signature().get()));
                case ToolCall c -> blocks.add(Json.object("type", "tool_use", "id", c.id(), "name", c.name(), "input", input(c)));
                case Content.Compaction s when s.providerData() instanceof JsonObject raw -> blocks.add(raw);
                case Content.Unknown u when u.raw() instanceof JsonObject raw -> blocks.add(raw);
                default -> { }   // unsigned reasoning cannot be replayed; media has no assistant form
            }
        }
        return blocks;
    }

    /// Truncated arguments in a stored history must not make every later call fail.
    private static JsonObject input(ToolCall call) {
        try {
            return call.arguments();
        } catch (RuntimeException e) {
            return Json.object();
        }
    }

    private static JsonObject result(ToolResult r) {
        var content = r.content().stream().map(MessagesCodec::block).filter(Objects::nonNull).toList();
        var block = Json.object("type", "tool_result", "tool_use_id", r.callId(), "content", content);
        return r.isError() ? block.with("is_error", true) : block;
    }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) {
        var json = (JsonObject) reply.json();
        var b = AssistantMessage.builder(ctx.model().ref(), ID).stopReason(stop(json.optString("stop_reason").orElse(null)))
                .usage(usage(json.object("usage"))).responseId(json.optString("id").orElse(null)).responseModel(json.optString("model").orElse(null));
        int position = 0;
        for (var block : json.objects("content"))
            b.add(part(block, position++, block.optString("text").orElse(""), block.optString("thinking").orElse(null), null));
        return b.build();
    }

    private static String callId(JsonObject block, int position) { return Codecs.callId(block.optString("id").orElse(null), position); }

    /// One content block; streamed blocks pass their accumulated text, thinking and tool input.
    private static Content part(JsonObject block, int position, String text, @Nullable String thinking, @Nullable String inputJson) {
        return switch (block.optString("type").orElse("")) {
            case "text" -> Content.Text.of(text, block.objects("citations").stream()
                    .map(c -> new Content.Citation(c.optString("title").or(() -> c.optString("document_title")).orElse(""), source(c), 0, text.length())).toList());
            case "thinking" -> Content.Reasoning.of(thinking, block.optString("signature").orElse(null), false, JsonNull.INSTANCE);
            case "redacted_thinking" -> Content.Reasoning.of(null, block.optString("data").orElse(null), true, JsonNull.INSTANCE);
            case "tool_use" -> inputJson != null ? ToolCall.of(callId(block, position), block.string("name"), inputJson)
                                                 : ToolCall.of(callId(block, position), block.string("name"), block.object("input"));
            case "compaction" -> Content.Compaction.of(block.optString("content").orElse(null), block);   // replayed as returned
            default -> Content.Unknown.of(block.optString("type").orElse("unknown"),
                    inputJson == null || inputJson.isBlank() ? block : block.with("input", Json.parse(inputJson)));
        };
    }

    /// The citation's URL, else `document:<index>` or `search-result:<index>`.
    private static URI source(JsonObject citation) {
        boolean search = "search_result_location".equals(citation.optString("type").orElse(null));
        try {
            var url = citation.optString(search ? "source" : "url").orElse("");
            if (!url.isEmpty()) return URI.create(url);
        } catch (IllegalArgumentException e) {
            // a source title rather than a URL
        }
        return URI.create(search ? "search-result:" + citation.optLong("search_result_index").orElse(0) : "document:" + citation.optLong("document_index").orElse(0));
    }

    private static StopReason stop(@Nullable String reason) {
        return reason == null ? StopReason.STOP : switch (reason) {
            case "end_turn", "stop_sequence" -> StopReason.STOP;
            case "max_tokens", "model_context_window_exceeded" -> StopReason.LENGTH;
            case "tool_use" -> StopReason.TOOL_USE;
            case "refusal" -> StopReason.REFUSAL;
            case "compaction" -> StopReason.COMPACTION;
            default -> StopReason.of(reason);
        };
    }

    /// `input_tokens` already excludes cache reads and writes; `cache_creation` splits writes by TTL. With
    /// `iterations` (compaction), the top-level counters leave the summarisation out: every bucket is the sum over
    /// the iterations that report it.
    private static Usage usage(JsonObject u) {
        if (u.isEmpty()) return Usage.empty();
        var b = Usage.builder().raw(u);
        var iterations = u.objects("iterations");
        summed(iterations, u, "input_tokens").ifPresent(b::input);
        summed(iterations, u, "cache_read_input_tokens").ifPresent(b::cacheRead);
        summed(iterations, u, "cache_creation_input_tokens").ifPresent(b::cacheWrite);
        summed(iterations, u, "output_tokens").ifPresent(b::output);
        var classes = u.object("cache_creation");
        classes.optLong("ephemeral_5m_input_tokens").ifPresent(n -> b.cacheWrite(CacheRetention.SHORT, n));
        classes.optLong("ephemeral_1h_input_tokens").ifPresent(n -> b.cacheWrite(CacheRetention.LONG, n));
        return b.build();
    }

    /// The bucket summed over the iterations that report it, else the top-level counter.
    private static OptionalLong summed(List<JsonObject> iterations, JsonObject usage, String bucket) {
        long sum = 0;
        boolean reported = false;
        for (var iteration : iterations) {
            var value = iteration.optLong(bucket);
            if (value.isPresent()) { sum += value.getAsLong(); reported = true; }
        }
        return reported ? OptionalLong.of(sum) : usage.optLong(bucket);
    }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) {
        return new StreamDecoder() {
            private final class Block {
                JsonObject start;
                final StringBuilder text = new StringBuilder(), input = new StringBuilder();
                Block(JsonObject start) { this.start = start; }
            }

            private final Map<Integer, Block> blocks = new HashMap<>();
            private JsonObject usage = Json.object();
            private @Nullable String id, model, stopReason;

            @Override public List<ChatEvent> onFrame(Frame frame) {
                var event = Codecs.json(frame);
                int index = (int) event.optLong("index").orElse(0);
                return switch (event.optString("type").orElse(frame.event().orElse(""))) {
                    case "message_start" -> {
                        var message = event.object("message");
                        id = message.optString("id").orElse(null);
                        model = message.optString("model").orElse(null);
                        usage = message.object("usage");
                        yield usage.isEmpty() ? List.of(ChatEvent.Started.of(id, model))
                                : List.of(ChatEvent.Started.of(id, model), new ChatEvent.UsageUpdate(usage(usage)));
                    }
                    case "content_block_start" -> {
                        var block = event.object("content_block");
                        blocks.put(index, new Block(block));
                        yield "tool_use".equals(block.optString("type").orElse(null))
                                ? List.of(new ChatEvent.ToolCallStart(index, callId(block, index), block.string("name"))) : List.of();
                    }
                    case "content_block_delta" -> delta(index, event.object("delta"));
                    case "content_block_stop" -> {
                        var block = blocks.remove(index);
                        var type = block == null ? "" : block.start.optString("type").orElse("");
                        if (block == null || type.equals("tool_use") || type.equals("text") && block.start.array("citations").isEmpty()) yield List.of();
                        yield List.of(new ChatEvent.PartEnd(index, type.equals("text") ? part(block.start, index, block.text.toString(), null, null)
                                : part(block.start, index, "", block.text.toString(), block.input.toString())));
                    }
                    case "message_delta" -> {
                        event.object("delta").optString("stop_reason").ifPresent(r -> stopReason = r);
                        var delta = event.object("usage");
                        for (var member : delta.members().entrySet()) usage = usage.with(member.getKey(), member.getValue());
                        yield delta.isEmpty() ? List.of() : List.of(new ChatEvent.UsageUpdate(usage(usage)));
                    }
                    case "message_stop" -> List.of(ChatEvent.Done.of(AssistantMessage.builder(ctx.model().ref(), ID).stopReason(stop(stopReason))
                            .usage(usage(usage)).responseId(id).responseModel(model).build()));
                    case "error" -> {
                        var error = event.object("error");
                        throw Codecs.streamError(error.optString("type").orElse("error"), error.optString("message").orElse("no details"));
                    }
                    default -> List.of();   // ping and future events
                };
            }

            private List<ChatEvent> delta(int index, JsonObject delta) {
                var block = blocks.get(index);
                return switch (delta.optString("type").orElse("")) {
                    case "text_delta" -> {
                        if (block != null) block.text.append(delta.string("text"));   // for the cited block's authoritative part
                        yield List.of(new ChatEvent.TextDelta(index, delta.string("text")));
                    }
                    case "citations_delta" -> {
                        if (block != null) block.start = block.start.with("citations",
                                Stream.concat(block.start.array("citations").stream(), Stream.of(delta.object("citation"))).toList());
                        yield List.of();
                    }
                    case "thinking_delta" -> {
                        if (block != null) block.text.append(delta.string("thinking"));
                        yield List.of(new ChatEvent.ReasoningDelta(index, delta.string("thinking")));
                    }
                    case "signature_delta" -> {
                        if (block != null) block.start = block.start.with("signature", delta.string("signature"));
                        yield List.of();
                    }
                    case "compaction_delta" -> {   // threshold compaction streams the whole summary in one delta
                        if (block != null) block.start = block.start.with("content", delta.string("content"));
                        yield List.of();
                    }
                    case "input_json_delta" -> {
                        if (block != null && "tool_use".equals(block.start.optString("type").orElse(null)))
                            yield List.of(new ChatEvent.ToolCallDelta(index, delta.string("partial_json"), Json.object()));
                        if (block != null) block.input.append(delta.string("partial_json"));
                        yield List.of();
                    }
                    default -> List.of();
                };
            }

            /// Without `message_stop` the stream ended prematurely; the core reports `stream_interrupted`.
            @Override public List<ChatEvent> onEnd() { return List.of(); }
        };
    }

    /// Tool-use ids must match `^[a-zA-Z0-9_-]{1,64}$`: foreign ids (OpenAI's reach 450+ characters) are rewritten
    /// deterministically, so a call and its result map to the same id.
    @Override public String normalizeToolCallId(String foreignId) { return Codecs.toolCallId(foreignId, 64); }

    @Override public ApiFeatures features(DecodeContext ctx) {
        var compat = ctx.compat(AnthropicCompat.defaults());
        return new ApiFeatures(ID, ApiFeatures.OutputCap.ENFORCED, 1, ApiFeatures.PromptCache.EXPLICIT_MARKERS, MAX_CACHE_MARKERS,
                compat.cacheTtl() ? Set.of(CacheRetention.SHORT, CacheRetention.LONG) : Set.of(CacheRetention.SHORT), false, true, true,
                Set.of("input", "output", "cache_read", "cache_write", "cache_write_5m", "cache_write_1h"), true, true, false, true,
                compat.betaHeaders());
    }

    /// `messages/count_tokens` takes the members that make up the prompt, with the same version and beta headers.
    @Override public Optional<HttpCall> countRequest(HttpCall request) {
        if (!(request.body().orElse(null) instanceof JsonObject body)) return Optional.empty();
        var counted = new LinkedHashMap<String, Object>();
        for (var member : List.of("model", "system", "messages", "tools", "tool_choice", "thinking"))
            body.get(member).ifPresent(v -> counted.put(member, v));
        return Optional.of(HttpCall.post("messages/count_tokens", Json.valueOf(counted)).withHeaders(request.headers()));
    }

    @Override public OptionalLong countReply(HttpReply reply) {
        return reply.json() instanceof JsonObject json ? json.optLong("input_tokens") : OptionalLong.empty();
    }
}
