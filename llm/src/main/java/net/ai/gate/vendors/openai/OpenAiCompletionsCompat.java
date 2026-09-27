package net.ai.gate.vendors.openai;

import java.util.Objects;

import net.ai.gate.spi.protocol.ApiCompat;
import org.jspecify.annotations.Nullable;

/// Immutable typed flags for the Chat Completions dialect family: set on presets, overridable per provider and
/// model, merged field by field. Accessors return the documented default for unset fields.
public final class OpenAiCompletionsCompat implements ApiCompat {
    /// How reasoning is requested and returned.
    public enum ReasoningFormat { OPENAI, OPENROUTER, DEEPSEEK, QWEN, ZAI, TOGETHER, CHAT_TEMPLATE, NONE }
    public enum CacheControl { NONE, ANTHROPIC_STYLE }
    public enum SessionHeader { NONE, OPENAI, OPENROUTER }

    private static final OpenAiCompletionsCompat DEFAULTS = builder().build();

    private final @Nullable String maxTokensField;
    private final @Nullable Boolean developerRole, streamUsage, strictTools, reasoningContentReplay;
    private final @Nullable ReasoningFormat reasoningFormat;
    private final @Nullable CacheControl cacheControl;
    private final @Nullable SessionHeader sessionHeader;

    private OpenAiCompletionsCompat(Builder b) {
        maxTokensField = b.maxTokensField; developerRole = b.developerRole; streamUsage = b.streamUsage; strictTools = b.strictTools;
        reasoningContentReplay = b.reasoningContentReplay; reasoningFormat = b.reasoningFormat; cacheControl = b.cacheControl;
        sessionHeader = b.sessionHeader;
    }

    /// Every flag at its documented default — what `EncodeContext.compat(…)` merges onto.
    public static OpenAiCompletionsCompat defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    @Override public String api() { return "openai-completions"; }

    /// `max_completion_tokens` (default) or `max_tokens`.
    public String maxTokensField() { return maxTokensField != null ? maxTokensField : "max_completion_tokens"; }
    /// System instructions as a `developer` message (default true), else `system`.
    public boolean developerRole() { return developerRole == null || developerRole; }
    /// Request usage in the final stream chunk (default true).
    public boolean streamUsage() { return streamUsage == null || streamUsage; }
    /// Strict JSON Schema on function tools (default true).
    public boolean strictTools() { return strictTools == null || strictTools; }
    /// Send prior `reasoning_content` back on assistant turns (default false; DeepSeek tool use needs it).
    public boolean reasoningContentReplay() { return reasoningContentReplay != null && reasoningContentReplay; }
    public ReasoningFormat reasoningFormat() { return reasoningFormat != null ? reasoningFormat : ReasoningFormat.OPENAI; }
    public CacheControl cacheControl() { return cacheControl != null ? cacheControl : CacheControl.NONE; }
    public SessionHeader sessionHeader() { return sessionHeader != null ? sessionHeader : SessionHeader.NONE; }

    @Override public ApiCompat overriddenBy(ApiCompat higher) {
        if (!(higher instanceof OpenAiCompletionsCompat h)) throw new IllegalArgumentException("Cannot merge " + higher + " into " + this);
        var b = toBuilder();
        if (h.maxTokensField != null) b.maxTokensField = h.maxTokensField;
        if (h.developerRole != null) b.developerRole = h.developerRole;
        if (h.streamUsage != null) b.streamUsage = h.streamUsage;
        if (h.strictTools != null) b.strictTools = h.strictTools;
        if (h.reasoningContentReplay != null) b.reasoningContentReplay = h.reasoningContentReplay;
        if (h.reasoningFormat != null) b.reasoningFormat = h.reasoningFormat;
        if (h.cacheControl != null) b.cacheControl = h.cacheControl;
        if (h.sessionHeader != null) b.sessionHeader = h.sessionHeader;
        return b.build();
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.maxTokensField = maxTokensField; b.developerRole = developerRole; b.streamUsage = streamUsage; b.strictTools = strictTools;
        b.reasoningContentReplay = reasoningContentReplay; b.reasoningFormat = reasoningFormat; b.cacheControl = cacheControl;
        b.sessionHeader = sessionHeader;
        return b;
    }

    @Override public boolean equals(Object o) {
        return o instanceof OpenAiCompletionsCompat c && Objects.equals(maxTokensField, c.maxTokensField)
                && Objects.equals(developerRole, c.developerRole) && Objects.equals(streamUsage, c.streamUsage)
                && Objects.equals(strictTools, c.strictTools) && Objects.equals(reasoningContentReplay, c.reasoningContentReplay)
                && reasoningFormat == c.reasoningFormat && cacheControl == c.cacheControl && sessionHeader == c.sessionHeader;
    }

    @Override public int hashCode() {
        return Objects.hash(maxTokensField, developerRole, streamUsage, strictTools, reasoningContentReplay, reasoningFormat, cacheControl, sessionHeader);
    }

    @Override public String toString() {
        return "OpenAiCompletionsCompat[maxTokensField=" + maxTokensField() + ", developerRole=" + developerRole()
                + ", reasoningFormat=" + reasoningFormat() + "]";
    }

    /// Not thread-safe. Unset fields keep the defaults.
    public static final class Builder {
        private @Nullable String maxTokensField;
        private @Nullable Boolean developerRole, streamUsage, strictTools, reasoningContentReplay;
        private @Nullable ReasoningFormat reasoningFormat;
        private @Nullable CacheControl cacheControl;
        private @Nullable SessionHeader sessionHeader;

        private Builder() { }

        public Builder maxTokensField(String field) {
            if (!field.equals("max_tokens") && !field.equals("max_completion_tokens"))
                throw new IllegalArgumentException("maxTokensField must be max_tokens or max_completion_tokens: " + field);
            maxTokensField = field;
            return this;
        }
        public Builder developerRole(boolean value) { developerRole = value; return this; }
        public Builder streamUsage(boolean value) { streamUsage = value; return this; }
        public Builder strictTools(boolean value) { strictTools = value; return this; }
        public Builder reasoningContentReplay(boolean value) { reasoningContentReplay = value; return this; }
        public Builder reasoningFormat(ReasoningFormat value) { reasoningFormat = value; return this; }
        public Builder cacheControl(CacheControl value) { cacheControl = value; return this; }
        public Builder sessionHeader(SessionHeader value) { sessionHeader = value; return this; }
        public OpenAiCompletionsCompat build() { return new OpenAiCompletionsCompat(this); }
    }
}
