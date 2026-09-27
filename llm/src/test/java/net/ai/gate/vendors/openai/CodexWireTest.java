package net.ai.gate.vendors.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.ai.gate.Llm;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.RateLimitedException;
import net.ai.gate.json.Json;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.vendors.WireScript;
import org.junit.jupiter.api.Test;

/// The ChatGPT subscription preset through the real core: the Codex backend's URL, headers and body, events
/// collected by `complete()`, and plan limits.
class CodexWireTest {
    private static final List<String> EVENTS = List.of(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_c\",\"model\":\"gpt-5.5\"}}",
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hi\"}",
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi there\"}]}}",
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_c\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}}");

    private static Llm runtime(WireScript wire) {
        var store = CredentialStore.inMemory();
        store.update("openai-codex", _ -> Optional.of(OAuthCredential.builder(Secret.of("access-token-0123456789"), "https://auth.openai.com",
                "app_EMoamEEZ73f0CkXaXp7hrann").account("acct-7").expiresAt(Instant.now().plusSeconds(3600)).build()));
        return Llm.builder().provider(OpenAi.codex().toBuilder().transport(wire).build()).credentials(store)
                .environment(Environment.none()).catalog(c -> c.offline()).build();
    }

    @Test
    void completeStreamsTheCodexDialectAndCollectsTheEvents() {
        var wire = new WireScript().sse(EVENTS.toArray(String[]::new));
        try (var llm = runtime(wire)) {
            var reply = llm.complete(llm.model("openai-codex", "gpt-5.5"), Conversation.of("Hello"),
                    ChatOptions.builder().reasoning(ReasoningLevel.HIGH).maxTokens(500).sessionId("s-9").build());

            assertEquals("Hi there", reply.text());
            assertEquals("resp_c", reply.responseId().orElseThrow());
            assertEquals(2, reply.usage().output().orElseThrow());
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("option_dropped")), "maxTokens: " + reply.warnings());

            var call = wire.calls().getFirst();
            assertEquals("https://chatgpt.com/backend-api/codex/responses", call.uri().toString());
            assertEquals("Bearer access-token-0123456789", call.headers().get("Authorization"));
            assertEquals("acct-7", call.headers().get("chatgpt-account-id"));
            assertEquals("ai-gate", call.headers().get("originator"));
            assertEquals("text/event-stream", call.headers().get("Accept"));
            assertEquals("s-9", call.headers().get("session-id"));
            assertEquals("s-9", call.headers().get("x-client-request-id"));

            var body = wire.body(0);
            assertTrue(body.bool("stream"), "streaming only");
            assertFalse(body.bool("store"));
            assertEquals("You are a helpful assistant.", body.string("instructions"), "instructions are required");
            assertFalse(body.members().containsKey("max_output_tokens"));
            assertEquals(List.of(Json.valueOf("reasoning.encrypted_content")), body.array("include"));
            assertEquals("s-9", body.string("prompt_cache_key"));
        }
    }

    @Test
    void anExhaustedPlanIsAQuotaErrorAndNotRetried() {
        var wire = new WireScript().error(429, "{\"error\":{\"type\":\"usage_limit_reached\",\"message\":\"The usage limit has been reached\","
                + "\"plan_type\":\"plus\",\"resets_in_seconds\":1200}}");
        try (var llm = runtime(wire)) {
            var error = assertThrows(RateLimitedException.class, () -> llm.complete(llm.model("openai-codex", "gpt-5.5"), "Hello"));
            assertEquals(ErrorCode.QUOTA_EXHAUSTED, error.code());
            assertEquals(1, wire.calls().size(), "waiting does not refill a plan");
        }
    }
}
