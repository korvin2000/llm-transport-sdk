package net.ai.gate.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.ai.gate.chat.content.Content;
import net.ai.gate.chat.content.ToolCall;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.InvalidResponseException;
import net.ai.gate.error.LlmException;
import net.ai.gate.internal.serialization.ConversationJson;
import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonValue;
import net.ai.gate.metadata.ResponseInfo;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ModelRef;
import org.jspecify.annotations.Nullable;

/// Immutable, thread-safe model reply — and the history entry at once: `conversation.append(reply)` continues the
/// conversation with any model. `model()` and `api()` record its origin, which drives the hand-off rules. A partial
/// reply (stream failure or cancellation) names the parts that did not finish in [#incompleteParts()]: they render,
/// but are never sent back to a model.
public final class AssistantMessage implements Message {
    private final List<Content> content;
    private final List<Integer> incompleteParts;
    private final StopReason stopReason;
    private final @Nullable String errorMessage, responseModel, responseId;
    private final Usage usage;
    private final ModelRef model;
    private final String api;
    private final List<Warning> warnings;
    private final ResponseInfo info;
    private final Instant timestamp;
    private final @Nullable Continuation continuation;

    private AssistantMessage(Builder b) {
        continuation = b.continuation;
        content = List.copyOf(b.content); stopReason = b.stopReason; errorMessage = b.errorMessage;
        incompleteParts = List.copyOf(b.incompleteParts);
        if (!incompleteParts.isEmpty() && incompleteParts.getLast() >= content.size())
            throw new IllegalArgumentException("Incomplete part " + incompleteParts.getLast() + " is not among " + content.size() + " parts");
        responseModel = b.responseModel; responseId = b.responseId; usage = b.usage; model = b.model; api = b.api;
        warnings = List.copyOf(b.warnings); info = b.info; timestamp = b.timestamp != null ? b.timestamp : Instant.now();
    }

    /// For codecs and tests: a reply of `model` received through the wire API `api`.
    public static Builder builder(ModelRef model, String api) { return new Builder(model, api); }

    public List<Content> content() { return content; }

    /// Indices into [#content()] of parts cut off before they were complete — a tool call with partial arguments,
    /// reasoning without its signature; empty for a complete reply.
    public List<Integer> incompleteParts() { return incompleteParts; }

    /// No part is incomplete.
    public boolean complete() { return incompleteParts.isEmpty(); }

    /// Text parts only: no reasoning, refusal or tool arguments.
    public String text() {
        return content.stream().filter(Content.Text.class::isInstance).map(c -> ((Content.Text) c).text()).collect(Collectors.joining());
    }

    public List<ToolCall> toolCalls() { return content.stream().filter(ToolCall.class::isInstance).map(ToolCall.class::cast).toList(); }
    public boolean hasToolCalls() { return content.stream().anyMatch(ToolCall.class::isInstance); }

