package net.ai.gate.auth;

import java.util.Map;
import java.util.Optional;

/// Where the auth chain reads ambient values such as `ANTHROPIC_API_KEY`; nothing else in the SDK reads the
/// environment. Injectable for tests and servers. Thread-safe.
@FunctionalInterface
public interface Environment {
    Optional<String> get(String name);

    /// The process environment.
    static Environment system() { return name -> Optional.ofNullable(System.getenv(name)).filter(v -> !v.isBlank()); }

    /// Nothing: multi-tenant servers, so operator keys never authenticate a tenant.
    static Environment none() { return _ -> Optional.empty(); }

    static Environment of(Map<String, String> values) {
        var copy = Map.copyOf(values);
        return name -> Optional.ofNullable(copy.get(name));
    }
}
