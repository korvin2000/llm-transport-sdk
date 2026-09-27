package net.ai.gate.vendors.anthropic;

import java.util.Objects;

import net.ai.gate.spi.protocol.ApiCompat;
import org.jspecify.annotations.Nullable;

/// Immutable flags for Anthropic-compatible endpoints, which accept the Messages format with fewer features.
public final class AnthropicCompat implements ApiCompat {
    private static final AnthropicCompat DEFAULTS = builder().build();

    private final @Nullable Boolean betaHeaders, cacheTtl;

    private AnthropicCompat(Builder b) { betaHeaders = b.betaHeaders; cacheTtl = b.cacheTtl; }

    public static AnthropicCompat defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

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

    @Override public boolean equals(Object o) { return o instanceof AnthropicCompat c && Objects.equals(betaHeaders, c.betaHeaders) && Objects.equals(cacheTtl, c.cacheTtl); }
    @Override public int hashCode() { return Objects.hash(betaHeaders, cacheTtl); }
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
