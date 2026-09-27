package net.ai.gate.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.JsonArray;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonNumber;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Immutable retry rules. Defaults: 3 attempts; retry on pre-send connection failures and 408, 409, 429,
/// 503, 529 — responses providers document as *not processed*; honour `Retry-After` up to 60 s; exponential backoff
/// 500 ms × 2 capped at 8 s with full jitter. Never after visible stream output, never after an ambiguous post-send
/// failure (500, 502, 504, timeouts, resets): those surface with `outcomeUnknown()` unless the codec establishes
/// that the request was not processed. A host can then opt those statuses into its retry policy.
///
/// A policy may be partial: a field never set inherits from the wider scope (call ▷ provider ▷ runtime) and finally
/// from these defaults. The accessors always answer with the effective value.
public final class RetryPolicy {
    private static final int DEFAULT_ATTEMPTS = 3;
    private static final Set<Integer> DEFAULT_STATUSES = Set.of(408, 409, 429, 503, 529);
    private static final Duration DEFAULT_INITIAL = Duration.ofMillis(500), DEFAULT_MAX = Duration.ofSeconds(8),
            DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final RetryPolicy DEFAULTS = new RetryPolicy(DEFAULT_ATTEMPTS, DEFAULT_STATUSES, DEFAULT_INITIAL, DEFAULT_MULTIPLIER,
            DEFAULT_MAX, DEFAULT_RETRY_AFTER);
    private static final RetryPolicy NONE = builder().maxAttempts(1).build();

    private final @Nullable Integer maxAttempts;
    private final @Nullable Set<Integer> retryOnStatus;
    private final @Nullable Duration initialBackoff, maxBackoff, maxRetryAfter;
    private final @Nullable Double multiplier;

    private RetryPolicy(@Nullable Integer maxAttempts, @Nullable Set<Integer> retryOnStatus, @Nullable Duration initialBackoff,
                        @Nullable Double multiplier, @Nullable Duration maxBackoff, @Nullable Duration maxRetryAfter) {
        this.maxAttempts = maxAttempts; this.retryOnStatus = retryOnStatus == null ? null : Set.copyOf(retryOnStatus);
        this.initialBackoff = initialBackoff; this.multiplier = multiplier; this.maxBackoff = maxBackoff; this.maxRetryAfter = maxRetryAfter;
    }

    /// Every field at its documented default.
    public static RetryPolicy defaults() { return DEFAULTS; }
    /// Exactly one attempt; backoff fields inherit.
    public static RetryPolicy none() { return NONE; }
    /// A partial policy: fields left unset inherit.
    public static Builder builder() { return new Builder(); }

    public int maxAttempts() { return maxAttempts != null ? maxAttempts : DEFAULT_ATTEMPTS; }
    public Set<Integer> retryOnStatus() { return retryOnStatus != null ? retryOnStatus : DEFAULT_STATUSES; }
    public Duration initialBackoff() { return initialBackoff != null ? initialBackoff : DEFAULT_INITIAL; }
    public double backoffMultiplier() { return multiplier != null ? multiplier : DEFAULT_MULTIPLIER; }
    public Duration maxBackoff() { return maxBackoff != null ? maxBackoff : DEFAULT_MAX; }
    /// A longer `Retry-After` is not waited for: the call fails with the provider's hint.
    public Duration maxRetryAfter() { return maxRetryAfter != null ? maxRetryAfter : DEFAULT_RETRY_AFTER; }

    /// The backoff ceiling before `attempt` (2 = first retry); the delay is drawn uniformly below it (full jitter).
    public Duration backoffCeiling(int attempt) {
        double millis = initialBackoff().toMillis() * Math.pow(backoffMultiplier(), Math.max(0, attempt - 2));
        return Duration.ofMillis((long) Math.min(millis, maxBackoff().toMillis()));
    }

    /// Field by field: values set in `higher` win, unset ones keep this policy's.
    public RetryPolicy overriddenBy(RetryPolicy h) {
        return new RetryPolicy(h.maxAttempts != null ? h.maxAttempts : maxAttempts, h.retryOnStatus != null ? h.retryOnStatus : retryOnStatus,
                h.initialBackoff != null ? h.initialBackoff : initialBackoff, h.multiplier != null ? h.multiplier : multiplier,
                h.maxBackoff != null ? h.maxBackoff : maxBackoff, h.maxRetryAfter != null ? h.maxRetryAfter : maxRetryAfter);
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.maxAttempts = maxAttempts; b.retryOnStatus = retryOnStatus; b.initialBackoff = initialBackoff;
        b.multiplier = multiplier; b.maxBackoff = maxBackoff; b.maxRetryAfter = maxRetryAfter;
        return b;
    }

