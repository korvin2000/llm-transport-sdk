package net.ai.gate

import net.ai.gate.auth.Environment
import net.ai.gate.chat.Conversation
import net.ai.gate.chat.options.ChatOptions
import net.ai.gate.chat.stream.ChatEvent
import net.ai.gate.json.Json
import net.ai.gate.model.Model
import net.ai.gate.testing.FakeProvider
import net.ai.gate.vendors.WireScript
import net.ai.gate.vendors.openai.OpenAiCompatible
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The Java API used from Kotlin without a wrapper: builders, consumer-builders, sealed events, `use`. */
class KotlinUsageTest {
    @Test
    fun `kotlin callers use builders and sealed events without platform types`() {
        val fake = FakeProvider.create()
            .reply("Sealed types make switches exhaustive.")
            .reply { it.text("One, two, three.").usage(5, 4) }
        Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog { it.offline() }.build().use { llm ->
            val model: Model = llm.model("fake", "fake")
            val reply = llm.complete(model, Conversation.builder().system("Be terse.").user("Why sealed types?").build())
            assertEquals("Sealed types make switches exhaustive.", reply.text())

            val text = StringBuilder()
            llm.stream(model, Conversation.of("Count to three"), ChatOptions.builder().tag("run", "kt").build()).use { stream ->
                for (event in stream) {
                    when (event) {
                        is ChatEvent.TextDelta -> text.append(event.text())
                        is ChatEvent.Done -> assertEquals(4L, event.message().usage().output().asLong)
                        else -> Unit
                    }
                }
            }
            assertEquals("One, two, three.", text.toString())
            assertEquals("kotlin", Json.`object`("lang", "kotlin").string("lang")) // `object` is a Kotlin keyword
        }
        fake.assertAllRepliesConsumed()
    }

    @Test
    fun `kotlin callers read the charge, the route and the timings with their nullability`() {
        val wire = WireScript().json(
            """{"provider":"DeepInfra","choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":3,"completion_tokens":1,"cost":0.5}}""",
        )
        wire.runtime(OpenAiCompatible.openRouter(), "OPENROUTER_API_KEY").use { llm ->
            val reply = llm.complete(llm.model("openrouter", "anthropic/claude-sonnet-4.5"), Conversation.of("hi"))
            val charge = reply.usage().charge().orElseThrow()
            assertEquals(0, BigDecimal("0.5").compareTo(charge.amount()))
            val upstream: BigDecimal? = charge.upstream() // @Nullable reaches Kotlin as a nullable type
            assertNull(upstream)
            assertEquals("DeepInfra", reply.info().route().orElseThrow())
            assertEquals(true, reply.info().timeToFirstOutput().isPresent)
        }
    }
}
