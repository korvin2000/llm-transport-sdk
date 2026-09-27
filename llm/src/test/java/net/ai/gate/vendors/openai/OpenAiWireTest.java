package net.ai.gate.vendors.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.chat.tool.Tool;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.vendors.WireScript;
import org.junit.jupiter.api.Test;

class OpenAiWireTest {
    record Weather(String city) { }

    private static final Conversation ASK = Conversation.builder().system("Be brief.")
            .tool(Tool.of("weather", "Current weather", Weather.class)).user("Weather in Paris?").build();

    @Test
    void responsesEncodeDecodeAndReplayEncryptedReasoning() {
        var reply = """
                {"id":"resp_1","model":"gpt-5.1","status":"completed",
                 "output":[{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"Use the tool."}],"encrypted_content":"enc-1"},
                           {"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"See docs",
                             "annotations":[{"type":"url_citation","url":"https://example.com","title":"Docs","start_index":4,"end_index":8}]}]},
                           {"id":"fc_1","type":"function_call","call_id":"call_1","name":"weather","arguments":"{\\"city\\":\\"Paris\\"}"}],
                 "usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":100},"output_tokens":30,
                          "output_tokens_details":{"reasoning_tokens":12},"total_tokens":150}}""";
        var wire = new WireScript().json(reply).json(reply);
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = llm.model("openai", "gpt-5.1");
            var options = ChatOptions.builder().reasoning(ReasoningLevel.HIGH).sessionId("s-1").output(Weather.class).build();
            var message = llm.complete(model, ASK, options);

            assertEquals("https://api.openai.com/v1/responses", wire.calls().getFirst().uri().toString());
            var body = wire.body(0);
            assertEquals("Be brief.", body.string("instructions"));
            assertFalse(body.bool("store"));
            assertEquals(Json.object("effort", "high", "summary", "auto"), body.get("reasoning").orElseThrow());
            assertEquals(List.of(Json.valueOf("reasoning.encrypted_content")), body.array("include"));
            assertEquals("s-1", body.string("prompt_cache_key"));
            assertEquals("json_schema", body.object("text").object("format").string("type"));
            assertTrue(body.objects("tools").getFirst().bool("strict"), "record-derived tools are strict");

            assertEquals(StopReason.TOOL_USE, message.stopReason());
            assertEquals("Use the tool.", message.reasoningText().orElseThrow());
            assertEquals("Docs", ((Content.Text) message.content().get(1)).citations().getFirst().title());
            assertEquals(20, message.usage().input().orElseThrow());
            assertEquals(100, message.usage().cacheRead().orElseThrow());
            assertEquals(12, message.usage().reasoning().orElseThrow());

            llm.complete(model, ASK.append(message, List.of(ToolResult.of(message.toolCalls().getFirst(), "18°C"))), options);
            var input = wire.body(1).objects("input");
            assertEquals(Json.object("id", "rs_1", "type", "reasoning", "summary", List.of(Json.object("type", "summary_text", "text", "Use the tool.")),
                    "encrypted_content", "enc-1"), input.get(1));
            assertEquals(Json.object("role", "assistant", "content", "See docs"), input.get(2));
            assertEquals("call_1", input.get(3).string("call_id"));
            assertEquals(Json.object("type", "function_call_output", "call_id", "call_1", "output", "18°C"), input.get(4));
        }
    }

    @Test
    void responsesStreamItemsAndDeltas() {
        var wire = new WireScript().sse(
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_2\",\"model\":\"gpt-5.1\"}}",
                "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"message\"}}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"Hel\"}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"lo\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]}}",
                "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"weather\"}}",
                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"{\\\"city\\\":\\\"Rome\\\"}\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"Rome\\\"}\"}}",
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\",\"status\":\"completed\",\"output\":[{\"type\":\"function_call\"}],\"usage\":{\"input_tokens\":5,\"output_tokens\":6}}}");
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY"); var stream = llm.stream(llm.model("openai", "gpt-5.1"), ASK)) {
            var events = stream.events().toList();
            assertTrue(events.stream().anyMatch(e -> e instanceof ChatEvent.TextDelta d && d.text().equals("Hel")));
            var reply = ((ChatEvent.Done) events.getLast()).message();
            assertEquals(List.of(Content.Text.of("Hello", List.of()), ToolCall.of("call_9", "weather", "{\"city\":\"Rome\"}")), reply.content());
            assertEquals(StopReason.TOOL_USE, reply.stopReason());
            assertEquals(6, reply.usage().output().orElseThrow());
        }
    }

    @Test
    void completionsStreamToolCallsAndUsageOnCompatibleProviders() {
        var wire = new WireScript().sse(
                "{\"id\":\"c1\",\"model\":\"deepseek-v4-pro\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"Think\"}}]}",
                "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Let me check.\"}}]}",
                "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"\"}}]}}]}",
                "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"Oslo\\\"}\"}}]}}]}",
                "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "{\"id\":\"c1\",\"choices\":[],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":9,\"prompt_cache_hit_tokens\":40,\"total_tokens\":59}}",
                "[DONE]")
                .json("{\"id\":\"c2\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Sunny\"},\"finish_reason\":\"stop\"}]}");
        try (var llm = wire.runtime(OpenAiCompatible.deepSeek(), "DEEPSEEK_API_KEY")) {
            var model = llm.model("deepseek", "deepseek-v4-pro");
            var options = ChatOptions.builder().reasoning(ReasoningLevel.HIGH).maxTokens(500).build();
            AssistantMessage reply;
            try (var stream = llm.stream(model, ASK, options)) { reply = stream.result(); }
            var body = wire.body(0);
            assertEquals("https://api.deepseek.com/v1/chat/completions", wire.calls().getFirst().uri().toString());
            assertEquals(500, body.optLong("max_tokens").orElseThrow(), "the preset's max-tokens field");
            assertEquals("system", body.objects("messages").getFirst().string("role"), "no developer role on DeepSeek");
            assertEquals(Json.object("type", "enabled"), body.get("thinking").orElseThrow());
            assertEquals(Json.object("include_usage", true), body.get("stream_options").orElseThrow());

            assertEquals(List.of(Content.reasoning("Think"), Content.text("Let me check."), ToolCall.of("call_a", "weather", "{\"city\":\"Oslo\"}")), reply.content());
            assertEquals(StopReason.TOOL_USE, reply.stopReason());
            assertEquals(10, reply.usage().input().orElseThrow());
            assertEquals(40, reply.usage().cacheRead().orElseThrow());

            var answer = llm.complete(model, ASK.append(reply, List.of(ToolResult.of(reply.toolCalls().getFirst(), "cold"))), options);
            assertEquals("Sunny", answer.text());
            var messages = wire.body(1).objects("messages");
            assertEquals("Think", messages.get(2).string("reasoning_content"), "DeepSeek replays reasoning on tool turns");
            assertEquals("call_a", messages.get(2).objects("tool_calls").getFirst().string("id"));
            assertEquals(Json.object("role", "tool", "tool_call_id", "call_a", "content", "cold"), messages.get(3));
        }
    }

    @Test
    void openRouterUsesItsReasoningObjectAndSessionHeader() {
        var wire = new WireScript().json("{\"choices\":[{\"message\":{\"content\":\"ok\",\"reasoning\":\"r\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":0}}}");
        try (var llm = wire.runtime(OpenAiCompatible.openRouter(), "OPENROUTER_API_KEY")) {
            var reply = llm.complete(llm.model("openrouter", "anthropic/claude-sonnet-4.5"), Conversation.of("hi"),
                    ChatOptions.builder().reasoning(ReasoningLevel.LOW).sessionId("abc").build());
            assertEquals(Json.object("effort", "low"), wire.body(0).get("reasoning").orElseThrow());
            assertEquals("abc", wire.calls().getFirst().headers().get("x-session-id"));
            assertEquals("r", reply.reasoningText().orElseThrow());
        }
    }

    @Test
    void reasoningModelsGetNoSamplingParametersAndAWarning() {
        var ok = "{\"id\":\"r\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";
        var wire = new WireScript().json(ok).json(ok);
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var reply = llm.complete(llm.model("openai", "gpt-5"), Conversation.of("hi"), ChatOptions.builder().temperature(0.2).topP(0.9).build());
            assertEquals("ok", reply.text());
            assertFalse(wire.body(0).members().containsKey("temperature"));
            assertFalse(wire.body(0).members().containsKey("top_p"));
            assertTrue(reply.warnings().stream().anyMatch(w -> w.code().equals("option_dropped") && w.message().contains("temperature")), reply.warnings().toString());

            llm.complete(llm.model("openai", "gpt-5.1"), Conversation.of("hi"), ChatOptions.builder().temperature(0.2).reasoning(ReasoningLevel.OFF).build());
            assertEquals(0.2, wire.body(1).get("temperature").map(v -> Json.convert(v, Double.class)).orElseThrow(), "no reasoning: sampling applies");
        }
    }

    @Test
    void responsesDecodeGeneratedImagesAndReplayTheirItem() {
        var item = "{\"id\":\"ig_1\",\"type\":\"image_generation_call\",\"status\":\"completed\",\"output_format\":\"webp\","
                + "\"revised_prompt\":\"a red cat\",\"result\":\"UE5HMQ==\"}";
        var wire = new WireScript().sse(
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_i\"}}",
                "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"ig_1\",\"type\":\"image_generation_call\",\"status\":\"in_progress\"}}",
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + item + "}",
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_i\",\"status\":\"completed\",\"output\":[]}}")
                .json("{\"id\":\"resp_j\",\"status\":\"completed\",\"output\":[" + item + "]}")
                .json("{\"id\":\"resp_k\",\"status\":\"completed\",\"output\":[]}");
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = llm.model("openai", "gpt-5.1");
            AssistantMessage streamed;
            try (var stream = llm.stream(model, Conversation.of("Draw a cat"))) { streamed = stream.result(); }
            var reply = llm.complete(model, Conversation.of("Draw a cat"));
            assertEquals(streamed.content(), reply.content(), "the stream's authoritative item decodes as the whole reply does");

            var image = (Content.Image) reply.content().getFirst();
            assertEquals("image/webp", image.mediaType());
            assertEquals(new Content.Source.Inline("PNG1".getBytes(StandardCharsets.US_ASCII)), image.source());
            assertEquals("a red cat", ((JsonObject) image.providerData()).string("revised_prompt"));

            var history = Conversation.fromJson(Conversation.of("Draw a cat").append(reply).appendUser("Make it blue").toJson());
            llm.complete(model, history);
            assertEquals(Json.object("type", "image_generation_call", "id", "ig_1", "status", "completed", "result", "UE5HMQ=="),
                    wire.body(2).objects("input").get(1), "replayed as its item, also after a JSON round trip");
        }
    }

    @Test
    void completionsDecodeAudioOutputAndReplayItsTranscript() {
        var wire = new WireScript()
                .json("{\"id\":\"a1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"audio\":{\"id\":\"audio_1\",\"data\":\"AAEC\",\"expires_at\":1,\"transcript\":\"Hello!\"}},\"finish_reason\":\"stop\"}]}")
                .sse("{\"id\":\"a2\",\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"id\":\"audio_2\",\"transcript\":\"Hel\"}}}]}",
                        "{\"id\":\"a2\",\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"data\":\"AAE=\",\"transcript\":\"lo\"}}}]}",
                        "{\"id\":\"a2\",\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"data\":\"Ag==\"}},\"finish_reason\":\"stop\"}]}",
                        "[DONE]")
                .json("{\"id\":\"a3\",\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
        try (var llm = wire.runtime(OpenAi.provider(), "OPENAI_API_KEY")) {
            var model = Model.builder("openai", "gpt-audio").api(OpenAi.CHAT_COMPLETIONS.id()).build();
            var reply = llm.complete(model, Conversation.of("Say hello"));
            assertEquals(List.of(Content.Audio.of(new byte[] {0, 1, 2}, "wav", "Hello!")), reply.content());

            AssistantMessage streamed;
            try (var stream = llm.stream(model, Conversation.of("Say hello"))) { streamed = stream.result(); }
            assertEquals(List.of(Content.Audio.of(new byte[] {0, 1, 2}, "pcm16", "Hello")), streamed.content(), "chunks decoded one by one");

            llm.complete(model, Conversation.of("Say hello").append(reply).appendUser("Again"));
            assertEquals(Json.object("role", "assistant", "content", "Hello!"), wire.body(2).objects("messages").get(1), "audio ids expire: the transcript replays");
        }
    }

    @Test
    void mistralGetsNineCharacterToolCallIdsThatPairCallsWithResults() {
        var wire = new WireScript().json("{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}");
        var foreign = AssistantMessage.builder(new ModelRef("openai", "gpt-5.1"), OpenAi.RESPONSES.id())
                .add(ToolCall.of("call_Fz3lQv9Xw2dK8mNcR7pT4yBs", "weather", "{\"city\":\"Paris\"}"))
                .add(ToolCall.of("abcDEF123", "weather", "{\"city\":\"Rome\"}")).stopReason(StopReason.TOOL_USE).build();
        var history = ASK.append(foreign, List.of(ToolResult.of(foreign.toolCalls().get(0), "18°C"), ToolResult.of(foreign.toolCalls().get(1), "25°C")));
        try (var llm = wire.runtime(OpenAiCompatible.mistral(), "MISTRAL_API_KEY")) {
            llm.complete(llm.model("mistral", "mistral-large-latest"), history);
            var messages = wire.body(0).objects("messages");
            var calls = messages.get(2).objects("tool_calls");
            var rewritten = calls.get(0).string("id");
            assertTrue(rewritten.matches("[a-zA-Z0-9]{9}"), rewritten);
            assertEquals("abcDEF123", calls.get(1).string("id"), "Mistral's own ids stay");
            assertEquals(rewritten, messages.get(3).string("tool_call_id"));
            assertEquals("abcDEF123", messages.get(4).string("tool_call_id"));
        }
    }
}
