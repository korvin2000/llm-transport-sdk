package net.ai.gate.vendors.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.error.ProviderException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNull;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.vendors.WireScript;
import org.junit.jupiter.api.Test;

class AnthropicWireTest {
    record Weather(String city) { }

    private static final Conversation ASK = Conversation.builder().system("Be brief.")
            .tool(Tool.of("weather", "Current weather", Weather.class)).user("Weather in Paris?").build();

    private static final String REPLY = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5-20250929",
             "content":[{"type":"thinking","thinking":"Need the tool.","signature":"sig-1"},
                        {"type":"text","text":"Checking."},
                        {"type":"tool_use","id":"toolu_1","name":"weather","input":{"city":"Paris"}}],
             "stop_reason":"tool_use",
             "usage":{"input_tokens":10,"cache_read_input_tokens":100,"cache_creation_input_tokens":5,"output_tokens":20}}""";

    @Test
    void encodesDecodesAndReplaysSignedThinking() {
        var wire = new WireScript().json(REPLY).json(REPLY);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5-20250929");
            var reply = llm.complete(model, ASK, ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).maxTokens(2000).build());

            var call = wire.calls().getFirst();
            assertEquals("https://api.anthropic.com/v1/messages", call.uri().toString());
            assertEquals("2023-06-01", call.headers().get("anthropic-version"));
            var body = wire.body(0);
            assertEquals(Json.object("type", "enabled", "budget_tokens", 8192), body.get("thinking").orElseThrow(), "budget models think by budget");
            assertEquals(8192 + 4096, body.optLong("max_tokens").orElseThrow(), "raised above the budget");
            assertEquals("ephemeral", body.objects("system").getFirst().object("cache_control").string("type"));
            assertEquals("weather", body.objects("tools").getFirst().string("name"));
            assertTrue(body.objects("tools").getFirst().object("input_schema").object("properties").get("city").isPresent());

            assertEquals(StopReason.TOOL_USE, reply.stopReason());
            assertEquals("Checking.", reply.text());
            assertEquals("Need the tool.", reply.reasoningText().orElseThrow());
            assertEquals(new Weather("Paris"), reply.toolCalls().getFirst().arguments(Weather.class));
            assertEquals(10, reply.usage().input().orElseThrow());
            assertEquals(100, reply.usage().cacheRead().orElseThrow());
            assertEquals(5, reply.usage().cacheWrite().orElseThrow());

            var next = ASK.append(reply, List.of(ToolResult.of(reply.toolCalls().getFirst(), "18°C")));
            llm.complete(model, next, ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).build());
            var messages = wire.body(1).objects("messages");
            var assistant = messages.get(1).objects("content");
            assertEquals(Json.object("type", "thinking", "thinking", "Need the tool.", "signature", "sig-1"), assistant.getFirst());
            assertEquals(Json.object("city", "Paris"), assistant.get(2).object("input"));
            var result = messages.get(2).objects("content").getFirst();
            assertEquals("toolu_1", result.string("tool_use_id"));
            assertTrue(result.get("cache_control").isPresent(), "the last message carries the automatic marker");
        }
    }

    @Test
    void adaptiveModelsSendEffortAndStructuredOutput() {
        var wire = new WireScript().json(REPLY.replace("tool_use\",\n", "end_turn\",\n"));
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-opus-4-7");
            llm.complete(model, Conversation.of("hi"), ChatOptions.builder().reasoning(ReasoningLevel.XHIGH).temperature(0.5)
                    .cacheRetention(CacheRetention.LONG).output(Weather.class).build());
            var body = wire.body(0);
            assertEquals(Json.object("type", "adaptive"), body.get("thinking").orElseThrow());
            assertEquals("xhigh", body.object("output_config").string("effort"));
            assertEquals("json_schema", body.object("output_config").object("format").string("type"));
            assertFalse(body.get("temperature").isPresent(), "sampling settings are dropped with thinking");
            assertEquals("1h", body.objects("messages").getFirst().objects("content").getFirst().object("cache_control").string("ttl"));
        }
    }

    @Test
    void streamsTextThinkingAndToolInput() {
        var wire = new WireScript().sse(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Hmm\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-2\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"ping\"}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"weather\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"Oslo\\\"}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":2}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":30}}",
                "{\"type\":\"message_stop\"}");
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5");
            try (var stream = llm.stream(model, ASK)) {
                var events = stream.events().toList();
                assertTrue(events.stream().anyMatch(e -> e instanceof ChatEvent.ToolCallDelta d && d.partialArguments().get("city").isPresent()));
                var reply = ((ChatEvent.Done) events.getLast()).message();
                assertEquals(List.of(Content.Reasoning.of("Hmm", "sig-2", false, JsonNull.INSTANCE), Content.text("Hello"),
                        ToolCall.of("toolu_2", "weather", "{\"city\":\"Oslo\"}")), reply.content());
                assertEquals(7, reply.usage().input().orElseThrow());
                assertEquals(30, reply.usage().output().orElseThrow());
                assertEquals(StopReason.TOOL_USE, reply.stopReason());
                assertEquals("msg_2", reply.responseId().orElseThrow());
            }
            assertTrue(wire.body(0).bool("stream"));
        }
    }

    @Test
    void streamErrorsAndHttpErrorsAreTyped() {
        var wire = new WireScript()
                .sse("{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{}}}",
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")
                .error(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 250000 tokens > 200000 maximum\"}}");
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5");
            var options = ChatOptions.builder().retry(r -> r.maxAttempts(1)).build();
            try (var stream = llm.stream(model, ASK, options)) {
                var error = assertThrows(ProviderException.class, stream::result);
                assertEquals(ErrorCode.OVERLOADED, error.code());
            }
            var overflow = assertThrows(InvalidRequestException.class, () -> llm.complete(model, ASK, options));
            assertEquals(ErrorCode.CONTEXT_OVERFLOW, overflow.code());
        }
    }

    @Test
    void foreignToolCallIdsAreNormalized() {
        var id = "call_" + "x".repeat(100) + "|fc_1";
        var normalized = Anthropic.MESSAGES.normalizeToolCallId(id);
        assertTrue(normalized.matches("[a-zA-Z0-9_-]{1,64}"), normalized);
        assertEquals(normalized, Anthropic.MESSAGES.normalizeToolCallId(id));
    }

    @Test
    void citationsCoverTheirTextBlockInRepliesAndStreams() {
        var reply = """
                {"id":"msg_c","type":"message","role":"assistant","model":"claude-sonnet-4-6",
                 "content":[{"type":"text","text":"Per the docs, "},
                            {"type":"text","text":"the sky is blue","citations":[{"type":"char_location","cited_text":"The sky is blue.",
                              "document_index":0,"document_title":"Facts","start_char_index":0,"end_char_index":16}]},
                            {"type":"text","text":" and grass is green","citations":[{"type":"web_search_result_location",
                              "url":"https://example.com/grass","title":"Grass","cited_text":"Grass is green.","encrypted_index":"e1"}]}],
                 "stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":9}}""";
        var wire = new WireScript().json(reply).sse(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_s\",\"model\":\"claude-sonnet-4-6\",\"usage\":{\"input_tokens\":5}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Per the docs, \"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\",\"citations\":[]}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"char_location\","
                        + "\"cited_text\":\"The sky is blue.\",\"document_index\":0,\"document_title\":\"Facts\",\"start_char_index\":0,\"end_char_index\":16}}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"the sky is blue\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":9}}",
                "{\"type\":\"message_stop\"}")
                .json(reply);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-6");
            var message = llm.complete(model, Conversation.of("Colors?"));
            var sky = Content.Text.of("the sky is blue", List.of(new Content.Citation("Facts", URI.create("document:0"), 0, 15)));
            assertEquals(List.of(Content.text("Per the docs, "), sky, Content.Text.of(" and grass is green",
                    List.of(new Content.Citation("Grass", URI.create("https://example.com/grass"), 0, 19)))), message.content());

            try (var stream = llm.stream(model, Conversation.of("Colors?"))) {
                assertEquals(List.of(Content.text("Per the docs, "), sky), stream.result().content());
            }

            llm.complete(model, Conversation.of("Colors?").append(message).appendUser("Thanks"));
            assertEquals(Json.object("type", "text", "text", "the sky is blue"), wire.body(2).objects("messages").get(1).objects("content").get(1),
                    "cited text replays as plain text");
        }
    }

    @Test
    void forcedToolChoiceBecomesAutoWhereTheModelCannotForceATool() {
        var ok = REPLY.replace("tool_use\",\n", "end_turn\",\n");
        var wire = new WireScript().json(ok).json(ok).json(ok);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var required = ChatOptions.builder().toolChoice(ToolChoice.required()).reasoning(ReasoningLevel.HIGH).build();
            var budget = llm.complete(llm.model("anthropic", "claude-sonnet-4-5"), ASK, required);
            assertEquals(Json.object("type", "auto"), wire.body(0).get("tool_choice").orElseThrow());
            assertTrue(budget.warnings().stream().anyMatch(w -> w.code().equals("option_adapted")), budget.warnings().toString());

            llm.complete(llm.model("anthropic", "claude-opus-4-7"), ASK, required);
            assertEquals(Json.object("type", "any"), wire.body(1).get("tool_choice").orElseThrow(), "adaptive thinking may force a tool");

            llm.complete(llm.model("anthropic", "claude-opus-5-5"), ASK, ChatOptions.builder().toolChoice(ToolChoice.only("weather")).build());
            assertEquals(Json.object("type", "auto"), wire.body(2).get("tool_choice").orElseThrow(), "Opus 5.5 never forces a tool");

            var strict = assertThrows(InvalidRequestException.class, () -> llm.complete(llm.model("anthropic", "claude-sonnet-4-5"), ASK,
                    required.toBuilder().strict(true).build()));
            assertEquals(ErrorCode.UNSUPPORTED_FEATURE, strict.code());
            assertEquals(3, wire.calls().size());
        }
    }
}
