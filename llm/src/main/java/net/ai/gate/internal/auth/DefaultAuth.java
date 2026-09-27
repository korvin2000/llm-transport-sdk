package net.ai.gate.internal.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.ai.gate.Provider;
import net.ai.gate.auth.Auth;
import net.ai.gate.auth.AuthStatus;
import net.ai.gate.auth.AuthType;
import net.ai.gate.auth.Credential;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.lifecycle.CancelToken;

/// `llm.auth()` over one credential store — the runtime's or a view's.
public final class DefaultAuth implements Auth {
    private final Function<String, Provider> providers;
    private final AuthResolver resolver;
    private final CredentialStore store;

    public DefaultAuth(Function<String, Provider> providers, AuthResolver resolver, CredentialStore store) {
        this.providers = providers; this.resolver = resolver; this.store = store;
    }

    @Override public AuthStatus status(String providerId) { return resolver.status(providers.apply(providerId), store); }

    @Override public List<AuthType> methods(String providerId) {
        var provider = providers.apply(providerId);
        var methods = new ArrayList<AuthType>();
        provider.apiKeyAuth().filter(a -> !(a instanceof KeyAuth k && k.keyless())).ifPresent(_ -> methods.add(AuthType.API_KEY));
        provider.oauthAuth().ifPresent(_ -> methods.add(AuthType.OAUTH));
        return List.copyOf(methods);
    }

    @Override public Credential login(String providerId, AuthType type, AuthInteraction ui) {
        return login(providerId, type, ui, CancelToken.create());
    }

    @Override public Credential login(String providerId, AuthType type, AuthInteraction ui, CancelToken cancel) {
        var provider = providers.apply(providerId);
        Credential credential;
        try {
            credential = switch (type) {
                case API_KEY -> provider.apiKeyAuth().flatMap(a -> a.login(ui))
                        .orElseThrow(() -> failure(ErrorCode.LOGIN_CANCELLED, provider, "no API key was entered", null));
                case OAUTH -> provider.oauthAuth().orElseThrow(() -> failure(ErrorCode.INVALID_REQUEST, provider,
                        "the provider offers no OAuth login", null)).login(ui, cancel);
            };
        } catch (LlmException | UnsupportedOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw failure(ErrorCode.LOGIN_CANCELLED, provider, e.getMessage(), e);
        }
        if (cancel.isCancelled()) throw failure(ErrorCode.LOGIN_CANCELLED, provider, "cancelled", null);
        save(providerId, credential);
        return credential;
    }

    @Override public void save(String providerId, Credential credential) {
        var provider = providers.apply(providerId);
        store.update(provider.id(), _ -> Optional.of(credential));
        resolver.clearFailure(provider, store);
    }

    @Override public void logout(String providerId) { store.delete(providers.apply(providerId).id()); }

    @Override public void revoke(String providerId) {
        var provider = providers.apply(providerId);
        if (store.read(provider.id()).orElse(null) instanceof OAuthCredential c) provider.oauthAuth().ifPresent(a -> a.revoke(c));
        logout(providerId);
    }

    private static AuthenticationException failure(ErrorCode code, Provider provider, String reason, Throwable cause) {
        return new AuthenticationException(LlmException.Details.builder(code, "Login to '" + provider.id() + "' failed: " + reason)
                .providerId(provider.id()).build(), cause);
    }
}
