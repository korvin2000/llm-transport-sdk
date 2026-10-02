package net.ai.gate.metadata;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/// What a provider or gateway reports it charged for one call — as reported, never computed; [Usage#cost()] is the
/// estimate from the model's prices. `upstream` is the upstream provider's own charge where a gateway reports it
/// separately (OpenRouter `cost_details.upstream_inference_cost`); `amount` is what the account was charged.
public record Charge(Currency currency, BigDecimal amount, @Nullable BigDecimal upstream) {
    public Charge {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0 || upstream != null && upstream.signum() < 0)
            throw new IllegalArgumentException("A charge must not be negative: " + amount + ", upstream " + upstream);
    }

    @Override public String toString() {
        return currency.getCurrencyCode() + " " + amount.stripTrailingZeros().toPlainString()
                + (upstream == null ? "" : " (upstream " + upstream.stripTrailingZeros().toPlainString() + ")");
    }
}
