package net.ai.gate.auth;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/// Immutable credentials applied to one request; anything not expressible here is provider configuration, not
/// auth. `source` labels where they came from (`ANTHROPIC_API_KEY`, `stored credential`). `toString()` redacts.
public record ResolvedAuth(Map<String, String> headers, Map<String, String> query, Optional<URI> baseUrl, String source) {
    public ResolvedAuth {
        headers = Map.copyOf(headers);
        query = Map.copyOf(query);
    }

    public static ResolvedAuth headers(Map<String, String> headers, String source) {
        return new ResolvedAuth(headers, Map.of(), Optional.empty(), source);
    }

    public static ResolvedAuth none(String source) { return headers(Map.of(), source); }

    @Override public String toString() {
        return "ResolvedAuth[headers=" + headers.keySet() + ", query=" + query.keySet() + ", source=" + source + "]";
    }
}
