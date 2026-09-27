package net.ai.gate.vendors.openai.internal;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;
import net.ai.gate.spi.protocol.StreamDecoder;
import net.ai.gate.spi.protocol.WireApi;

/// OpenAI Responses API (`POST responses`).
///
/// **Stub (roadmap slice 3).** Mapping to implement: `system` → `instructions`; messages → `input` items
/// (`message`, `function_call`, `function_call_output`, reasoning items with encrypted content for same-origin
/// replay); tools → function tools plus `OpenAiTools`; `maxTokens` → `max_output_tokens`; `reasoning` → `reasoning.effort`
/// (+ `OpenAiResponsesOptions.reasoningSummary`); `OutputFormat` → `text.format`; `cacheRetention`/`sessionId` →
/// retention and `prompt_cache_key`; typed SSE events (`response.output_text.delta`, `response.completed`…);
/// usage with cached and reasoning details normalized into disjoint buckets.
public final class ResponsesCodec implements WireApi {
    public static final ResponsesCodec INSTANCE = new ResponsesCodec();

    private ResponsesCodec() { }

    @Override public String id() { return "openai-responses"; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) { throw pending("encoding"); }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) { throw pending("decoding"); }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) { throw pending("stream decoding"); }

    private UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(id() + " " + what + " is not implemented yet (roadmap slice 3)");
    }
}
