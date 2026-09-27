package net.ai.gate.vendors.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.ai.gate.spi.protocol.ProviderOptions;
import org.jspecify.annotations.Nullable;

/// Immutable Responses-only request options; inert on every other API.
public final class OpenAiResponsesOptions implements ProviderOptions {
    public enum ReasoningSummary { AUTO, CONCISE, DETAILED }

    private final @Nullable String serviceTier, previousResponseId, safetyIdentifier, promptCacheKey;
    private final @Nullable ReasoningSummary reasoningSummary;
    private final @Nullable Boolean store;
    private final List<String> include;

    private OpenAiResponsesOptions(Builder b) {
        serviceTier = b.serviceTier; previousResponseId = b.previousResponseId; safetyIdentifier = b.safetyIdentifier;
        promptCacheKey = b.promptCacheKey; reasoningSummary = b.reasoningSummary; store = b.store; include = List.copyOf(b.include);
    }

    public static Builder builder() { return new Builder(); }

    @Override public String api() { return OpenAi.RESPONSES.id(); }

    /// `auto`, `default`, `flex`, `priority`.
    public Optional<String> serviceTier() { return Optional.ofNullable(serviceTier); }
    public Optional<ReasoningSummary> reasoningSummary() { return Optional.ofNullable(reasoningSummary); }
    public Optional<Boolean> store() { return Optional.ofNullable(store); }
    /// Server-side conversation state; the portable conversation is still sent unless the codec elides it.
    public Optional<String> previousResponseId() { return Optional.ofNullable(previousResponseId); }
    public Optional<String> safetyIdentifier() { return Optional.ofNullable(safetyIdentifier); }
    public List<String> include() { return include; }
    /// Overrides the key derived from `ChatOptions.sessionId`.
    public Optional<String> promptCacheKey() { return Optional.ofNullable(promptCacheKey); }

    @Override public String toString() { return "OpenAiResponsesOptions[tier=" + serviceTier + ", summary=" + reasoningSummary + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable String serviceTier, previousResponseId, safetyIdentifier, promptCacheKey;
        private @Nullable ReasoningSummary reasoningSummary;
        private @Nullable Boolean store;
        private final List<String> include = new ArrayList<>();

        private Builder() { }

        public Builder serviceTier(String tier) { serviceTier = tier; return this; }
        public Builder reasoningSummary(ReasoningSummary summary) { reasoningSummary = summary; return this; }
        public Builder store(boolean value) { store = value; return this; }
        public Builder previousResponseId(String id) { previousResponseId = id; return this; }
        public Builder safetyIdentifier(String id) { safetyIdentifier = id; return this; }
        public Builder include(String field) { include.add(field); return this; }
        public Builder promptCacheKey(String key) { promptCacheKey = key; return this; }
        public OpenAiResponsesOptions build() { return new OpenAiResponsesOptions(this); }
    }
}
