package net.ai.gate.internal.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.ai.gate.Llm;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.ApiKeyCredential;
import net.ai.gate.auth.CredentialStore;
import net.ai.gate.auth.Environment;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.Model;
import net.ai.gate.model.Prices;
import net.ai.gate.spi.catalog.ModelSource;
import net.ai.gate.spi.http.HttpCall;
import net.ai.gate.testing.FakeProvider;
import net.ai.gate.testing.Fixtures;
import org.junit.jupiter.api.Test;

/// Listings run with each view's own credentials and their availability is kept per view; metadata is shared and
/// merged component by component.
class CatalogViewTest {
    private static final ModelSource LISTING = http -> {
        var body = (JsonObject) http.get("models");
        return body.object("data").members().keySet().stream().map(id -> Model.builder(http.provider().id(), id).build()).toList();
    };

    /// Lists models by bearer token: `key-a` sees `a-model`, `key-b` sees `b-model`.
    private static net.ai.gate.spi.http.HttpTransport listingByKey(net.ai.gate.spi.http.HttpTransport delegate) {
        return (call, options) -> {
            if (!call.uri().getPath().endsWith("/models")) return delegate.send(call, options);
            var key = call.headers().getOrDefault("Authorization", "").replace("Bearer ", "");
            return switch (key) {
                case "key-a" -> Fixtures.json(200, "{\"data\":{\"a-model\":{}}}");
                case "key-b" -> Fixtures.json(200, "{\"data\":{\"b-model\":{}}}");
                default -> Fixtures.error(401, "invalid_credentials", "who are you");
            };
        };
    }

    @Test
    void eachViewListsWithItsOwnCredentialsAndSeesItsOwnAvailability() {
        var fake = FakeProvider.create();
        var provider = fake.provider().toBuilder().auth(ApiKeyAuth.bearer("Fake key", "FAKE_API_KEY")).modelSource(LISTING)
                .transport(listingByKey(fake.provider().transport().orElseThrow())).build();
        var root = CredentialStore.inMemory();   // the runtime itself has no credentials
        try (var llm = Llm.builder().provider(provider).credentials(root).environment(Environment.none()).catalog(c -> c.manualRefresh().noFeeds()).build()) {
            var storeA = CredentialStore.inMemory();
            storeA.update("fake", _ -> Optional.of(ApiKeyCredential.of("key-a")));
            var storeB = CredentialStore.inMemory();
            storeB.update("fake", _ -> Optional.of(ApiKeyCredential.of("key-b")));
            var a = llm.withCredentials(storeA);
            var b = llm.withCredentials(storeB);
            assertTrue(a.models().refresh("fake").ok(), "a configured view refreshes although the runtime store is empty");
            assertTrue(b.models().refresh("fake").ok());
            assertEquals(List.of("a-model"), a.models().available().stream().map(Model::id).filter(id -> id.endsWith("-model")).toList());
            assertEquals(List.of("b-model"), b.models().available().stream().map(Model::id).filter(id -> id.endsWith("-model")).toList());
            assertTrue(llm.models().available().isEmpty(), "the unconfigured runtime sees nothing");
            var all = llm.models().all("fake").stream().map(Model::id).toList();
            assertTrue(all.contains("a-model") && all.contains("b-model"), "existence is shared metadata: " + all);
        }
    }

    @Test
    void pricesMergeComponentByComponentAcrossSources() {
        var older = Model.builder("p", "m").prices(Prices.usd().input("1").output("2").cacheRead("0.1").build())
                .updatedAt(Instant.parse("2020-01-01T00:00:00Z")).source(Model.Source.FEED).build();
        var newer = Model.builder("p", "m").prices(Prices.usd().input("3").build()).updatedAt(Instant.parse("2030-01-01T00:00:00Z"))
                .source(Model.Source.FEED).build();
        var merged = CatalogService.overlay(older, newer).prices().orElseThrow();
        assertEquals(new BigDecimal("3"), merged.inputPerMillion().orElseThrow());
        assertEquals(new BigDecimal("2"), merged.outputPerMillion().orElseThrow(), "an input-only update keeps the other prices");
        assertEquals(new BigDecimal("0.1"), merged.cacheReadPerMillion().orElseThrow());
        assertEquals(HttpCall.get("x").method(), "GET");
    }
}
