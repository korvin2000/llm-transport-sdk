package net.ai.gate.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

import net.ai.gate.internal.validation.Checks;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Immutable deadlines. Defaults: connect 10 s · stream idle 5 min (streams only, reset by any received bytes) ·
/// total 10 min covering credentials, every attempt, backoff and stream consumption. There is deliberately no
/// first-byte timeout: non-streamed replies arrive only after generation finishes.
///
/// A policy may be partial: a field never set inherits from the wider scope (call ▷ provider ▷ runtime) and finally
/// from these defaults, so `timeouts(t -> t.total(…))` on a call keeps the runtime's connect and idle limits. The
/// accessors always answer with the effective value.
public final class TimeoutPolicy {
    private static final Duration DEFAULT_CONNECT = Duration.ofSeconds(10), DEFAULT_IDLE = Duration.ofMinutes(5),
            DEFAULT_TOTAL = Duration.ofMinutes(10);
    private static final TimeoutPolicy DEFAULTS = new TimeoutPolicy(DEFAULT_CONNECT, DEFAULT_IDLE, DEFAULT_TOTAL, false);
    private static final TimeoutPolicy LOCAL = new TimeoutPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofMinutes(30), false);

    private final @Nullable Duration connect, streamIdle, total;
    private final boolean noTotal;

    private TimeoutPolicy(@Nullable Duration connect, @Nullable Duration streamIdle, @Nullable Duration total, boolean noTotal) {
        this.connect = connect; this.streamIdle = streamIdle; this.total = total; this.noTotal = noTotal;
    }

    /// Every field at its documented default.
    public static TimeoutPolicy defaults() { return DEFAULTS; }
    /// Connect 5 s · idle 10 min · total 30 min: local servers load models on first use.
    public static TimeoutPolicy forLocalModels() { return LOCAL; }
    /// A partial policy: fields left unset inherit.
    public static Builder builder() { return new Builder(); }

    /// Applied by the JDK transport per runtime (its client-level connect timeout, taken from the runtime defaults);
    /// injected transports receive the per-call value.
    public Duration connect() { return connect != null ? connect : DEFAULT_CONNECT; }
    public Duration streamIdle() { return streamIdle != null ? streamIdle : DEFAULT_IDLE; }
    /// Absent only after [Builder#noTotalTimeout()].
    public Optional<Duration> total() { return noTotal ? Optional.empty() : Optional.of(total != null ? total : DEFAULT_TOTAL); }

    /// Field by field: values set in `higher` win, unset ones keep this policy's.
    public TimeoutPolicy overriddenBy(TimeoutPolicy higher) {
        return new TimeoutPolicy(higher.connect != null ? higher.connect : connect,
                higher.streamIdle != null ? higher.streamIdle : streamIdle,
                higher.noTotal ? null : higher.total != null ? higher.total : noTotal ? null : total,
                higher.noTotal || higher.total == null && noTotal);
    }

    public Builder toBuilder() {
        var b = new Builder();
        b.connect = connect; b.streamIdle = streamIdle; b.total = total; b.noTotal = noTotal;
        return b;
    }

    /// The partial form (`connect`/`streamIdle`/`total` present only when set explicitly; `"total":"none"` for
    /// [Builder#noTotalTimeout()]); unset fields are omitted, not defaulted.
    public JsonObject toJson() {
        var json = new LinkedHashMap<String, JsonValue>();
        if (connect != null) json.put("connect", JsonString.of(connect.toString()));
        if (streamIdle != null) json.put("streamIdle", JsonString.of(streamIdle.toString()));
        if (noTotal) json.put("total", JsonString.of("none"));
        else if (total != null) json.put("total", JsonString.of(total.toString()));
        return JsonObject.of(json);
    }

    /// @throws IllegalArgumentException naming the member that does not fit
    public static TimeoutPolicy fromJson(JsonObject json) {
        var connect = optDuration(json, "connect");
        var streamIdle = optDuration(json, "streamIdle");
        var totalValue = json.get("total").orElse(null);
        Duration total = null;
        boolean noTotal = false;
        if (totalValue instanceof JsonString s) {
            if (s.value().equals("none")) noTotal = true; else total = parseDuration(s.value(), "total");
        } else if (totalValue != null && !(totalValue instanceof JsonNull)) {
            throw new IllegalArgumentException("total: expected a string");
        }
        return new TimeoutPolicy(connect, streamIdle, total, noTotal);
    }

    private static @Nullable Duration optDuration(JsonObject json, String name) {
        var value = json.get(name).orElse(null);
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof JsonString s) return parseDuration(s.value(), name);
        throw new IllegalArgumentException(name + ": expected a string");
    }

    private static Duration parseDuration(String text, String field) {
        try {
            return Duration.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + ": not an ISO-8601 duration: " + text);
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof TimeoutPolicy t && Objects.equals(connect, t.connect) && Objects.equals(streamIdle, t.streamIdle)
                && Objects.equals(total, t.total) && noTotal == t.noTotal;
    }

    @Override public int hashCode() { return Objects.hash(connect, streamIdle, total, noTotal); }

    @Override public String toString() {
        return "TimeoutPolicy[connect=" + connect() + ", streamIdle=" + streamIdle() + ", total=" + total().map(Duration::toString).orElse("none") + "]";
    }

    /// Not thread-safe. Durations must be positive.
    public static final class Builder {
        private @Nullable Duration connect, streamIdle, total;
        private boolean noTotal;

        private Builder() { }

        public Builder connect(Duration value) { connect = Checks.positive(value, "Connect timeout"); return this; }
        public Builder streamIdle(Duration value) { streamIdle = Checks.positive(value, "Stream idle timeout"); return this; }
        public Builder total(Duration value) { total = Checks.positive(value, "Total timeout"); noTotal = false; return this; }
        /// The only way to disable the total deadline; wins over an inherited total.
        public Builder noTotalTimeout() { total = null; noTotal = true; return this; }
        public TimeoutPolicy build() { return new TimeoutPolicy(connect, streamIdle, total, noTotal); }
    }
}