    /// The partial form; unset fields are omitted, not defaulted. `retryOnStatus` is written in ascending order.
    public JsonObject toJson() {
        var json = new LinkedHashMap<String, JsonValue>();
        if (maxAttempts != null) json.put("maxAttempts", JsonNumber.of((long) maxAttempts));
        if (retryOnStatus != null)
            json.put("retryOnStatus", JsonArray.of(retryOnStatus.stream().sorted().map(v -> (JsonValue) JsonNumber.of((long) v)).toList()));
        if (initialBackoff != null) json.put("initialBackoff", JsonString.of(initialBackoff.toString()));
        if (multiplier != null) json.put("backoffMultiplier", JsonNumber.of(multiplier));
        if (maxBackoff != null) json.put("maxBackoff", JsonString.of(maxBackoff.toString()));
        if (maxRetryAfter != null) json.put("maxRetryAfter", JsonString.of(maxRetryAfter.toString()));
        return JsonObject.of(json);
    }

    /// @throws IllegalArgumentException naming the member that does not fit
    public static RetryPolicy fromJson(JsonObject json) {
        var maxAttempts = optInt(json, "maxAttempts");
        var retryOnStatus = optIntSet(json, "retryOnStatus");
        var initialBackoff = optDuration(json, "initialBackoff");
        var multiplier = optDouble(json, "backoffMultiplier");
        var maxBackoff = optDuration(json, "maxBackoff");
        var maxRetryAfter = optDuration(json, "maxRetryAfter");
        return new RetryPolicy(maxAttempts, retryOnStatus, initialBackoff, multiplier, maxBackoff, maxRetryAfter);
    }

    private static @Nullable Integer optInt(JsonObject json, String name) {
        var value = json.get(name).orElse(null);
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof JsonNumber n) return (int) n.longValue();
        throw new IllegalArgumentException(name + ": expected a number");
    }

    private static @Nullable Double optDouble(JsonObject json, String name) {
        var value = json.get(name).orElse(null);
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof JsonNumber n) return n.doubleValue();
        throw new IllegalArgumentException(name + ": expected a number");
    }

    private static @Nullable Set<Integer> optIntSet(JsonObject json, String name) {
        var value = json.get(name).orElse(null);
        if (value == null || value instanceof JsonNull) return null;
        if (!(value instanceof JsonArray array)) throw new IllegalArgumentException(name + ": expected an array");
        var set = new LinkedHashSet<Integer>();
        for (var v : array.values()) {
            if (!(v instanceof JsonNumber n)) throw new IllegalArgumentException(name + ": expected numbers");
            set.add((int) n.longValue());
        }
        return set;
    }

    private static @Nullable Duration optDuration(JsonObject json, String name) {
        var value = json.get(name).orElse(null);
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof JsonString s) {
            try {
                return Duration.parse(s.value());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(name + ": not an ISO-8601 duration: " + s.value());
            }
        }
        throw new IllegalArgumentException(name + ": expected a string");
    }

    @Override public boolean equals(Object o) {
        return o instanceof RetryPolicy r && Objects.equals(maxAttempts, r.maxAttempts) && Objects.equals(retryOnStatus, r.retryOnStatus)
                && Objects.equals(initialBackoff, r.initialBackoff) && Objects.equals(multiplier, r.multiplier)
                && Objects.equals(maxBackoff, r.maxBackoff) && Objects.equals(maxRetryAfter, r.maxRetryAfter);
    }

    @Override public int hashCode() { return Objects.hash(maxAttempts, retryOnStatus, initialBackoff, multiplier, maxBackoff, maxRetryAfter); }
    @Override public String toString() { return "RetryPolicy[maxAttempts=" + maxAttempts() + ", retryOnStatus=" + retryOnStatus() + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private @Nullable Integer maxAttempts;
        private @Nullable Set<Integer> retryOnStatus;
        private @Nullable Duration initialBackoff, maxBackoff, maxRetryAfter;
        private @Nullable Double multiplier;

        private Builder() { }

        /// At least 1; `RetryPolicy.none()` is one attempt.
        public Builder maxAttempts(int attempts) { maxAttempts = Checks.positive(attempts, "maxAttempts"); return this; }
        /// Replaces the set; empty means no status is retried.
        public Builder retryOnStatus(Set<Integer> statuses) { retryOnStatus = Set.copyOf(statuses); return this; }

        public Builder backoff(Duration initial, double factor, Duration max) {
            if (factor < 1) throw new IllegalArgumentException("Backoff multiplier must be at least 1: " + factor);
            initialBackoff = Checks.positive(initial, "Initial backoff");
            maxBackoff = Checks.positive(max, "Max backoff");
            multiplier = factor;
            return this;
        }

        public Builder maxRetryAfter(Duration max) { maxRetryAfter = Checks.positive(max, "maxRetryAfter"); return this; }
        public RetryPolicy build() { return new RetryPolicy(maxAttempts, retryOnStatus, initialBackoff, multiplier, maxBackoff, maxRetryAfter); }
    }
}
