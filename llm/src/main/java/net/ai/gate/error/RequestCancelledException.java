package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// The call was cancelled by its token, `close()`, an interrupt or runtime shutdown: `cancelled`.
public final class RequestCancelledException extends LlmException {
    public RequestCancelledException(Details details) { super(details); }

    public RequestCancelledException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
