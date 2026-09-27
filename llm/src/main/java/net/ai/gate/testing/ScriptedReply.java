package net.ai.gate.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.ai.gate.chat.StopReason;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
import net.ai.gate.metadata.Usage;
import org.jspecify.annotations.Nullable;

/// One scripted answer of a [FakeProvider]: parts in order, optional usage (estimated when absent) and stop reason
/// (`TOOL_USE` when it calls tools, else `STOP`). Streamed in small chunks, tool arguments included. Immutable.
public final class ScriptedReply {
    private final List<Content> parts;
    private final @Nullable Usage usage;
    private final @Nullable StopReason stopReason;
    private final boolean truncated;

    private ScriptedReply(Builder b) {
        parts = List.copyOf(b.parts);
        usage = b.usage;
        stopReason = b.stopReason;
        truncated = b.truncated;
    }

    public static Builder builder() { return new Builder(); }
    public static ScriptedReply text(String text) { return builder().text(text).build(); }

    public List<Content> parts() { return parts; }
    public Optional<Usage> usage() { return Optional.ofNullable(usage); }

    public StopReason stopReason() {
        return stopReason != null ? stopReason : parts.stream().anyMatch(ToolCall.class::isInstance) ? StopReason.TOOL_USE : StopReason.STOP;
    }

    /// Cut short instead of served whole: streamed, the terminal `done` frame is left out (the core reports
    /// `stream_interrupted` with the partial reply); non-streamed, the body is cut in the middle of the JSON (the
    /// core reports `malformed_response`).
    public boolean truncated() { return truncated; }

    /// Not thread-safe.
    public static final class Builder {
        private final List<Content> parts = new ArrayList<>();
        private @Nullable Usage usage;
        private @Nullable StopReason stopReason;
        private boolean truncated;
        private int calls;

        private Builder() { }

        public Builder text(String text) { parts.add(Content.text(text)); return this; }
        /// Readable reasoning with a fake signature, as reasoning models return it.
        public Builder reasoning(String text) { parts.add(Content.Reasoning.of(text, "fake-signature", false, JsonNull.INSTANCE)); return this; }
        public Builder toolCall(String name, JsonObject arguments) { parts.add(ToolCall.of("call_" + (++calls), name, arguments)); return this; }
        public Builder part(Content part) { parts.add(part); return this; }
        public Builder usage(long input, long output) { usage = Usage.builder().input(input).output(output).cacheRead(0).cacheWrite(0).build(); return this; }
        public Builder stopReason(StopReason reason) { stopReason = reason; return this; }
        /// See [ScriptedReply#truncated()].
        public Builder truncated() { truncated = true; return this; }
        public ScriptedReply build() { return new ScriptedReply(this); }
    }
}
