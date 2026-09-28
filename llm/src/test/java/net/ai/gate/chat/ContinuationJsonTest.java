package net.ai.gate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.ModelRef;
import org.junit.jupiter.api.Test;

/// Version 3 of the conversation and options forms: a reply's continuation handle and compaction parts.
class ContinuationJsonTest {
    private static final ModelRef GPT = new ModelRef("openai", "gpt-5.1");
    private static final Continuation HANDLE = new Continuation(GPT, "openai-responses", "resp_1",
            Optional.of(Instant.parse("2026-10-28T00:00:00Z")), OptionalLong.of(18));

    @Test
    void continuationAndCompactionUseVersionThreeAndRoundTrip() {
        var summary = Content.Compaction.of("Summary.", Json.object("type", "compaction", "content", "Summary.", "signature", "sig"));
        var reply = AssistantMessage.builder(GPT, "openai-responses").add(summary).stopReason(StopReason.COMPACTION).continuation(HANDLE).build();
        var conversation = Conversation.of("hi").append(reply);

        var json = conversation.toJson();
        assertEquals("ai-gate.conversation/3", json.string("schema"));
        var restored = Conversation.fromJson(json);
        assertEquals(conversation, restored);
        var restoredReply = (AssistantMessage) restored.messages().get(1);
        assertEquals(HANDLE, restoredReply.continuation().orElseThrow());
        assertEquals(summary, restoredReply.compaction().orElseThrow());
        assertTrue(HANDLE.continues(restoredReply));

        assertEquals("ai-gate.reply/2", reply.toJson().string("schema"));
        assertEquals(reply, AssistantMessage.fromJson(reply.toJson()));
        assertEquals("ai-gate.reply/1", AssistantMessage.builder(GPT, "openai-responses").text("plain").build().toJson().string("schema"),
                "older readers keep reading plain replies");
        assertEquals("ai-gate.conversation/1", Conversation.of("hi").toJson().string("schema"));
    }

    @Test
    void continueFromIsAVersionThreeOptionMember() {
        var options = ChatOptions.builder().continueFrom(HANDLE).maxTokens(5).build();
        var json = options.toJson();
        assertEquals("ai-gate.options/3", json.string("schema"));
        assertEquals("resp_1", json.object("continueFrom").string("id"));
        var read = ChatOptions.fromJson(json);
        assertEquals(HANDLE, read.continuation().orElseThrow());
        assertEquals(json, read.toJson());
        assertEquals("ai-gate.options/1", ChatOptions.builder().maxTokens(5).build().toJson().string("schema"));
        assertTrue(ChatOptions.builder().continueFrom(HANDLE).build().overriddenBy(ChatOptions.builder().build()).continuation().isPresent(), "inherited");

        var bad = (JsonObject) Json.parse("{\"schema\":\"ai-gate.options/3\",\"continueFrom\":{\"api\":\"x\",\"id\":\"y\"}}");
        var error = assertThrows(IllegalArgumentException.class, () -> ChatOptions.fromJson(bad));
        assertTrue(error.getMessage().startsWith("continueFrom.model:"), error.getMessage());
    }
}
