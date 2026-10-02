package net.ai.gate.chat.stream;

import java.util.Optional;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonValue;
import net.ai.gate.metadata.Usage;
import org.jspecify.annotations.Nullable;

/// Sealed stream events. Deltas and part ends are frozen record shapes; lifecycle events are classes that may gain
/// accessors. Keep a `default` branch: variants may be added in minor releases. Parts are identified by `index`
/// and may interleave.
public sealed interface ChatEvent {
    record TextDelta(int index, String text) implements ChatEvent { }

    record ReasoningDelta(int index, String text) implements ChatEvent { }

    record ToolCallStart(int index, String callId, String name) implements ChatEvent { }

    /// `partialArguments()`: best-effort parse of the arguments so far, never validated. Decoders may pass an empty
    /// object; the core fills it from the accumulated fragments.
    record ToolCallDelta(int index, String fragment, JsonObject partialArguments) implements ChatEvent { }

    /// The authoritative completed part: text, reasoning with its signature, a [ToolCall], an image, a refusal.
    record PartEnd(int index, Content content) implements ChatEvent { }

    /// An event no variant models, preserved as received.
    record Unknown(String type, JsonValue raw) implements ChatEvent { }

    /// Usage reported before the end of the stream (`finalForCall() == false`); the last one is what a partial reply
    /// carries. `Done` stays authoritative: its usage is the call's, never a sum of updates.
    record UsageUpdate(Usage observed) implements ChatEvent { }

    /// The response began; ids where the API reports them, and the upstream a gateway reports it routed to.
    final class Started implements ChatEvent {
        private final @Nullable String responseId, responseModel, route;
        private Started(@Nullable String responseId, @Nullable String responseModel, @Nullable String route) {
            this.responseId = responseId; this.responseModel = responseModel; this.route = route;
        }
        public static Started of(@Nullable String responseId, @Nullable String responseModel) { return new Started(responseId, responseModel, null); }
        public static Started of(@Nullable String responseId, @Nullable String responseModel, @Nullable String route) { return new Started(responseId, responseModel, route); }
        public Optional<String> responseId() { return Optional.ofNullable(responseId); }
        public Optional<String> responseModel() { return Optional.ofNullable(responseModel); }
        /// See `ResponseInfo.route()`.
        public Optional<String> route() { return Optional.ofNullable(route); }
        @Override public String toString() { return "Started[" + responseId + "]"; }
    }

    /// The last event of a successful stream. Consumers receive the aggregate, equal to what `complete()` returns;
    /// decoders emit it with stop reason, usage and ids, and the core fills the content.
    final class Done implements ChatEvent {
        private final AssistantMessage message;
        private Done(AssistantMessage message) { this.message = message; }
        public static Done of(AssistantMessage message) { return new Done(message); }
        public AssistantMessage message() { return message; }
        @Override public String toString() { return "Done[" + message.stopReason() + "]"; }
    }
}
