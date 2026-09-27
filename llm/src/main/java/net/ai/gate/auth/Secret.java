package net.ai.gate.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/// Immutable secret value — API key or token. `toString()` redacts; only [#fingerprint()] may appear in logs.
public final class Secret {
    private final String value;

    private Secret(String value) { this.value = value; }

    /// @throws IllegalArgumentException for blank values
    public static Secret of(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("A secret must not be blank");
        return new Secret(value);
    }

    /// The plain value, for auth strategies only; never log it.
    public String reveal() { return value; }

    /// `sk-…a1b2`: enough to tell keys apart, never enough to use one.
    public String fingerprint() {
        return value.length() < 12 ? "…" : value.substring(0, 3) + "…" + value.substring(value.length() - 4);
    }

    /// Constant-time comparison.
    @Override public boolean equals(Object o) {
        return o instanceof Secret s && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), s.value.getBytes(StandardCharsets.UTF_8));
    }

    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return "Secret[" + fingerprint() + "]"; }
}
