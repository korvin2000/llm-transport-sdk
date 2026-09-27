package net.ai.gate.internal.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyCredential;
import net.ai.gate.auth.AuthInput;
import net.ai.gate.auth.AuthStatus;
import net.ai.gate.auth.AuthType;
import net.ai.gate.auth.Credential;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.error.LlmException;
import net.ai.gate.event.CredentialEvent;
import net.ai.gate.event.LlmEvent;
import net.ai.gate.internal.auth.store.ScopedStore;
import org.jspecify.annotations.Nullable;

/// The resolution chain, for every call: stored credential (OAuth refreshed inside `CredentialStore.update`) ▷
/// environment and ambient sources ▷ keyless ▷ `login_required` naming the variables. A stored credential owns its
/// provider: a failed refresh never falls back to an environment key. Refresh state is kept per store instance,
/// scope and provider. Thread-safe.
public final class AuthResolver {
    private static final Duration SKEW = Duration.ofSeconds(60);

    private final Environment environment;
    private final Clock clock;
    private final Consumer<LlmEvent> events;
    /// Root store → `scope + provider id` whose last refresh failed.
    private final Map<CredentialStore, Set<String>> refreshFailed = Collections.synchronizedMap(new WeakHashMap<>());

    public AuthResolver(Environment environment, Clock clock, Consumer<LlmEvent> events) {
        this.environment = environment; this.clock = clock; this.events = events;
    }

    public Environment environment() { return environment; }

    public ResolvedAuth resolve(Provider provider, CredentialStore store) { return resolve(provider, store, null); }

    /// After a `401` on OAuth credentials: one forced refresh of exactly the rejected token. A token another caller
    /// already replaced is used as it is, so concurrent rejections refresh once.
    public ResolvedAuth refreshAfterRejection(Provider provider, CredentialStore store, ResolvedAuth rejected) { return resolve(provider, store, rejected); }

    public boolean usesOAuth(Provider provider, CredentialStore store) { return read(provider, store) instanceof OAuthCredential; }

    private ResolvedAuth resolve(Provider provider, CredentialStore store, @Nullable ResolvedAuth rejected) {
        var stored = read(provider, store);
        if (stored instanceof OAuthCredential credential) return oauth(provider, store, credential, rejected);
        var input = input(stored);
        return provider.apiKeyAuth().flatMap(a -> a.resolve(input)).orElseThrow(() -> loginRequired(provider, null));
    }

    /// Local state only: the store and the environment; never fetches tokens.
    public AuthStatus status(Provider provider, CredentialStore store) {
        var stored = read(provider, store);
        if (stored instanceof OAuthCredential c) {
            var oauth = provider.oauthAuth().orElse(null);
            if (oauth == null) return AuthStatus.notConfigured();
            try {
                oauth.toAuth(c);   // local validation: a credential for a previous configuration is not usable
            } catch (AuthenticationException e) {
                return AuthStatus.of(AuthStatus.State.NOT_CONFIGURED, AuthType.OAUTH, "OAuth");
            }
            var now = clock.instant();
            var state = failed(provider, store) ? AuthStatus.State.REFRESH_FAILED
                    : c.expiresWithin(Duration.ZERO, now) && c.refresh().isEmpty() ? AuthStatus.State.EXPIRED
                    : c.expiresWithin(SKEW, now) ? AuthStatus.State.EXPIRING : AuthStatus.State.CONFIGURED;
            return AuthStatus.of(state, AuthType.OAUTH, "OAuth").withExpiry(c.expiresAt().orElse(null)).withAccount(c.account().orElse(null));
        }
        var input = input(stored);
        return provider.apiKeyAuth().filter(a -> a.configured(input))
                .map(a -> AuthStatus.of(AuthStatus.State.CONFIGURED, AuthType.API_KEY,
                        a instanceof KeyAuth k ? k.source(input).orElse(a.name()) : stored != null ? "stored credential" : a.name()))
                .orElseGet(AuthStatus::notConfigured);
    }

    /// Local check for `models().available()`.
    public boolean configured(Provider provider, CredentialStore store) {
        return status(provider, store).state() != AuthStatus.State.NOT_CONFIGURED;
    }

    /// The non-secret principal of a stored credential, for cache and listing namespaces: issuer, client and account
    /// of an OAuth session; empty for API keys (one scope holds one key per provider) and for nothing stored.
    public String principal(Provider provider, CredentialStore store) {
        return read(provider, store) instanceof OAuthCredential c ? c.issuer() + "|" + c.clientId() + "|" + c.account().orElse("") : "";
    }

    public void clearFailure(Provider provider, CredentialStore store) { failures(store).remove(key(provider, store)); }

