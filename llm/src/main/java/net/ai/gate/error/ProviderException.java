package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// The provider failed or is overloaded: `overloaded`, `server_error`. HTTP 500, 502, 503, 529; retried by default.
public final class ProviderException extends LlmException {
    public ProviderException(Details details) { super(details); }

    public ProviderException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
