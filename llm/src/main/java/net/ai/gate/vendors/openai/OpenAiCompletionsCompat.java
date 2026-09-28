package net.ai.gate.vendors.openai;

import java.util.List;

import net.ai.gate.config.FieldDescriptor;
import net.ai.gate.json.JsonObject;
import net.ai.gate.spi.protocol.ApiCompat;
import org.jspecify.annotations.Nullable;

/// Immutable typed flags for the Chat Completions dialect family: set on presets, overridable per provider and
/// model, merged field by field. Accessors return the documented default for unset fields.
public final class OpenAiCompletionsCompat implements ApiCompat {
    /// How reasoning is requested and returned.
    public enum ReasoningFormat { OPENAI, OPENROUTER, DEEPSEEK, QWEN, ZAI, TOGETHER, CHAT_TEMPLATE, NONE }
    public enum CacheControl { NONE, ANTHROPIC_STYLE }
    public enum SessionHeader { NONE, OPENAI, OPENROUTER }
    /// Tool-call ids as sent: as they are, or `MISTRAL` — exactly nine letters and digits.
    public enum ToolCallIdFormat { ANY, MISTRAL }

    private static final OpenAiCompletionsCompat DEFAULTS = builder().build();

    private final @Nullable String maxTokensField;
    private final @Nullable Boolean developerRole, streamUsage, strictTools, reasoningContentReplay;
    private final @Nullable ReasoningFormat reasoningFormat;
    private final @Nullable CacheControl cacheControl;
    private final @Nullable SessionHeader sessionHeader;
    private final @Nullable ToolCallIdFormat toolCallIdFormat;

    private OpenAiCompletionsCompat(Builder b) {
        maxTokensField = b.maxTokensField; developerRole = b.developerRole; streamUsage = b.streamUsage; strictTools = b.strictTools;
        reasoningContentReplay = b.reasoningContentReplay; reasoningFormat = b.reasoningFormat; cacheControl = b.cacheControl;
        sessionHeader = b.sessionHeader; toolCallIdFormat = b.toolCallIdFormat;
    }

    /// Every flag at its documented default — what `EncodeContext.compat(…)` merges onto.
    public static OpenAiCompletionsCompat defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    /// Reads [#toJson()]'s form.
    /// @throws IllegalArgumentException naming a member that is unknown or of the wrong type
    public static OpenAiCompletionsCompat fromJson(JsonObject json) {
        var b = builder();
        json.members().forEach((name, value) -> {
            switch (name) {
                case "maxTokensField" -> b.maxTokensField(ApiCompat.text(value, name));
                case "developerRole" -> b.developerRole(ApiCompat.flag(value, name));
                case "streamUsage" -> b.streamUsage(ApiCompat.flag(value, name));
                case "strictTools" -> b.strictTools(ApiCompat.flag(value, name));
                case "reasoningContentReplay" -> b.reasoningContentReplay(ApiCompat.flag(value, name));
                case "reasoningFormat" -> b.reasoningFormat(ApiCompat.choice(value, name, ReasoningFormat.class));
                case "cacheControl" -> b.cacheControl(ApiCompat.choice(value, name, CacheControl.class));
                case "sessionHeader" -> b.sessionHeader(ApiCompat.choice(value, name, SessionHeader.class));
                case "toolCallIdFormat" -> b.toolCallIdFormat(ApiCompat.choice(value, name, ToolCallIdFormat.class));
                default -> ApiCompat.unknown(name);
            }
        });
        return b.build();
    }

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
    public ToolCallIdFormat toolCallIdFormat() { return toolCallIdFormat != null ? toolCallIdFormat : ToolCallIdFormat.ANY; }

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
        if (h.toolCallIdFormat != null) b.toolCallIdFormat = h.toolCallIdFormat;
        return b.build();
    }

    @Override public JsonObject toJson() {
        return ApiCompat.json("maxTokensField", maxTokensField, "developerRole", developerRole, "streamUsage", streamUsage,
                "strictTools", strictTools, "reasoningContentReplay", reasoningContentReplay, "reasoningFormat", reasoningFormat,
                "cacheControl", cacheControl, "sessionHeader", sessionHeader, "toolCallIdFormat", toolCallIdFormat);
    }

    @Override public List<FieldDescriptor> fields() {
        return List.of(FieldDescriptor.builder("maxTokensField", FieldDescriptor.Kind.CHOICE).label("Output limit field").group("Compatibility")
                        .choices(List.of("max_completion_tokens", "max_tokens")).defaultValue("max_completion_tokens").build(),
                ApiCompat.flagField("developerRole", "Developer role", true, "System instructions as a developer message"),
                ApiCompat.flagField("streamUsage", "Stream usage", true, "Request usage in the final stream chunk"),
                ApiCompat.flagField("strictTools", "Strict tools", true, "Strict JSON Schema on function tools"),
                ApiCompat.flagField("reasoningContentReplay", "Replay reasoning", false, "Send prior reasoning_content back on assistant turns"),
                ApiCompat.choiceField("reasoningFormat", "Reasoning format", ReasoningFormat.OPENAI, "How reasoning is requested and returned"),
                ApiCompat.choiceField("cacheControl", "Cache markers", CacheControl.NONE, "Anthropic-style cache_control on messages"),
                ApiCompat.choiceField("sessionHeader", "Session header", SessionHeader.NONE, "How the session id is sent"),
                ApiCompat.choiceField("toolCallIdFormat", "Tool-call ids", ToolCallIdFormat.ANY, "Id format the endpoint requires"));
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.maxTokensField = maxTokensField; b.developerRole = developerRole; b.streamUsage = streamUsage; b.strictTools = strictTools;
        b.reasoningContentReplay = reasoningContentReplay; b.reasoningFormat = reasoningFormat; b.cacheControl = cacheControl;
        b.sessionHeader = sessionHeader; b.toolCallIdFormat = toolCallIdFormat;
        return b;
    }

    @Override public boolean equals(Object o) { return o instanceof OpenAiCompletionsCompat c && toJson().equals(c.toJson()); }
    @Override public int hashCode() { return toJson().hashCode(); }

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
        private @Nullable ToolCallIdFormat toolCallIdFormat;

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
        public Builder toolCallIdFormat(ToolCallIdFormat value) { toolCallIdFormat = value; return this; }
        public OpenAiCompletionsCompat build() { return new OpenAiCompletionsCompat(this); }
    }
}
