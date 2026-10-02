package net.ai.gate.event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.error.ErrorCode;
import net.ai.gate.metadata.Attempt;
import net.ai.gate.metadata.Cost;
import net.ai.gate.metadata.Usage;
import net.ai.gate.metadata.Warning;
import net.ai.gate.model.ModelRef;
import org.jspecify.annotations.Nullable;

/// The lifecycle of one call: `Started` → `Retrying`* → `FirstOutput`? → `Progress`* → `Finished`, in order.
public sealed interface RequestEvent extends LlmEvent {
    String requestId();
    String providerId();
    ModelRef model();

    final class Started extends CallEventBase implements RequestEvent {
        private final String api;
        private final boolean streaming;

        private Started(String requestId, ModelRef model, Map<String, String> tags, Instant at, String api, boolean streaming) {
            super(requestId, model, tags, at);
            this.api = api; this.streaming = streaming;
        }

        public static Started of(String requestId, ModelRef model, Map<String, String> tags, Instant at, String api, boolean streaming) {
            return new Started(requestId, model, tags, at, api, streaming);
        }

        public String api() { return api; }
        public boolean streaming() { return streaming; }
    }

    /// The model's first output arrived: in a stream, the first text, reasoning or tool-call event — not the first
    /// byte, nor a usage or lifecycle event; for a non-streamed call, the decoded response. `latency` runs from the
    /// call's start (credentials, connection, retries and backoff included): a time to first output, not a TTFT.
    final class FirstOutput extends CallEventBase implements RequestEvent {
        private final Duration latency;

        private FirstOutput(String requestId, ModelRef model, Map<String, String> tags, Instant at, Duration latency) {
            super(requestId, model, tags, at);
            this.latency = latency;
        }

        public static FirstOutput of(String requestId, ModelRef model, Map<String, String> tags, Instant at, Duration latency) {
            return new FirstOutput(requestId, model, tags, at, latency);
        }

        public Duration latency() { return latency; }
    }

    /// A stream is producing output: emitted at most every [#INTERVAL] from the stream loop, so a host that owns the
    /// stream can show "the model is typing" to a UI subscribed only to events. Content-free, like every event.
    final class Progress extends CallEventBase implements RequestEvent {
        public static final Duration INTERVAL = Duration.ofMillis(250);

        private final @Nullable Long outputTokens;
        private final long outputChars;

        private Progress(String requestId, ModelRef model, Map<String, String> tags, Instant at, @Nullable Long outputTokens, long outputChars) {
            super(requestId, model, tags, at);
            this.outputTokens = outputTokens; this.outputChars = outputChars;
        }

        public static Progress of(String requestId, ModelRef model, Map<String, String> tags, Instant at, @Nullable Long outputTokens, long outputChars) {
            return new Progress(requestId, model, tags, at, outputTokens, outputChars);
        }

        /// Output tokens reported so far by the provider, where it reports them while streaming.
        public OptionalLong outputTokens() { return outputTokens == null ? OptionalLong.empty() : OptionalLong.of(outputTokens); }
        /// Characters of text, reasoning and tool arguments received so far.
        public long outputChars() { return outputChars; }
    }

    final class Retrying extends CallEventBase implements RequestEvent {
        private final int attempt;
        private final Duration delay;
        private final ErrorCode errorCode;

        private Retrying(String requestId, ModelRef model, Map<String, String> tags, Instant at, int attempt, Duration delay, ErrorCode errorCode) {
            super(requestId, model, tags, at);
            this.attempt = attempt; this.delay = delay; this.errorCode = errorCode;
        }

        public static Retrying of(String requestId, ModelRef model, Map<String, String> tags, Instant at, int attempt,
                                  Duration delay, ErrorCode errorCode) {
            return new Retrying(requestId, model, tags, at, attempt, delay, errorCode);
        }

        /// The attempt about to start (2 = first retry).
        public int attempt() { return attempt; }
        public Duration delay() { return delay; }
        /// Why the previous attempt failed.
        public ErrorCode errorCode() { return errorCode; }
    }

