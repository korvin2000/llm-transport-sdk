package net.ai.gate.chat.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.ai.gate.Llm;
import net.ai.gate.auth.Environment;
import net.ai.gate.chat.Conversation;
import net.ai.gate.config.RetryPolicy;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.testing.FakeProvider;
import org.junit.jupiter.api.Test;

/// Unset is not default: nested policies inherit field by field, explicit `false` and empty lists override, and
/// header names compare case-insensitively across scopes.
class OptionsInheritanceTest {
    @Test
    void nestedPoliciesInheritFieldByField() {
        var runtime = ChatOptions.builder().timeouts(t -> t.connect(Duration.ofSeconds(2)).streamIdle(Duration.ofSeconds(7)))
                .retry(r -> r.backoff(Duration.ofMillis(10), 3, Duration.ofMillis(40))).build();
        var call = ChatOptions.builder().timeouts(t -> t.total(Duration.ofSeconds(30))).retry(RetryPolicy.none()).build();
        var effective = runtime.overriddenBy(call);
        var timeouts = effective.timeouts().orElseThrow();
        assertEquals(Duration.ofSeconds(2), timeouts.connect());
        assertEquals(Duration.ofSeconds(7), timeouts.streamIdle());
        assertEquals(Optional.of(Duration.ofSeconds(30)), timeouts.total());
        var retry = effective.retry().orElseThrow();
        assertEquals(1, retry.maxAttempts());
        assertEquals(3.0, retry.backoffMultiplier(), "backoff inherited from the runtime scope");
        assertEquals(Duration.ofMillis(40), retry.maxBackoff());
    }

    @Test
    void noTotalTimeoutWinsOverAnInheritedTotalAndCanBeSetAgain() {
        var base = TimeoutPolicy.builder().total(Duration.ofMinutes(1)).build();
        assertTrue(base.overriddenBy(TimeoutPolicy.builder().noTotalTimeout().build()).total().isEmpty());
        assertEquals(Optional.of(Duration.ofMinutes(1)), TimeoutPolicy.builder().noTotalTimeout().build().overriddenBy(base).total());
        assertEquals(Optional.of(Duration.ofMinutes(10)), TimeoutPolicy.builder().build().total(), "unset means the default");
    }

    @Test
    void explicitFalseAndEmptyListsOverrideWiderScopes() {
        var runtime = ChatOptions.builder().strict().stop("END").build();
        var call = ChatOptions.builder().strict(false).stops(List.of()).build();
        var effective = runtime.overriddenBy(call);
        assertFalse(effective.strict());
        assertTrue(effective.stop().isEmpty());
        assertEquals(List.of("END"), runtime.overriddenBy(ChatOptions.none()).stop(), "an unset call keeps the inherited list");
        assertFalse(runtime.overriddenBy(ChatOptions.builder().set("strict", "false").build()).strict(), "form values can say false");
    }

    @Test
    void headersMergeCaseInsensitivelyAndRejectInvalidValues() {
        var runtime = ChatOptions.builder().header("X-Tenant", "a").header("X-Trace", "1").build();
        var effective = runtime.overriddenBy(ChatOptions.builder().header("x-tenant", "b").build());
        assertEquals(2, effective.headers().size());
        assertEquals("b", effective.headers().get("X-TENANT"));
        assertThrows(IllegalArgumentException.class, () -> ChatOptions.builder().header("X-Bad", "line\r\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> ChatOptions.builder().header("bad name", "v"));
        assertThrows(IllegalArgumentException.class, () -> ChatOptions.builder().header("Cookie", "session=1"));
    }

    @Test
    void aCallSettingOnlyTheTotalKeepsTheRuntimeConnectAndIdleLimits() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline())
                .defaults(o -> o.timeouts(t -> t.connect(Duration.ofSeconds(2)).streamIdle(Duration.ofSeconds(7)))).build()) {
            llm.complete(llm.model("fake", "fake"), Conversation.of("hi"),
                    ChatOptions.builder().timeouts(t -> t.total(Duration.ofSeconds(30))).build());
        }
        var timeouts = fake.requests().getFirst().options().timeouts().orElseThrow();
        assertEquals(Duration.ofSeconds(2), timeouts.connect());
        assertEquals(Duration.ofSeconds(7), timeouts.streamIdle());
        assertEquals(Optional.of(Duration.ofSeconds(30)), timeouts.total());
    }

    @Test
    void strictFalseOnACallRelaxesAStrictRuntime() {
        var fake = FakeProvider.create().reply("ok");
        try (var llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline())
                .defaults(o -> o.strict()).build()) {
            var thinker = llm.model("fake", "fake-thinker");
            var strictCall = ChatOptions.builder().reasoning(ReasoningLevel.XHIGH).build();
            assertThrows(net.ai.gate.error.InvalidRequestException.class, () -> llm.complete(thinker, Conversation.of("hi"), strictCall));
            var relaxed = llm.complete(thinker, Conversation.of("hi"), ChatOptions.builder().reasoning(ReasoningLevel.XHIGH).strict(false).build());
            assertTrue(relaxed.warnings().stream().anyMatch(w -> w.code().equals("reasoning_clamped")));
        }
    }

    @Test
    void tagsAndHeadersAccumulateAcrossScopes() {
        var fake = FakeProvider.create().reply("ok");
        var provider = fake.provider().toBuilder().header("X-Provider", "p").defaults(o -> o.tag("scope", "provider")).build();
        try (var llm = Llm.builder().provider(provider).environment(Environment.none()).catalog(c -> c.offline())
                .defaults(o -> o.header("X-Runtime", "r")).build()) {
            var preview = llm.preview(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.builder().header("X-Call", "c").header("x-runtime", "call wins").build());
            assertEquals("p", preview.headers().get("X-Provider"));
            assertEquals("call wins", preview.headers().get("X-Runtime"));
            assertEquals("c", preview.headers().get("X-Call"));
        }
        assertEquals(Map.of(), fake.requests().stream().findFirst().map(r -> r.options().tags()).orElse(Map.of()), "preview sends nothing");
    }
}
