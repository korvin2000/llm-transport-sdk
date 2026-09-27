package net.ai.gate.vendors.anthropic;

import net.ai.gate.json.JsonObject;
import net.ai.gate.spi.protocol.ApiCompat;
import org.jspecify.annotations.Nullable;

/// Immutable flags for Anthropic-compatible endpoints, which accept the Messages format with fewer features.
public final class AnthropicCompat implements ApiCompat {
    private static final AnthropicCompat DEFAULTS = builder().build();

    private final @Nullable Boolean betaHeaders, cacheTtl;

    private AnthropicCompat(Builder b) { betaHeaders = b.betaHeaders; cacheTtl = b.cacheTtl; }

    public static AnthropicCompat defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    /// Reads [#toJson()]'s form.
    /// @throws IllegalArgumentException naming a member that is unknown or of the wrong type
    public static AnthropicCompat fromJson(JsonObject json) {
        var b = builder();
        json.members().forEach((name, value) -> {
            switch (name) {
                case "betaHeaders" -> b.betaHeaders(ApiCompat.flag(value, name));
                case "cacheTtl" -> b.cacheTtl(ApiCompat.flag(value, name));
                default -> ApiCompat.unknown(name);
            }
        });
        return b.build();
    }

    @Override public String api() { return Anthropic.MESSAGES.id(); }

    /// `anthropic-beta` headers are accepted (default true).
    public boolean betaHeaders() { return betaHeaders == null || betaHeaders; }
    /// Extended cache TTLs are accepted, so `CacheRetention.LONG` maps natively (default true).
    public boolean cacheTtl() { return cacheTtl == null || cacheTtl; }

    @Override public ApiCompat overriddenBy(ApiCompat higher) {
        if (!(higher instanceof AnthropicCompat h)) throw new IllegalArgumentException("Cannot merge " + higher + " into " + this);
        var b = builder();
        b.betaHeaders = h.betaHeaders != null ? h.betaHeaders : betaHeaders;
        b.cacheTtl = h.cacheTtl != null ? h.cacheTtl : cacheTtl;
        return b.build();
    }

    @Override public JsonObject toJson() { return ApiCompat.json("betaHeaders", betaHeaders, "cacheTtl", cacheTtl); }

    @Override public boolean equals(Object o) { return o instanceof AnthropicCompat c && toJson().equals(c.toJson()); }
    @Override public int hashCode() { return toJson().hashCode(); }
    @Override public String toString() { return "AnthropicCompat[betaHeaders=" + betaHeaders() + ", cacheTtl=" + cacheTtl() + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable Boolean betaHeaders, cacheTtl;

        private Builder() { }

        public Builder betaHeaders(boolean value) { betaHeaders = value; return this; }
        public Builder cacheTtl(boolean value) { cacheTtl = value; return this; }
        public AnthropicCompat build() { return new AnthropicCompat(this); }
    }
}
