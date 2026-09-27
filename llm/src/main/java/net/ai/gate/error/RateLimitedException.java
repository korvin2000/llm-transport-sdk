package net.ai.gate.error;

import java.time.Duration;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/// Rate or quota limits: `rate_limited` (retried by default), `quota_exhausted`. HTTP 429 and 402.
public final class RateLimitedException extends LlmException {
    public RateLimitedException(Details details) { super(details); }

    public RateLimitedException(Details details, @Nullable Throwable cause) { super(details, cause); }

    /// The provider's `Retry-After`, when it sent one.
    public Optional<Duration> retryAfter() { return details().retryAfter(); }
}
