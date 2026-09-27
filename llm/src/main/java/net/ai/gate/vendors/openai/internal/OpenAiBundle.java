package net.ai.gate.vendors.openai.internal;

import java.util.List;

import net.ai.gate.Provider;
import net.ai.gate.spi.provider.ProviderBundle;
import net.ai.gate.vendors.openai.OpenAi;
import net.ai.gate.vendors.openai.OpenAiCompatible;

/// Contributes the OpenAI family's presets to `Llm.create()`; templates that need a base URL are not presets.
public final class OpenAiBundle implements ProviderBundle {
    @Override public List<Provider> providers() {
        return List.of(OpenAi.provider(), OpenAi.codex(), OpenAiCompatible.openRouter(), OpenAiCompatible.deepSeek(), OpenAiCompatible.xai(),
                OpenAiCompatible.qwen(), OpenAiCompatible.mistral(), OpenAiCompatible.groq(), OpenAiCompatible.ollama(),
                OpenAiCompatible.lmStudio(), OpenAiCompatible.vllm());
    }
}
