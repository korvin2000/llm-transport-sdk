package net.ai.gate.testing;

import java.time.Duration;

import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.ProviderException;
import net.ai.gate.error.RateLimitedException;
import net.ai.gate.error.RequestTimeoutException;
import net.ai.gate.error.TransportException;

/// Realistic exceptions for host tests and for `FakeProvider.fail(…)`.
public final class LlmErrors {
    private LlmErrors() { }

    public static RateLimitedException rateLimited(Duration retryAfter) {
        return new RateLimitedException(details(ErrorCode.RATE_LIMITED, "Rate limit reached", 429).retryAfter(retryAfter).retryable(true).build());
    }

    public static ProviderException overloaded() {
        return new ProviderException(details(ErrorCode.OVERLOADED, "The provider is overloaded", 529).retryable(true).build());
    }

    public static ProviderException serverError() {
        return new ProviderException(details(ErrorCode.SERVER_ERROR, "Internal server error", 500).retryable(true).build());
    }

    public static InvalidRequestException invalidRequest(String message) {
        return new InvalidRequestException(details(ErrorCode.INVALID_REQUEST, message, 400).build());
    }

    public static InvalidRequestException contextOverflow() {
        return new InvalidRequestException(details(ErrorCode.CONTEXT_OVERFLOW, "The prompt is too long for the context window", 400).build());
    }

    public static AuthenticationException invalidCredentials() {
        return new AuthenticationException(details(ErrorCode.INVALID_CREDENTIALS, "Invalid API key", 401).build());
    }

    /// Before the request was sent: retried by default.
    public static TransportException connectFailed() {
        return new TransportException(LlmException.Details.builder(ErrorCode.CONNECT_FAILED, "Connection refused").retryable(true).build());
    }

    /// After the request was sent: never retried, the outcome is unknown.
    public static TransportException connectionReset() {
        return new TransportException(LlmException.Details.builder(ErrorCode.OUTCOME_UNKNOWN, "Connection reset").outcomeUnknown(true).build());
    }

    public static RequestTimeoutException gatewayTimeout() {
        return new RequestTimeoutException(details(ErrorCode.DEADLINE_EXCEEDED, "Gateway timeout", 504).outcomeUnknown(true).build());
    }

    private static LlmException.Details.Builder details(ErrorCode code, String message, int status) {
        return LlmException.Details.builder(code, message).httpStatus(status);
    }
}
