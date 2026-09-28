package net.ai.gate.internal.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import net.ai.gate.CallOutcome;
import net.ai.gate.LlmCall;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.error.LlmException;

/// [LlmCall] over one started [Call]: the worker settles the outcome, then the reply, from the same result.
/// Cancelling the reply's future cancels the call and leaves the outcome to settle; callers get copies of the
/// outcome stage, so none of them can complete or cancel it for the others.
final class DefaultLlmCall implements LlmCall {
    private final Call call;
    private final CompletableFuture<CallOutcome> outcome = new CompletableFuture<>();
    private final CompletableFuture<AssistantMessage> reply = new CompletableFuture<>() {
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            call.token.cancel();
            return super.cancel(mayInterruptIfRunning);
        }
    };

    private DefaultLlmCall(Call call) { this.call = call; }

    /// Runs `work` — which reports its failures through `call` — on `executor`.
    static LlmCall start(Call call, Executor executor, Supplier<AssistantMessage> work) {
        var handle = new DefaultLlmCall(call);
        try {
            executor.execute(() -> handle.run(work));
        } catch (RejectedExecutionException e) {
            handle.broken(call.fail(new IllegalStateException("The runtime's executor rejected the call", e), null));
        }
        return handle;
    }

    private void run(Supplier<AssistantMessage> work) {
        try {
            var message = work.get();
            outcome.complete(new CallOutcome(call.requestId, message, null, call.ledger()));
            reply.complete(message);
        } catch (LlmException e) {
            outcome.complete(new CallOutcome(call.requestId, null, e, call.ledger()));
            reply.completeExceptionally(e);
        } catch (RuntimeException | Error e) {
            broken(e);
        }
    }

    private void broken(Throwable e) {
        outcome.completeExceptionally(e);
        reply.completeExceptionally(e);
    }

    @Override public String requestId() { return call.requestId; }
    @Override public CompletionStage<AssistantMessage> reply() { return reply; }
    @Override public void cancel() { call.token.cancel(); }
    @Override public CompletionStage<CallOutcome> outcome() { return outcome.minimalCompletionStage(); }
    @Override public String toString() { return "LlmCall[" + call.requestId + (outcome.isDone() ? ", settled" : "") + "]"; }
}
