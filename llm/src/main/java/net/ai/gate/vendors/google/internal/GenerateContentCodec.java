package net.ai.gate.vendors.google.internal;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.protocol.ApiRequest;
import net.ai.gate.spi.protocol.DecodeContext;
import net.ai.gate.spi.protocol.EncodeContext;
import net.ai.gate.spi.protocol.StreamDecoder;
import net.ai.gate.spi.protocol.WireApi;

/// Gemini `models/{model}:generateContent` and `:streamGenerateContent?alt=sse` (`v1beta` in the preset's base URL).
///
/// **Stub (roadmap slice 3).** Mapping to implement: `systemInstruction`; `contents` with `user`/`model` roles;
/// `functionDeclarations`; `functionCall` parts with synthesized ids and `functionResponse` parts; thought
/// signatures replayed to their origin; `thinkingConfig` from the reasoning level (`GeminiOptions` overrides);
/// `maxOutputTokens`; response MIME type and schema for `OutputFormat`; `cachedContent`; `usageMetadata` with cached
/// tokens normalized; SSE frames.
public final class GenerateContentCodec implements WireApi {
    public static final GenerateContentCodec INSTANCE = new GenerateContentCodec();

    private GenerateContentCodec() { }

    @Override public String id() { return "google-generate-content"; }

    @Override public HttpCall encode(ApiRequest request, EncodeContext ctx) { throw pending("encoding"); }

    @Override public AssistantMessage decode(HttpReply reply, DecodeContext ctx) { throw pending("decoding"); }

    @Override public StreamDecoder streamDecoder(DecodeContext ctx) { throw pending("stream decoding"); }

    private UnsupportedOperationException pending(String what) {
        return new UnsupportedOperationException(id() + " " + what + " is not implemented yet (roadmap slice 3)");
    }
}
