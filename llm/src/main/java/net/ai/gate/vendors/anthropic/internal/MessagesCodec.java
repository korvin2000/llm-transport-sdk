package net.ai.gate.vendors.anthropic.internal;

import java.util.regex.Pattern;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;
import net.ai.gate.spi.protocol.StreamDecoder;
import net.ai.gate.spi.protocol.WireApi;

/// Anthropic Messages (`POST messages`), revision `2023-06-01` (the `anthropic-version` header).
///
/// **Stub (roadmap slice 1).** Mapping to implement: top-level `system`; `tools[]` with `input_schema` plus
/// `AnthropicTools`; `tool_use` / `tool_result` blocks (results in a user turn); `thinking` blocks with signatures
/// replayed to their origin; `max_tokens` required — `ctx.defaultMaxTokens()`, raised to the thinking budget plus
/// answer room; reasoning level → budget (`AnthropicOptions.thinkingBudget` overrides); `cache_control` markers per
/// `cacheRetention` and breakpoints, excess markers dropped with a warning; typed SSE events; usage with cache read
/// and creation counters; `overloaded_error` (529) and overflow messages in `decodeError`.
public final class MessagesCodec implements WireApi {
    public static final MessagesCodec INSTANCE = new MessagesCodec();

    private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private MessagesCodec() { }

    @Override public String id() { return "anthropic-messages"; }

    @Override public String revision() { return "2023-06-01"; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) { throw pending("encoding"); }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) { throw pending("decoding"); }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) { throw pending("stream decoding"); }

    /// Tool-use ids must match `^[a-zA-Z0-9_-]{1,64}$`: foreign ids (OpenAI's reach 450+ characters) are rewritten
    /// deterministically, so a call and its result map to the same id.
    @Override public String normalizeToolCallId(String foreignId) {
        if (VALID_ID.matcher(foreignId).matches()) return foreignId;
        var cleaned = foreignId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 55) + "_" + Integer.toHexString(foreignId.hashCode());
    }

    private UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(id() + " " + what + " is not implemented yet (roadmap slice 1)");
    }
}
