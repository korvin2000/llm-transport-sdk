package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// A deadline passed: `deadline_exceeded` (total), `stream_idle_timeout`. After send the outcome is unknown.
public final class RequestTimeoutException extends LlmException {
    public RequestTimeoutException(Details details) { super(details); }

    public RequestTimeoutException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
