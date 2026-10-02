package net.ai.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.ai.gate.auth.Environment;
import net.ai.gate.metadata.Usage;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.model.Prices;
import net.ai.gate.providers.Providers;
import net.ai.gate.spi.catalog.CatalogFeed;
import net.ai.gate.spi.catalog.FeedHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The freshest data wins: host values always, then the newest non-absent value; absent never overwrites.
class CatalogTest {
    private static final ModelRef SONNET = new ModelRef("anthropic", "claude-sonnet-5");

    private static CatalogFeed feed(Instant updatedAt, long maxOutputTokens) {
        return new CatalogFeed() {
            @Override public String id() { return "test-feed"; }
            @Override public List<Model> fetch(FeedHttp http) {
                return List.of(Model.builder("anthropic", "claude-sonnet-5").maxOutputTokens(maxOutputTokens).updatedAt(updatedAt).build());
            }
        };
    }

    /// The shipped entry, whatever the regenerated data says.
    private static Model bundled() {
        try (var llm = Llm.builder().provider(Providers.anthropic()).environment(Environment.none()).catalog(c -> c.offline()).build()) {
            return llm.models().find(SONNET).orElseThrow();
        }
    }

    @Test
    void bundledDataFillsWhatTheHostDidNotState() {
        var shipped = bundled();
        var anthropic = Providers.anthropic().toBuilder().model(Model.builder("anthropic", "claude-sonnet-5").contextWindow(1_000).build()).build();
        try (var llm = Llm.builder().provider(anthropic).environment(Environment.none()).catalog(c -> c.offline()).build()) {
            var model = llm.model("anthropic", "claude-sonnet-5");
            assertEquals(1_000, model.contextWindow().orElseThrow(), "host values win");
            assertEquals(shipped.prices(), model.prices(), "absent never overwrites");
            assertEquals(shipped.maxOutputTokens(), model.maxOutputTokens());
            assertEquals(Model.Source.CUSTOM, model.source());
        }
    }

    @Test
    void newerFeedDataWinsOlderDoesNot() {
        var shipped = bundled();
        for (var newer : List.of(true, false)) {
            var updated = Instant.parse(newer ? "2030-01-01T00:00:00Z" : "2020-01-01T00:00:00Z");
            try (var llm = Llm.builder().provider(Providers.anthropic()).environment(Environment.none())
                    .catalog(c -> c.manualRefresh().feeds(List.of(feed(updated, 99)))).build()) {
                var report = llm.models().refresh();
                assertTrue(report.ok(), report.toString());
                var model = llm.models().find(SONNET).orElseThrow();
                assertEquals(newer ? 99 : shipped.maxOutputTokens().orElseThrow(), model.maxOutputTokens().orElseThrow());
                assertEquals(shipped.contextWindow(), model.contextWindow());
                assertEquals(newer ? Model.Source.FEED : Model.Source.BUNDLED, model.source());
            }
        }
    }

    @Test
    void availableListsModelsOfConfiguredProvidersOnly() {
        try (var llm = Llm.builder().discoverProviders().environment(Environment.of(Map.of("ANTHROPIC_API_KEY", "sk-ant-test-123456")))
                .catalog(c -> c.offline()).build()) {
            var available = llm.models().available().stream().map(Model::ref).toList();
            assertTrue(available.contains(SONNET), available.toString());
            assertTrue(available.stream().noneMatch(r -> r.providerId().equals("openai")), available.toString());
            assertTrue(llm.models().all().stream().anyMatch(m -> m.providerId().equals("openai")));
        }
    }

    @Test
    void refreshedSnapshotsSurviveRestarts(@TempDir Path directory) {
        var snapshot = directory.resolve("models.json");
        try (var llm = Llm.builder().provider(Providers.anthropic()).environment(Environment.none())
                .catalog(c -> c.manualRefresh().feeds(List.of(feed(Instant.parse("2030-01-01T00:00:00Z"), 77))).snapshotFile(snapshot)).build()) {
            llm.models().refresh();
        }
        assertTrue(Files.exists(snapshot));
        try (var llm = Llm.builder().provider(Providers.anthropic()).environment(Environment.none())
                .catalog(c -> c.offline().snapshotFile(snapshot)).build()) {
            assertEquals(77, llm.model("anthropic", "claude-sonnet-5").maxOutputTokens().orElseThrow());
        }
    }

    @Test
    void costUsesTheTierSelectedByTotalInputAndIsAbsentWhenUnknown() {
        var prices = Prices.usd().input("1.25").output("10").cacheRead("0.31")
                .tier(200_000, Prices.usd().input("2.5").output("15").build()).build();
        var small = prices.cost(Usage.builder().input(100_000).cacheRead(0).cacheWrite(0).output(1_000).build()).orElseThrow();
        assertEquals(new BigDecimal("0.135"), small.total().stripTrailingZeros());
        var large = prices.cost(Usage.builder().input(300_000).cacheRead(0).cacheWrite(0).output(1_000).build()).orElseThrow();
        assertEquals(new BigDecimal("0.765"), large.total().stripTrailingZeros());
        assertTrue(prices.cost(Usage.builder().output(10).build()).isEmpty(), "unreported input makes cost absent");
        assertTrue(prices.tier(Usage.builder().input(100_000).cacheRead(0).cacheWrite(0).output(1_000).build()).isEmpty(), "base prices");
        assertEquals(200_000, prices.tier(Usage.builder().input(300_000).cacheRead(0).cacheWrite(0).output(1_000).build()).orElseThrow().inputTokensAbove());
    }
}
