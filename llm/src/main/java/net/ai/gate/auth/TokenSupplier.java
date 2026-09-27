package net.ai.gate.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/// **SPI** (host). Fetches a short-lived access token from cloud identity. Called on the refresh thread,
/// single-flight; failures become `AuthenticationException(refresh_failed)` and are not retried.
@FunctionalInterface
public interface TokenSupplier {
    AccessToken fetch() throws IOException;

    record AccessToken(Secret token, Optional<Instant> expiresAt) { }
}
