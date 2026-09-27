package net.ai.gate.metadata;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonValue;
import net.ai.gate.model.Model;
import org.jspecify.annotations.Nullable;

/// Immutable token usage in disjoint buckets: `input` excludes cache reads and writes, so cost is a dot product.
/// Absent means "not reported" — never zero.
public final class Usage {
    private static final Usage EMPTY = builder().build();

    private final @Nullable Long input, cacheRead, cacheWrite, output, reasoning, total;
    private final @Nullable Cost cost;
    private final boolean spent;
    private final JsonValue raw;

    private Usage(Builder b) {
        input = b.input; cacheRead = b.cacheRead; cacheWrite = b.cacheWrite; output = b.output;
        reasoning = b.reasoning; total = b.total; cost = b.cost; spent = b.spent; raw = b.raw;
    }

    public static Usage empty() { return EMPTY; }
    public static Builder builder() { return new Builder(); }

    /// Uncached input tokens.
    public OptionalLong input() { return optional(input); }
    public OptionalLong cacheRead() { return optional(cacheRead); }
    public OptionalLong cacheWrite() { return optional(cacheWrite); }
    /// Output tokens, reasoning included.
    public OptionalLong output() { return optional(output); }
    public OptionalLong reasoning() { return optional(reasoning); }

    /// `input + cacheRead + cacheWrite`, when all three are known.
    public OptionalLong totalInput() {
        return input != null && cacheRead != null && cacheWrite != null ? OptionalLong.of(input + cacheRead + cacheWrite)
                                                                          : OptionalLong.empty();
    }

    /// Reported by the provider, or derived when every bucket is known.
    public OptionalLong total() {
        if (total != null) return OptionalLong.of(total);
        var in = totalInput();
        return in.isPresent() && output != null ? OptionalLong.of(in.getAsLong() + output) : OptionalLong.empty();
    }

    /// The total the provider reported, if any; [#total()] derives one otherwise.
    public OptionalLong reportedTotal() { return optional(total); }

    /// [Model#prices()] applied; absent when a needed price or counter is unknown.
    public Optional<Cost> cost() { return Optional.ofNullable(cost); }
    /// `false` for replies replayed from the response cache: nothing was billed.
    public boolean spent() { return spent; }
    /// The provider's usage object as received.
    public JsonValue raw() { return raw; }

    public Builder toBuilder() {
        var b = new Builder();
        b.input = input; b.cacheRead = cacheRead; b.cacheWrite = cacheWrite; b.output = output;
        b.reasoning = reasoning; b.total = total; b.cost = cost; b.spent = spent; b.raw = raw;
        return b;
    }

    private static OptionalLong optional(@Nullable Long value) { return value == null ? OptionalLong.empty() : OptionalLong.of(value); }

    @Override public boolean equals(Object o) {
        return o instanceof Usage u && Objects.equals(input, u.input) && Objects.equals(cacheRead, u.cacheRead)
                && Objects.equals(cacheWrite, u.cacheWrite) && Objects.equals(output, u.output)
                && Objects.equals(reasoning, u.reasoning) && Objects.equals(total, u.total)
                && Objects.equals(cost, u.cost) && spent == u.spent;
    }

    @Override public int hashCode() { return Objects.hash(input, cacheRead, cacheWrite, output, reasoning, total, cost, spent); }

    @Override public String toString() {
        return "Usage[input=" + text(input) + ", output=" + text(output) + ", cacheRead=" + text(cacheRead)
                + ", cacheWrite=" + text(cacheWrite) + ", cost=" + (cost == null ? "?" : cost) + (spent ? "" : ", replayed") + "]";
    }

    private static String text(@Nullable Long value) { return value == null ? "?" : value.toString(); }

    /// Not thread-safe. Counters must not be negative.
    public static final class Builder {
        private @Nullable Long input, cacheRead, cacheWrite, output, reasoning, total;
        private @Nullable Cost cost;
        private boolean spent = true;
        private JsonValue raw = JsonNull.INSTANCE;

        private Builder() { }

        public Builder input(long tokens) { input = count(tokens); return this; }
        public Builder cacheRead(long tokens) { cacheRead = count(tokens); return this; }
        public Builder cacheWrite(long tokens) { cacheWrite = count(tokens); return this; }
        public Builder output(long tokens) { output = count(tokens); return this; }
        public Builder reasoning(long tokens) { reasoning = count(tokens); return this; }
        public Builder total(long tokens) { total = count(tokens); return this; }
        public Builder cost(@Nullable Cost value) { cost = value; return this; }
        public Builder spent(boolean value) { spent = value; return this; }
        public Builder raw(JsonValue value) { raw = value; return this; }
        public Usage build() { return new Usage(this); }

        private static long count(long tokens) {
            if (tokens < 0) throw new IllegalArgumentException("Token count must not be negative: " + tokens);
            return tokens;
        }
    }
}
