package net.ai.gate.auth;

import java.util.Map;

/// Immutable API key plus provider-scoped non-secret settings collected with it (account id, gateway id, region),
/// so "connect provider" is one flow. `toString()` redacts.
public record ApiKeyCredential(Secret key, Map<String, String> settings) implements Credential {
    public ApiKeyCredential { settings = Map.copyOf(settings); }

    public static ApiKeyCredential of(String key) { return new ApiKeyCredential(Secret.of(key), Map.of()); }

    @Override public AuthType type() { return AuthType.API_KEY; }
    @Override public String toString() { return "ApiKeyCredential[" + key.fingerprint() + ", settings=" + settings.keySet() + "]"; }
}
