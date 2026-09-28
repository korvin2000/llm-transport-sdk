package net.ai.gate.metadata;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.json.JsonNull;
import net.ai.gate.json.JsonValue;
import net.ai.gate.model.Model;
import org.jspecify.annotations.Nullable;

/// Immutable token usage in disjoint buckets: `input` excludes cache reads and writes, so cost is a dot product.
/// Absent means "not reported" — never zero. Usage observed before a call ended (stream updates, partial replies)
/// is marked `finalForCall() == false`.
public final class Usage {
    private static final Usage EMPTY = builder().build();

    private final @Nullable Long input, cacheRead, cacheWrite, output, reasoning, total;
    private final Map<CacheRetention, Long> cacheWrites;
    private final @Nullable Cost cost;
    private final boolean spent, finalForCall;
    private final JsonValue raw;

    private Usage(Builder b) {
        input = b.input; cacheRead = b.cacheRead; cacheWrite = b.cacheWrite; output = b.output;
        reasoning = b.reasoning; total = b.total; cost = b.cost; spent = b.spent; finalForCall = b.finalForCall; raw = b.raw;
        cacheWrites = b.cacheWrites.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(b.cacheWrites));
    }

    public static Usage empty() { return EMPTY; }
    public static Builder builder() { return new Builder(); }

    /// Uncached input tokens.
    public OptionalLong input() { return optional(input); }
    public OptionalLong cacheRead() { return optional(cacheRead); }
    /// The undifferentiated count where the API reports one, else the sum of [#cacheWrites()].
    public OptionalLong cacheWrite() {
        if (cacheWrite != null || cacheWrites.isEmpty()) return optional(cacheWrite);
        return OptionalLong.of(cacheWrites.values().stream().mapToLong(Long::longValue).sum());
    }
    /// Cache writes by retention class (`SHORT`: 5 minutes, `LONG`: 1 hour); empty when the API reports no split.
    public Map<CacheRetention, Long> cacheWrites() { return cacheWrites; }
    /// Output tokens, reasoning included.
    public OptionalLong output() { return optional(output); }
    public OptionalLong reasoning() { return optional(reasoning); }

    /// `input + cacheRead + cacheWrite`, when all three are known.
    public OptionalLong totalInput() {
        var write = cacheWrite();
        return input != null && cacheRead != null && write.isPresent() ? OptionalLong.of(input + cacheRead + write.getAsLong())
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
    /// `false` while the call may still report more: counters may grow and absent buckets may still arrive.
    public boolean finalForCall() { return finalForCall; }
    /// The provider's usage object as received.
    public JsonValue raw() { return raw; }

    public Builder toBuilder() {
        var b = new Builder();
        b.input = input; b.cacheRead = cacheRead; b.cacheWrite = cacheWrite; b.output = output;
        b.reasoning = reasoning; b.total = total; b.cost = cost; b.spent = spent; b.finalForCall = finalForCall; b.raw = raw;
        b.cacheWrites.putAll(cacheWrites);
        return b;
    }

    private static OptionalLong optional(@Nullable Long value) { return value == null ? OptionalLong.empty() : OptionalLong.of(value); }

    @Override public boolean equals(Object o) {
        return o instanceof Usage u && Objects.equals(input, u.input) && Objects.equals(cacheRead, u.cacheRead)
                && cacheWrite().equals(u.cacheWrite()) && Objects.equals(output, u.output)
                && Objects.equals(reasoning, u.reasoning) && Objects.equals(total, u.total)
                && cacheWrites.equals(u.cacheWrites) && Objects.equals(cost, u.cost) && spent == u.spent && finalForCall == u.finalForCall;
    }

    @Override public int hashCode() { return Objects.hash(input, cacheRead, cacheWrite(), cacheWrites, output, reasoning, total, cost, spent, finalForCall); }

    @Override public String toString() {
        return "Usage[input=" + text(input) + ", output=" + text(output) + ", cacheRead=" + text(cacheRead)
                + ", cacheWrite=" + (cacheWrites.isEmpty() ? text(cacheWrite) : cacheWrites) + ", cost=" + (cost == null ? "?" : cost)
                + (spent ? "" : ", replayed") + (finalForCall ? "" : ", observed") + "]";
    }

    private static String text(@Nullable Long value) { return value == null ? "?" : value.toString(); }

    /// Not thread-safe. Counters must not be negative.
    public static final class Builder {
        private @Nullable Long input, cacheRead, cacheWrite, output, reasoning, total;
        private final Map<CacheRetention, Long> cacheWrites = new EnumMap<>(CacheRetention.class);
        private @Nullable Cost cost;
        private boolean spent = true, finalForCall = true;
        private JsonValue raw = JsonNull.INSTANCE;

        private Builder() { }

        public Builder input(long tokens) { input = count(tokens); return this; }
        public Builder cacheRead(long tokens) { cacheRead = count(tokens); return this; }
        /// The undifferentiated count; [#cacheWrite(CacheRetention, long)] records a class.
        public Builder cacheWrite(long tokens) { cacheWrite = count(tokens); return this; }
        public Builder cacheWrite(CacheRetention retention, long tokens) {
            if (retention == CacheRetention.NONE) throw new IllegalArgumentException("Cache writes have a retention class: SHORT or LONG");
            cacheWrites.put(retention, count(tokens));
            return this;
        }
        public Builder output(long tokens) { output = count(tokens); return this; }
        public Builder reasoning(long tokens) { reasoning = count(tokens); return this; }
        public Builder total(long tokens) { total = count(tokens); return this; }
        public Builder cost(@Nullable Cost value) { cost = value; return this; }
        public Builder spent(boolean value) { spent = value; return this; }
        public Builder finalForCall(boolean value) { finalForCall = value; return this; }
        public Builder raw(JsonValue value) { raw = value; return this; }
        public Usage build() { return new Usage(this); }

        private static long count(long tokens) {
            if (tokens < 0) throw new IllegalArgumentException("Token count must not be negative: " + tokens);
            return tokens;
        }
    }
}
