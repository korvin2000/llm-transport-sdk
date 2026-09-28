package net.ai.gate.vendors.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.chat.tool.ToolChoice;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonSchema;
import net.ai.gate.vendors.WireScript;
import org.junit.jupiter.api.Test;

/// Compaction on the Messages API: the on-demand request under its beta, the signed block that replaces the history,
/// usage from the iterations, threshold blocks in streams, and endpoints without betas.
class AnthropicCompactionTest {
    private static final String SUMMARY_TEXT = "Summary: the recipe app's entities are Recipe, Ingredient and Step.";
    private static final String SUMMARY = """
            {"id":"msg_c","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"compaction","content":"%s","signature":"EuYB=="}],
             "stop_reason":"compaction",
             "usage":{"input_tokens":0,"output_tokens":0,"iterations":[{"type":"compaction","input_tokens":144,"output_tokens":276}]}}"""
            .formatted(SUMMARY_TEXT);
    private static final String ANSWER = """
            {"id":"msg_a","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":"title, servings, prep_minutes."}],"stop_reason":"end_turn",
             "usage":{"input_tokens":90,"output_tokens":12}}""";

    private static final Conversation DESIGN = Conversation.builder().system("Keep answers short.")
            .tool(Tool.function("lookup").description("Looks things up").parameters(JsonSchema.of(Json.object("type", "object", "properties", Json.object()))).build())
            .user("Name the entities.").assistant("Recipe, Ingredient, Step.").user("Fields for Recipe?").build();

    @Test
    void compactOnDemandSendsSummarizeUnderItsBetaAndTheSignedBlockReplaysFirst() {
        var wire = new WireScript().json(SUMMARY).json(ANSWER);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-opus-5-5");
            assertTrue(llm.features(model).compaction());
            var options = ChatOptions.builder().stop("END").toolChoice(ToolChoice.required()).maxTokens(4096)
                    .provider(AnthropicOptions.builder().compactionInstructions("Keep every entity name.").build()).build();
            var summary = llm.compact(model, DESIGN, options);

            var call = wire.calls().getFirst();
            assertEquals("https://api.anthropic.com/v1/messages", call.uri().toString());
            assertEquals("compact-2026-09-04", call.headers().get("anthropic-beta"));
            var body = wire.body(0);
            assertEquals(Json.object("type", "summarize", "instructions", "Keep every entity name."), body.get("compaction").orElseThrow());
            assertEquals("Keep answers short.", body.objects("system").getFirst().string("text"));
            assertEquals("lookup", body.objects("tools").getFirst().string("name"), "the summariser reads the same tools");
            assertEquals(3, body.objects("messages").size());
            assertEquals(4096, body.optLong("max_tokens").orElseThrow());
            assertFalse(body.members().containsKey("stream"));
            assertFalse(body.members().containsKey("stop_sequences"), "rejected on a compaction request");
            assertFalse(body.members().containsKey("tool_choice"), "a forced tool is rejected on a compaction request");
            assertEquals(2, summary.warnings().stream().filter(w -> w.code().equals("option_dropped")).count(), summary.warnings().toString());

            assertEquals(StopReason.COMPACTION, summary.stopReason());
            assertEquals(SUMMARY_TEXT, summary.compaction().orElseThrow().text().orElseThrow());
            assertEquals(144, summary.usage().input().orElseThrow(), "the summarisation iteration, not the empty top-level counters");
            assertEquals(276, summary.usage().output().orElseThrow());
            assertEquals("msg_c", summary.responseId().orElseThrow());

            var next = Conversation.fromJson(DESIGN.withMessages(List.of(summary)).appendUser("Fields for Ingredient?").toJson());
            var answer = llm.complete(model, next);
            assertEquals("title, servings, prep_minutes.", answer.text());
            assertEquals("compact-2026-09-04", wire.calls().get(1).headers().get("anthropic-beta"), "every request that carries the block");
            var messages = wire.body(1).objects("messages");
            assertEquals("assistant", messages.getFirst().string("role"));
            assertEquals(Json.object("type", "compaction", "content", SUMMARY_TEXT, "signature", "EuYB=="),
                    messages.getFirst().objects("content").getFirst(), "the block replays exactly as returned");
            assertEquals("Fields for Ingredient?", messages.get(1).objects("content").getFirst().string("text"));
        }
    }

    @Test
    void thresholdCompactionBlocksStreamWholeAndReplayUnderTheirOwnBeta() {
        var wire = new WireScript().sse(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_t\",\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":23000}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"compaction\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"compaction_delta\",\"content\":\"Summary so far.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Next: error handling.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1000,"
                        + "\"iterations\":[{\"type\":\"compaction\",\"input_tokens\":180000,\"output_tokens\":3500},{\"type\":\"message\",\"input_tokens\":23000,\"output_tokens\":1000}]}}",
                "{\"type\":\"message_stop\"}")
                .json(ANSWER);
        try (var llm = wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY")) {
            var model = llm.model("anthropic", "claude-opus-5-5");
            var ask = Conversation.of("Help me build a web scraper");
            AssistantMessage reply;
            try (var stream = llm.stream(model, ask)) { reply = stream.result(); }
            assertEquals(List.of(Content.Compaction.of("Summary so far.", Json.object("type", "compaction", "content", "Summary so far.")),
                    Content.text("Next: error handling.")), reply.content());
            assertEquals(StopReason.STOP, reply.stopReason());
            assertEquals(180000 + 23000, reply.usage().input().orElseThrow(), "both iterations are billed");
            assertEquals(3500 + 1000, reply.usage().output().orElseThrow());

            llm.complete(model, ask.append(reply).appendUser("Now add error handling"));
            assertEquals("compact-2026-01-12", wire.calls().get(1).headers().get("anthropic-beta"));
            var blocks = wire.body(1).objects("messages").get(1).objects("content");
            assertEquals(Json.object("type", "compaction", "content", "Summary so far."), blocks.getFirst());
        }
    }

    @Test
    void endpointsWithoutBetaHeadersHaveNoCompaction() {
        var wire = new WireScript();
        try (var llm = wire.runtime(Anthropic.compatible("mini", URI.create("https://mini.test/v1")), "MINI_API_KEY")) {
            var model = llm.model("mini", "claude-x");
            assertFalse(llm.features(model).compaction());
            var error = assertThrows(InvalidRequestException.class, () -> llm.compact(model, DESIGN));
            assertEquals(ErrorCode.UNSUPPORTED_FEATURE, error.code());
            assertTrue(wire.calls().isEmpty());
        }
    }
}
