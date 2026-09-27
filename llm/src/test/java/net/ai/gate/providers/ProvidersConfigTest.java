package net.ai.gate.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import net.ai.gate.cache.CacheRetention;
import net.ai.gate.json.Json;
import net.ai.gate.json.JsonObject;
import net.ai.gate.model.Model;
import net.ai.gate.vendors.openai.OpenAiCompatible;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat.ReasoningFormat;
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
    void compatFlagsRoundTripAsDifferencesFromThePreset() {
        var flags = OpenAiCompletionsCompat.builder().reasoningFormat(ReasoningFormat.QWEN).maxTokensField("max_tokens").developerRole(false).build();
        var vllm = Providers.vllm().toBuilder().compat(flags)
                .model(Model.builder("vllm", "qwen3").compat(OpenAiCompletionsCompat.builder().streamUsage(false).build()).build()).build();
        var json = ProvidersConfig.write(List.of(vllm));
        var entry = ((JsonObject) Json.parse(json)).objects("providers").getFirst();
        assertEquals(Json.object("reasoningFormat", "qwen"), entry.get("compat").orElseThrow(), "only what differs from the preset: " + json);
        assertEquals(Json.object("streamUsage", false), entry.objects("models").getFirst().get("compat").orElseThrow());

        var restored = ProvidersConfig.read(json, Providers.presets()).getFirst();
        assertEquals(vllm.compat(), restored.compat());
        assertEquals(vllm.models().getFirst().compat(), restored.models().getFirst().compat());
        assertFalse(ProvidersConfig.write(List.of(Providers.vllm())).contains("compat"));
    }

    @Test
    void unknownCompatFieldsFailNamingThem() {
        var json = "{\"schema\": \"ai-gate.providers/1\", \"providers\": [{\"preset\": \"vllm\", \"compat\": {\"reasoningFormat\": \"qwen\", "
                + "\"thinkHarder\": true, \"x-note\": \"ignored\"}}]}";
        var error = assertThrows(IllegalArgumentException.class, () -> ProvidersConfig.read(json, Providers.presets()));
        assertTrue(error.getMessage().contains("thinkHarder"), error.getMessage());
        assertFalse(error.getMessage().contains("x-note"), error.getMessage());
    }
}
