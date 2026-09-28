package net.ai.gate;

import java.util.concurrent.CompletionStage;

import net.ai.gate.chat.AssistantMessage;
import org.jetbrains.annotations.ApiStatus;

/// A started call, run on the runtime's executor, whose outcome survives cancellation: [#reply()] is for the happy
/// path, [#outcome()] for the bill — reply or failure, the partial reply, observed usage and every attempt.
/// Thread-safe. Not for implementation by consumers.
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public interface LlmCall {
    /// The SDK request id, shared with events, logs and JFR.
    String requestId();

    /// Completes with the reply, or exceptionally with the `LlmException`. Cancelling it (through
    /// `toCompletableFuture().cancel(…)`) cancels the call; [#outcome()] still completes.
    CompletionStage<AssistantMessage> reply();

    /// Requests cancellation; idempotent. The provider may still process a request that was sent.
    void cancel();

    /// Completes exactly once, when the call has settled — also after cancellation. Completes exceptionally only for
    /// failures that are not call outcomes: the executor rejected the call, or a bug.
    CompletionStage<CallOutcome> outcome();
}
