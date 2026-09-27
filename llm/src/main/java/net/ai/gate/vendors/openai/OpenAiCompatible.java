package net.ai.gate.vendors.openai;

import java.net.URI;
import java.util.Locale;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.auth.Secret;
import net.ai.gate.auth.oauth.OAuthAuth;
import net.ai.gate.auth.oauth.OAuthConfig;
import net.ai.gate.auth.oauth.OAuthCredential;
import net.ai.gate.cache.CacheRetention;
import net.ai.gate.config.TimeoutPolicy;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat.ReasoningFormat;
import net.ai.gate.vendors.openai.OpenAiCompletionsCompat.SessionHeader;
import net.ai.gate.vendors.openai.internal.ModelList;
import org.jspecify.annotations.Nullable;

/// Presets for providers and gateways speaking OpenAI's APIs. Presets are data: base URL, auth chain, compat flags —
/// verified against provider documentation at each release. Chat Completions unless noted.
public final class OpenAiCompatible {
    private OpenAiCompatible() { }

    /// Keys from `OPENROUTER_API_KEY`, or an OAuth PKCE login that issues a key.
    public static Provider openRouter() {
        var oauth = OAuthConfig.builder("ai-gate").authorizationEndpoint(URI.create("https://openrouter.ai/auth"))
                .tokenEndpoint(URI.create("https://openrouter.ai/api/v1/auth/keys"))
                .tokenResponseMapper(json -> OAuthCredential.builder(Secret.of(json.string("key")), "https://openrouter.ai", "ai-gate").build())
                .build();
        return preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "https://openrouter.ai/keys")
                .auth(OAuthAuth.standard(oauth))
                .compat(OpenAiCompletionsCompat.builder().reasoningFormat(ReasoningFormat.OPENROUTER).sessionHeader(SessionHeader.OPENROUTER).build())
                .build();
    }

    public static Provider deepSeek() {
        return preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY", "https://platform.deepseek.com/api_keys")
                .compat(OpenAiCompletionsCompat.builder().reasoningFormat(ReasoningFormat.DEEPSEEK).reasoningContentReplay(true)
                        .maxTokensField("max_tokens").developerRole(false).build())
                .build();
    }

    /// Chat Completions and Responses.
    public static Provider xai() {
        return preset("xai", "xAI", "https://api.x.ai/v1", "XAI_API_KEY", "https://console.x.ai").api(OpenAi.RESPONSES).build();
    }

    public static Provider qwen() {
        return preset("qwen", "Qwen (DashScope)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", null)
                .compat(OpenAiCompletionsCompat.builder().reasoningFormat(ReasoningFormat.QWEN).maxTokensField("max_tokens")
                        .developerRole(false).build())
                .build();
    }

    public static Provider mistral() {
        return preset("mistral", "Mistral", "https://api.mistral.ai/v1", "MISTRAL_API_KEY", "https://console.mistral.ai/api-keys")
                .compat(OpenAiCompletionsCompat.builder().maxTokensField("max_tokens").developerRole(false).strictTools(false).build())
                .build();
    }

    public static Provider groq() {
        return preset("groq", "Groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY", "https://console.groq.com/keys")
                .compat(OpenAiCompletionsCompat.builder().developerRole(false).strictTools(false).build())
                .build();
    }

    public static Provider ollama() { return local("ollama", "Ollama", "http://127.0.0.1:11434/v1"); }

    public static Provider lmStudio() { return local("lm-studio", "LM Studio", "http://127.0.0.1:1234/v1"); }

    /// Keyless by default; servers started with `--api-key` get `toBuilder().auth(ApiKeyAuth.bearer(…))`.
    public static Provider vllm() { return local("vllm", "vLLM", "http://127.0.0.1:8000/v1"); }

    public static Provider liteLlm(URI baseUrl) {
        return preset("litellm", "LiteLLM", baseUrl.toString(), "LITELLM_API_KEY", null).build();
    }

    /// `https://<resource>.openai.azure.com`; deployment names are model ids. Entra ID: replace the auth with
    /// `ApiKeyAuth.dynamic(…)`.
    public static Provider azureOpenAi(URI resourceUrl) {
        var base = resourceUrl.toString().replaceAll("/+$", "") + "/openai/v1";
        return Provider.builder("azure-openai", OpenAi.RESPONSES).api(OpenAi.CHAT_COMPLETIONS).name("Azure OpenAI").preset("azure-openai")
                .baseUrl(base).auth(ApiKeyAuth.header("Azure OpenAI API key", "api-key", "AZURE_OPENAI_API_KEY")).build();
    }

    /// A gateway or server by base URL: bearer keys from `<ID>_API_KEY`, conservative flags. The template behind
    /// `ProvidersConfig` entries with `"preset": "openai-compatible"`.
    public static Provider custom(String id, URI baseUrl) {
        var variable = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_API_KEY";
        return Provider.builder(id, OpenAi.CHAT_COMPLETIONS).preset("openai-compatible").baseUrl(baseUrl)
                .auth(ApiKeyAuth.bearer(id + " API key", variable)).modelSource(ModelList.INSTANCE)
                .compat(OpenAiCompletionsCompat.builder().maxTokensField("max_tokens").developerRole(false).strictTools(false).build())
                .build();
    }

    private static Provider.Builder preset(String id, String name, String baseUrl, String variable, @Nullable String keyUrl) {
        var builder = Provider.builder(id, OpenAi.CHAT_COMPLETIONS).name(name).preset(id).baseUrl(baseUrl)
                .auth(ApiKeyAuth.bearer(name + " API key", variable)).modelSource(ModelList.INSTANCE);
        return keyUrl == null ? builder : builder.apiKeyUrl(URI.create(keyUrl));
    }

    /// Local servers load models on first use: long timeouts, no prompt-cache hints, live model listing.
    private static Provider local(String id, String name, String baseUrl) {
        return Provider.builder(id, OpenAi.CHAT_COMPLETIONS).name(name).preset(id).baseUrl(baseUrl).auth(ApiKeyAuth.none())
                .modelSource(ModelList.INSTANCE)
                .compat(OpenAiCompletionsCompat.builder().maxTokensField("max_tokens").developerRole(false).strictTools(false).build())
                .defaults(o -> o.timeouts(TimeoutPolicy.forLocalModels()).cacheRetention(CacheRetention.NONE))
                .build();
    }
}
