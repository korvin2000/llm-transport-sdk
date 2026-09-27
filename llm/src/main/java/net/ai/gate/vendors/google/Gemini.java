package net.ai.gate.vendors.google;

import java.net.URI;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.spi.protocol.WireApi;
import net.ai.gate.spi.provider.ProviderApi;
import net.ai.gate.vendors.google.internal.CachesClient;
import net.ai.gate.vendors.google.internal.GenerateContentCodec;

/// Google's Gemini wire API, preset and provider-only APIs.
public final class Gemini {
    public static final WireApi GENERATE_CONTENT = GenerateContentCodec.INSTANCE;

    /// Explicit cached contents: `llm.providerApi("google", Gemini.CACHES)`.
    public static final ProviderApi<GeminiCaches> CACHES =
            ProviderApi.of(GENERATE_CONTENT.id(), "caches", GeminiCaches.class, CachesClient::new);

    private Gemini() { }

    /// `https://generativelanguage.googleapis.com/v1beta`, keys from `GEMINI_API_KEY` sent as `x-goog-api-key`.
    public static Provider provider() {
        return Provider.builder("google", GENERATE_CONTENT).name("Google Gemini").preset("google")
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .auth(ApiKeyAuth.header("Gemini API key", "x-goog-api-key", "GEMINI_API_KEY"))
                .apiKeyUrl(URI.create("https://aistudio.google.com/apikey")).build();
    }
}
