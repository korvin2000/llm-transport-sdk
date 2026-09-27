package net.ai.gate.auth.oauth;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

import net.ai.gate.json.JsonObject;
import org.jspecify.annotations.Nullable;

/// Immutable OAuth client configuration from trusted sources — presets or the host; an issuer URL alone is not
/// permission to contact a host. PKCE S256 is always used; endpoints must be HTTPS except on loopback.
public final class OAuthConfig {
    public enum Grant { AUTHORIZATION_CODE, DEVICE_CODE, CLIENT_CREDENTIALS }
    /// The device grant as RFC 8628 defines it, or OpenAI's variant: JSON requests to `…/deviceauth/usercode` (the
    /// device endpoint) and `…/deviceauth/token`, which yield an authorization code and verifier that are exchanged at
    /// the token endpoint with the redirect `/deviceauth/callback`; the user approves at `/codex/device` — all on the
    /// device endpoint's origin.
    public enum DeviceDialect { RFC_8628, OPENAI }

    private final String clientId;
    private final @Nullable String clientSecretKey;
    private final URI authorizationEndpoint, tokenEndpoint;
    private final @Nullable URI deviceAuthorizationEndpoint, revocationEndpoint, redirectUri;
    private final @Nullable Integer loopbackPort;
    private final Set<String> scopes;
    private final Set<Grant> grants;
    private final @Nullable Function<JsonObject, OAuthCredential> tokenResponseMapper;
    private final String redirectParameter;
    private final boolean jsonTokenRequests;
    private final Map<String, String> authorizationParameters;
    private final List<String> accountClaim;
    private final @Nullable String accountHeader;
    private final DeviceDialect deviceDialect;

    private OAuthConfig(Builder b, URI authorizationEndpoint, URI tokenEndpoint) {
        clientId = b.clientId; clientSecretKey = b.clientSecretKey; this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint; deviceAuthorizationEndpoint = b.deviceAuthorizationEndpoint;
        revocationEndpoint = b.revocationEndpoint; redirectUri = b.redirectUri; loopbackPort = b.loopbackPort;
        scopes = Set.copyOf(b.scopes); grants = Set.copyOf(b.grants); tokenResponseMapper = b.tokenResponseMapper;
        redirectParameter = b.redirectParameter; jsonTokenRequests = b.jsonTokenRequests;
        authorizationParameters = Collections.unmodifiableMap(new LinkedHashMap<>(b.authorizationParameters));
        accountClaim = List.copyOf(b.accountClaim); accountHeader = b.accountHeader; deviceDialect = b.deviceDialect;
    }

    public static Builder builder(String clientId) { return new Builder(clientId); }

    public String clientId() { return clientId; }
    /// Confidential clients: the credential-store key of an `ApiKeyCredential` holding the secret — never the secret.
    public Optional<String> clientSecretKey() { return Optional.ofNullable(clientSecretKey); }
    public URI authorizationEndpoint() { return authorizationEndpoint; }
    public URI tokenEndpoint() { return tokenEndpoint; }
    public Optional<URI> deviceAuthorizationEndpoint() { return Optional.ofNullable(deviceAuthorizationEndpoint); }
    public Optional<URI> revocationEndpoint() { return Optional.ofNullable(revocationEndpoint); }
    /// A fixed external redirect; a `RedirectInteraction`'s URI wins.
    public Optional<URI> redirectUri() { return Optional.ofNullable(redirectUri); }
    /// For providers with fixed loopback redirect URIs.
    public OptionalInt loopbackPort() { return loopbackPort == null ? OptionalInt.empty() : OptionalInt.of(loopbackPort); }
    public Set<String> scopes() { return scopes; }
    public Set<Grant> grants() { return grants; }
    /// Maps non-standard token responses (OpenRouter's PKCE exchange returns `{"key": …}`).
    /// The credential must name this config's client id and the authorization endpoint's origin as its issuer.
    public Optional<Function<JsonObject, OAuthCredential>> tokenResponseMapper() { return Optional.ofNullable(tokenResponseMapper); }

    /// The authorization-request parameter carrying the redirect: `redirect_uri`, or a dialect's own
    /// (OpenRouter's `callback_url`).
    public String redirectParameter() { return redirectParameter; }

