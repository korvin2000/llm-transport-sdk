package net.ai.gate.kotlin

import java.time.Duration
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import net.ai.gate.CallOutcome
import net.ai.gate.Llm
import net.ai.gate.auth.Environment
import net.ai.gate.chat.Conversation
import net.ai.gate.chat.options.ChatOptions
import net.ai.gate.chat.stream.ChatEvent
import net.ai.gate.error.RateLimitedException
import net.ai.gate.error.RequestCancelledException
import net.ai.gate.event.RequestEvent
import net.ai.gate.testing.FakeProvider
import net.ai.gate.testing.LlmErrors
import net.ai.gate.testing.RecordingListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The adapters over a real runtime and a scripted provider: replies, flows, failures, and cancellation that keeps the outcome. */
class LlmCoroutinesTest {
    private fun runtime(fake: FakeProvider): Llm =
        Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog { it.offline() }
            .defaults { it.retry { r -> r.maxAttempts(1) } }.build()

    @Test
    fun `completeSuspending returns the reply and awaitOutcome the same call settled`(): Unit = runBlocking {
        val fake = FakeProvider.create().reply { it.text("Hello").usage(5, 2) }.reply("Again")
        runtime(fake).use { llm ->
            val reply = llm.completeSuspending(llm.model("fake", "fake"), Conversation.of("hi"))
            assertEquals("Hello", reply.text())
            assertEquals(2L, reply.usage().output().asLong)

            val call = llm.start(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.none())
            val outcome = call.awaitOutcome()
            assertEquals("Again", outcome.reply()!!.text())
            assertEquals(CallOutcome.Cancellation.NONE, outcome.cancellation())
            assertEquals("Again", call.awaitReply().text(), "the reply stage settles from the same result")
        }
        fake.assertAllRepliesConsumed()
    }

    @Test
    fun `events is a cold flow ending with Done`(): Unit = runBlocking {
        val fake = FakeProvider.create().reply { it.text("One, two.").usage(3, 4) }
        runtime(fake).use { llm ->
            val events = llm.events(llm.model("fake", "fake"), Conversation.of("count")).toList()
            assertEquals("One, two.", events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text() })
            val done = assertInstanceOf(ChatEvent.Done::class.java, events.last())
            assertEquals(4L, done.message().usage().output().asLong)
        }
        fake.assertAllRepliesConsumed()
    }

    @Test
    fun `a failed call throws the LlmException itself`() {
        val fake = FakeProvider.create().fail(LlmErrors.rateLimited(Duration.ofSeconds(30)))
        runtime(fake).use { llm ->
            val error = assertThrows(RateLimitedException::class.java) {
                runBlocking { llm.completeSuspending(llm.model("fake", "fake"), Conversation.of("hi")) }
            }
            assertEquals(1, error.attempts())
        }
    }

    @Test
    fun `cancelling the awaiting coroutine cancels the call and the outcome still settles`(): Unit = runBlocking {
        val fake = FakeProvider.create()
        val stall = fake.stall()
        runtime(fake).use { llm ->
            val call = llm.start(llm.model("fake", "fake"), Conversation.of("go"), ChatOptions.none())
            val waiter = launch { call.awaitReply() }
            while (fake.sends() == 0) yield()   // the request has left the client: the stall answered its headers
            waiter.cancelAndJoin()

            val outcome = withTimeoutOrNull(5_000) { call.awaitOutcome() }
            assertTrue(outcome != null, "outcome() settles after cancellation")
            assertInstanceOf(RequestCancelledException::class.java, outcome!!.error())
            assertEquals(CallOutcome.Cancellation.REQUESTED_AFTER_SEND, outcome.cancellation())
            assertFalse(stall.released(), "the provider never answered")
            assertThrows(RequestCancelledException::class.java) { runBlocking { call.awaitReply() } }
        }
    }

    @Test
    fun `a collector that stops early cancels the stream`(): Unit = runBlocking {
        val fake = FakeProvider.create()
        fake.stall()
        val events = RecordingListener()
        runtime(fake).use { llm ->
            llm.addListener(events)
            val collected = withTimeoutOrNull(300) { llm.events(llm.model("fake", "fake"), Conversation.of("go")).toList() }
            assertNull(collected, "the stalled stream never ended on its own")
            withTimeoutOrNull(5_000) {
                while (events.events(RequestEvent.Finished::class.java).isEmpty()) yield()
            }
            assertEquals(RequestEvent.Finished.Outcome.CANCELLED, events.events(RequestEvent.Finished::class.java).single().outcome())
        }
    }
}
