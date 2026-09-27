package net.ai.gate.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import net.ai.gate.config.HttpOptions;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.spi.http.TransportOptions;

/// The default transport over `java.net.http.HttpClient`: HTTP/2 negotiated, HTTP/1.1 forced for cleartext
/// loopback servers (no `h2c` upgrade surprises with local runtimes), redirects never followed. The JDK client has
/// no read timeout: the core's watchdog enforces idle and total deadlines by closing the body. The connect timeout
/// is a client-level setting of the JDK, so it is fixed per transport (from the runtime's default policy); the
/// per-attempt value in [TransportOptions] reaches only transports that can honour it. `close()` is bounded.
public final class JdkHttpTransport implements HttpTransport {
    private static final System.Logger LOG = System.getLogger("net.ai.gate");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Duration CLOSE_BUDGET = Duration.ofSeconds(5);

    private final HttpClient client;
    private final String userAgent;

    public JdkHttpTransport(HttpOptions options, Duration connectTimeout) {
        if (options.trustStore().isPresent() || options.clientCertificate().isPresent() || options.insecureSkipTlsVerification())
            throw new UnsupportedOperationException("Custom TLS settings are not implemented yet (roadmap slice 1)");
        var builder = HttpClient.newBuilder().version(options.httpVersion()).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout);
        options.proxy().ifPresent(builder::proxy);
        client = builder.build();
        userAgent = "ai-gate/0.1 Java/" + Runtime.version().feature() + options.userAgentSuffix().map(s -> " " + s).orElse("");
    }

    @Override public HttpReply send(HttpCall call, TransportOptions options) throws IOException {
        var body = call.body().isPresent() ? HttpRequest.BodyPublishers.ofByteArray(call.bytes()) : HttpRequest.BodyPublishers.noBody();
        var request = HttpRequest.newBuilder(call.uri()).method(call.method(), body).header("User-Agent", userAgent);
        call.headers().forEach(request::header);
        if (call.body().isPresent() && call.headers().keySet().stream().noneMatch("content-type"::equalsIgnoreCase))
            request.header("Content-Type", "application/json");
        if (options.streaming()) request.header("Accept", "text/event-stream, application/x-ndjson, application/json");
        if (isLoopbackCleartext(call.uri())) request.version(HttpClient.Version.HTTP_1_1);
        options.responseTimeout().ifPresent(request::timeout);
        try {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return HttpReply.of(response.statusCode(), response.headers().map(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for " + call.uri().getHost());
        }
    }

    static boolean isLoopbackCleartext(URI uri) { return "http".equals(uri.getScheme()) && LOOPBACK.contains(String.valueOf(uri.getHost())); }

    /// Graceful shutdown within a budget, then forced: an open body never blocks the caller indefinitely.
    @Override public void close() {
        client.shutdown();
        try {
            if (!client.awaitTermination(CLOSE_BUDGET)) {
                client.shutdownNow();
                LOG.log(System.Logger.Level.WARNING, "HTTP connections were still open after " + CLOSE_BUDGET + "; they were aborted");
            }
        } catch (InterruptedException e) {
            client.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
