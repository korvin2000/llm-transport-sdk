package net.ai.gate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.tool.ProviderTool;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonSchema;
import net.ai.gate.metadata.Cost;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ModelRef;
import org.junit.jupiter.api.Test;

/// The canonical JSON form of [Conversation] (`ai-gate.conversation/1`).
class ConversationJsonTest {
    @Test
    void richConversationRoundTrips() {
        var call = ToolCall.of("call-1", "lookup", "{not json");
        var toolResult = ToolResult.error(call, "placeholder")
                .withContent(List.of(Content.text("boom"), Content.image(URI.create("https://x.test/pic.png"))));

        var citation = new Content.Citation("Source A", URI.create("https://src.test/a"), 0, 5);
        var text = Content.Text.of("hello world", List.of(citation));
        var reasoning = Content.Reasoning.of("thinking...", "sig-xyz", false, Json.object("k", "v"));
        var unknown = Content.Unknown.of("vendor_specific", Json.object("weird", true));
        var refusal = Content.Refusal.of("cannot help with that");

        var imageRemote = Content.image(URI.create("https://cdn.test/photo.jpg"));
        var imageRef = (Content.Image) Content.fileRef("file-abc", "image/png");
        var docInline = Content.document("hello".getBytes(StandardCharsets.UTF_8), "text/plain");
        var audio = Content.Audio.of(new byte[] {1, 2, 3, 4}, "wav", "hi there");

        var usage = Usage.builder().input(100).cacheRead(10).cacheWrite(5).output(20).reasoning(3).total(135)
                .cost(new Cost(Currency.getInstance("USD"), new BigDecimal("0.0010"), new BigDecimal("0.0001"),
                        new BigDecimal("0.0005"), new BigDecimal("0.0060"), new BigDecimal("0.0076")))
                .build();
        var at = Instant.parse("2026-01-01T00:00:00Z");

        var assistant = AssistantMessage.builder(new ModelRef("anthropic", "claude-x"), "anthropic-messages")
                .content(List.of(Content.text("Sure, here it is."), reasoning, unknown, refusal, call))
                .stopReason(StopReason.TOOL_USE).usage(usage).responseModel("claude-x-20260101").responseId("resp-1")
                .warning(new Warning("reasoning_clamped", "clamped to HIGH")).timestamp(at).build();
        var user = UserMessage.of(List.of(text, imageRemote, imageRef, docInline, audio), at);
        var toolMessage = ToolResultMessage.of(toolResult);

        var functionTool = Tool.function("search").description("Web search")
                .parameters(JsonSchema.of(Json.object("type", "object", "properties", Json.object()))).strict().build();
        var providerTool = ProviderTool.of("openai-responses", "web_search", Json.object("type", "web_search"));

        var conversation = Conversation.builder().system("Be nice").tool(functionTool).tool(providerTool)
                .message(user).cacheBreakpoint().message(assistant).message(toolMessage).build();

        var json = conversation.toJson();
        assertEquals("ai-gate.conversation/1", json.string("schema"));
        assertEquals(json.toJson(), conversation.toJson().toJson(), "toJson() is deterministic across calls");

        var restored = Conversation.fromJson(json);
        assertEquals(conversation, restored);

        // equals() ignores warnings and timestamps on messages; verify those explicitly.
        assertEquals(at, restored.messages().get(0).timestamp());
        assertEquals(at, restored.messages().get(1).timestamp());
        var restoredAssistant = (AssistantMessage) restored.messages().get(1);
        assertEquals(assistant.warnings(), restoredAssistant.warnings());
        assertEquals(List.of(1), restored.cacheBreakpoints());
    }

    @Test
    void invalidToolCallArgumentsSurviveVerbatim() {
        var conversation = Conversation.builder()
                .message(AssistantMessage.builder(new ModelRef("p", "m"), "api").add(ToolCall.of("c1", "t", "{ not: valid")).build())
                .build();
        var restored = Conversation.fromJson(conversation.toJson());
        var call = (ToolCall) ((AssistantMessage) restored.messages().getFirst()).content().getFirst();
        assertEquals("{ not: valid", call.argumentsJson());
    }

