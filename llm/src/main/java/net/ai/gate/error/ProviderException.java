package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// The provider failed or is overloaded: `overloaded`, `server_error`. HTTP 503 and 529 are retried by default;
/// 500 and 502 surface with `outcomeUnknown()`, as the request may have been processed.
public final class ProviderException extends LlmException {
    public ProviderException(Details details) { super(details); }

    public ProviderException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