    /// Token requests as JSON instead of `application/x-www-form-urlencoded` (RFC 6749).
    public boolean jsonTokenRequests() { return jsonTokenRequests; }

    /// Extra parameters of the authorization request, in order.
    public Map<String, String> authorizationParameters() { return authorizationParameters; }

    /// The claim path in the access token (a JWT) that names the account; empty means the ID token's `email` or
    /// `sub`. A login without it fails.
    public List<String> accountClaim() { return accountClaim; }

    /// The header that carries the account on every request (ChatGPT's `chatgpt-account-id`).
    public Optional<String> accountHeader() { return Optional.ofNullable(accountHeader); }

    public DeviceDialect deviceDialect() { return deviceDialect; }

    @Override public String toString() { return "OAuthConfig[" + clientId + ", " + tokenEndpoint.getHost() + ", grants=" + grants + "]"; }

    /// Not thread-safe.
    public static final class Builder {
        private final String clientId;
        private @Nullable String clientSecretKey;
        private @Nullable URI authorizationEndpoint, tokenEndpoint, deviceAuthorizationEndpoint, revocationEndpoint, redirectUri;
        private @Nullable Integer loopbackPort;
        private Set<String> scopes = Set.of();
        private final Set<Grant> grants = EnumSet.of(Grant.AUTHORIZATION_CODE);
        private @Nullable Function<JsonObject, OAuthCredential> tokenResponseMapper;
        private String redirectParameter = "redirect_uri";
        private boolean jsonTokenRequests;
        private final Map<String, String> authorizationParameters = new LinkedHashMap<>();
        private List<String> accountClaim = List.of();
        private @Nullable String accountHeader;
        private DeviceDialect deviceDialect = DeviceDialect.RFC_8628;

        private Builder(String clientId) { this.clientId = clientId; }

        public Builder clientSecretKey(String credentialStoreKey) { clientSecretKey = credentialStoreKey; return this; }
        public Builder authorizationEndpoint(URI uri) { authorizationEndpoint = secure(uri); return this; }
        public Builder tokenEndpoint(URI uri) { tokenEndpoint = secure(uri); return this; }
        public Builder deviceAuthorizationEndpoint(URI uri) { return deviceAuthorizationEndpoint(uri, DeviceDialect.RFC_8628); }
        public Builder deviceAuthorizationEndpoint(URI uri, DeviceDialect dialect) {
            deviceAuthorizationEndpoint = secure(uri);
            deviceDialect = dialect;
            grants.add(Grant.DEVICE_CODE);
            return this;
        }
        public Builder revocationEndpoint(URI uri) { revocationEndpoint = secure(uri); return this; }
        public Builder redirectUri(URI uri) { redirectUri = uri; return this; }
        public Builder loopbackPort(int port) { loopbackPort = port; return this; }
        public Builder scopes(Set<String> values) { scopes = values; return this; }
        public Builder grant(Grant grant) { grants.add(grant); return this; }
        public Builder tokenResponseMapper(Function<JsonObject, OAuthCredential> mapper) { tokenResponseMapper = mapper; return this; }
        public Builder redirectParameter(String name) { redirectParameter = name; return this; }
        public Builder jsonTokenRequests() { jsonTokenRequests = true; return this; }
        public Builder authorizationParameter(String name, String value) { authorizationParameters.put(name, value); return this; }
        public Builder accountClaim(String... path) { accountClaim = List.of(path); return this; }
        public Builder accountHeader(String name) { accountHeader = name; return this; }

        public OAuthConfig build() {
            if (authorizationEndpoint == null || tokenEndpoint == null)
                throw new IllegalArgumentException("OAuth config '" + clientId + "' needs authorization and token endpoints");
            return new OAuthConfig(this, authorizationEndpoint, tokenEndpoint);
        }

        private static URI secure(URI uri) {
            var host = String.valueOf(uri.getHost());
            if (!"https".equals(uri.getScheme()) && !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]")))
                throw new IllegalArgumentException("OAuth endpoints must use HTTPS: " + uri);
            return uri;
        }
    }
}
