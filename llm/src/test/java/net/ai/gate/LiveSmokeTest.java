package net.ai.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import net.ai.gate.auth.CredentialStore;
import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.model.ReasoningLevel;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Opt-in smoke tests against the real endpoints, never part of `build`: `./gradlew liveTest`. Each provider runs when
/// its key is in the environment (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`); the ChatGPT subscription
/// runs when `AI_GATE_CREDENTIALS` names a file credential store holding an `openai-codex` login. Small models, low
/// reasoning and short answers: a run costs cents.
@EnabledIfSystemProperty(named = "ai-gate.live", matches = "true")
class LiveSmokeTest {
    record City(String name) { }

    private static final ChatOptions CHEAP = ChatOptions.builder().reasoning(ReasoningLevel.LOW).build();

    static Stream<Arguments> models() {
        return Stream.of(Arguments.of("openai", "gpt-5.4-nano", "OPENAI_API_KEY"), Arguments.of("anthropic", "claude-haiku-4-5", "ANTHROPIC_API_KEY"),
                Arguments.of("google", "gemini-2.5-flash", "GEMINI_API_KEY"), Arguments.of("openai-codex", "gpt-5.5", "AI_GATE_CREDENTIALS"));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("models")
    void completeStreamToolsStructuredOutputCachingAndConnectionTest(String provider, String id, String variable) {
        var value = System.getenv(variable);
        assumeTrue(value != null, variable + " is not set");
        var store = variable.equals("AI_GATE_CREDENTIALS") ? CredentialStore.file(Path.of(value)) : CredentialStore.inMemory();
        try (var llm = Llm.builder().discoverProviders().credentials(store).build()) {
            var model = llm.model(provider, id);
            assertFalse(llm.complete(model, Conversation.of("Reply with the single word OK."), CHEAP).text().isBlank());
            try (var stream = llm.stream(model, Conversation.of("Count from 1 to 3."), CHEAP)) { assertFalse(stream.result().text().isBlank()); }

            var ask = Conversation.builder().tool(Tool.of("weather", "Current weather in a city", City.class))
                    .user("What is the weather in Paris? Use the tool.").build();
            var call = llm.complete(model, ask, CHEAP.toBuilder().toolChoice(ToolChoice.required()).build());
            assertFalse(call.toolCalls().isEmpty(), call.toString());
            var answer = llm.complete(model, ask.append(call, List.of(ToolResult.of(call.toolCalls().getFirst(), "18°C and sunny"))), CHEAP);
            assertTrue(answer.text().contains("18"), "the second turn replays the call, its result and any reasoning: " + answer.text());

            assertEquals("Paris", llm.complete(model, Conversation.of("Which city is the capital of France?"), City.class).name());

            if (!provider.equals("google")) {   // Gemini caches implicitly, without a guarantee
                var longPrompt = Conversation.builder().system("You answer questions about this text.\n" + "The quick brown fox jumps over the lazy dog. ".repeat(700))
                        .user("How many dogs are mentioned per sentence?").build();
                var cached = ChatOptions.builder().sessionId("ai-gate-live-" + provider).reasoning(ReasoningLevel.LOW).build();
                llm.complete(model, longPrompt, cached);
                AssistantMessage repeat = llm.complete(model, longPrompt, cached);
                assertTrue(repeat.usage().cacheRead().orElse(0) > 0, "the repeated prefix is read from the prompt cache: " + repeat.usage());
            }

            var report = llm.test(model);
            assertTrue(report.firstFailure().isEmpty(), report.toString());
        }
    }
}
