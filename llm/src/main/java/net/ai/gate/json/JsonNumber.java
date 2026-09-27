package net.ai.gate.json;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/// Immutable JSON number that keeps its lexical form, so `1.50` is written back as `1.50`.
public final class JsonNumber implements JsonValue {
    /// The JSON number grammar: no leading zeros, no bare `.5` or `1.`, no leading `+`.
    private static final Pattern GRAMMAR = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String lexical;
    private final BigDecimal value;

    private JsonNumber(String lexical, BigDecimal value) { this.lexical = lexical; this.value = value; }

    /// @throws NumberFormatException when `lexical` is not a JSON number
    public static JsonNumber of(String lexical) {
        if (!GRAMMAR.matcher(lexical).matches()) throw new NumberFormatException("Not a JSON number: " + lexical);
        return new JsonNumber(lexical, new BigDecimal(lexical));
    }

    public static JsonNumber of(long value) { return new JsonNumber(Long.toString(value), BigDecimal.valueOf(value)); }

    public static JsonNumber of(BigDecimal value) { return new JsonNumber(value.toPlainString(), value); }

    /// @throws IllegalArgumentException for NaN and infinities, which JSON cannot represent
    public static JsonNumber of(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("JSON cannot represent " + value);
        return of(BigDecimal.valueOf(value));
    }

    public BigDecimal value() { return value; }

    /// @throws ArithmeticException when the number has a fraction or does not fit a `long`
    public long longValue() { return value.longValueExact(); }

    public double doubleValue() { return value.doubleValue(); }

    @Override public boolean equals(Object o) { return o instanceof JsonNumber n && value.compareTo(n.value) == 0; }
    @Override public int hashCode() { return value.stripTrailingZeros().hashCode(); }
    /// The lexical form, which is also its JSON text.
    @Override public String toString() { return lexical; }
}
