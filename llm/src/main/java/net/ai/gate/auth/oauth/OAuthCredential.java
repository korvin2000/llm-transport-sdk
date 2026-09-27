package net.ai.gate.auth.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.ai.gate.auth.AuthType;
import net.ai.gate.auth.Credential;
import net.ai.gate.auth.Secret;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import org.jspecify.annotations.Nullable;

/// Immutable OAuth tokens bound to issuer, client and account: a refresh or re-login cannot silently change the
/// principal. The access token may be an API key issued through OAuth (OpenRouter). `toString()` redacts.
public final class OAuthCredential implements Credential {
    private final Secret access;
    private final @Nullable Secret refresh;
    private final @Nullable Instant expiresAt;
    private final String issuer, clientId;
    private final @Nullable String account;
    private final Set<String> scopes;
    private final JsonObject extra;

    private OAuthCredential(Builder b) {
        access = b.access; refresh = b.refresh; expiresAt = b.expiresAt; issuer = b.issuer; clientId = b.clientId;
        account = b.account; scopes = Set.copyOf(b.scopes); extra = b.extra;
    }

    public static Builder builder(Secret access, String issuer, String clientId) { return new Builder(access, issuer, clientId); }

    @Override public AuthType type() { return AuthType.OAUTH; }
    public Secret access() { return access; }
    public Optional<Secret> refresh() { return Optional.ofNullable(refresh); }
    public Optional<Instant> expiresAt() { return Optional.ofNullable(expiresAt); }
    public String issuer() { return issuer; }
    public String clientId() { return clientId; }
    public Optional<String> account() { return Optional.ofNullable(account); }
    public Set<String> scopes() { return scopes; }
    /// Provider data, e.g. a per-account base URL.
    public JsonObject extra() { return extra; }

    /// Expired, or expiring within `skew` of `now`.
    public boolean expiresWithin(Duration skew, Instant now) { return expiresAt != null && !now.plus(skew).isBefore(expiresAt); }

    public Builder toBuilder() {
        return new Builder(access, issuer, clientId).refresh(refresh).expiresAt(expiresAt).account(account).scopes(scopes).extra(extra);
    }

    @Override public boolean equals(Object o) {
        return o instanceof OAuthCredential c && access.equals(c.access) && Objects.equals(refresh, c.refresh)
                && Objects.equals(expiresAt, c.expiresAt) && issuer.equals(c.issuer) && clientId.equals(c.clientId)
                && Objects.equals(account, c.account);
    }

    @Override public int hashCode() { return Objects.hash(access, issuer, clientId, account); }
    @Override public String toString() { return "OAuthCredential[" + issuer + ", " + access.fingerprint() + ", expires=" + expiresAt + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private final Secret access;
        private final String issuer, clientId;
        private @Nullable Secret refresh;
        private @Nullable Instant expiresAt;
        private @Nullable String account;
        private Set<String> scopes = Set.of();
        private JsonObject extra = Json.object();

        private Builder(Secret access, String issuer, String clientId) { this.access = access; this.issuer = issuer; this.clientId = clientId; }

        public Builder refresh(@Nullable Secret token) { refresh = token; return this; }
        public Builder expiresAt(@Nullable Instant value) { expiresAt = value; return this; }
        public Builder account(@Nullable String value) { account = value; return this; }
        public Builder scopes(Set<String> values) { scopes = values; return this; }
        public Builder extra(JsonObject value) { extra = value; return this; }
        public OAuthCredential build() { return new OAuthCredential(this); }
    }
}
