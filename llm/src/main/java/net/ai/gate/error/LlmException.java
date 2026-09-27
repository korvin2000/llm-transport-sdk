package net.ai.gate.error;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import net.ai.gate.chat.AssistantMessage;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Unchecked root of every failure of a call. Messages are for people; callers branch on the subclass and
/// [#code()]. A failed call leaves the runtime usable; [#partial()] keeps what was received before the failure.
public class LlmException extends RuntimeException {
    private final Details details;

    public LlmException(Details details) {
        super(details.message());
        this.details = details;
    }

    public LlmException(Details details, @Nullable Throwable cause) {
        super(details.message(), cause);
        this.details = details;
    }

    public Details details() { return details; }
    public ErrorCode code() { return details.code; }
    public boolean is(ErrorCode code) { return details.code.equals(code); }
    /// The SDK's assessment after its own retries.
    public boolean retryable() { return details.retryable; }
    /// The request may have executed — and been billed — remotely.
    public boolean outcomeUnknown() { return details.outcomeUnknown; }
    public OptionalInt httpStatus() { return details.httpStatus(); }
    public Optional<String> providerCode() { return details.providerCode(); }
    public Optional<String> providerRequestId() { return details.providerRequestId(); }
    /// The SDK request id; absent for local validation errors.
    public Optional<String> requestId() { return details.requestId(); }
    public Optional<String> providerId() { return details.providerId(); }
    public int attempts() { return details.attempts; }
    /// Bounded and redacted provider error body.
    public Optional<JsonValue> errorBody() { return details.errorBody(); }
    /// What was received before the failure — stop reason `ERROR` or `ABORTED`, usage included; appendable.
    public Optional<AssistantMessage> partial() { return details.partial(); }

    /// Immutable error facts. Codecs return them from `WireApi.decodeError`; the core selects the exception type from
    /// the code, adds call facts (request id, provider, attempts, outcome, partial) and throws.
    public static final class Details {
        private final ErrorCode code;
        private final String message;
        private final @Nullable Integer httpStatus;
        private final @Nullable String providerCode, providerRequestId, requestId, providerId;
        private final @Nullable Duration retryAfter;
        private final boolean retryable, outcomeUnknown;
        private final int attempts;
        private final @Nullable JsonValue errorBody;
        private final @Nullable AssistantMessage partial;

        private Details(Builder b) {
            code = b.code; message = b.message; httpStatus = b.httpStatus; providerCode = b.providerCode;
            providerRequestId = b.providerRequestId; requestId = b.requestId; providerId = b.providerId;
            retryAfter = b.retryAfter; retryable = b.retryable; outcomeUnknown = b.outcomeUnknown; attempts = b.attempts;
            errorBody = b.errorBody; partial = b.partial;
        }

        public static Builder builder(ErrorCode code, String message) { return new Builder(code, message); }

        public Builder toBuilder() {
            return new Builder(code, message).httpStatus(httpStatus).providerCode(providerCode)
                    .providerRequestId(providerRequestId).requestId(requestId).providerId(providerId)
                    .retryAfter(retryAfter).retryable(retryable).outcomeUnknown(outcomeUnknown).attempts(attempts)
                    .errorBody(errorBody).partial(partial);
        }

        public ErrorCode code() { return code; }
        public String message() { return message; }
        public OptionalInt httpStatus() { return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus); }
        public Optional<String> providerCode() { return Optional.ofNullable(providerCode); }
        public Optional<String> providerRequestId() { return Optional.ofNullable(providerRequestId); }
        public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }
        public boolean retryable() { return retryable; }
        public boolean outcomeUnknown() { return outcomeUnknown; }
        public Optional<String> requestId() { return Optional.ofNullable(requestId); }
        public Optional<String> providerId() { return Optional.ofNullable(providerId); }
        public int attempts() { return attempts; }
        public Optional<JsonValue> errorBody() { return Optional.ofNullable(errorBody); }
        public Optional<AssistantMessage> partial() { return Optional.ofNullable(partial); }

        @Override public String toString() { return "Details[" + code + ": " + message + "]"; }

        /// Not thread-safe.
        public static final class Builder {
            private final ErrorCode code;
            private final String message;
            private @Nullable Integer httpStatus;
            private @Nullable String providerCode, providerRequestId, requestId, providerId;
            private @Nullable Duration retryAfter;
            private boolean retryable, outcomeUnknown;
            private int attempts;
            private @Nullable JsonValue errorBody;
            private @Nullable AssistantMessage partial;

            private Builder(ErrorCode code, String message) { this.code = code; this.message = message; }

            public Builder httpStatus(@Nullable Integer status) { httpStatus = status; return this; }
            public Builder providerCode(@Nullable String code) { providerCode = code; return this; }
            public Builder providerRequestId(@Nullable String id) { providerRequestId = id; return this; }
            public Builder requestId(@Nullable String id) { requestId = id; return this; }
            public Builder providerId(@Nullable String id) { providerId = id; return this; }
            public Builder retryAfter(@Nullable Duration delay) { retryAfter = delay; return this; }
            public Builder retryable(boolean value) { retryable = value; return this; }
            public Builder outcomeUnknown(boolean value) { outcomeUnknown = value; return this; }
            public Builder attempts(int value) { attempts = value; return this; }
            public Builder errorBody(@Nullable JsonValue body) { errorBody = body; return this; }
            public Builder partial(@Nullable AssistantMessage reply) { partial = reply; return this; }
            public Details build() { return new Details(this); }
        }
    }
}
