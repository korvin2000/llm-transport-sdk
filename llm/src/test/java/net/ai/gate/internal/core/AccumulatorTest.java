package net.ai.gate.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

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
    void argumentsThatArriveBeforeAnyStartStillGetADistinctId() {
        accumulator.accept(new ChatEvent.ToolCallDelta(0, "{}", Json.object()));
        accumulator.accept(new ChatEvent.ToolCallDelta(1, "{}", Json.object()));
        assertEquals(List.of("call_0", "call_1"), accumulator.snapshot().toolCalls().stream().map(ToolCall::id).toList());
    }
}
