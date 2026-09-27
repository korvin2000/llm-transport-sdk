package net.ai.gate.event;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import net.ai.gate.error.ErrorCode;
import org.jspecify.annotations.Nullable;

/// Background credential maintenance: OAuth refreshes. Never carries tokens.
public sealed interface CredentialEvent extends LlmEvent {
    String providerId();

    final class Refreshed extends EventBase implements CredentialEvent {
        private final String providerId;
        private final @Nullable Instant expiresAt;

        private Refreshed(String providerId, @Nullable Instant expiresAt, Instant at) {
            super(at, Map.of());
            this.providerId = providerId; this.expiresAt = expiresAt;
        }

        public static Refreshed of(String providerId, @Nullable Instant expiresAt, Instant at) { return new Refreshed(providerId, expiresAt, at); }

        @Override public String providerId() { return providerId; }
        public Optional<Instant> expiresAt() { return Optional.ofNullable(expiresAt); }
        @Override public String toString() { return "CredentialEvent.Refreshed[" + providerId + "]"; }
    }

    final class RefreshFailed extends EventBase implements CredentialEvent {
        private final String providerId;
        private final ErrorCode errorCode;
        private final boolean loginRequired;

        private RefreshFailed(String providerId, ErrorCode errorCode, boolean loginRequired, Instant at) {
            super(at, Map.of());
            this.providerId = providerId; this.errorCode = errorCode; this.loginRequired = loginRequired;
        }

        public static RefreshFailed of(String providerId, ErrorCode errorCode, boolean loginRequired, Instant at) {
            return new RefreshFailed(providerId, errorCode, loginRequired, at);
        }

        @Override public String providerId() { return providerId; }
        public ErrorCode errorCode() { return errorCode; }
        /// The user must sign in again: the refresh token was rejected.
        public boolean loginRequired() { return loginRequired; }
        @Override public String toString() { return "CredentialEvent.RefreshFailed[" + providerId + ", " + errorCode + "]"; }
    }
}
