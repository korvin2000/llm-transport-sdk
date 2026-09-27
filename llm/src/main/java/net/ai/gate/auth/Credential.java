package net.ai.gate.auth;

import net.ai.gate.auth.oauth.OAuthCredential;

/// What authenticates a provider, kept in a [CredentialStore] under the provider id. Closed set.
public sealed interface Credential permits ApiKeyCredential, OAuthCredential {
    AuthType type();
}
