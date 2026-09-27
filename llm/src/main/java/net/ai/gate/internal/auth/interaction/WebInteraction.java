package net.ai.gate.internal.auth.interaction;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import net.ai.gate.auth.interaction.AuthNotice;
import net.ai.gate.auth.interaction.AuthPrompt;
import net.ai.gate.auth.interaction.RedirectInteraction;

/// `AuthInteraction.redirect(…)`: the login thread waits in the `Code` prompt until the host's callback route
/// completes or fails this single-use interaction.
public final class WebInteraction implements RedirectInteraction {
    private final URI redirectUri;
    private final Consumer<URI> sendBrowserTo;
    private final CompletableFuture<String> callback = new CompletableFuture<>();

    public WebInteraction(URI redirectUri, Consumer<URI> sendBrowserTo) {
        this.redirectUri = redirectUri;
        this.sendBrowserTo = sendBrowserTo;
    }

    @Override public URI redirectUri() { return redirectUri; }

    @Override public String prompt(AuthPrompt prompt) {
        if (!(prompt instanceof AuthPrompt.Code))
            throw new UnsupportedOperationException("A redirect interaction answers only Code prompts; collect "
                    + prompt.getClass().getSimpleName() + " with a form and auth().save(…)");
        try {
            return callback.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        }
    }

    @Override public void notify(AuthNotice notice) {
        if (notice instanceof AuthNotice.OpenUrl u) sendBrowserTo.accept(u.url());
    }

    @Override public void complete(URI callbackUri) { callback.complete(callbackUri.toString()); }

    @Override public void fail(String reason) { callback.completeExceptionally(new IllegalStateException(reason)); }
}
