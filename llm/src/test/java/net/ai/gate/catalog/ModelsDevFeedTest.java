package net.ai.gate.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.ai.gate.json.Json;
import net.ai.gate.json.JsonValue;
import net.ai.gate.model.Capability;
import net.ai.gate.model.Modality;
import net.ai.gate.model.Model;
import net.ai.gate.model.ModelRef;
import net.ai.gate.model.ReasoningLevel;
import net.ai.gate.model.SupportLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/// Pins the models.dev format with a sample taken from the live feed; `./gradlew updateModelCatalog` regenerates the
/// bundled `models.json` from the live feed through the same mapping.
class ModelsDevFeedTest {
    private static JsonValue sample() throws IOException {
        try (var in = ModelsDevFeedTest.class.getResourceAsStream("models-dev-sample.json")) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void mapsProvidersLimitsModalitiesReasoningAndPrices() throws IOException {
        var models = ModelsDevFeed.parse(sample()).stream().collect(Collectors.toMap(Model::ref, m -> m));
        assertEquals(Set.of(new ModelRef("anthropic", "claude-sonnet-4-6"), new ModelRef("anthropic", "claude-haiku-4-5-20251001"),
                new ModelRef("google", "gemini-2.5-pro"), new ModelRef("openai", "gpt-5"), new ModelRef("openai", "gpt-3.5-turbo"),
                new ModelRef("openai", "gpt-5.5"), new ModelRef("openai-codex", "gpt-5.5")),
                models.keySet(), "unmapped providers and malformed entries are skipped; Codex models are listed twice");

        var sonnet = models.get(new ModelRef("anthropic", "claude-sonnet-4-6"));
        assertEquals(Model.Source.FEED, sonnet.source());
        assertEquals("Claude Sonnet 4.6", sonnet.name());
        assertEquals(1_000_000, sonnet.contextWindow().orElseThrow());
        assertEquals(128_000, sonnet.maxOutputTokens().orElseThrow());
        assertEquals(Set.of(Modality.TEXT, Modality.IMAGE, Modality.DOCUMENT), sonnet.input());
        assertEquals(List.of(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.MAX), sonnet.reasoningLevels());
        assertTrue(sonnet.capabilities().supported().containsAll(Set.of(Capability.TOOLS, Capability.REASONING, Capability.VISION, Capability.DOCUMENTS)));
        assertEquals(new BigDecimal("3.75"), sonnet.prices().orElseThrow().cacheWritePerMillion().orElseThrow());
        assertEquals(Instant.parse("2026-03-13T00:00:00Z"), sonnet.updatedAt().orElseThrow());

        assertEquals(List.of(ReasoningLevel.OFF, ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
                models.get(new ModelRef("anthropic", "claude-haiku-4-5-20251001")).reasoningLevels(), "budget control: switchable, default levels");
        assertEquals(List.of(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
                models.get(new ModelRef("openai", "gpt-5")).reasoningLevels(), "effort values only: GPT-5 cannot switch reasoning off");
        assertEquals(SupportLevel.UNSUPPORTED, models.get(new ModelRef("openai", "gpt-5")).capabilities().support(Capability.TEMPERATURE));
        assertEquals(SupportLevel.SUPPORTED, sonnet.capabilities().support(Capability.TEMPERATURE));

        var codex = models.get(new ModelRef("openai-codex", "gpt-5.5"));
        assertEquals(272_000, codex.contextWindow().orElseThrow(), "the Codex backend's window");
        assertTrue(codex.prices().isEmpty(), "a subscription has no per-token prices");
        assertEquals(models.get(new ModelRef("openai", "gpt-5.5")).reasoningLevels(), codex.reasoningLevels());

        var gemini = models.get(new ModelRef("google", "gemini-2.5-pro"));
        assertEquals(List.of(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), gemini.reasoningLevels(),
                "a budget of at least 128 tokens: thinking cannot be switched off");
        var tier = gemini.prices().orElseThrow().tiers().getFirst();
        assertEquals(200_000, tier.inputTokensAbove());
        assertEquals(new BigDecimal("2.5"), tier.prices().inputPerMillion().orElseThrow());
        assertTrue(models.get(new ModelRef("openai", "gpt-3.5-turbo")).deprecatedAt().isPresent());
    }

    /// `./gradlew updateModelCatalog`: the live feed's text models, without OpenRouter (listed live) and deprecated ones.
    @Test
    @EnabledIfSystemProperty(named = "ai-gate.updateCatalog", matches = ".+")
    void regenerateBundledCatalog() throws Exception {
        String text;
        try (var http = HttpClient.newHttpClient()) {
            text = http.send(HttpRequest.newBuilder(ModelsDevFeed.URL).build(), HttpResponse.BodyHandlers.ofString()).body();
        }
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var models = ModelsDevFeed.parse(Json.parse(text)).stream()
                .filter(m -> !m.providerId().equals("openrouter") && m.deprecatedAt().isEmpty()
                        && m.input().contains(Modality.TEXT) && m.output().contains(Modality.TEXT))
                .sorted(Comparator.comparing(Model::providerId).thenComparing(Model::id))
                .map(m -> m.toBuilder().source(Model.Source.BUNDLED).build().toJson()).toList();
        var document = Json.object("schema", "ai-gate.catalog/1", "generatedAt", now.toString(), "source", ModelsDevFeed.URL.toString(),
                "note", "Generated by ./gradlew updateModelCatalog from models.dev; do not edit by hand.", "models", models);
        Files.writeString(Path.of(System.getProperty("ai-gate.updateCatalog")), document.toPrettyJson() + "\n", StandardCharsets.UTF_8);
        assertTrue(models.size() > 50, "models: " + models.size());
    }
}
