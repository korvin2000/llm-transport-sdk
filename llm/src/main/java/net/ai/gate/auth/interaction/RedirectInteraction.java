package net.ai.gate.auth.interaction;

import java.net.URI;

/// An [AuthInteraction] for web hosts: the `Code` prompt waits until the host's callback route calls
/// [#complete(URI)] or [#fail(String)]. Thread-safe.
public interface RedirectInteraction extends AuthInteraction {
    URI redirectUri();

    /// The full callback URI, after the host has checked that the session belongs to the initiating user.
    void complete(URI callbackUri);

    void fail(String reason);
}
