package net.ai.gate;

import static net.ai.gate.LlmTest.runtime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.ai.gate.auth.Environment;
import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.vendors.anthropic.AnthropicOptions;
import org.junit.jupiter.api.Test;

/// Option scopes, catalog-driven adaptation and strictness, observed where a codec sees them.
class ResolutionTest {
    @Test
    void callOptionsOverrideProviderDefaultsWhichOverrideRuntimeDefaultsFieldByField() {
        var fake = FakeProvider.create().reply("ok");
        var provider = fake.provider().toBuilder().defaults(o -> o.temperature(0.5).tag("scope", "provider")).build();
        try (var llm = Llm.builder().provider(provider).environment(Environment.none()).catalog(c -> c.offline())
                .defaults(o -> o.temperature(0.1).maxTokens(100).tag("runtime", "yes")).build()) {
            llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.builder().maxTokens(200).tag("call", "yes").build());
        }
        var options = fake.requests().getFirst().options();
        assertEquals(0.5, options.temperature().orElseThrow());
        assertEquals(200, options.maxTokens().orElseThrow());
        assertEquals(Map.of("runtime", "yes", "scope", "provider", "call", "yes"), options.tags());
        assertEquals(CacheRetention.SHORT, options.cacheRetention().orElseThrow(), "prompt caching is on by default");
    }

    @Test
    void reasoningMapsToTheNearestSupportedLevelOrFailsWhenStrict() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = runtime(fake)) {
            var thinker = llm.model("fake", "fake-thinker");
            var reply = llm.complete(thinker, Conversation.of("hi"), ChatOptions.builder().reasoning(ReasoningLevel.XHIGH).build());
            assertEquals(ReasoningLevel.HIGH, fake.requests().getFirst().options().reasoning().orElseThrow());
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("reasoning_clamped")), reply.warnings().toString());

            var strict = ChatOptions.builder().reasoning(ReasoningLevel.XHIGH).strict().build();
            var error = assertThrows(InvalidRequestException.class, () -> llm.complete(thinker, Conversation.of("hi"), strict));
            assertEquals(ErrorCode.UNSUPPORTED_FEATURE, error.code());
            assertEquals(1, fake.requests().size(), "a rejected call is never sent");
        }
    }

    @Test
    void reasoningOnAModelWithoutReasoningControlIsNotSent() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = runtime(fake)) {
            var reply = llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.builder().reasoning(ReasoningLevel.HIGH).build());
            assertTrue(fake.requests().getFirst().options().reasoning().isEmpty());
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("option_dropped")));
        }
    }

    @Test
    void nearestLevelResolvesTiesUpward() {
        var levels = List.of(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH);
        assertEquals(ReasoningLevel.HIGH, ReasoningLevel.XHIGH.nearest(levels));
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.MINIMAL.nearest(List.of(ReasoningLevel.LOW, ReasoningLevel.HIGH)));
        assertEquals(ReasoningLevel.HIGH, ReasoningLevel.MEDIUM.nearest(List.of(ReasoningLevel.LOW, ReasoningLevel.HIGH)));
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.OFF.nearest(List.of(ReasoningLevel.LOW, ReasoningLevel.HIGH)));
        assertEquals(ReasoningLevel.MEDIUM, ReasoningLevel.MEDIUM.nearest(List.of()));
    }

    @Test
    void foreignProviderOptionsAreInertAndReported() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = runtime(fake)) {
            var reply = llm.complete(llm.model("fake", "fake"), Conversation.of("hi"),
                    ChatOptions.builder().provider(AnthropicOptions.builder().thinkingBudget(2048).build()).strict().build());
            assertTrue(fake.requests().getFirst().options().providerOptions().isEmpty());
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("option_not_applicable")), "inert even when strict");
        }
    }

    @Test
    void outputLimitAboveTheModelMaximumIsClamped() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = runtime(fake)) {
            var reply = llm.complete(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.builder().maxTokens(50_000).build());
            assertEquals(4_096, fake.requests().getFirst().options().maxTokens().orElseThrow());
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("max_tokens_clamped")));
        }
    }

    @Test
    void formValuesAreParsedAndEveryInvalidFieldIsReported() {
        var options = ChatOptions.builder().set("temperature", "0.7").set("reasoning", "high").set("cacheRetention", "none").build();
        assertEquals(0.7, options.temperature().orElseThrow());
        assertEquals(ReasoningLevel.HIGH, options.reasoning().orElseThrow());
        var error = assertThrows(IllegalArgumentException.class,
                () -> ChatOptions.builder().set("maxTokens", "many").set("bogus", "1").set("temperature", "3").build());
        assertTrue(error.getMessage().contains("maxTokens") && error.getMessage().contains("bogus") && error.getMessage().contains("temperature"),
                error.getMessage());
    }

    @Test
    void payloadHookEditsTheBodyButNotTheModel() {
        try (var llm = runtime(FakeProvider.create())) {
            var model = llm.model("fake", "fake");
            var edited = llm.preview(model, Conversation.of("hi"),
                    ChatOptions.builder().payload(body -> body.with("metadata", Map.of("user", "u-17"))).build());
            assertEquals("u-17", ((JsonObject) edited.body()).object("metadata").string("user"));
            var rejected = llm.preview(model, Conversation.of("hi"), ChatOptions.builder().payload(body -> body.with("model", "other")).build());
            assertTrue(!rejected.sendable() && rejected.problems().getFirst().contains("model"), rejected.problems().toString());
        }
    }

    @Test
    void protectedHeadersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChatOptions.builder().header("Authorization", "Bearer x"));
        assertThrows(IllegalArgumentException.class, () -> FakeProvider.create().provider().toBuilder().header("x-api-key", "k"));
    }
}
