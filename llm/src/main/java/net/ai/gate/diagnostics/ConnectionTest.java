package net.ai.gate.diagnostics;

import java.time.Duration;
import java.util.OptionalInt;

import net.ai.gate.internal.validation.Checks;
import org.jspecify.annotations.Nullable;

/// Immutable settings of `llm.test(model, …)`. Non-billable by default: no inference unless requested. The probes
/// ([Builder#usageFields()], [Builder#toolRoundTrip()], [Builder#cacheRoundTrip()]) are billable and establish by
/// experiment what an endpoint — a gateway above all — really does.
public final class ConnectionTest {
    private static final ConnectionTest DEFAULTS = new Builder().build();

    private final @Nullable Integer inferenceProbe;
    private final Duration timeout;
    private final boolean usageFields, toolRoundTrip, cacheRoundTrip;

    private ConnectionTest(Builder b) {
        inferenceProbe = b.inferenceProbe; timeout = b.timeout;
        usageFields = b.usageFields; toolRoundTrip = b.toolRoundTrip; cacheRoundTrip = b.cacheRoundTrip;
    }

    public static ConnectionTest defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    /// The output limit of an explicitly requested — billable — inference probe.
    public OptionalInt inferenceProbe() { return inferenceProbe == null ? OptionalInt.empty() : OptionalInt.of(inferenceProbe); }
    /// Default 15 s.
    public Duration timeout() { return timeout; }
    public boolean usageFields() { return usageFields; }
    public boolean toolRoundTrip() { return toolRoundTrip; }
    public boolean cacheRoundTrip() { return cacheRoundTrip; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable Integer inferenceProbe;
        private Duration timeout = Duration.ofSeconds(15);
        private boolean usageFields, toolRoundTrip, cacheRoundTrip;

        private Builder() { }

        /// Explicitly billable; off by default.
        public Builder inferenceProbe(int maxOutputTokens) { inferenceProbe = Checks.positive(maxOutputTokens, "maxOutputTokens"); return this; }
        public Builder timeout(Duration value) { timeout = Checks.positive(value, "Timeout"); return this; }
        /// Billable: which usage buckets a reply reports (`USAGE`); fails when input or output is missing.
        public Builder usageFields() { usageFields = true; return this; }
        /// Billable, two calls: a forced tool call and its result (`TOOLS`).
        public Builder toolRoundTrip() { toolRoundTrip = true; return this; }
        /// Billable, two calls over a prefix long enough to cache: whether the second reports a cache read (`CACHE`).
        public Builder cacheRoundTrip() { cacheRoundTrip = true; return this; }
        public ConnectionTest build() { return new ConnectionTest(this); }
    }
}
