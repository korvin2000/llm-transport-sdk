package net.ai.gate.internal.auth.store;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;

import net.ai.gate.auth.ApiKeyCredential;
import net.ai.gate.auth.Credential;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.json.JsonString;
import org.jspecify.annotations.Nullable;

/// The persisted form of credentials. Secrets are revealed here and only here: the file is the secret store.
final class CredentialJson {
    private CredentialJson() { }

    static JsonObject write(Credential credential) {
        return switch (credential) {
            case ApiKeyCredential k -> Json.object("type", "api_key", "key", k.key().reveal(), "settings", k.settings());
            case OAuthCredential o -> Json.object("type", "oauth", "access", o.access().reveal(),
                    "refresh", o.refresh().map(Secret::reveal).orElse(null), "expiresAt", o.expiresAt().orElse(null),
                    "issuer", o.issuer(), "clientId", o.clientId(), "account", o.account().orElse(null),
                    "scopes", o.scopes().stream().sorted().toList(), "extra", o.extra());
        };
    }

    static Credential read(JsonObject json) {
        return switch (json.string("type")) {
            case "api_key" -> {
                var settings = new LinkedHashMap<String, String>();
                json.object("settings").members().forEach((k, v) -> settings.put(k, ((JsonString) v).value()));
                yield new ApiKeyCredential(Secret.of(json.string("key")), settings);
            }
            case "oauth" -> {
                var refresh = optional(json, "refresh");
                var expiresAt = optional(json, "expiresAt");
                yield OAuthCredential.builder(Secret.of(json.string("access")), json.string("issuer"), json.string("clientId"))
                        .refresh(refresh == null ? null : Secret.of(refresh))
                        .expiresAt(expiresAt == null ? null : Instant.parse(expiresAt))
                        .account(optional(json, "account"))
                        .scopes(json.get("scopes").map(s -> Set.of(Json.convert(s, String[].class))).orElse(Set.of()))
                        .extra(json.object("extra")).build();
            }
            default -> throw new IllegalArgumentException("Unknown credential type " + json.string("type"));
        };
    }

    private static @Nullable String optional(JsonObject json, String name) {
        return json.get(name).orElse(null) instanceof JsonString s ? s.value() : null;
    }
}