    /// Readable reasoning, only as far as the provider returned it.
    public Optional<String> reasoningText() {
        var text = content.stream().filter(Content.Reasoning.class::isInstance)
                .flatMap(c -> ((Content.Reasoning) c).text().stream()).collect(Collectors.joining());
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    public Optional<String> refusal() {
        return content.stream().filter(Content.Refusal.class::isInstance).map(c -> ((Content.Refusal) c).text()).findFirst();
    }

    public StopReason stopReason() { return stopReason; }
    /// Partial replies (`ABORTED`, `ERROR`) only.
    public Optional<String> errorMessage() { return Optional.ofNullable(errorMessage); }
    public Usage usage() { return usage; }
    public ModelRef model() { return model; }
    public String api() { return api; }
    /// The concrete model reported by the provider (gateways and aliases).
    public Optional<String> responseModel() { return Optional.ofNullable(responseModel); }
    public Optional<String> responseId() { return Optional.ofNullable(responseId); }
    /// Adaptations made for the call that produced this reply.
    public List<Warning> warnings() { return warnings; }
    /// Transient facts about the call; not part of the JSON form.
    public ResponseInfo info() { return info; }
    @Override public Instant timestamp() { return timestamp; }

    /// Server-side state this reply left behind, for `ChatOptions.Builder.continueFrom(…)`: the OpenAI Responses
    /// `previous_response_id` of a stored reply. Absent when the API keeps none, which is the default everywhere.
    public Optional<Continuation> continuation() { return Optional.ofNullable(continuation); }

    /// The summary of a `Llm.compact` reply (stop reason `compaction`), if this reply carries one.
    public Optional<Content.Compaction> compaction() {
        return content.stream().filter(Content.Compaction.class::isInstance).map(Content.Compaction.class::cast).findFirst();
    }

    /// Parses `text()` as JSON.
    /// @throws InvalidResponseException `output_invalid` (`partial()` keeps this reply)
    public JsonValue json() {
        try {
            return Json.parse(text());
        } catch (IllegalArgumentException e) {
            throw failure(ErrorCode.OUTPUT_INVALID, "The reply is not valid JSON: " + e.getMessage());
        }
    }

    /// Binds `json()` to `type`; truncation and refusal fail before binding.
    /// @throws InvalidResponseException `output_truncated`, `output_refused` or `output_invalid`
    public <T> T as(Class<T> type) {
        if (stopReason.equals(StopReason.LENGTH)) throw failure(ErrorCode.OUTPUT_TRUNCATED, "The reply was truncated by the output limit");
        if (stopReason.equals(StopReason.REFUSAL) || refusal().isPresent())
            throw failure(ErrorCode.OUTPUT_REFUSED, "The model refused: " + refusal().orElse("no reason given"));
        try {
            return Json.convert(json(), type);
        } catch (IllegalArgumentException e) {
            throw failure(ErrorCode.OUTPUT_INVALID, "The reply does not fit " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private InvalidResponseException failure(ErrorCode code, String message) {
        return new InvalidResponseException(LlmException.Details.builder(code, message)
                .requestId(info.requestId().isEmpty() ? null : info.requestId()).providerId(model.providerId()).partial(this).build());
    }

    public Builder toBuilder() {
        var b = new Builder(model, api).content(content).stopReason(stopReason).errorMessage(errorMessage).usage(usage)
                .responseModel(responseModel).responseId(responseId).warnings(warnings).info(info).continuation(continuation);
        b.incompleteParts.addAll(incompleteParts);
        b.timestamp = timestamp;
        return b;
    }

    /// An archive form (`ai-gate.reply/1`, `/2` with a continuation or compaction) — the message as in a conversation's JSON form, plus the provider's raw usage
    /// and the call facts of [#info()], which the conversation form leaves out.
    public JsonObject toJson() { return ConversationJson.writeReply(this); }

    /// Reads [#toJson()]'s form; performs no I/O.
    /// @throws IllegalArgumentException naming the JSON path that does not fit
    public static AssistantMessage fromJson(JsonObject json) { return ConversationJson.readReply(json); }

    @Override public boolean equals(Object o) {
        return o instanceof AssistantMessage m && content.equals(m.content) && incompleteParts.equals(m.incompleteParts) && stopReason.equals(m.stopReason)
                && Objects.equals(errorMessage, m.errorMessage) && usage.equals(m.usage) && model.equals(m.model)
                && api.equals(m.api) && Objects.equals(responseModel, m.responseModel) && Objects.equals(responseId, m.responseId)
                && Objects.equals(continuation, m.continuation);
    }

    @Override public int hashCode() { return Objects.hash(content, stopReason, usage, model, api, responseId); }

    @Override public String toString() {
        return "AssistantMessage[" + model + ", stop=" + stopReason + ", parts=" + content + ", " + usage + "]";
    }

    /// Not thread-safe.
    public static final class Builder {
        private final ModelRef model;
        private final String api;
        private final List<Content> content = new ArrayList<>();
        private final TreeSet<Integer> incompleteParts = new TreeSet<>();
        private StopReason stopReason = StopReason.STOP;
        private @Nullable String errorMessage, responseModel, responseId;
        private Usage usage = Usage.empty();
        private final List<Warning> warnings = new ArrayList<>();
        private ResponseInfo info = ResponseInfo.empty();
        private @Nullable Instant timestamp;
        private @Nullable Continuation continuation;

        private Builder(ModelRef model, String api) { this.model = model; this.api = Checks.notBlank(api, "API id"); }

        public Builder add(Content part) { content.add(part); return this; }
        public Builder text(String text) { return add(Content.text(text)); }
        /// Replaces the parts and forgets which were incomplete.
        public Builder content(List<? extends Content> parts) { content.clear(); content.addAll(parts); incompleteParts.clear(); return this; }
        /// Marks the part at `index` of the content as cut off.
        public Builder incompletePart(int index) {
            if (index < 0) throw new IllegalArgumentException("Part index must not be negative: " + index);
            incompleteParts.add(index);
            return this;
        }
        public Builder stopReason(StopReason reason) { stopReason = reason; return this; }
        public Builder errorMessage(@Nullable String message) { errorMessage = message; return this; }
        public Builder usage(Usage value) { usage = value; return this; }
        public Builder responseModel(@Nullable String id) { responseModel = id; return this; }
        public Builder responseId(@Nullable String id) { responseId = id; return this; }
        public Builder warning(Warning warning) { warnings.add(warning); return this; }
        public Builder warnings(List<Warning> values) { warnings.clear(); warnings.addAll(values); return this; }
        public Builder info(ResponseInfo value) { info = value; return this; }
        public Builder timestamp(Instant value) { timestamp = value; return this; }
        /// The server-side state the API kept for this reply; `null` for none.
        public Builder continuation(@Nullable Continuation value) { continuation = value; return this; }
        public AssistantMessage build() { return new AssistantMessage(this); }
    }
}
