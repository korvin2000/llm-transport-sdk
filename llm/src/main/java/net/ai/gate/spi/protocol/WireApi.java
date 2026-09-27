package net.ai.gate.spi.protocol;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.error.LlmException;
import net.ai.gate.internal.http.HttpErrors;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;

/// **SPI**. One wire protocol at one revision — the only way to add a wire format. Stateless, thread-safe and pure:
/// no I/O, credentials, retries or clocks. Constants live in provider packages (`OpenAi.RESPONSES`,
/// `Anthropic.MESSAGES`); an incompatible revision is a new constant.
///
/// | Item | `WireApi` | `StreamDecoder` |
/// |---|---|---|
/// | Thread | any; stateless | the consuming thread only |
/// | Errors | encode: `InvalidRequestException`; decode: `malformed_response` | a throw ends the stream |
/// | Blocking | never | never |
/// | Lifecycle | singleton per constant | one per stream; `onEnd()` at most once |
public interface WireApi {
    /// `openai-responses`, `openai-completions`, `anthropic-messages`, `google-generate-content`.
    String id();

    /// The protocol revision this codec was tested against, e.g. `2023-06-01`.
    default String revision() { return "1"; }

    /// Encodes one request with a URI relative to the provider's base URL (no leading `/`). Inexpressible settings:
    /// soft ones through `ctx.adapt(…)`, hard ones throw `InvalidRequestException(unsupported_feature)`.
    HttpCall encode(ApiRequest request, EncodeContext ctx);

    AssistantMessage decode(HttpReply reply, DecodeContext ctx);

    /// A fresh decoder for one stream.
    StreamDecoder streamDecoder(DecodeContext ctx);

    default StreamFormat streamFormat() { return StreamFormat.SSE; }

    /// Maps a non-2xx reply to error facts — code, provider code, retry hint, `Retry-After`, context-overflow
    /// patterns. The core picks the exception type and adds call facts, so every API's errors look alike.
    default LlmException.Details decodeError(HttpReply reply, DecodeContext ctx) { return HttpErrors.details(reply); }

    /// A tool-call id produced by another API, made acceptable to this one (hand-off).
    default String normalizeToolCallId(String foreignId) { return foreignId; }
}
