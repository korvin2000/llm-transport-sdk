package net.ai.gate.vendors.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNull;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.vendors.WireScript;
import org.junit.jupiter.api.Test;

class GeminiWireTest {
    record Weather(String city) { }

    private static final Conversation ASK = Conversation.builder().system("Be brief.")
            .tool(Tool.of("weather", "Current weather", Weather.class)).user("Weather in Paris?").build();

    @Test
    void encodesDecodesAndReplaysThoughtSignatures() {
        var reply = """
                {"responseId":"r1","modelVersion":"gemini-2.5-pro",
                 "candidates":[{"content":{"role":"model","parts":[
                     {"text":"Plan","thought":true,"thoughtSignature":"sig-t"},
                     {"functionCall":{"name":"weather","args":{"city":"Paris"}},"thoughtSignature":"sig-c"}]},
                   "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":100,"cachedContentTokenCount":60,"candidatesTokenCount":10,"thoughtsTokenCount":5,"totalTokenCount":115}}""";
        var wire = new WireScript().json(reply).json(reply);
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY")) {
            var model = llm.model("google", "gemini-2.5-pro");
            var options = ChatOptions.builder().reasoning(ReasoningLevel.MEDIUM).maxTokens(1000).build();
            var message = llm.complete(model, ASK, options);

            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent", wire.calls().getFirst().uri().toString());
            var body = wire.body(0);
            assertEquals("Be brief.", body.object("systemInstruction").objects("parts").getFirst().string("text"));
            var config = body.object("generationConfig");
            assertEquals(1000, config.optLong("maxOutputTokens").orElseThrow());
            assertEquals(Json.object("thinkingBudget", 8192, "includeThoughts", true), config.get("thinkingConfig").orElseThrow());
            assertEquals("weather", body.objects("tools").getFirst().objects("functionDeclarations").getFirst().string("name"));

            assertEquals(StopReason.TOOL_USE, message.stopReason());
            assertEquals(List.of(Content.Reasoning.of("Plan", "sig-t", false, JsonNull.INSTANCE), Content.Reasoning.of(null, "sig-c", false, JsonNull.INSTANCE)),
                    message.content().subList(0, 2));
            var call = message.toolCalls().getFirst();
            assertEquals(new Weather("Paris"), call.arguments(Weather.class));
            assertEquals(40, message.usage().input().orElseThrow());
            assertEquals(60, message.usage().cacheRead().orElseThrow());
            assertEquals(15, message.usage().output().orElseThrow(), "thoughts are output tokens");

            llm.complete(model, ASK.append(message, List.of(ToolResult.of(call, "18°C"))), options);
            var contents = wire.body(1).objects("contents");
            var parts = contents.get(1).objects("parts");
            assertEquals(Json.object("text", "Plan", "thought", true, "thoughtSignature", "sig-t"), parts.get(0));
            assertEquals(Json.object("functionCall", Json.object("name", "weather", "args", Json.object("city", "Paris")), "thoughtSignature", "sig-c"), parts.get(1));
            assertEquals(Json.object("functionResponse", Json.object("name", "weather", "response", Json.object("output", "18°C"))),
                    contents.get(2).objects("parts").getFirst());
        }
    }

    @Test
    void gemini3UsesThinkingLevelsAndStreams() {
        var wire = new WireScript().sse(
                "{\"responseId\":\"r2\",\"modelVersion\":\"gemini-3-pro-preview\",\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Th\",\"thought\":true}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ink\",\"thought\":true,\"thoughtSignature\":\"s1\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":4,\"candidatesTokenCount\":2,\"totalTokenCount\":6}}");
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY");
             var stream = llm.stream(llm.model("google", "gemini-3-pro-preview"), Conversation.of("hi"),
                     ChatOptions.builder().reasoning(ReasoningLevel.XHIGH).build())) {
            var reply = stream.result();
            assertTrue(wire.calls().getFirst().uri().toString().endsWith("models/gemini-3-pro-preview:streamGenerateContent?alt=sse"));
            assertEquals("HIGH", wire.body(0).object("generationConfig").object("thinkingConfig").string("thinkingLevel"));
            assertEquals(List.of(Content.Reasoning.of("Think", "s1", false, JsonNull.INSTANCE), Content.text("Hello")), reply.content());
            assertEquals(StopReason.STOP, reply.stopReason());
            assertEquals("r2", reply.responseId().orElseThrow());
        }
    }

    @Test
    void cachedContentsLifecycle() {
        var cached = "{\"name\":\"cachedContents/abc\",\"model\":\"models/gemini-2.5-pro\",\"expireTime\":\"2030-01-01T00:00:00Z\","
                + "\"usageMetadata\":{\"totalTokenCount\":4096}}";
        var wire = new WireScript().json(cached).json(cached).json("{\"cachedContents\":[" + cached + "],\"nextPageToken\":\"p2\"}")
                .json("{\"cachedContents\":[]}").json(cached).json("{}");
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY")) {
            var caches = llm.providerApi("google", Gemini.CACHES);
            var created = caches.create(llm.model("google", "gemini-2.5-pro"), List.of(UserMessage.of("A long document")), Duration.ofMinutes(10));
            assertEquals("cachedContents/abc", created.name());
            assertEquals("gemini-2.5-pro", created.model().modelId());
            assertEquals(Instant.parse("2030-01-01T00:00:00Z"), created.expiresAt().orElseThrow());
            assertEquals(4096, created.usage().cacheWrite().orElseThrow());
            assertEquals(Json.object("model", "models/gemini-2.5-pro", "contents", List.of(Json.object("role", "user",
                    "parts", List.of(Json.object("text", "A long document")))), "ttl", "600s"), wire.body(0));

            caches.get("cachedContents/abc");
            assertEquals(1, caches.list().size());
            assertTrue(wire.calls().get(3).uri().toString().endsWith("cachedContents?pageToken=p2"));
            caches.extend("cachedContents/abc", Duration.ofHours(1));
            assertEquals("PATCH", wire.calls().get(4).method());
            assertEquals(Json.object("ttl", "3600s"), wire.body(4));
            caches.delete("cachedContents/abc");
            assertEquals("DELETE", wire.calls().get(5).method());
            assertThrows(IllegalArgumentException.class, () -> caches.get("../models"));
        }
    }

    @Test
    void cachedContentIsSentWithTheRequest() {
        var wire = new WireScript().json("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}");
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY")) {
            AssistantMessage reply = llm.complete(llm.model("google", "gemini-2.5-flash"), Conversation.of("hi"),
                    ChatOptions.builder().provider(GeminiOptions.builder().cachedContent("cachedContents/abc").build()).build());
            assertEquals("ok", reply.text());
            assertEquals("cachedContents/abc", wire.body(0).string("cachedContent"));
            assertTrue(reply.toolCalls().isEmpty() && !(reply.content().getFirst() instanceof ToolCall));
        }
    }

    @Test
    void generatedImagesAndAudioDecodeAsMediaAndReplayAsInlineData() {
        var reply = """
                {"responseId":"r-img","modelVersion":"gemini-2.5-flash-image",
                 "candidates":[{"content":{"role":"model","parts":[{"text":"Here it is."},
                    {"inlineData":{"mimeType":"image/png","data":"UE5HMQ=="},"thoughtSignature":"sig-i"},
                    {"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"AAEC"}}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":6,"totalTokenCount":10}}""";
        var wire = new WireScript().json(reply).sse(reply.replace("\n", "")).json(reply);
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY")) {
            var model = llm.model("google", "gemini-2.5-flash-image");
            var message = llm.complete(model, Conversation.of("Draw and say it"));
            var expected = List.of(Content.text("Here it is."), Content.Reasoning.of(null, "sig-i", false, JsonNull.INSTANCE),
                    Content.image(new byte[] {'P', 'N', 'G', '1'}, "image/png"), Content.Audio.of(new byte[] {0, 1, 2}, "L16;codec=pcm;rate=24000", null));
            assertEquals(expected, message.content());
            try (var stream = llm.stream(model, Conversation.of("Draw and say it"))) { assertEquals(expected, stream.result().content()); }

            llm.complete(model, Conversation.of("Draw and say it").append(message).appendUser("Again"));
            assertEquals(List.of(Json.object("text", "Here it is."),
                    Json.object("inlineData", Json.object("mimeType", "image/png", "data", "UE5HMQ=="), "thoughtSignature", "sig-i"),
                    Json.object("inlineData", Json.object("mimeType", "audio/L16;codec=pcm;rate=24000", "data", "AAEC"))),
                    wire.body(2).objects("contents").get(1).objects("parts"));
        }
    }

    @Test
    void functionCallIdsOfTheApiGoBackWithTheCallAndItsResponse() {
        var wire = new WireScript()
                .json("{\"responseId\":\"r2\",\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
                        + "{\"id\":\"fc-9\",\"name\":\"weather\",\"args\":{\"city\":\"Paris\"}}}]},\"finishReason\":\"STOP\"}]}")
                .json("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Mild.\"}]},\"finishReason\":\"STOP\"}]}");
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY")) {
            var model = llm.model("google", "gemini-2.5-flash");
            var message = llm.complete(model, ASK);
            assertEquals("fc-9", message.toolCalls().getFirst().id());
            llm.complete(model, ASK.append(message, List.of(ToolResult.of(message.toolCalls().getFirst(), "18°C"))));
            var contents = wire.body(1).objects("contents");
            assertEquals("fc-9", contents.get(1).objects("parts").getFirst().object("functionCall").string("id"));
            assertEquals("fc-9", contents.get(2).objects("parts").getFirst().object("functionResponse").string("id"));
        }
    }

    @Test
    void aFunctionCallWithABlankIdGetsTheIdAMissingOneWould() {
        var withId = "{\"responseId\":\"r3\",\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
                + "{\"id\":\"%s\",\"name\":\"weather\",\"args\":{\"city\":\"Paris\"}}}]},\"finishReason\":\"STOP\"}]}";
        var wire = new WireScript().json(withId.formatted("")).json(withId.formatted(" ")).json(withId.replace("\"id\":\"%s\",", ""));
        try (var llm = wire.runtime(Gemini.provider(), "GEMINI_API_KEY")) {
            var model = llm.model("google", "gemini-2.5-flash");
            var blank = llm.complete(model, ASK).toolCalls().getFirst().id();
            assertTrue(blank.startsWith("call_ai-gate_"), blank);
            assertEquals(blank, llm.complete(model, ASK).toolCalls().getFirst().id());
            assertEquals(blank, llm.complete(model, ASK).toolCalls().getFirst().id());
        }
    }
}
