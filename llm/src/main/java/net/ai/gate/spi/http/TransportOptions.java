package net.ai.gate.spi.http;

import java.time.Duration;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/// Immutable per-attempt limits for a transport.
public final class TransportOptions {
    private final Duration connectTimeout;
    private final @Nullable Duration responseTimeout;
    private final boolean streaming;

    private TransportOptions(Duration connectTimeout, @Nullable Duration responseTimeout, boolean streaming) {
        this.connectTimeout = connectTimeout; this.responseTimeout = responseTimeout; this.streaming = streaming;
    }

    public static TransportOptions of(Duration connectTimeout, @Nullable Duration responseTimeout, boolean streaming) {
        return new TransportOptions(connectTimeout, responseTimeout, streaming);
    }

    public Duration connectTimeout() { return connectTimeout; }
    /// Until response headers arrive: the remaining total deadline.
    public Optional<Duration> responseTimeout() { return Optional.ofNullable(responseTimeout); }
    /// The body will be consumed as a stream.
    public boolean streaming() { return streaming; }
}
