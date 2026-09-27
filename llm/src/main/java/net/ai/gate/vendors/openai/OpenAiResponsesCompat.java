package net.ai.gate.vendors.openai;

import java.util.Optional;

import net.ai.gate.json.JsonObject;
import net.ai.gate.spi.protocol.ApiCompat;
import org.jspecify.annotations.Nullable;

/// Immutable typed flags for endpoints speaking a dialect of the Responses API (the ChatGPT Codex backend, gateways):
/// set on presets, overridable per provider and model, merged field by field. Accessors return the documented
/// default for unset fields.
public final class OpenAiResponsesCompat implements ApiCompat {
    private static final OpenAiResponsesCompat DEFAULTS = builder().build();

    private final @Nullable Boolean streamingOnly, maxOutputTokens, sessionHeaders;
    private final @Nullable String defaultInstructions;

    private OpenAiResponsesCompat(Builder b) {
        streamingOnly = b.streamingOnly; maxOutputTokens = b.maxOutputTokens; sessionHeaders = b.sessionHeaders;
        defaultInstructions = b.defaultInstructions;
    }

    /// Every flag at its documented default — what `EncodeContext.compat(…)` merges onto.
    public static OpenAiResponsesCompat defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    /// Reads [#toJson()]'s form.
    /// @throws IllegalArgumentException naming a member that is unknown or of the wrong type
    public static OpenAiResponsesCompat fromJson(JsonObject json) {
        var b = builder();
        json.members().forEach((name, value) -> {
            switch (name) {
                case "streamingOnly" -> b.streamingOnly(ApiCompat.flag(value, name));
                case "maxOutputTokens" -> b.maxOutputTokens(ApiCompat.flag(value, name));
                case "sessionHeaders" -> b.sessionHeaders(ApiCompat.flag(value, name));
                case "defaultInstructions" -> b.defaultInstructions(ApiCompat.text(value, name));
                default -> ApiCompat.unknown(name);
            }
        });
        return b.build();
    }

    @Override public String api() { return OpenAi.RESPONSES.id(); }

    /// The endpoint answers with events only (default false): every request streams, and `complete()` collects the
    /// events into the same reply.
    public boolean streamingOnly() { return streamingOnly != null && streamingOnly; }
    /// `max_output_tokens` is accepted (default true); otherwise a set limit is left out with a warning.
    public boolean maxOutputTokens() { return maxOutputTokens == null || maxOutputTokens; }
    /// `ChatOptions.sessionId` is also sent as the `session-id` and `x-client-request-id` headers (default false).
    public boolean sessionHeaders() { return sessionHeaders != null && sessionHeaders; }
    /// Instructions sent when the conversation has no system prompt, for endpoints that require them.
    public Optional<String> defaultInstructions() { return Optional.ofNullable(defaultInstructions); }

    @Override public ApiCompat overriddenBy(ApiCompat higher) {
        if (!(higher instanceof OpenAiResponsesCompat h)) throw new IllegalArgumentException("Cannot merge " + higher + " into " + this);
        var b = builder();
        b.streamingOnly = h.streamingOnly != null ? h.streamingOnly : streamingOnly;
        b.maxOutputTokens = h.maxOutputTokens != null ? h.maxOutputTokens : maxOutputTokens;
        b.sessionHeaders = h.sessionHeaders != null ? h.sessionHeaders : sessionHeaders;
        b.defaultInstructions = h.defaultInstructions != null ? h.defaultInstructions : defaultInstructions;
        return b.build();
    }

    @Override public JsonObject toJson() {
        return ApiCompat.json("streamingOnly", streamingOnly, "maxOutputTokens", maxOutputTokens, "sessionHeaders", sessionHeaders,
                "defaultInstructions", defaultInstructions);
    }

    @Override public boolean equals(Object o) { return o instanceof OpenAiResponsesCompat c && toJson().equals(c.toJson()); }
    @Override public int hashCode() { return toJson().hashCode(); }
    @Override public String toString() { return "OpenAiResponsesCompat" + toJson(); }

    /// Not thread-safe. Unset fields keep the defaults.
    public static final class Builder {
        private @Nullable Boolean streamingOnly, maxOutputTokens, sessionHeaders;
        private @Nullable String defaultInstructions;

        private Builder() { }

        public Builder streamingOnly(boolean value) { streamingOnly = value; return this; }
        public Builder maxOutputTokens(boolean value) { maxOutputTokens = value; return this; }
        public Builder sessionHeaders(boolean value) { sessionHeaders = value; return this; }
        public Builder defaultInstructions(String value) { defaultInstructions = value; return this; }
        public OpenAiResponsesCompat build() { return new OpenAiResponsesCompat(this); }
    }
}
