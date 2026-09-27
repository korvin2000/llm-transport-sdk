package net.ai.gate.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.ai.gate.Llm;
import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.AuthStatus;
import net.ai.gate.auth.Credential;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.auth.ResolvedAuth;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.auth.oauth.OAuthConfig;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.event.CredentialEvent;
import net.ai.gate.lifecycle.CancelToken;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.spi.http.HttpReply;
import net.ai.gate.spi.http.HttpTransport;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import net.ai.gate.testing.RecordingListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Generic OAuth coordination with an in-process strategy double: a rejected token is refreshed exactly once,
/// concurrent rejections share that refresh, and failures keep their cause. Real OAuth endpoints stay deferred.
@Timeout(20)
class AuthResolverTest {
    /// Refreshes by appending `+1` to the access token; counts refreshes.
    static final class CountingOAuth implements OAuthAuth {
        final AtomicInteger refreshes = new AtomicInteger();
        volatile boolean fail;

        @Override public String name() { return "Counting OAuth"; }
        @Override public OAuthCredential login(AuthInteraction ui, CancelToken cancel) { throw new UnsupportedOperationException(); }
        @Override public OAuthCredential refresh(OAuthCredential credential) {
            refreshes.incrementAndGet();
            if (fail) throw new IllegalStateException("refresh token revoked");
            return OAuthCredential.builder(Secret.of(credential.access().reveal() + "+1"), credential.issuer(), credential.clientId())
                    .refresh(credential.refresh().orElse(null)).expiresAt(credential.expiresAt().orElse(null))
                    .account(credential.account().orElse(null)).scopes(credential.scopes()).extra(credential.extra()).build();
        }
        @Override public ResolvedAuth toAuth(OAuthCredential credential) { return ResolvedAuth.headers(Map.of("Authorization", "Bearer " + credential.access().reveal()), "OAuth"); }
    }

    private static OAuthCredential token(String access) {
        return OAuthCredential.builder(Secret.of(access), "https://issuer.example", "client").refresh(Secret.of("refresh-token-1")).account("alice").build();
    }

    /// Accepts only bearer tokens in `valid`; everything else is 401.
    private static HttpTransport gate(HttpTransport delegate, java.util.Set<String> valid, AtomicInteger sends) {
        return (call, options) -> {
            sends.incrementAndGet();
            var auth = call.headers().get("Authorization");
            if (auth == null || !valid.contains(auth.substring("Bearer ".length()))) return Fixtures.error(401, "invalid_credentials", "bad token");
            return delegate.send(call, options);
        };
    }

    private static Provider provider(FakeProvider fake, OAuthAuth oauth, HttpTransport transport) {
        return fake.provider().toBuilder().auth(ApiKeyAuth.bearer("Fake key", "FAKE_API_KEY")).auth(oauth).transport(transport).build();
    }

    @Test
    void aRejectedTokenIsRefreshedOnceAndTheRetryUsesTheNewOne() {
        var fake = FakeProvider.create().reply("ok");
        var oauth = new CountingOAuth();
        var sends = new AtomicInteger();
        var provider = provider(fake, oauth, gate(fake.provider().transport().orElseThrow(), java.util.Set.of("old+1"), sends));
        var store = CredentialStore.inMemory();
        store.update("fake", _ -> Optional.of(token("old")));
        var events = new RecordingListener();
        try (var llm = Llm.builder().provider(provider).credentials(store).environment(Environment.none()).catalog(c -> c.offline()).listener(events).build()) {
            var reply = llm.complete(llm.model("fake", "fake"), "hi");
            assertEquals("ok", reply.text());
            assertEquals(1, oauth.refreshes.get());
            assertEquals(2, sends.get(), "401, then the retry with the fresh token");
            assertEquals(1, events.events(CredentialEvent.Refreshed.class).size());
            assertEquals("old+1", ((OAuthCredential) store.read("fake").orElseThrow()).access().reveal(), "the rotated token is persisted");
        }
    }

    @Test
    void aSecond401AfterTheRefreshIsNotRefreshedAgain() {
        var fake = FakeProvider.create();
        var oauth = new CountingOAuth();
        var sends = new AtomicInteger();
        var provider = provider(fake, oauth, gate(fake.provider().transport().orElseThrow(), java.util.Set.of(), sends));
        var store = CredentialStore.inMemory();
        store.update("fake", _ -> Optional.of(token("old")));
        try (var llm = Llm.builder().provider(provider).credentials(store).environment(Environment.none()).catalog(c -> c.offline()).build()) {
            var error = assertThrows(AuthenticationException.class, () -> llm.complete(llm.model("fake", "fake"), "hi"));
            assertEquals(ErrorCode.INVALID_CREDENTIALS, error.code());
            assertEquals(1, oauth.refreshes.get());
            assertEquals(2, sends.get());
        }
    }

