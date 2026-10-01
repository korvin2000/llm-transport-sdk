package net.ai.gate.spi.protocol;

import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.options.OutputFormat;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.ProviderException;
import net.ai.gate.error.RateLimitedException;
import net.ai.gate.internal.http.HttpErrors;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonMapper;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.Capability;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.model.SupportLevel;
import org.jspecify.annotations.Nullable;

/// Mapping steps every codec needs: media bytes, output schemas, effort names, sampling rules, tool-call ids, JSON
/// frames and failures reported inside a stream. Pure functions.
public final class Codecs {
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]+");

    private Codecs() { }

    /// The bytes of inline or local media.
    /// @throws IllegalArgumentException for remote URLs and provider files, which have no bytes here
    public static byte[] bytes(Content.Source source) {
        return switch (source) {
            case Content.Source.Inline i -> i.data();
            case Content.Source.Local l -> l.read();
            case Content.Source.Remote _, Content.Source.Ref _ -> throw new IllegalArgumentException("This API needs the bytes of " + source);
        };
    }

    public static String base64(Content.Source source) { return Base64.getEncoder().encodeToString(bytes(source)); }

    /// The URL itself for remote media, else a `data:` URL.
    public static String url(Content.Source source, String mediaType) {
        return source instanceof Content.Source.Remote r ? r.url().toString() : "data:" + mediaType + ";base64," + base64(source);
    }

    /// The named schema of a structured-output format; empty for plain text and any JSON.
    public static Optional<OutputFormat.Schema> schema(OutputFormat format, JsonMapper mapper) {
        return switch (format) {
            case OutputFormat.Schema s -> Optional.of(s);
            case OutputFormat.Typed t -> Optional.of(new OutputFormat.Schema(t.type().getSimpleName(), mapper.schemaFor(t.type()), true));
            case OutputFormat.PlainText _, OutputFormat.AnyJson _ -> Optional.empty();
        };
    }

    /// The effort vocabulary most APIs share: `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`.
    public static String effort(ReasoningLevel level) {
        return level == ReasoningLevel.OFF ? "none" : level.name().toLowerCase(Locale.ROOT);
    }

    /// Whether `temperature`, `topP` and `topK` may be sent: not when the catalog marks the model as rejecting them
    /// ([Capability#TEMPERATURE]) or `reasoningRejects` — an API's rule for models that are reasoning — says so. Left
    /// out, they are reported as an `option_dropped` warning, never a failure.
    public static boolean sampling(ApiRequest request, EncodeContext ctx, boolean reasoningRejects) {
        var o = request.options();
        boolean rejected = reasoningRejects || request.model().capabilities().support(Capability.TEMPERATURE) == SupportLevel.UNSUPPORTED;
        if (rejected && (o.temperature().isPresent() || o.topP().isPresent() || o.topK().isPresent()))
            ctx.warn(new Warning("option_dropped", "temperature, topP and topK were not sent: " + request.model().ref()
                    + (reasoningRejects ? " does not accept them while reasoning" : " does not accept them")));
        return !rejected;
    }

    /// `foreignId` if it is `[a-zA-Z0-9_-]{1,maxLength}`, else a deterministic rewrite — so a call and its result map
    /// to the same id.
    public static String toolCallId(String foreignId, int maxLength) {
        if (foreignId.length() <= maxLength && ID.matcher(foreignId).matches()) return foreignId;
        var cleaned = foreignId.replaceAll("[^a-zA-Z0-9_-]", "_");
        var hash = "_" + Integer.toHexString(foreignId.hashCode());
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength - hash.length()) + hash;
    }

    /// `id`, or `call_<position>` when the provider sent none or a blank one (some compatible gateways stream `"id":""`):
    /// unique among the calls of one response and the same wherever that call is decoded.
    public static String callId(@Nullable String id, int position) {
        return id == null || id.isBlank() ? "call_" + position : id;
    }

    /// The JSON object of an SSE or NDJSON frame.
    /// @throws IllegalArgumentException when the data is not a JSON object
    public static JsonObject json(Frame frame) {
        if (Json.parse(frame.data()) instanceof JsonObject o) return o;
        throw new IllegalArgumentException("Expected a JSON object frame: " + frame);
    }

    /// A failure the provider reported inside a stream after a 2xx status: quotas, rate limits and overload stay typed,
    /// anything else is a server error.
    public static LlmException streamError(String type, String message) {
        var lower = type.toLowerCase(Locale.ROOT);
        var code = HttpErrors.QUOTA.contains(lower) ? ErrorCode.QUOTA_EXHAUSTED
                : lower.contains("rate_limit") || lower.contains("resource_exhausted") ? ErrorCode.RATE_LIMITED
                : lower.contains("overloaded") || lower.contains("unavailable") ? ErrorCode.OVERLOADED : ErrorCode.SERVER_ERROR;
        var details = LlmException.Details.builder(code, "Stream error " + type + ": " + message).providerCode(type).build();
        return code == ErrorCode.RATE_LIMITED || code == ErrorCode.QUOTA_EXHAUSTED ? new RateLimitedException(details) : new ProviderException(details);
    }
}
