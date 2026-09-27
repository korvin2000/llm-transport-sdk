package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// A request the provider or the SDK rejects: `invalid_request`, `unsupported_feature`, `context_overflow`, `model_not_found`, `request_too_large`, `cache_miss`. Never retried.
public final class InvalidRequestException extends LlmException {
    public InvalidRequestException(Details details) { super(details); }

    public InvalidRequestException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
