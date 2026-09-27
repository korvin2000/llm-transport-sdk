package net.ai.gate.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.Environment;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.LlmException;
import net.ai.gate.internal.http.HttpErrors;
import net.ai.gate.json.JsonString;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import org.junit.jupiter.api.Test;

/// Previews and diagnostics never expose credentials, and the curl form stays literal for everything but the
/// credential placeholders.
class PreparedRequestTest {
    @Test
    void curlQuotesEveryLiteralAndLeavesOnlyPlaceholdersExpandable() {
        var provider = FakeProvider.create().provider().toBuilder().auth(ApiKeyAuth.bearer("Fake key", "FAKE_API_KEY"))
                .header("X-Note", "it's $(dangerous) `too`").build();
        try (var llm = Fixtures.runtime(provider)) {
            var preview = llm.preview(llm.model("fake", "fake"), Conversation.of("say 'hi' $HOME"), ChatOptions.none());
            var curl = preview.toCurl();
            assertTrue(curl.contains("-H 'Authorization: Bearer '\"$FAKE_API_KEY\""), curl);
            assertTrue(curl.contains("-H 'X-Note: it'\\''s $(dangerous) `too`'"), curl);
            assertTrue(curl.contains("say '\\''hi'\\'' $HOME"), curl);
            assertFalse(curl.contains("\"$HOME\""), "a literal dollar in the body is not expandable");
            assertEquals(java.util.Set.of("Authorization"), preview.credentialHeaders());
            assertEquals("Bearer $FAKE_API_KEY", preview.headers().get("Authorization"));
        }
    }

    @Test
    void credentialBearingHostHeadersAndUrlComponentsAreRejected() {
        var fake = FakeProvider.create();
        assertThrows(IllegalArgumentException.class, () -> fake.provider().toBuilder().header("Cookie", "session=1"));
        assertThrows(IllegalArgumentException.class, () -> fake.provider().toBuilder().header("Set-Cookie", "x"));
        var error = assertThrows(IllegalArgumentException.class, () -> Provider.builder("p", fake.provider().defaultApi())
                .baseUrl(URI.create("https://user:secret@example.com/v1")).auth(ApiKeyAuth.none()).build());
        assertFalse(error.getMessage().contains("secret"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Provider.builder("p", fake.provider().defaultApi())
                .baseUrl(URI.create("https://example.com/v1?key=abc")).auth(ApiKeyAuth.none()).build());
    }

    @Test
    void describeRedactsHeadersAndShowsEffectivePolicies() {
        var provider = FakeProvider.create().provider().toBuilder().header("X-Custom-Token", "topsecret").header("X-Tenant", "t").build();
        try (var llm = Fixtures.runtime(provider)) {
            var description = llm.describe().toJson();
            assertFalse(description.contains("topsecret"), description);
            assertTrue(description.contains("\"X-Tenant\":\"t\""), description);
            assertTrue(description.contains("\"timeouts\":{\"connect\":\"PT10S\""), description);
        }
    }

    @Test
    void errorBodiesAreBounded() {
        var huge = "{\"error\":{\"message\":\"" + "x".repeat(HttpErrors.MAX_ERROR_BODY) + "\"}}";
        var details = HttpErrors.details(HttpReply.of(500, Map.of("content-type", List.of("application/json")), huge.getBytes()));
        var body = details.errorBody().orElseThrow();
        assertTrue(body instanceof JsonString s && s.value().length() <= HttpErrors.MAX_ERROR_BODY + 1, "truncated text, not the full body");
        assertTrue(details.message().length() < 400, details.message());
        LlmException.Details small = HttpErrors.details(Fixtures.error(429, "rate_limited", "slow down"));
        assertEquals("HTTP 429: slow down", small.message());
    }

    @Test
    void environmentPlaceholdersNameTheFirstVariable() {
        var provider = FakeProvider.create().provider().toBuilder().auth(ApiKeyAuth.header("Key", "x-api-key", "FIRST_KEY", "SECOND_KEY")).build();
        try (var llm = Fixtures.runtime(provider)) {
            var preview = llm.preview(llm.model("fake", "fake"), Conversation.of("hi"), ChatOptions.none());
            assertEquals("$FIRST_KEY", preview.headers().get("x-api-key"));
            assertTrue(preview.toCurl().contains("-H 'x-api-key: '\"$FIRST_KEY\""), preview.toCurl());
        }
        assertTrue(Environment.none().get("FIRST_KEY").isEmpty());
    }
}
