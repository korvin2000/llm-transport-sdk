package net.ai.gate.metadata;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/// Immutable rate-limit state parsed from response headers where the API defines them.
public final class RateLimits {
    private final OptionalLong requestsRemaining, tokensRemaining;
    private final Optional<Instant> requestsReset, tokensReset;

    private RateLimits(OptionalLong requestsRemaining, OptionalLong tokensRemaining,
                       Optional<Instant> requestsReset, Optional<Instant> tokensReset) {
        this.requestsRemaining = requestsRemaining; this.tokensRemaining = tokensRemaining;
        this.requestsReset = requestsReset; this.tokensReset = tokensReset;
    }

    /// For codecs.
    public static RateLimits of(OptionalLong requestsRemaining, OptionalLong tokensRemaining,
                                Optional<Instant> requestsReset, Optional<Instant> tokensReset) {
        return new RateLimits(requestsRemaining, tokensRemaining, requestsReset, tokensReset);
    }

    public OptionalLong requestsRemaining() { return requestsRemaining; }
    public OptionalLong tokensRemaining() { return tokensRemaining; }
    public Optional<Instant> requestsReset() { return requestsReset; }
    public Optional<Instant> tokensReset() { return tokensReset; }

    @Override public String toString() { return "RateLimits[requests=" + requestsRemaining + ", tokens=" + tokensRemaining + "]"; }
}
