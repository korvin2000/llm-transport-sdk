package net.ai.gate.vendors.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.chat.Continuation;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.ModelRef;
import net.ai.gate.vendors.WireScript;
import org.junit.jupiter.api.Test;

/// Server-side state on the Responses API: a stored reply's `previous_response_id` as a `Continuation`, the suffix
/// the next call sends, the expired case, and `responses/compact` as the next context window.
class ResponsesContinuationTest {
    private static final String STORED = """
            {"id":"resp_1","object":"response","model":"gpt-5.1","status":"completed","store":true,
             "output":[{"type":"message","content":[{"type":"output_text","text":"Paris."}]}],
             "usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":0},"output_tokens":6,"total_tokens":18}}""";

    @Test
    void aStoredReplyCarriesAContinuationAndTheNextCallSendsOnlyWhatFollowedIt() {
        var wire = new WireScript().json(STORED).json(STORED.replace("resp_1", "resp_2")).json(STORED.replace("\"store\":true", "\"store\":false"));
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = llm.model("openai", "gpt-5.1");
            var stored = ChatOptions.builder().provider(OpenAiResponsesOptions.builder().store(true).build()).build();
            var reply = llm.complete(model, Conversation.of("Capital of France?"), stored);
            var continuation = reply.continuation().orElseThrow();
            assertEquals("resp_1", continuation.opaqueId());
            assertEquals(OpenAi.RESPONSES.id(), continuation.api());
            assertEquals(model.ref(), continuation.model());
            assertEquals(18, continuation.effectiveHistoryTokens().orElseThrow());
            assertTrue(continuation.expiresAt().isEmpty(), "the API does not report the expiry");

            var history = Conversation.of("Capital of France?").append(reply).appendUser("And of Spain?");
            var prepared = llm.prepare(model, history, stored.toBuilder().continueFrom(continuation).build(), false);
            assertEquals(1, prepared.effectiveConversation().messages().size(), "only what came after the continued reply");
            assertTrue(prepared.request().notes().stream().anyMatch(n -> n.code().equals("continuation")), prepared.request().notes().toString());
            var next = llm.complete(prepared);
            assertEquals("resp_2", next.continuation().orElseThrow().opaqueId());
            var body = wire.body(1);
            assertEquals("resp_1", body.string("previous_response_id"));
            assertEquals(1, body.objects("input").size());
            assertEquals("And of Spain?", body.objects("input").getFirst().objects("content").getFirst().string("text"));

            assertTrue(llm.complete(model, Conversation.of("hi")).continuation().isEmpty(), "not stored: nothing to continue from");
        }
    }

    @Test
    void anUnknownContinuationFailsAsExpiredWithoutRetry() {
        var error = "{\"error\":{\"message\":\"Previous response with id 'resp_gone' not found.\",\"type\":\"invalid_request_error\","
                + "\"param\":\"previous_response_id\",\"code\":\"previous_response_not_found\"}}";
        var wire = new WireScript().error(404, error).sse("{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"output\":[],"
                + "\"error\":{\"code\":\"previous_response_not_found\",\"message\":\"gone\"}}}");
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = llm.model("openai", "gpt-5.1");
            var gone = new Continuation(model.ref(), OpenAi.RESPONSES.id(), "resp_gone", Optional.empty(), OptionalLong.empty());
            var options = ChatOptions.builder().continueFrom(gone).build();

            var failure = assertThrows(InvalidRequestException.class, () -> llm.complete(model, Conversation.of("more"), options));
            assertEquals(ErrorCode.CONTINUATION_EXPIRED, failure.code());
            assertEquals("previous_response_not_found", failure.providerCode().orElseThrow());
            assertEquals(404, failure.httpStatus().orElseThrow());
            assertEquals(1, wire.calls().size(), "not retried");
            assertEquals("resp_gone", wire.body(0).string("previous_response_id"));
            assertEquals(1, wire.body(0).objects("input").size(), "the continued reply is not in the conversation: every message is sent after it");

            try (var stream = llm.stream(model, Conversation.of("more"), options)) {
                assertEquals(ErrorCode.CONTINUATION_EXPIRED, assertThrows(InvalidRequestException.class, stream::result).code());
            }
        }
    }

    @Test
    void aContinuationOfAnotherOriginIsIgnoredWithAWarningAndRejectedUnderStrict() {
        var wire = new WireScript().json(STORED);
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = llm.model("openai", "gpt-5.1");
            assertTrue(llm.features(model).continuation());
            var foreign = new Continuation(new ModelRef("anthropic", "claude-sonnet-5"), "anthropic-messages", "msg_1", Optional.empty(), OptionalLong.empty());
            var reply = llm.complete(model, Conversation.of("hi"), ChatOptions.builder().continueFrom(foreign).build());
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("continuation_ignored")), reply.warnings().toString());
            assertFalse(wire.body(0).members().containsKey("previous_response_id"));

            var strict = assertThrows(InvalidRequestException.class,
                    () -> llm.complete(model, Conversation.of("hi"), ChatOptions.builder().continueFrom(foreign).strict().build()));
            assertEquals(ErrorCode.UNSUPPORTED_FEATURE, strict.code());
            assertEquals(1, wire.calls().size(), "rejected before it is sent");
        }
    }

    @Test
    void compactionReturnsTheNextWindowWhichReplaysVerbatimAlsoAfterAJsonRoundTrip() {
        var compacted = """
                {"id":"resp_c","object":"response.compaction","created_at":1,
                 "output":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Capital of France?"}]},
                           {"id":"cmp_1","type":"compaction","encrypted_content":"gAAAA=="}],
                 "usage":{"input_tokens":40,"output_tokens":9,"total_tokens":49}}""";
        var wire = new WireScript().json(compacted).json(STORED.replace("\"store\":true", "\"store\":false"));
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = llm.model("openai", "gpt-5.1");
            assertTrue(llm.features(model).compaction());
            var conversation = Conversation.builder().system("Be brief.").user("Capital of France?").assistant("Paris.")
                    .user("And of Spain?").assistant("Madrid.").build();
            var summary = llm.compact(model, conversation);

            assertEquals("https://api.openai.com/v1/responses/compact", wire.calls().getFirst().uri().toString());
            var body = wire.body(0);
            assertEquals("Be brief.", body.string("instructions"));
            assertEquals(4, body.objects("input").size());
            assertFalse(body.members().containsKey("stream"));
            assertEquals(StopReason.COMPACTION, summary.stopReason());
            var block = summary.compaction().orElseThrow();
            assertTrue(block.text().isEmpty(), "OpenAI summaries are opaque");
            assertEquals("gAAAA==", ((JsonObject) block.providerData()).string("encrypted_content"));
            assertEquals(40, summary.usage().input().orElseThrow());
            assertEquals(9, summary.usage().output().orElseThrow());
            assertTrue(summary.continuation().isEmpty());

            var next = Conversation.fromJson(conversation.withMessages(List.of(summary)).appendUser("And of Italy?").toJson());
            llm.complete(model, next);
            var input = wire.body(1).objects("input");
            assertEquals(3, input.size());
            assertEquals("user", input.get(0).string("role"), "items of the window are kept verbatim");
            assertEquals(Json.object("id", "cmp_1", "type", "compaction", "encrypted_content", "gAAAA=="), input.get(1));
            assertEquals("And of Italy?", input.get(2).objects("content").getFirst().string("text"));
        }
    }

    @Test
    void theCodexDialectHasNoCompaction() {
        var wire = new WireScript();
        try (var llm = wire.runtime(OpenAi.codex(), "UNUSED")) {
            assertFalse(llm.features(llm.model("openai-codex", "gpt-5.5")).compaction());
        }
    }
}