    /// The call ended. `CANCELLED` reports only what the client observed: closing a socket does not prove the
    /// provider stopped work or billing.
    final class Finished extends CallEventBase implements RequestEvent {
        public enum Outcome { COMPLETED, FAILED, CANCELLED }

        private final Outcome outcome;
        private final Usage usage;
        private final Duration latency;
        private final @Nullable Duration timeToFirstOutput;
        private final int attempts;
        private final List<Attempt> attemptsDetail;
        private final List<Warning> warnings;
        private final boolean fromCache, outcomeUnknown;
        private final @Nullable ErrorCode errorCode;
        private final @Nullable String providerRequestId;

        private Finished(Builder b) {
            super(b.requestId, b.model, b.tags, b.at);
            outcome = b.outcome; usage = b.usage; latency = b.latency; timeToFirstOutput = b.timeToFirstOutput;
            attempts = b.attempts; attemptsDetail = List.copyOf(b.attemptsDetail); warnings = List.copyOf(b.warnings); fromCache = b.fromCache;
            outcomeUnknown = b.outcomeUnknown; errorCode = b.errorCode; providerRequestId = b.providerRequestId;
        }

        public static Builder builder(String requestId, ModelRef model, Map<String, String> tags, Instant at, Outcome outcome) {
            return new Builder(requestId, model, tags, at, outcome);
        }

        public Outcome outcome() { return outcome; }
        public Usage usage() { return usage; }
        public Optional<Cost> cost() { return usage.cost(); }
        public Duration latency() { return latency; }
        public Optional<Duration> timeToFirstOutput() { return Optional.ofNullable(timeToFirstOutput); }
        public int attempts() { return attempts; }
        /// One entry per attempt, in order.
        public List<Attempt> attemptsDetail() { return attemptsDetail; }
        public List<Warning> warnings() { return warnings; }
        public boolean fromCache() { return fromCache; }
        public Optional<ErrorCode> errorCode() { return Optional.ofNullable(errorCode); }
        public boolean outcomeUnknown() { return outcomeUnknown; }
        public Optional<String> providerRequestId() { return Optional.ofNullable(providerRequestId); }

        @Override public String toString() { return "Finished[" + requestId() + ", " + outcome + ", " + usage + "]"; }

        /// Not thread-safe.
        public static final class Builder {
            private final String requestId;
            private final ModelRef model;
            private final Map<String, String> tags;
            private final Instant at;
            private final Outcome outcome;
            private Usage usage = Usage.empty();
            private Duration latency = Duration.ZERO;
            private @Nullable Duration timeToFirstOutput;
            private int attempts;
            private List<Attempt> attemptsDetail = List.of();
            private List<Warning> warnings = List.of();
            private boolean fromCache, outcomeUnknown;
            private @Nullable ErrorCode errorCode;
            private @Nullable String providerRequestId;

            private Builder(String requestId, ModelRef model, Map<String, String> tags, Instant at, Outcome outcome) {
                this.requestId = requestId; this.model = model; this.tags = tags; this.at = at; this.outcome = outcome;
            }

            public Builder usage(Usage value) { usage = value; return this; }
            public Builder latency(Duration value) { latency = value; return this; }
            public Builder timeToFirstOutput(@Nullable Duration value) { timeToFirstOutput = value; return this; }
            public Builder attempts(int value) { attempts = value; return this; }
            public Builder attemptsDetail(List<Attempt> values) { attemptsDetail = values; return this; }
            public Builder warnings(List<Warning> values) { warnings = values; return this; }
            public Builder fromCache(boolean value) { fromCache = value; return this; }
            public Builder outcomeUnknown(boolean value) { outcomeUnknown = value; return this; }
            public Builder errorCode(@Nullable ErrorCode value) { errorCode = value; return this; }
            public Builder providerRequestId(@Nullable String value) { providerRequestId = value; return this; }
            public Finished build() { return new Finished(this); }
        }
    }
}
