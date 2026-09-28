package net.ai.gate.vendors.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.AssistantMessage;
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
import net.ai.gate.error.TransportException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.TokenCount;
import net.ai.gate.model.Prices;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.spi.protocol.ApiFeatures;
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

    @Test
    void anInterruptedToolCallKeepsTheObservedUsageAndIsNeverReplayed() {
        var wire = new WireScript().sse(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_3\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":300,\"cache_creation_input_tokens\":0,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Checking.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_3\",\"name\":\"weather\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"ci\"}}")
                .json(REPLY);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5");
            var error = assertThrows(TransportException.class, () -> llm.stream(model, ASK).result());
            var partial = error.partial().orElseThrow();
            assertEquals(12, partial.usage().input().orElseThrow());
            assertEquals(300, partial.usage().cacheRead().orElseThrow());
            assertFalse(partial.usage().finalForCall());
            assertEquals(List.of(1), partial.incompleteParts());
            assertEquals("toolu_3", partial.toolCalls().getFirst().id(), "the real id, for rendering");

            llm.complete(model, ASK.append(partial).appendUser("Go on."));
            var replayed = wire.body(1).objects("messages").get(1).objects("content");
            assertEquals(List.of(Json.object("type", "text", "text", "Checking.")), replayed, "the cut-off tool call is omitted");
        }
    }

    @Test
    void cacheWritesAreSplitByTtlAndAbsentCountersStayAbsent() {
        var classes = REPLY.replace("\"cache_creation_input_tokens\":5,",
                "\"cache_creation_input_tokens\":5,\"cache_creation\":{\"ephemeral_5m_input_tokens\":2,\"ephemeral_1h_input_tokens\":3},");
        var gateway = REPLY.replace("\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":5,", "");
        var wire = new WireScript().json(classes).json(gateway);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5");
            var usage = llm.complete(model, ASK).usage();
            assertEquals(Map.of(CacheRetention.SHORT, 2L, CacheRetention.LONG, 3L), usage.cacheWrites());
            assertEquals(5, usage.cacheWrite().orElseThrow());
            var prices = Prices.usd().input("3").output("15").cacheRead("0.3").cacheWrite("3.75").cacheWriteLong("6").build();
            assertEquals(new BigDecimal("0.0000255"), prices.cost(usage).orElseThrow().cacheWrite().stripTrailingZeros(), "2 × 3.75 + 3 × 6 per million");
            assertEquals(new BigDecimal("0.00001875"), Prices.usd().input("3").output("15").cacheRead("0.3").cacheWrite("3.75").build()
                    .cost(usage).orElseThrow().cacheWrite().stripTrailingZeros(), "without a 1-hour price every write costs the same");

            var unreported = llm.complete(model, ASK).usage();
            assertTrue(unreported.cacheRead().isEmpty());
            assertTrue(unreported.cacheWrite().isEmpty());
            assertTrue(unreported.totalInput().isEmpty(), "no wrong total");
        }
    }

    @Test
    void breakpointsCarryTheirOwnTtlLongerFirst() {
        var ok = REPLY.replace("tool_use\",\n", "end_turn\",\n");
        var wire = new WireScript().json(ok).json(ok);
        var stable = Conversation.builder().system("Stable instructions.").cacheBreakpoint(CacheRetention.LONG)
                .user("First question").cacheBreakpoint().build();
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5");
            llm.complete(model, stable);
            assertEquals(Json.object("type", "ephemeral", "ttl", "1h"), wire.body(0).objects("system").getFirst().object("cache_control"));
            assertEquals(Json.object("type", "ephemeral"), wire.body(0).objects("messages").getFirst().objects("content").getLast().object("cache_control"));

            var inverted = Conversation.builder().system("Stable instructions.").cacheBreakpoint().user("q").cacheBreakpoint(CacheRetention.LONG).build();
            var reply = llm.complete(model, inverted);
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("cache_hint_ignored")), reply.warnings().toString());
            assertEquals(Json.object("type", "ephemeral"), wire.body(1).objects("messages").getFirst().objects("content").getLast().object("cache_control"));
            assertThrows(InvalidRequestException.class, () -> llm.complete(model, inverted, ChatOptions.builder().strict().build()));
            assertEquals(Conversation.fromJson(stable.toJson()), stable);
            assertEquals("ai-gate.conversation/2", stable.toJson().string("schema"));
        }
    }

    @Test
    void raisingMaxTokensAboveTheBudgetIsAnAdaptationAndTheLimitIsReported() {
        try (var llm = new WireScript().runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5-20250929");
            var budget = ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).maxTokens(2000);
            var prepared = llm.prepare(model, ASK, budget.build(), false);
            assertEquals(8192 + 4096, prepared.effectiveOptions().maxTokens().orElseThrow());
            assertTrue(prepared.request().warnings().stream().anyMatch(w -> w.code().equals("option_adapted")));
            var strict = assertThrows(InvalidRequestException.class, () -> llm.prepare(model, ASK, budget.strict().build(), false));
            assertEquals(ErrorCode.UNSUPPORTED_FEATURE, strict.code());
            assertEquals(ApiFeatures.OutputCap.ENFORCED, llm.features(model).outputCap());
            assertEquals(4, llm.features(model).maxCacheMarkers());
        }
    }

    @Test
    void aPreparedCallSendsItsPreviewedBodyAndCountsItsTokensAtTheEndpoint() {
        var wire = new WireScript().json("{\"input_tokens\":1234}").json(REPLY).error(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"no\"}}");
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var prepared = llm.prepare(llm.model("anthropic", "claude-sonnet-4-5"), ASK, ChatOptions.builder().maxTokens(500).build(), false);
            assertEquals(new TokenCount(1234, true, TokenCount.ENDPOINT, 0), llm.countTokens(prepared));
            var counting = wire.calls().getFirst();
            assertTrue(counting.uri().toString().endsWith("/v1/messages/count_tokens"), counting.uri().toString());
            assertEquals("2023-06-01", counting.headers().get("anthropic-version"));
            assertTrue(wire.body(0).get("max_tokens").isEmpty(), "count_tokens takes no output limit");
            assertEquals(((JsonObject) prepared.request().body()).get("messages"), wire.body(0).get("messages"));

            llm.complete(prepared);
            assertEquals(prepared.request().body(), wire.body(1), "sent byte for byte as previewed");
            assertEquals(TokenCount.ESTIMATE, llm.countTokens(prepared).method(), "a gateway without the endpoint falls back");
        }
    }

    @Test
    void aReplyArchivesWithRawUsageAndCallFactsAndReplaysUnchanged() {
        var wire = new WireScript().json(REPLY).json(REPLY).json(REPLY);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-sonnet-4-5-20250929");
            var options = ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).maxTokens(2000).build();
            var reply = llm.complete(model, ASK, options);
            var json = reply.toJson();
            assertEquals("ai-gate.reply/1", json.string("schema"));
            var restored = AssistantMessage.fromJson(json);
            assertEquals(reply, restored);
            assertEquals(reply.usage().raw(), restored.usage().raw());
            assertEquals(reply.info().requestId(), restored.info().requestId());
            assertEquals(reply.info().attemptsDetail(), restored.info().attemptsDetail());

            var result = List.of(ToolResult.of(reply.toolCalls().getFirst(), "18°C"));
            llm.complete(model, ASK.append(reply, result), options);
            llm.complete(model, ASK.append(restored, result), options);
            assertEquals(wire.body(1), wire.body(2), "the archived reply re-encodes to the same wire body");
        }
    }
}
