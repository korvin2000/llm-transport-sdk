@file:JvmName("LlmCoroutines")

package net.ai.gate.kotlin

import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.ai.gate.CallOutcome
import net.ai.gate.Llm
import net.ai.gate.LlmCall
import net.ai.gate.chat.AssistantMessage
import net.ai.gate.chat.Conversation
import net.ai.gate.chat.options.ChatOptions
import net.ai.gate.chat.stream.ChatEvent
import net.ai.gate.lifecycle.CancelToken
import net.ai.gate.model.Model

/*
 * Coroutine and Flow adapters over the Java API. Cancelling a coroutine cancels the call it waits for — through
 * `LlmCall.cancel()` or the call's `CancelToken`, never by cancelling a future — so `LlmCall.outcome()` still
 * settles with the bill: usage observed so far, the partial reply and the attempts.
 */

/** Suspends until the reply. Cancelling the coroutine cancels the call; [LlmCall.outcome] still settles. */
suspend fun LlmCall.awaitReply(): AssistantMessage = await(reply())

/**
 * Suspends until the call has settled — also after cancellation — and returns how: reply or error, partial reply,
 * usage, cancellation facts and attempts. Cancelling the coroutine cancels the call, never the outcome.
 */
suspend fun LlmCall.awaitOutcome(): CallOutcome = await(outcome())

private suspend fun <T> LlmCall.await(stage: CompletionStage<T>): T = suspendCancellableCoroutine { continuation ->
    stage.whenComplete { value, error ->
        if (error != null) continuation.resumeWithException(unwrapped(error)) else continuation.resume(value)
    }
    continuation.invokeOnCancellation { cancel() }
}

/** The `LlmException` itself, not the `CompletionException` a dependent stage wraps it in. */
private fun unwrapped(error: Throwable): Throwable = if (error is CompletionException) error.cause ?: error else error

/**
 * Starts the call on the runtime's executor and suspends until the reply, as `Llm.complete` would return it.
 * Cancelling the coroutine cancels the call.
 */
suspend fun Llm.completeSuspending(model: Model, conversation: Conversation, options: ChatOptions = ChatOptions.none()): AssistantMessage =
    start(model, conversation, options).awaitReply()

/**
 * The events of a streamed reply as a cold flow: every collection is a new call, read on [Dispatchers.IO] and
 * back-pressured by the collector; the last event is `ChatEvent.Done` and a failure ends the flow with the
 * `LlmException`. When the collector stops early, the call is cancelled and the connection released.
 */
fun Llm.events(model: Model, conversation: Conversation, options: ChatOptions = ChatOptions.none()): Flow<ChatEvent> = callbackFlow {
    val token = options.cancel().map { it.child() }.orElseGet { CancelToken.create() }
    launch(Dispatchers.IO) {
        try {
            stream(model, conversation, options.toBuilder().cancel(token).build()).use { stream ->
                for (event in stream) send(event)
            }
            close()
        } catch (e: Throwable) {
            close(e)
        }
    }
    awaitClose { token.cancel() }
}
