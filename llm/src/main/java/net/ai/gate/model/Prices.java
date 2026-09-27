package net.ai.gate.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import net.ai.gate.metadata.Cost;
import net.ai.gate.metadata.Usage;
import org.jspecify.annotations.Nullable;

/// Immutable decimal prices per million tokens in one currency; a missing component is absent, never zero.
/// A [Tier] replaces the components it states for the whole request when its total input exceeds the threshold.
public final class Prices {
    private static final Currency USD = Currency.getInstance("USD");

    private final Currency currency;
    private final @Nullable BigDecimal input, output, cacheRead, cacheWrite;
    private final List<Tier> tiers;

    private Prices(Builder b) {
        currency = b.currency; input = b.input; output = b.output; cacheRead = b.cacheRead; cacheWrite = b.cacheWrite;
        tiers = b.tiers.stream().sorted(Comparator.comparingLong(Tier::inputTokensAbove)).toList();
    }

    public static Builder builder(Currency currency) { return new Builder(currency); }
    public static Builder usd() { return new Builder(USD); }

    public Currency currency() { return currency; }
    public Optional<BigDecimal> inputPerMillion() { return Optional.ofNullable(input); }
    public Optional<BigDecimal> outputPerMillion() { return Optional.ofNullable(output); }
    public Optional<BigDecimal> cacheReadPerMillion() { return Optional.ofNullable(cacheRead); }
    public Optional<BigDecimal> cacheWritePerMillion() { return Optional.ofNullable(cacheWrite); }
    public List<Tier> tiers() { return tiers; }

    /// The cost of `usage`; absent when input or output is unreported or a price for non-zero tokens is unknown.
    /// Unreported cache counters count as zero.
    public Optional<Cost> cost(Usage usage) {
        var p = tierFor(usage.totalInput().orElse(usage.input().orElse(0)));
        var in = part(usage.input(), p.input, true);
        var out = part(usage.output(), p.output, true);
        var read = part(usage.cacheRead(), p.cacheRead, false);
        var write = part(usage.cacheWrite(), p.cacheWrite, false);
        if (in == null || out == null || read == null || write == null) return Optional.empty();
        return Optional.of(new Cost(currency, in, read, write, out, in.add(read).add(write).add(out)));
    }

    private Prices tierFor(long inputTokens) {
        var chosen = this;
        for (var tier : tiers) {
            if (inputTokens <= tier.inputTokensAbove()) break;
            var t = tier.prices();
            chosen = new Builder(currency).input(or(t.input, input)).output(or(t.output, output))
                    .cacheRead(or(t.cacheRead, cacheRead)).cacheWrite(or(t.cacheWrite, cacheWrite)).build();
        }
        return chosen;
    }

    private static @Nullable BigDecimal or(@Nullable BigDecimal preferred, @Nullable BigDecimal fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static @Nullable BigDecimal part(OptionalLong tokens, @Nullable BigDecimal perMillion, boolean required) {
        if (tokens.isEmpty()) return required ? null : BigDecimal.ZERO;
        if (tokens.getAsLong() == 0) return BigDecimal.ZERO;
        return perMillion == null ? null : perMillion.multiply(BigDecimal.valueOf(tokens.getAsLong())).movePointLeft(6);
    }

    /// Component by component: prices `newer` states win, absent ones keep these; another currency replaces wholesale.
    public Prices overriddenBy(Prices newer) {
        if (!newer.currency.equals(currency)) return newer;
        var b = new Builder(currency).input(or(newer.input, input)).output(or(newer.output, output))
                .cacheRead(or(newer.cacheRead, cacheRead)).cacheWrite(or(newer.cacheWrite, cacheWrite));
        b.tiers.addAll(newer.tiers.isEmpty() ? tiers : newer.tiers);
        return b.build();
    }

    public Builder toBuilder() {
        var b = new Builder(currency).input(input).output(output).cacheRead(cacheRead).cacheWrite(cacheWrite);
        b.tiers.addAll(tiers);
        return b;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Prices p && currency.equals(p.currency) && Objects.equals(input, p.input)
                && Objects.equals(output, p.output) && Objects.equals(cacheRead, p.cacheRead)
                && Objects.equals(cacheWrite, p.cacheWrite) && tiers.equals(p.tiers);
    }

    @Override public int hashCode() { return Objects.hash(currency, input, output, cacheRead, cacheWrite, tiers); }

    @Override public String toString() {
        return "Prices[" + currency + " in=" + input + " out=" + output + " cacheRead=" + cacheRead + " cacheWrite=" + cacheWrite
                + (tiers.isEmpty() ? "" : " tiers=" + tiers) + "]";
    }

    /// Prices that apply when the request's total input exceeds `inputTokensAbove`.
    public record Tier(long inputTokensAbove, Prices prices) {
        public Tier {
            if (inputTokensAbove < 0) throw new IllegalArgumentException("Tier threshold must not be negative");
        }
    }

    /// Not thread-safe. Prices must not be negative.
    public static final class Builder {
        private final Currency currency;
        private @Nullable BigDecimal input, output, cacheRead, cacheWrite;
        private final List<Tier> tiers = new ArrayList<>();

        private Builder(Currency currency) { this.currency = currency; }

        public Builder input(@Nullable BigDecimal perMillion) { input = price(perMillion); return this; }
        public Builder output(@Nullable BigDecimal perMillion) { output = price(perMillion); return this; }
        public Builder cacheRead(@Nullable BigDecimal perMillion) { cacheRead = price(perMillion); return this; }
        public Builder cacheWrite(@Nullable BigDecimal perMillion) { cacheWrite = price(perMillion); return this; }
        public Builder input(String perMillion) { return input(new BigDecimal(perMillion)); }
        public Builder output(String perMillion) { return output(new BigDecimal(perMillion)); }
        public Builder cacheRead(String perMillion) { return cacheRead(new BigDecimal(perMillion)); }
        public Builder cacheWrite(String perMillion) { return cacheWrite(new BigDecimal(perMillion)); }

        public Builder tier(long inputTokensAbove, Prices prices) {
            if (!prices.currency.equals(currency)) throw new IllegalArgumentException("Tier currency differs: " + prices.currency);
            tiers.add(new Tier(inputTokensAbove, prices));
            return this;
        }

        public Prices build() { return new Prices(this); }

        private static @Nullable BigDecimal price(@Nullable BigDecimal value) {
            if (value != null && value.signum() < 0) throw new IllegalArgumentException("Price must not be negative: " + value);
            return value;
        }
    }
}
