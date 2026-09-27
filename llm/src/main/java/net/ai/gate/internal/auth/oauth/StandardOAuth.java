package net.ai.gate.internal.auth.oauth;

import java.util.Map;

import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.auth.oauth.OAuthConfig;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.lifecycle.CancelToken;

/// `OAuthAuth.standard(config)`. Token presentation is complete; the interactive flows are the next vertical slice.
///
/// **Stub (roadmap slice 2):** authorization code + PKCE S256 with a `127.0.0.1` loopback listener or an external
/// redirect (`RedirectInteraction`), device authorization (RFC 8628) with polling, client credentials, refresh with
/// token rotation, revocation; `state` validation and issuer/client binding as specified in RFC 8252 and RFC 9700.
public final class StandardOAuth implements OAuthAuth {
    private final OAuthConfig config;

    public StandardOAuth(OAuthConfig config) { this.config = config; }

    public OAuthConfig config() { return config; }

    @Override public String name() { return "OAuth (" + config.tokenEndpoint().getHost() + ")"; }

    @Override public OAuthCredential login(AuthInteraction ui, CancelToken cancel) {
        throw new UnsupportedOperationException("OAuth login flows are not implemented yet (roadmap slice 2)");
    }

    @Override public OAuthCredential refresh(OAuthCredential credential) {
        throw new UnsupportedOperationException("OAuth token refresh is not implemented yet (roadmap slice 2)");
    }

    @Override public ResolvedAuth toAuth(OAuthCredential credential) {
        return ResolvedAuth.headers(Map.of("Authorization", "Bearer " + credential.access().reveal()), "OAuth");
    }
}
