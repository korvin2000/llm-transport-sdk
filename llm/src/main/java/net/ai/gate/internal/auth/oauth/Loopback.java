package net.ai.gate.internal.auth.oauth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import net.ai.gate.lifecycle.CancelToken;

/// A one-shot HTTP listener on the loopback interface for OAuth redirects (RFC 8252 §7.3): bound to loopback only,
/// answering the first request to its path and 404 to anything else.
final class Loopback implements AutoCloseable {
    private static final Set<String> HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    private static final String PAGE = "<!doctype html><meta charset=utf-8><title>Signed in</title>"
            + "<p style=\"font:16px system-ui;margin:3em\">Signed in. You can close this window and return to the application.</p>";

    private final ServerSocket server;
    private final String path;

    private Loopback(ServerSocket server, String path) { this.server = server; this.path = path; }

    static boolean is(URI uri) { return "http".equals(uri.getScheme()) && HOSTS.contains(String.valueOf(uri.getHost())); }

    /// Port `0` picks a free one.
    static Loopback open(int port, String path) throws IOException {
        return new Loopback(new ServerSocket(port, 8, InetAddress.getLoopbackAddress()), path);
    }

    URI uri(String host) { return URI.create("http://" + host + ":" + server.getLocalPort() + path); }

    /// The callback URL (path and query) of the first request to the path.
    String await(CancelToken cancel, Duration timeout) throws IOException {
        var deadline = Instant.now().plus(timeout);
        server.setSoTimeout(250);
        while (Instant.now().isBefore(deadline)) {
            if (cancel.isCancelled()) throw new InterruptedIOException("cancelled");
            try (var socket = server.accept()) {
                socket.setSoTimeout(5_000);
                var line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)).readLine();
                var parts = line == null ? new String[0] : line.split(" ");
                var target = parts.length > 1 ? parts[1] : "";
                boolean ours = target.split("[?]", 2)[0].equals(path);
                respond(socket, ours);
                if (ours) return uri("127.0.0.1").resolve(target).toString();
            } catch (SocketTimeoutException e) {
                // poll the cancel token and the deadline again
            }
        }
        throw new InterruptedIOException("no browser callback within " + timeout.toMinutes() + " minutes");
    }

    private static void respond(Socket socket, boolean ours) throws IOException {
        var body = (ours ? PAGE : "Not found").getBytes(StandardCharsets.UTF_8);
        var head = (ours ? "HTTP/1.1 200 OK" : "HTTP/1.1 404 Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        var out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { /* releasing the port: nothing left to report */ }
    }
}
