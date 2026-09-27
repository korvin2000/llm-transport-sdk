package net.ai.gate.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.ToolResultMessage;
import net.ai.gate.chat.UserMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.content.ToolResult;
import net.ai.gate.chat.options.ReasoningHandoff;
import net.ai.gate.error.InvalidRequestException;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonNull;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.vendors.anthropic.Anthropic;
import org.junit.jupiter.api.Test;

/// One conversation, any model: the documented hand-off rules, applied to the copy sent in one call.
class HandoffTest {
    private static final Model CLAUDE = Model.builder("anthropic", "claude-sonnet-5").input(Modality.TEXT, Modality.IMAGE).build();
    private static final Model TEXT_ONLY = Model.builder("anthropic", "claude-text").input(Modality.TEXT).build();
    private static final ModelRef GPT = new ModelRef("openai", "gpt-5.1");

    private static AssistantMessage fromGpt(Content... parts) {
        return AssistantMessage.builder(GPT, "openai-responses").content(List.of(parts)).build();
    }

    @Test
    void foreignReasoningTextIsKeptAsThinkingTextAndSignaturesNeverCross() {
        var reply = fromGpt(Content.Reasoning.of("Plan the answer.", "gpt-signature", false, JsonNull.INSTANCE), Content.text("450"));
        var conversation = Conversation.of("25 * 18?").append(reply).appendUser("Sure?");
        var notes = new Notes(false);

        var adapted = Handoff.adapt(conversation, Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.KEEP, notes);

        var turn = assertInstanceOf(AssistantMessage.class, adapted.messages().get(1));
        assertEquals(List.of(Content.text("<thinking>Plan the answer.</thinking>"), Content.text("450")), turn.content());
        assertTrue(notes.warnings().stream().anyMatch(w -> w.code().equals("reasoning_converted")));
        assertInstanceOf(Content.Reasoning.class, ((AssistantMessage) conversation.messages().get(1)).content().getFirst(),
                "the stored conversation is untouched");
    }

    @Test
    void dropOmitsForeignReasoningButNeverSameOriginReasoning() {
        var foreign = fromGpt(Content.reasoning("Plan."), Content.text("450"));
        var dropped = Handoff.adapt(Conversation.of("q").append(foreign), Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.DROP, new Notes(false));
        assertEquals(List.of(Content.text("450")), ((AssistantMessage) dropped.messages().get(1)).content());

        var own = AssistantMessage.builder(CLAUDE.ref(), Anthropic.MESSAGES.id())
                .content(List.of(Content.Reasoning.of("Plan.", "sig", false, JsonNull.INSTANCE), Content.text("450"))).build();
        var sameOrigin = Conversation.of("q").append(own);
        assertSame(own, Handoff.adapt(sameOrigin, Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.DROP, new Notes(false)).messages().get(1));
    }

    @Test
    void toolCallIdsAreNormalizedForTheTargetApiAndResultsFollow() {
        var call = ToolCall.of("fc_" + "x".repeat(300) + ":1", "read_file", Json.object("path", "a.txt"));
        var conversation = Conversation.of("read").append(fromGpt(call), List.of(ToolResult.of(call, "hello")));
        var notes = new Notes(false);

        var adapted = Handoff.adapt(conversation, Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.KEEP, notes);

        var id = ((AssistantMessage) adapted.messages().get(1)).toolCalls().getFirst().id();
        assertTrue(id.matches("[a-zA-Z0-9_-]{1,64}"), id);
        assertEquals(id, ((ToolResultMessage) adapted.messages().get(2)).results().getFirst().callId());
        assertTrue(notes.warnings().stream().anyMatch(w -> w.code().equals("tool_call_id_normalized")));
    }

    @Test
    void imagesBecomePlaceholdersForTextOnlyModelsOrFailWhenStrict() {
        var conversation = Conversation.builder().user(Content.text("What is this?"), Content.image(Path.of("photo.png"))).build();
        var notes = new Notes(false);
        var adapted = Handoff.adapt(conversation, Anthropic.MESSAGES, TEXT_ONLY, ReasoningHandoff.KEEP, notes);
        assertEquals(Content.text(Handoff.IMAGE_PLACEHOLDER), ((UserMessage) adapted.messages().getFirst()).content().get(1));
        assertTrue(notes.warnings().stream().anyMatch(w -> w.code().equals("image_omitted")));
        assertThrows(InvalidRequestException.class,
                () -> Handoff.adapt(conversation, Anthropic.MESSAGES, TEXT_ONLY, ReasoningHandoff.KEEP, new Notes(true)));
    }

    @Test
    void breakpointsAreRemappedPastDroppedTurns() {
        var conversation = Conversation.builder().user("q").message(fromGpt()).cacheBreakpoint().user("more").build();
        assertEquals(List.of(2), conversation.cacheBreakpoints());
        var notes = new Notes(false);
        var adapted = Handoff.adapt(conversation, Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.KEEP, notes);
        assertEquals(2, adapted.messages().size());
        assertEquals(List.of(1), adapted.cacheBreakpoints(), "the empty turn before the marker was dropped");
        assertTrue(notes.notes().stream().anyMatch(n -> n.code().equals("cache_breakpoints_remapped")));
        assertThrows(IllegalArgumentException.class, () -> conversation.withMessages(conversation.messages(), List.of(5)));
    }

    @Test
    void normalizedIdsThatCollideAreDisambiguatedConsistently() {
        var first = ToolCall.of("call:1", "a", Json.object());
        var second = ToolCall.of("call_1", "b", Json.object());
        var conversation = Conversation.of("go").append(fromGpt(first, second), List.of(ToolResult.of(first, "r1"), ToolResult.of(second, "r2")));
        var adapted = Handoff.adapt(conversation, Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.KEEP, new Notes(false));
        var calls = ((AssistantMessage) adapted.messages().get(1)).toolCalls();
        assertEquals(2, calls.stream().map(ToolCall::id).distinct().count(), calls.toString());
        var results = ((ToolResultMessage) adapted.messages().get(2)).results();
        assertEquals(calls.get(0).id(), results.get(0).callId());
        assertEquals(calls.get(1).id(), results.get(1).callId());
    }

    @Test
    void refusalsBecomeTextUnknownPartsAndEmptyTurnsAreDropped() {
        var conversation = Conversation.of("q")
                .append(fromGpt(Content.Refusal.of("I can't."), Content.Unknown.of("audio_transcript", Json.object())))
                .append(fromGpt());
        var adapted = Handoff.adapt(conversation, Anthropic.MESSAGES, CLAUDE, ReasoningHandoff.KEEP, new Notes(false));
        assertEquals(2, adapted.messages().size());
        assertEquals(List.of(Content.text("I can't.")), ((AssistantMessage) adapted.messages().get(1)).content());
    }
}
