package net.ai.gate.auth.oauth;

import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.internal.auth.oauth.StandardOAuth;
import net.ai.gate.lifecycle.CancelToken;

/// **SPI** (provider or host). OAuth for a provider. `refresh` and `toAuth` are split so the core owns the locked
/// refresh inside `CredentialStore.update`. Thread-safe.
public interface OAuthAuth {
    /// `OpenRouter (OAuth)`.
    String name();

    /// Runs the interactive flow; network. Talks to the user only through `ui`.
    OAuthCredential login(AuthInteraction ui, CancelToken cancel);

    /// Network; runs inside `CredentialStore.update`.
    OAuthCredential refresh(OAuthCredential credential);

    /// No I/O.
    ResolvedAuth toAuth(OAuthCredential credential);

    /// Network: revokes the tokens at the issuer where it offers revocation; a no-op otherwise.
    default void revoke(OAuthCredential credential) { }

    /// PKCE S256 with a loopback or external redirect, device code, client credentials — by the config's grants.
    static OAuthAuth standard(OAuthConfig config) { return new StandardOAuth(config); }
}
