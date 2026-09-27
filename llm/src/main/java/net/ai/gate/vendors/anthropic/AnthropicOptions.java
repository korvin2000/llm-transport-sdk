package net.ai.gate.vendors.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.ai.gate.spi.protocol.ProviderOptions;
import org.jspecify.annotations.Nullable;

/// Immutable Messages-only request options; inert on every other API.
public final class AnthropicOptions implements ProviderOptions {
    private final @Nullable Integer thinkingBudget;
    private final List<String> betas;
    private final @Nullable String apiVersion, metadataUserId;

    private AnthropicOptions(Builder b) {
        thinkingBudget = b.thinkingBudget; betas = List.copyOf(b.betas); apiVersion = b.apiVersion; metadataUserId = b.metadataUserId;
    }

    public static Builder builder() { return new Builder(); }

    @Override public String api() { return Anthropic.MESSAGES.id(); }

    /// Overrides the budget derived from `ChatOptions.reasoning`.
    public OptionalInt thinkingBudget() { return thinkingBudget == null ? OptionalInt.empty() : OptionalInt.of(thinkingBudget); }
    /// `anthropic-beta` flags.
    public List<String> betas() { return betas; }
    /// Overrides the codec's revision; reported as `untested_api_version`.
    public Optional<String> apiVersion() { return Optional.ofNullable(apiVersion); }
    public Optional<String> metadataUserId() { return Optional.ofNullable(metadataUserId); }

    @Override public String toString() { return "AnthropicOptions[thinkingBudget=" + thinkingBudget + ", betas=" + betas + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable Integer thinkingBudget;
        private final List<String> betas = new ArrayList<>();
        private @Nullable String apiVersion, metadataUserId;

        private Builder() { }

        public Builder thinkingBudget(int tokens) {
            if (tokens < 1024) throw new IllegalArgumentException("thinkingBudget must be at least 1024 tokens: " + tokens);
            thinkingBudget = tokens;
            return this;
        }
        public Builder beta(String flag) { betas.add(flag); return this; }
        public Builder apiVersion(String version) { apiVersion = version; return this; }
        public Builder metadataUserId(String id) { metadataUserId = id; return this; }
        public AnthropicOptions build() { return new AnthropicOptions(this); }
    }
}
