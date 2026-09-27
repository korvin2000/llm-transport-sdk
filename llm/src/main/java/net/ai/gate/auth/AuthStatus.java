package net.ai.gate.auth;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/// Immutable local auth state of a provider — from the credential store and the environment only, no network.
public final class AuthStatus {
    public enum State { NOT_CONFIGURED, CONFIGURED, EXPIRING, EXPIRED, REFRESH_FAILED }

    private static final AuthStatus NOT_CONFIGURED = new AuthStatus(State.NOT_CONFIGURED, null, null, null, null);

    private final State state;
    private final @Nullable AuthType type;
    private final @Nullable String source, account;
    private final @Nullable Instant expiresAt;

    private AuthStatus(State state, @Nullable AuthType type, @Nullable String source, @Nullable Instant expiresAt, @Nullable String account) {
        this.state = state; this.type = type; this.source = source; this.expiresAt = expiresAt; this.account = account;
    }

    public static AuthStatus notConfigured() { return NOT_CONFIGURED; }

    /// `source`: `ANTHROPIC_API_KEY`, `stored credential`, `keyless`, `OAuth`…
    public static AuthStatus of(State state, AuthType type, String source) { return new AuthStatus(state, type, source, null, null); }

    public AuthStatus withExpiry(@Nullable Instant value) { return new AuthStatus(state, type, source, value, account); }
    public AuthStatus withAccount(@Nullable String value) { return new AuthStatus(state, type, source, expiresAt, value); }

    public State state() { return state; }
    public Optional<AuthType> type() { return Optional.ofNullable(type); }
    public Optional<String> source() { return Optional.ofNullable(source); }
    public Optional<Instant> expiresAt() { return Optional.ofNullable(expiresAt); }
    public Optional<String> account() { return Optional.ofNullable(account); }

    @Override public String toString() { return "AuthStatus[" + state + (source == null ? "" : " via " + source) + "]"; }
}
