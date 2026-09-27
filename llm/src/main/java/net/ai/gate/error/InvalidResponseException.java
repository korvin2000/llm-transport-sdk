package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// A reply that cannot be used: `malformed_response`, `invalid_tool_arguments`, `output_invalid`, `output_truncated`, `output_refused`. `partial()` keeps the reply.
public final class InvalidResponseException extends LlmException {
    public InvalidResponseException(Details details) { super(details); }

    public InvalidResponseException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
