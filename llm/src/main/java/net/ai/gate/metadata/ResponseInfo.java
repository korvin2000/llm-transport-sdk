package net.ai.gate.metadata;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Immutable operational facts about how a reply was obtained; transient — not part of a conversation's JSON form.
public final class ResponseInfo {
    private static final ResponseInfo EMPTY = builder("", "").build();

    private final String requestId, providerId;
    private final @Nullable String providerRequestId, route;
    private final int attempts;
    private final List<Attempt> attemptsDetail;
    private final Duration latency;
    private final @Nullable Duration timeToFirstOutput;
    private final boolean fromCache;
    private final @Nullable RateLimits rateLimits;
    private final @Nullable JsonValue rawBody;

    private ResponseInfo(Builder b) {
        requestId = b.requestId; providerId = b.providerId; providerRequestId = b.providerRequestId; route = b.route;
        attempts = b.attempts; attemptsDetail = b.attemptsDetail; latency = b.latency; timeToFirstOutput = b.timeToFirstOutput; fromCache = b.fromCache;
        rateLimits = b.rateLimits; rawBody = b.rawBody;
    }

    /// For replies that did not come from a call (deserialized or constructed).
    public static ResponseInfo empty() { return EMPTY; }
    public static Builder builder(String requestId, String providerId) { return new Builder(requestId, providerId); }

    /// The SDK request id, shared with events, logs and JFR.
    public String requestId() { return requestId; }
    public String providerId() { return providerId; }
    /// The provider's request id, for support tickets.
    public Optional<String> providerRequestId() { return Optional.ofNullable(providerRequestId); }
    /// The upstream a gateway reports it routed the call to (OpenRouter `provider`); absent where the reply names none.
    /// The model that answered is `AssistantMessage.responseModel()`.
    public Optional<String> route() { return Optional.ofNullable(route); }
    public int attempts() { return attempts; }
    /// One entry per attempt, in order; empty for replies not obtained by a call (cached, deserialized, constructed).
    public List<Attempt> attemptsDetail() { return attemptsDetail; }
    /// From the call's start to its reply (or failure): every attempt, backoff and the whole stream.
    public Duration latency() { return latency; }
    /// From the call's start to the model's first output (see `RequestEvent.FirstOutput`) — not a time to first token;
    /// absent when no output arrived.
    public Optional<Duration> timeToFirstOutput() { return Optional.ofNullable(timeToFirstOutput); }
    public boolean fromCache() { return fromCache; }
    public Optional<RateLimits> rateLimits() { return Optional.ofNullable(rateLimits); }
    /// The redacted provider body of a non-streamed reply.
    public Optional<JsonValue> rawBody() { return Optional.ofNullable(rawBody); }

    public Builder toBuilder() {
        return new Builder(requestId, providerId).providerRequestId(providerRequestId).route(route).attempts(attempts)
                .attemptsDetail(attemptsDetail).latency(latency).timeToFirstOutput(timeToFirstOutput).fromCache(fromCache).rateLimits(rateLimits).rawBody(rawBody);
    }

    @Override public String toString() {
        return "ResponseInfo[" + requestId + ", attempts=" + attempts + ", latency=" + latency.toMillis() + "ms"
                + (fromCache ? ", fromCache" : "") + "]";
    }

    /// Not thread-safe.
    public static final class Builder {
        private final String requestId, providerId;
        private @Nullable String providerRequestId, route;
        private int attempts;
        private List<Attempt> attemptsDetail = List.of();
        private Duration latency = Duration.ZERO;
        private @Nullable Duration timeToFirstOutput;
        private boolean fromCache;
        private @Nullable RateLimits rateLimits;
        private @Nullable JsonValue rawBody;

        private Builder(String requestId, String providerId) { this.requestId = requestId; this.providerId = providerId; }

        public Builder providerRequestId(@Nullable String id) { providerRequestId = id; return this; }
        public Builder route(@Nullable String upstream) { route = upstream; return this; }
        public Builder attempts(int count) { attempts = count; return this; }
        public Builder attemptsDetail(List<Attempt> values) { attemptsDetail = List.copyOf(values); return this; }
        public Builder latency(Duration value) { latency = value; return this; }
        public Builder timeToFirstOutput(@Nullable Duration value) { timeToFirstOutput = value; return this; }
        public Builder fromCache(boolean value) { fromCache = value; return this; }
        public Builder rateLimits(@Nullable RateLimits value) { rateLimits = value; return this; }
        public Builder rawBody(@Nullable JsonValue body) { rawBody = body; return this; }
        public ResponseInfo build() { return new ResponseInfo(this); }
    }
}
