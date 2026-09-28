package net.ai.gate;

import java.util.List;
import java.util.Optional;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.error.LlmException;
import net.ai.gate.error.RequestCancelledException;
import net.ai.gate.metadata.Attempt;
import net.ai.gate.metadata.Usage;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/// How a call settled: exactly one of `reply` and `error`, with every attempt; the other facts derive from those.
/// @param reply the complete reply, when the call finished normally
/// @param error the failure, cancellation included, otherwise
@ApiStatus.Experimental
public record CallOutcome(String requestId, @Nullable AssistantMessage reply, @Nullable LlmException error, List<Attempt> attempts) {
    /// Whether the call ended because it was cancelled, and whether the request had left the client by then.
    public enum Cancellation { NONE, REQUESTED_BEFORE_SEND, REQUESTED_AFTER_SEND }

    public CallOutcome {
        if ((reply == null) == (error == null)) throw new IllegalArgumentException("A call outcome has either a reply or an error");
        attempts = List.copyOf(attempts);
    }

    /// What arrived before the failure; its cut-off parts are marked (`AssistantMessage.incompleteParts()`).
    public Optional<AssistantMessage> partial() { return error == null ? Optional.empty() : error.partial(); }

    /// The reply's usage, else the last usage observed before the failure (`finalForCall() == false`).
    public Usage usage() { return reply != null ? reply.usage() : partial().map(AssistantMessage::usage).orElse(Usage.empty()); }

    /// The request may have been processed — and billed — although no reply arrived.
    public boolean outcomeUnknown() { return error != null && error.outcomeUnknown(); }

    public Cancellation cancellation() {
        if (!(error instanceof RequestCancelledException cancelled)) return Cancellation.NONE;
        return cancelled.outcomeUnknown() ? Cancellation.REQUESTED_AFTER_SEND : Cancellation.REQUESTED_BEFORE_SEND;
    }
}
