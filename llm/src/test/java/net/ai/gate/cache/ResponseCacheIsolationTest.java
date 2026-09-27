package net.ai.gate.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import net.ai.gate.Llm;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.chat.Conversation;
import net.ai.gate.chat.options.ChatOptions;
import net.ai.gate.testing.FakeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The response cache never serves one caller's reply to another: keys include the credential namespace (store,
/// scope, account) and the effective request, headers included.
class ResponseCacheIsolationTest {
    private static Llm runtime(FakeProvider fake, ResponseCache cache) {
        return Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline()).responseCache(cache).build();
    }

    @Test
    void viewsWithDifferentStoresDoNotShareEntries() {
        var fake = FakeProvider.create().reply("for a").reply("for b").reply("for a again");
        try (var llm = runtime(fake, ResponseCache.inMemory(10))) {
            var a = llm.withCredentials(CredentialStore.inMemory());
            var b = llm.withCredentials(CredentialStore.inMemory());
            var model = llm.model("fake", "fake");
            assertEquals("for a", a.complete(model, "same question").text());
            var second = b.complete(model, "same question");
            assertEquals("for b", second.text());
            assertFalse(second.info().fromCache(), "another store is another namespace");
            assertTrue(a.complete(model, "same question").info().fromCache(), "the same view hits its own entry");
        }
    }

    @Test
    void sameNamedScopesOnDifferentStoresAndDifferentScopesDoNotShare() {
        var fake = FakeProvider.create().reply("1").reply("2").reply("3");
        try (var llm = runtime(fake, ResponseCache.inMemory(10))) {
            var model = llm.model("fake", "fake");
            var storeA = CredentialStore.inMemory();
            var storeB = CredentialStore.inMemory();
            assertEquals("1", llm.withCredentials(storeA.scoped("alice")).complete(model, "q").text());
            assertEquals("2", llm.withCredentials(storeB.scoped("alice")).complete(model, "q").text());
            assertEquals("3", llm.withCredentials(storeA.scoped("bob")).complete(model, "q").text());
            assertTrue(llm.withCredentials(storeA.scoped("alice")).complete(model, "q").info().fromCache());
        }
        fake.assertAllRepliesConsumed();
    }

    @Test
    void effectiveHeadersArePartOfTheKey() {
        var fake = FakeProvider.create().reply("tenant a").reply("tenant b");
        try (var llm = runtime(fake, ResponseCache.inMemory(10))) {
            var model = llm.model("fake", "fake");
            var a = ChatOptions.builder().header("X-Tenant", "a").build();
            var b = ChatOptions.builder().header("x-tenant", "b").build();
            assertEquals("tenant a", llm.complete(model, Conversation.of("q"), a).text());
            assertEquals("tenant b", llm.complete(model, Conversation.of("q"), b).text());
            var again = llm.complete(model, Conversation.of("q"), ChatOptions.builder().header("X-TENANT", "a").build());
            assertEquals("tenant a", again.text());
            assertTrue(again.info().fromCache(), "header names compare case-insensitively");
        }
        fake.assertAllRepliesConsumed();
    }

    @Test
    void cassettesRecordedByARuntimeReplayInAnotherProcess(@TempDir Path directory) {
        var fake = FakeProvider.create().reply("recorded");
        try (var llm = runtime(fake, ResponseCache.directory(directory))) {
            assertEquals("recorded", llm.complete(llm.model("fake", "fake"), "q").text());
        }
        var offline = FakeProvider.create();
        try (var llm = runtime(offline, ResponseCache.directory(directory))) {
            var replayed = llm.complete(llm.model("fake", "fake"), Conversation.of("q"), ChatOptions.builder().responseCache(CacheMode.OFFLINE).build());
            assertEquals("recorded", replayed.text());
            assertTrue(replayed.info().fromCache());
            assertTrue(offline.requests().isEmpty(), "offline replay never touches the endpoint");
        }
    }

    @Test
    void interceptorsDisableCachingBecauseTheyMayChangeTheRequest() {
        var fake = FakeProvider.create().reply("1").reply("2");
        try (var llm = Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog(c -> c.offline())
                .responseCache(ResponseCache.inMemory(10)).interceptor(chain -> chain.proceed(chain.call().withHeader("X-Signed", "yes"))).build()) {
            var model = llm.model("fake", "fake");
            llm.complete(model, "q");
            assertFalse(llm.complete(model, "q").info().fromCache());
        }
        fake.assertAllRepliesConsumed();
    }
}
