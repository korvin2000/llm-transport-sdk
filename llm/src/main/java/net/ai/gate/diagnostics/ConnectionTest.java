package net.ai.gate.diagnostics;

import java.time.Duration;
import java.util.OptionalInt;

import net.ai.gate.internal.validation.Checks;
import org.jspecify.annotations.Nullable;

/// Immutable settings of `llm.test(model, …)`. Non-billable by default: no inference unless requested.
public final class ConnectionTest {
    private static final ConnectionTest DEFAULTS = new Builder().build();

    private final @Nullable Integer inferenceProbe;
    private final Duration timeout;

    private ConnectionTest(Builder b) { inferenceProbe = b.inferenceProbe; timeout = b.timeout; }

    public static ConnectionTest defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    /// The output limit of an explicitly requested — billable — inference probe.
    public OptionalInt inferenceProbe() { return inferenceProbe == null ? OptionalInt.empty() : OptionalInt.of(inferenceProbe); }
    /// Default 15 s.
    public Duration timeout() { return timeout; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable Integer inferenceProbe;
        private Duration timeout = Duration.ofSeconds(15);

        private Builder() { }

        /// Explicitly billable; off by default.
        public Builder inferenceProbe(int maxOutputTokens) { inferenceProbe = Checks.positive(maxOutputTokens, "maxOutputTokens"); return this; }
        public Builder timeout(Duration value) { timeout = Checks.positive(value, "Timeout"); return this; }
        public ConnectionTest build() { return new ConnectionTest(this); }
    }
}
