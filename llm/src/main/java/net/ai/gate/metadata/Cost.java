package net.ai.gate.metadata;

import java.math.BigDecimal;
import java.util.Currency;

import net.ai.gate.model.Model;

/// Immutable cost of one reply per usage bucket, computed from [Model#prices()]; see [Usage#cost()].
public record Cost(Currency currency, BigDecimal input, BigDecimal cacheRead, BigDecimal cacheWrite,
                   BigDecimal output, BigDecimal total) {
    @Override public String toString() { return currency.getCurrencyCode() + " " + total.stripTrailingZeros().toPlainString(); }
}
