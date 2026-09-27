package net.ai.gate.spi.http;

import java.io.IOException;

import net.ai.gate.config.HttpOptions;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.internal.http.JdkHttpTransport;

/// **SPI**. Sends absolute, credentialed calls. Thread-safe. `IOException` is translated by the core and classified
/// as *before send* (retryable) or *after send* (outcome unknown). Injected transports are borrowed. `close()` must
/// return within a bounded time even while bodies are open.
public interface HttpTransport extends AutoCloseable {
    /// Blocks until response headers arrive and returns a reply whose body the caller consumes and closes. Must abort
    /// promptly when the calling thread is interrupted and when the reply is closed early.
    HttpReply send(HttpCall call, TransportOptions options) throws IOException;

    @Override default void close() { }

    /// The zero-dependency default over `java.net.http.HttpClient`, with the default connect timeout.
    static HttpTransport jdk() { return jdk(HttpOptions.defaults()); }

    static HttpTransport jdk(HttpOptions options) { return new JdkHttpTransport(options, TimeoutPolicy.defaults().connect()); }
}
