package net.ai.gate.error;

import org.jspecify.annotations.Nullable;

/// The connection failed: `connect_failed` (before send, retried), `stream_interrupted` and `outcome_unknown` (after send, never retried).
public final class TransportException extends LlmException {
    public TransportException(Details details) { super(details); }

    public TransportException(Details details, @Nullable Throwable cause) { super(details, cause); }
}
