package net.ai.gate.spi.http;

import java.io.IOException;

/// **SPI** (host). Runs once per attempt, inside retries, after credentials are applied: registration order on the
/// way in, reverse order on the way out. May add headers or sign; may short-circuit deliberately. Streaming bodies
/// pass through unread. After the chain, the core re-validates that the destination is still the provider's origin.
@FunctionalInterface
public interface WireInterceptor {
    HttpReply intercept(Chain chain) throws IOException;

    /// The rest of the chain for one attempt.
    interface Chain {
        HttpCall call();
        String providerId();
        String requestId();
        HttpReply proceed(HttpCall call) throws IOException;
    }
}
