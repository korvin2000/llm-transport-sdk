package net.ai.gate.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.model.Model;
import net.ai.gate.vendors.openai.OpenAiCompatible;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat;
import org.junit.jupiter.api.Test;

/// Secret-free, versioned provider configuration that states only differences from presets.
class ProvidersConfigTest {
    @Test
    void gatewaysAndPresetCopiesRoundTrip() {
        var gateway = OpenAiCompatible.custom("corp-gw", URI.create("https://llm-gw.corp.example/v1")).toBuilder()
                .header("X-Tenant", "team-42")
                .model(Model.builder("corp-gw", "gpt-5.1").contextWindow(400_000).maxOutputTokens(128_000).build()).build();
        var work = Providers.anthropic().toBuilder().id("work-anthropic").build();

        var json = ProvidersConfig.write(List.of(gateway, work));
        assertFalse(json.toLowerCase().contains("key\""), "no credential fields: " + json);
        var restored = ProvidersConfig.read(json, Providers.presets());

        assertEquals(List.of("corp-gw", "work-anthropic"), restored.stream().map(p -> p.id()).toList());
        var corp = restored.getFirst();
        assertEquals(URI.create("https://llm-gw.corp.example/v1"), corp.baseUrl());
        assertEquals(Map.of("X-Tenant", "team-42"), corp.headers());
        assertEquals(400_000, corp.models().getFirst().contextWindow().orElseThrow());
        assertEquals("https://api.anthropic.com/v1", restored.get(1).baseUrl().toString());
        assertTrue(json.contains("\"preset\": \"anthropic\""), json);
    }

    @Test
    void unknownFieldsAndPresetsFailWithTheKnownNames() {
        var unknownField = "{\"schema\": \"ai-gate.providers/1\", \"providers\": [{\"preset\": \"openai\", \"apiKey\": \"sk\"}]}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.read(unknownField, Providers.presets()))
                .getMessage().contains("apiKey"));
        var extension = "{\"schema\": \"ai-gate.providers/1\", \"providers\": [{\"preset\": \"openai\", \"x-color\": \"blue\"}]}";
        assertEquals("openai", ProvidersConfig.read(extension, Providers.presets()).getFirst().id());
        var unknownPreset = "{\"schema\": \"ai-gate.providers/1\", \"providers\": [{\"preset\": \"acme\"}]}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.read(unknownPreset, Providers.presets()))
                .getMessage().contains("deepseek"));
    }

    @Test
    void defaultsRoundTripWhenTheyDifferFromThePreset() {
        var provider = Providers.ollama().toBuilder().defaults(o -> o.cacheRetention(CacheRetention.LONG)).build();
        var json = ProvidersConfig.write(List.of(provider));
        assertTrue(json.contains("\"defaults\""), json);

        var restored = ProvidersConfig.read(json, Providers.presets()).getFirst();
        assertEquals(CacheRetention.LONG, restored.defaults().cacheRetention().orElseThrow());

        var unchanged = ProvidersConfig.write(List.of(Providers.ollama()));
        assertFalse(unchanged.contains("\"defaults\""), unchanged);
    }

    @Test
    void nonArrayProvidersFails() {
        var json = "{\"schema\": \"ai-gate.providers/1\", \"providers\": {\"id\": \"x\"}}";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.read(json, Providers.presets()))
                .getMessage().contains("must be an array"));
    }

    @Test
    void duplicateProviderIdsFail() {
        var json = "{\"schema\": \"ai-gate.providers/1\", \"providers\": ["
                + "{\"preset\": \"openai\"}, {\"preset\": \"anthropic\", \"id\": \"openai\"}]}";
        var error = assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.read(json, Providers.presets()));
        assertTrue(error.getMessage().contains("duplicate provider id"), error.getMessage());
        assertTrue(error.getMessage().contains("openai"), error.getMessage());
    }

    @Test
    void writingADifferentCompatThanTheBaseFails() {
        var provider = OpenAiCompatible.custom("corp-gw2", URI.create("https://gw2.example/v1")).toBuilder()
                .compat(OpenAiCompletionsCompat.builder().developerRole(true).build()).build();
        var error = assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.write(List.of(provider)));
        assertTrue(error.getMessage().contains("corp-gw2"), error.getMessage());
    }

    @Test
    void readingACompatMemberFails() {
        var json = "{\"schema\": \"ai-gate.providers/1\", \"providers\": [{\"preset\": \"openai\", \"compat\": {}}]}";
        var error = assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.read(json, Providers.presets()));
        assertTrue(error.getMessage().contains("not supported"), error.getMessage());
    }
}