    @Test
    void concurrentRejectionsShareOneRefresh() throws Exception {
        var fake = FakeProvider.create();
        for (int i = 0; i < 8; i++) fake.reply("ok");
        var oauth = new CountingOAuth();
        var sends = new AtomicInteger();
        var provider = provider(fake, oauth, gate(fake.provider().transport().orElseThrow(), java.util.Set.of("old+1"), sends));
        var store = CredentialStore.inMemory();
        store.update("fake", _ -> Optional.of(token("old")));
        try (var llm = Llm.builder().provider(provider).credentials(store).environment(Environment.none()).catalog(c -> c.offline()).build();
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> { start.await(); return llm.complete(llm.model("fake", "fake"), "hi").text(); }));
            start.countDown();
            for (var f : futures) assertEquals("ok", f.get(10, TimeUnit.SECONDS));
            assertEquals(1, oauth.refreshes.get(), "the store serializes the refresh; waiters keep the fresh token");
        }
    }

    @Test
    void aFailedRefreshRequiresLoginAndNeverFallsBackToAnEnvironmentKey() {
        var fake = FakeProvider.create().reply("never");
        var oauth = new CountingOAuth();
        oauth.fail = true;
        var provider = provider(fake, oauth, gate(fake.provider().transport().orElseThrow(), java.util.Set.of(), new AtomicInteger()));
        var store = CredentialStore.inMemory();
        store.update("fake", _ -> Optional.of(token("old")));
        var events = new RecordingListener();
        try (var llm = Llm.builder().provider(provider).credentials(store).environment(Environment.of(Map.of("FAKE_API_KEY", "sk-env-0123456789")))
                .catalog(c -> c.offline()).listener(events).build()) {
            var error = assertThrows(AuthenticationException.class, () -> llm.complete(llm.model("fake", "fake"), "hi"));
            assertEquals(ErrorCode.LOGIN_REQUIRED, error.code());
            assertTrue(error.getMessage().contains("revoked"), error.getMessage());
            assertEquals(AuthStatus.State.REFRESH_FAILED, llm.auth().status("fake").state());
            assertEquals(1, events.events(CredentialEvent.RefreshFailed.class).size());
            assertTrue(fake.requests().isEmpty(), "no request with the environment key");
        }
    }

    @Test
    void aFailingStoreKeepsItsOwnErrorCode() {
        var fake = FakeProvider.create();
        var oauth = new CountingOAuth();
        var provider = provider(fake, oauth, gate(fake.provider().transport().orElseThrow(), java.util.Set.of(), new AtomicInteger()));
        var broken = new CredentialStore() {
            @Override public Optional<Credential> read(String key) { return Optional.of(token("old")); }
            @Override public List<Entry> list() { return List.of(); }
            @Override public Optional<Credential> update(String key, Function<Optional<Credential>, Optional<Credential>> change) {
                throw new IllegalStateException("disk full");
            }
        };
        try (var llm = Llm.builder().provider(provider).credentials(broken).environment(Environment.none()).catalog(c -> c.offline()).build()) {
            var error = assertThrows(AuthenticationException.class, () -> llm.complete(llm.model("fake", "fake"), "hi"));
            assertEquals(ErrorCode.CREDENTIAL_STORE, error.code());
            assertEquals(0, oauth.refreshes.get() > 1 ? 1 : 0, "at most one refresh attempt");
        }
    }

    @Test
    void theStandardOAuthStrategyPresentsTokens() {
        var config = OAuthConfig.builder("client").authorizationEndpoint(URI.create("https://issuer.example/auth"))
                .tokenEndpoint(URI.create("https://issuer.example/token")).build();
        var standard = OAuthAuth.standard(config);
        var credential = token("access-1").toBuilder().expiresAt(Instant.parse("2030-01-01T00:00:00Z")).build();
        assertEquals("Bearer access-1", standard.toAuth(credential).headers().get("Authorization"));
        var error = assertThrows(AuthenticationException.class, () -> standard.refresh(credential.toBuilder().refresh(null).build()));
        assertEquals(ErrorCode.REFRESH_FAILED, error.code());
        assertThrows(IllegalArgumentException.class, () -> OAuthConfig.builder("c").authorizationEndpoint(URI.create("http://issuer.example/auth")));
        assertEquals(HttpCall.get("models").uri().toString(), "models");
        assertTrue(HttpReply.of(200, Map.of(), new byte[0]).successful());
    }
}
