package net.ai.gate.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.ai.gate.Llm;
import net.ai.gate.auth.interaction.AuthInteraction;
import net.ai.gate.auth.interaction.AuthNotice;
import net.ai.gate.auth.interaction.AuthPrompt;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.error.AuthenticationException;
import net.ai.gate.error.ErrorCode;
import net.ai.gate.json.Json;
import net.ai.gate.providers.Providers;
import net.ai.gate.testing.FakeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The resolution chain with visible sources, stores, views and the login protocol. No network anywhere.
class AuthTest {
    private static final String KEY = "sk-test-0123456789abcdef";

    private static FakeProvider keyedFake() { return FakeProvider.create(); }

    private static Llm runtime(FakeProvider fake, Environment environment) {
        var provider = fake.provider().toBuilder().auth(ApiKeyAuth.bearer("Fake key", "FAKE_API_KEY")).build();
        return Llm.builder().provider(provider).environment(environment).catalog(c -> c.offline()).build();
    }

    @Test
    void statusNamesItsSourceWithoutNetwork() {
        try (var llm = Llm.builder().discoverProviders().environment(Environment.of(Map.of("ANTHROPIC_API_KEY", KEY)))
                .catalog(c -> c.offline()).build()) {
            assertEquals(Optional.of("ANTHROPIC_API_KEY"), llm.auth().status("anthropic").source());
            assertEquals(AuthStatus.State.NOT_CONFIGURED, llm.auth().status("openai").state());
            assertEquals(Optional.of("keyless"), llm.auth().status("ollama").source());
            llm.auth().save("openai", ApiKeyCredential.of(KEY));
            assertEquals(Optional.of("stored credential"), llm.auth().status("openai").source());
            assertEquals(List.of(AuthType.API_KEY, AuthType.OAUTH), llm.auth().methods("openrouter"));
        }
    }

    @Test
    void aMissingKeyFailsNamingTheVariableAndAnEnvironmentKeyWorks() {
        var fake = keyedFake().reply("ok");
        try (var llm = runtime(fake, Environment.none())) {
            var error = assertThrows(AuthenticationException.class, () -> llm.complete(llm.model("fake", "fake"), "hi"));
            assertEquals(ErrorCode.LOGIN_REQUIRED, error.code());
            assertTrue(error.getMessage().contains("FAKE_API_KEY"), error.getMessage());
            assertTrue(fake.requests().isEmpty());
        }
        try (var llm = runtime(fake, Environment.of(Map.of("FAKE_API_KEY", KEY)))) {
            assertEquals("ok", llm.complete(llm.model("fake", "fake"), "hi").text());
        }
    }

    @Test
    void viewsIsolateUsersInOneSharedStore() {
        var store = CredentialStore.inMemory();
        try (var llm = runtime(keyedFake(), Environment.none())) {
            var alice = llm.withCredentials(store.scoped("alice"));
            var bob = llm.withCredentials(store.scoped("bob"));
            alice.auth().save("fake", ApiKeyCredential.of(KEY));
            assertEquals(AuthStatus.State.CONFIGURED, alice.auth().status("fake").state());
            assertEquals(AuthStatus.State.NOT_CONFIGURED, bob.auth().status("fake").state());
            assertEquals(List.of(new CredentialStore.Entry("alice/fake", AuthType.API_KEY)), store.list());
            assertTrue(alice.models().available().stream().anyMatch(m -> m.id().equals("fake")));
            assertTrue(bob.models().available().isEmpty());
        }
    }

    @Test
    void apiKeyLoginRunsThroughTheInteractionOnly() {
        var notices = new ArrayList<AuthNotice>();
        var ui = new AuthInteraction() {
            @Override public String prompt(AuthPrompt prompt) { return prompt instanceof AuthPrompt.SecretText ? KEY : ""; }
            @Override public void notify(AuthNotice notice) { notices.add(notice); }
        };
        try (var llm = runtime(keyedFake(), Environment.none())) {
            var credential = llm.auth().login("fake", AuthType.API_KEY, ui);
            assertEquals(Secret.of(KEY), ((ApiKeyCredential) credential).key());
            assertEquals(AuthStatus.State.CONFIGURED, llm.auth().status("fake").state());
            llm.auth().logout("fake");
            assertEquals(AuthStatus.State.NOT_CONFIGURED, llm.auth().status("fake").state());
        }
    }

    @Test
    void openRouterOffersKeysAndOAuth() {
        try (var llm = Llm.builder().provider(Providers.openRouter()).environment(Environment.none()).catalog(c -> c.offline()).build()) {
            assertEquals(List.of(AuthType.API_KEY, AuthType.OAUTH), llm.auth().methods("openrouter"));
        }
    }

    @Test
    void fileStoreRoundTripsAndSecretsNeverPrint(@TempDir Path directory) throws Exception {
        var file = directory.resolve("credentials.json");
        var oauth = OAuthCredential.builder(Secret.of("access-token-0123456789"), "https://issuer.example", "client")
                .refresh(Secret.of("refresh-token-0123456789")).expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .scopes(Set.of("a", "b")).extra(Json.object("region", "eu")).build();
        var store = CredentialStore.file(file);
        store.update("openai", _ -> Optional.of(ApiKeyCredential.of(KEY)));
        store.update("openrouter", _ -> Optional.of(oauth));

        var reopened = CredentialStore.file(file);
        assertEquals(Optional.of(ApiKeyCredential.of(KEY)), reopened.read("openai"));
        assertEquals(Optional.of(oauth), reopened.read("openrouter"));
        assertFalse(ApiKeyCredential.of(KEY).toString().contains(KEY));
        assertFalse(oauth.toString().contains("access-token"));
        assertTrue(Files.readString(file).contains("ai-gate.credentials/1"));
        reopened.delete("openai");
        assertEquals(List.of(new CredentialStore.Entry("openrouter", AuthType.OAUTH)), CredentialStore.file(file).list());
    }

    @Test
    void secretsFingerprintAndRejectBlanks() {
        assertEquals("sk-…cdef", Secret.of(KEY).fingerprint());
        assertThrows(IllegalArgumentException.class, () -> Secret.of(" "));
    }
}
