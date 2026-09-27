package net.ai.gate.providers;

import java.util.List;

import net.ai.gate.Provider;
import net.ai.gate.vendors.anthropic.Anthropic;
import net.ai.gate.vendors.google.Gemini;
import net.ai.gate.vendors.openai.OpenAi;
import net.ai.gate.vendors.openai.OpenAiCompatible;

/// Every bundled preset in one place. Presets are immutable data; adjust copies with `toBuilder()`.
public final class Providers {
    private Providers() { }

    public static Provider openai() { return OpenAi.provider(); }
    public static Provider anthropic() { return Anthropic.provider(); }
    public static Provider google() { return Gemini.provider(); }
    public static Provider openRouter() { return OpenAiCompatible.openRouter(); }
    public static Provider deepSeek() { return OpenAiCompatible.deepSeek(); }
    public static Provider xai() { return OpenAiCompatible.xai(); }
    public static Provider qwen() { return OpenAiCompatible.qwen(); }
    public static Provider mistral() { return OpenAiCompatible.mistral(); }
    public static Provider groq() { return OpenAiCompatible.groq(); }
    public static Provider ollama() { return OpenAiCompatible.ollama(); }
    public static Provider lmStudio() { return OpenAiCompatible.lmStudio(); }
    public static Provider vllm() { return OpenAiCompatible.vllm(); }

    /// Every preset, for "connect provider" screens. Templates that need a base URL (`openai-compatible`,
    /// `anthropic-compatible`) are created with `OpenAiCompatible.custom(…)` and `Anthropic.compatible(…)`.
    public static List<Provider> presets() {
        return List.of(openai(), anthropic(), google(), openRouter(), deepSeek(), xai(), qwen(), mistral(), groq(), ollama(),
                lmStudio(), vllm());
    }
}