    @Test
    void writingIsDeterministic() {
        var conversation = Conversation.of("hi there");
        assertEquals(conversation.toJson().toJson(), conversation.toJson().toJson());
    }

    @Test
    void negativeFixturesNameTheJsonPath() {
        var noSchema = assertThrows(IllegalArgumentException.class, () -> Conversation.fromJson(Json.object("messages", Json.array())));
        assertTrue(noSchema.getMessage().startsWith("schema:"), noSchema.getMessage());

        var badRole = (JsonObject) Json.parse("""
                {"schema":"ai-gate.conversation/1","tools":[],
                 "messages":[{"role":"bogus","at":"2026-01-01T00:00:00Z","content":[]}]}""");
        var roleError = assertThrows(IllegalArgumentException.class, () -> Conversation.fromJson(badRole));
        assertTrue(roleError.getMessage().startsWith("messages[0].role:"), roleError.getMessage());

        var badPartType = (JsonObject) Json.parse("""
                {"schema":"ai-gate.conversation/1","tools":[],
                 "messages":[{"role":"user","at":"2026-01-01T00:00:00Z","content":[{"type":"bogus"}]}]}""");
        var typeError = assertThrows(IllegalArgumentException.class, () -> Conversation.fromJson(badPartType));
        assertTrue(typeError.getMessage().startsWith("messages[0].content[0].type:"), typeError.getMessage());

        var wrongTypeField = (JsonObject) Json.parse("""
                {"schema":"ai-gate.conversation/1","tools":[],
                 "messages":[{"role":"user","at":"2026-01-01T00:00:00Z","content":[{"type":"text","text":42}]}]}""");
        var textError = assertThrows(IllegalArgumentException.class, () -> Conversation.fromJson(wrongTypeField));
        assertTrue(textError.getMessage().startsWith("messages[0].content[0].text:"), textError.getMessage());

        var unknownTool = (JsonObject) Json.parse("""
                {"schema":"ai-gate.conversation/1","tools":[{"type":"bogus","name":"x"}],"messages":[]}""");
        var toolError = assertThrows(IllegalArgumentException.class, () -> Conversation.fromJson(unknownTool));
        assertTrue(toolError.getMessage().startsWith("tools[0].type:"), toolError.getMessage());

        var unknownSourceKind = (JsonObject) Json.parse("""
                {"schema":"ai-gate.conversation/1","tools":[],
                 "messages":[{"role":"user","at":"2026-01-01T00:00:00Z",
                              "content":[{"type":"image","mediaType":"image/png","source":{"kind":"bogus"}}]}]}""");
        var sourceError = assertThrows(IllegalArgumentException.class, () -> Conversation.fromJson(unknownSourceKind));
        assertTrue(sourceError.getMessage().startsWith("messages[0].content[0].source.kind:"), sourceError.getMessage());
    }

    @Test
    void partialRepliesKeepTheirMarksInVersionTwo() {
        var partial = AssistantMessage.builder(new ModelRef("anthropic", "claude-sonnet-5"), "anthropic-messages")
                .text("Checking").add(ToolCall.of("toolu_1", "weather", "{\"ci")).incompletePart(1)
                .usage(Usage.builder().input(12).cacheRead(300).cacheWrite(CacheRetention.SHORT, 2).cacheWrite(CacheRetention.LONG, 3).finalForCall(false).build())
                .stopReason(StopReason.ABORTED).build();
        var conversation = Conversation.of("q").append(partial);
        var json = conversation.toJson();
        assertEquals("ai-gate.conversation/2", json.string("schema"));
        var read = Conversation.fromJson(json);
        assertEquals(conversation, read);
        var restored = (AssistantMessage) read.messages().get(1);
        assertEquals(List.of(1), restored.incompleteParts());
        assertEquals(5, restored.usage().cacheWrite().orElseThrow());
        assertEquals("ai-gate.conversation/1", Conversation.of("q").toJson().string("schema"), "older readers keep reading plain conversations");
    }
}
