package net.ai.gate.auth.interaction;

import java.net.URI;
import java.util.function.Consumer;

import net.ai.gate.internal.auth.interaction.ConsoleInteraction;
import net.ai.gate.internal.auth.interaction.WebInteraction;

/// **SPI** (host UI). The one login protocol for every flow: API-key entry, browser OAuth, device codes and web
/// redirects. Prompts and notices reach only the caller of `login()` — never listeners, logs or events. Confined to
/// the login thread (a virtual thread in servers).
public interface AuthInteraction {
    /// Blocks until the user answers; throw to abort the login.
    String prompt(AuthPrompt prompt);

    /// Returns quickly.
    void notify(AuthNotice notice);

    /// A terminal: prints prompts and notices and opens a desktop browser when one is available.
    static AuthInteraction console() { return new ConsoleInteraction(); }

    /// Web hosts: OAuth flows redirect to `redirectUri` instead of a loopback listener; `OpenUrl` notices go to
    /// `sendBrowserTo`; the host's callback route completes the returned interaction.
    static RedirectInteraction redirect(URI redirectUri, Consumer<URI> sendBrowserTo) { return new WebInteraction(redirectUri, sendBrowserTo); }
}