    private ResolvedAuth oauth(Provider provider, CredentialStore store, OAuthCredential credential, @Nullable ResolvedAuth rejected) {
        var oauth = provider.oauthAuth().orElseThrow(() -> loginRequired(provider, "an OAuth credential is stored, but the provider has no OAuth flow"));
        boolean force = rejected != null && oauth.toAuth(credential).equals(rejected);
        if (!force && !credential.expiresWithin(SKEW, clock.instant())) return oauth.toAuth(credential);
        if (credential.refresh().isEmpty()) throw loginRequired(provider, "the OAuth session expired");
        try {
            var refreshed = store.update(provider.id(), current -> current.map(c -> needsRefresh(c, credential, force) ? refresh(oauth, (OAuthCredential) c) : c));
            if (!(refreshed.orElse(null) instanceof OAuthCredential fresh)) throw loginRequired(provider, "the credential was removed during refresh");
            failures(store).remove(key(provider, store));
            events.accept(CredentialEvent.Refreshed.of(provider.id(), fresh.expiresAt().orElse(null), clock.instant()));
            return oauth.toAuth(fresh);
        } catch (RefreshFailure e) {
            failures(store).add(key(provider, store));
            events.accept(CredentialEvent.RefreshFailed.of(provider.id(), ErrorCode.REFRESH_FAILED, true, clock.instant()));
            throw new AuthenticationException(LlmException.Details.builder(ErrorCode.LOGIN_REQUIRED,
                    "Refreshing the OAuth session of '" + provider.id() + "' failed; sign in again: " + e.getCause().getMessage())
                    .providerId(provider.id()).build(), e.getCause());
        } catch (LlmException e) {
            throw e;   // the store's own failures keep their code (credential_store, login_required)
        } catch (RuntimeException e) {
            throw new AuthenticationException(LlmException.Details.builder(ErrorCode.CREDENTIAL_STORE,
                    "Updating credentials of '" + provider.id() + "' failed: " + e.getMessage()).providerId(provider.id()).build(), e);
        }
    }

    /// Single-flight: waiters that find a credential already refreshed by someone else keep it; a forced refresh
    /// applies only while the rejected token is still the stored one.
    private boolean needsRefresh(Credential current, OAuthCredential seen, boolean force) {
        return current instanceof OAuthCredential c && (force ? c.equals(seen) : c.expiresWithin(SKEW, clock.instant()));
    }

    /// Distinguishes a failing refresh (`login_required`) from a failing store (`credential_store`).
    private static final class RefreshFailure extends RuntimeException {
        RefreshFailure(Throwable cause) { super(cause); }
    }

    private static OAuthCredential refresh(OAuthAuth oauth, OAuthCredential credential) {
        OAuthCredential fresh;
        try {
            fresh = oauth.refresh(credential);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) throw e;   // the call's deadline or cancellation, not a failed refresh
            throw new RefreshFailure(e);
        }
        if (!fresh.issuer().equals(credential.issuer()) || !fresh.clientId().equals(credential.clientId())
                || !fresh.account().equals(credential.account()) && credential.account().isPresent())
            throw new RefreshFailure(new IllegalStateException("A refresh must not change the principal"));
        return fresh;
    }

    private AuthInput input(@Nullable Credential stored) {
        return new AuthInput(stored instanceof ApiKeyCredential k ? Optional.of(k) : Optional.empty(), environment);
    }

    private static @Nullable Credential read(Provider provider, CredentialStore store) {
        try {
            return store.read(provider.id()).orElse(null);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AuthenticationException(LlmException.Details.builder(ErrorCode.CREDENTIAL_STORE,
                    "Reading credentials of '" + provider.id() + "' failed: " + e.getMessage()).providerId(provider.id()).build(), e);
        }
    }

    private boolean failed(Provider provider, CredentialStore store) { return failures(store).contains(key(provider, store)); }

    private Set<String> failures(CredentialStore store) { return refreshFailed.computeIfAbsent(ScopedStore.rootOf(store), _ -> ConcurrentHashMap.newKeySet()); }

    private static String key(Provider provider, CredentialStore store) { return ScopedStore.scopeOf(store) + provider.id(); }

    private static AuthenticationException loginRequired(Provider provider, @Nullable String reason) {
        var variables = provider.apiKeyAuth().orElse(null) instanceof KeyAuth k ? k.envVars() : List.<String>of();
        var how = (variables.isEmpty() ? "" : "set " + String.join(" or ", variables) + ", or ")
                + "call llm.auth().login(\"" + provider.id() + "\", …)";
        return new AuthenticationException(LlmException.Details.builder(ErrorCode.LOGIN_REQUIRED,
                "No usable credentials for provider '" + provider.id() + "'" + (reason == null ? "" : " (" + reason + ")") + ": " + how)
                .providerId(provider.id()).build());
    }
}
