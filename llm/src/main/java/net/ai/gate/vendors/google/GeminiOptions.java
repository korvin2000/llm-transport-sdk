package net.ai.gate.vendors.google;

import java.util.Optional;
import java.util.OptionalInt;

import net.ai.gate.json.JsonArray;
import net.ai.gate.spi.protocol.ProviderOptions;
import org.jspecify.annotations.Nullable;

/// Immutable `generateContent`-only request options; inert on every other API.
public final class GeminiOptions implements ProviderOptions {
    private final @Nullable Integer thinkingBudget;
    private final boolean includeThoughts;
    private final @Nullable JsonArray safetySettings;
    private final @Nullable String cachedContent;

    private GeminiOptions(Builder b) {
        thinkingBudget = b.thinkingBudget; includeThoughts = b.includeThoughts; safetySettings = b.safetySettings; cachedContent = b.cachedContent;
    }

    public static Builder builder() { return new Builder(); }

    @Override public String api() { return Gemini.GENERATE_CONTENT.id(); }

    /// Overrides the budget derived from `ChatOptions.reasoning`.
    public OptionalInt thinkingBudget() { return thinkingBudget == null ? OptionalInt.empty() : OptionalInt.of(thinkingBudget); }
    /// Return thought summaries as reasoning parts.
    public boolean includeThoughts() { return includeThoughts; }
    public Optional<JsonArray> safetySettings() { return Optional.ofNullable(safetySettings); }
    /// A `cachedContents/…` name from `Gemini.CACHES`.
    public Optional<String> cachedContent() { return Optional.ofNullable(cachedContent); }

    @Override public String toString() { return "GeminiOptions[thinkingBudget=" + thinkingBudget + ", cachedContent=" + cachedContent + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable Integer thinkingBudget;
        private boolean includeThoughts;
        private @Nullable JsonArray safetySettings;
        private @Nullable String cachedContent;

        private Builder() { }

        public Builder thinkingBudget(int tokens) { thinkingBudget = tokens; return this; }
        public Builder includeThoughts() { includeThoughts = true; return this; }
        public Builder safetySettings(JsonArray settings) { safetySettings = settings; return this; }
        public Builder cachedContent(String name) { cachedContent = name; return this; }
        public GeminiOptions build() { return new GeminiOptions(this); }
    }
}
