package net.ai.gate.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.chat.stream.ChatEvent;
import net.ai.gate.json.Json;
import net.ai.gate.model.ModelRef;
import org.junit.jupiter.api.Test;

class AccumulatorTest {
    private final Accumulator accumulator = new Accumulator(new ModelRef("test", "m"), "test-api");

    @Test
    void aBlankCallIdIsReplacedByOneFromThePartIndexAndReportedToTheCaller() {
        assertEquals(new ChatEvent.ToolCallStart(0, "call_0", "weather"), accumulator.accept(new ChatEvent.ToolCallStart(0, "", "weather")));
        assertEquals(new ChatEvent.ToolCallStart(2, "call_2", "weather"), accumulator.accept(new ChatEvent.ToolCallStart(2, "  ", "weather")));
        accumulator.accept(new ChatEvent.ToolCallDelta(0, "{}", Json.object()));
        accumulator.accept(new ChatEvent.ToolCallDelta(2, "{}", Json.object()));
        assertEquals(List.of(ToolCall.of("call_0", "weather", "{}"), ToolCall.of("call_2", "weather", "{}")), accumulator.snapshot().content());
    }

    @Test
    void aLaterBlankStartKeepsTheIdOfTheFirstOne() {
        accumulator.accept(new ChatEvent.ToolCallStart(0, "call_a", "weather"));
        assertEquals(new ChatEvent.ToolCallStart(0, "call_a", "weather"), accumulator.accept(new ChatEvent.ToolCallStart(0, "", "weather")));
        assertEquals(List.of(ToolCall.of("call_a", "weather", "")), accumulator.snapshot().content());
    }

    @Test
    void theRouteOfTheStartReachesThePartialAndTheFinalReply() {
        accumulator.accept(ChatEvent.Started.of("gen-1", "vendor/m-2025", "Fireworks"));
        accumulator.accept(new ChatEvent.TextDelta(0, "hi"));
        assertEquals("Fireworks", accumulator.snapshot().info().route().orElseThrow());
        var done = (ChatEvent.Done) accumulator.accept(ChatEvent.Done.of(AssistantMessage.builder(new ModelRef("test", "m"), "test-api").build()));
        assertEquals("Fireworks", done.message().info().route().orElseThrow());
        assertEquals("vendor/m-2025", done.message().responseModel().orElseThrow());
    }

    @Test
    void aRepeatedStartAddsTheRouteWithoutErasingWhatWasSeen() {
        accumulator.accept(ChatEvent.Started.of("gen-1", "vendor/m-2025"));
        accumulator.accept(new ChatEvent.TextDelta(0, "hi"));
        assertEquals(true, accumulator.snapshot().info().route().isEmpty());
        accumulator.accept(ChatEvent.Started.of(null, null, "Fireworks"));
        var partial = accumulator.snapshot();
        assertEquals("Fireworks", partial.info().route().orElseThrow());
        assertEquals("gen-1", partial.responseId().orElseThrow());
        assertEquals("vendor/m-2025", partial.responseModel().orElseThrow());
    }

    @Test
    void argumentsThatArriveBeforeAnyStartStillGetADistinctId() {
        accumulator.accept(new ChatEvent.ToolCallDelta(0, "{}", Json.object()));
        accumulator.accept(new ChatEvent.ToolCallDelta(1, "{}", Json.object()));
        assertEquals(List.of("call_0", "call_1"), accumulator.snapshot().toolCalls().stream().map(ToolCall::id).toList());
    }
}
