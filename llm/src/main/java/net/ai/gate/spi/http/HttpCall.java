package net.ai.gate.spi.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.ai.gate.json.JsonValue;
import org.jspecify.annotations.Nullable;

/// Immutable HTTP request. Codecs return URIs relative to the provider's base URL (`messages`,
/// `chat/completions`); the core resolves them, applies credentials and sends. Headers keep insertion order.
public final class HttpCall {
    private final String method;
    private final URI uri;
    private final Map<String, String> headers;
    private final @Nullable JsonValue body;

    private HttpCall(String method, URI uri, Map<String, String> headers, @Nullable JsonValue body) {
        this.method = method; this.uri = uri; this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers)); this.body = body;
    }

    public static HttpCall get(String relativePath) { return new HttpCall("GET", URI.create(relativePath), Map.of(), null); }
    public static HttpCall post(String relativePath, JsonValue body) { return new HttpCall("POST", URI.create(relativePath), Map.of(), body); }
    public static HttpCall of(String method, URI uri, Map<String, String> headers, @Nullable JsonValue body) {
        return new HttpCall(method, uri, headers, body);
    }

    public String method() { return method; }
    public URI uri() { return uri; }
    public Map<String, String> headers() { return headers; }
    public Optional<JsonValue> body() { return Optional.ofNullable(body); }

    /// The body as sent: compact JSON in UTF-8, byte-stable for equal trees.
    public byte[] bytes() { return body == null ? new byte[0] : body.toJson().getBytes(StandardCharsets.UTF_8); }

    public HttpCall withUri(URI value) { return new HttpCall(method, value, headers, body); }
    public HttpCall withBody(@Nullable JsonValue value) { return new HttpCall(method, uri, headers, value); }

    public HttpCall withHeader(String name, String value) {
        var copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new HttpCall(method, uri, copy, body);
    }

    public HttpCall withHeaders(Map<String, String> added) {
        var copy = new LinkedHashMap<>(headers);
        copy.putAll(added);
        return new HttpCall(method, uri, copy, body);
    }

    /// Never prints header values: they may hold credentials.
    @Override public String toString() { return "HttpCall[" + method + " " + uri + ", headers=" + headers.keySet() + "]"; }
}
