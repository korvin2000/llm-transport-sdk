package net.ai.gate.auth;

import java.util.List;

import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.lifecycle.CancelToken;
import org.jetbrains.annotations.ApiStatus;

/// Status and login for the runtime's providers, against the runtime's — or a view's — credential store.
/// Thread-safe.
@ApiStatus.NonExtendable
public interface Auth {
    /// Local: store and environment only; no network, never fetches tokens.
    AuthStatus status(String providerId);

    /// `API_KEY`, `OAUTH` in preference order.
    List<AuthType> methods(String providerId);

    /// Runs the provider's flow on the calling thread and stores the result.
    Credential login(String providerId, AuthType type, AuthInteraction ui);

    Credential login(String providerId, AuthType type, AuthInteraction ui, CancelToken cancel);

    /// For static forms: no dialog.
    void save(String providerId, Credential credential);

    /// Local and immediate: deletes the stored credential.
    void logout(String providerId);

    /// Remote revocation where supported, then `logout`.
    void revoke(String providerId);
}
