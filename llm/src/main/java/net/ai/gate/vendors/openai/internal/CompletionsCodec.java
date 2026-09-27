package net.ai.gate.vendors.openai.internal;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;
import net.ai.gate.spi.protocol.StreamDecoder;
import net.ai.gate.spi.protocol.WireApi;

/// OpenAI Chat Completions (`POST chat/completions`) — also the dialect of most compatible providers and gateways,
/// whose differences are `OpenAiCompletionsCompat` flags, never base-URL sniffing.
///
/// **Stub (roadmap slice 1).** Mapping to implement: `system` → system or developer message (`developerRole`);
/// tools → `tools[].function`; tool calls → `tool_calls[]`, results → `tool` role messages; `maxTokens` →
/// `max_completion_tokens` or `max_tokens` (`maxTokensField`); reasoning per `reasoningFormat`, with
/// `reasoning_content` replay where flagged; `OutputFormat` → `response_format`; `cache_control` pass-through
/// (`cacheControl`); SSE chunks with `[DONE]`, stream usage per `streamUsage`; error bodies and overflow patterns.
public final class CompletionsCodec implements WireApi {
    public static final CompletionsCodec INSTANCE = new CompletionsCodec();

    private CompletionsCodec() { }

    @Override public String id() { return "openai-completions"; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) { throw pending("encoding"); }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) { throw pending("decoding"); }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) { throw pending("stream decoding"); }

    private UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(id() + " " + what + " is not implemented yet (roadmap slice 1)");
    }
}
