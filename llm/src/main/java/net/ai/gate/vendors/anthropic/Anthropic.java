package net.ai.gate.vendors.anthropic;

import java.net.URI;
import java.util.Locale;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.spi.protocol.WireApi;
import net.ai.gate.vendors.anthropic.internal.MessagesCodec;

/// Anthropic's wire API and presets.
public final class Anthropic {
    /// Messages API at revision `2023-06-01`.
    public static final WireApi MESSAGES = MessagesCodec.INSTANCE;

    private Anthropic() { }

    /// `https://api.anthropic.com/v1`, keys from `ANTHROPIC_API_KEY` sent as `x-api-key`.
    public static Provider provider() {
        return Provider.builder("anthropic", MESSAGES).name("Anthropic").preset("anthropic").baseUrl("https://api.anthropic.com/v1")
                .auth(ApiKeyAuth.header("Anthropic API key", "x-api-key", "ANTHROPIC_API_KEY"))
                .apiKeyUrl(URI.create("https://console.anthropic.com/settings/keys")).build();
    }

    /// Template for Anthropic-compatible endpoints: keys from `<ID>_API_KEY`. `ProvidersConfig` preset
    /// `anthropic-compatible`.
    public static Provider compatible(String id, URI baseUrl) {
        var variable = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "_API_KEY";
        return Provider.builder(id, MESSAGES).preset("anthropic-compatible").baseUrl(baseUrl)
                .auth(ApiKeyAuth.header(id + " API key", "x-api-key", variable))
                .compat(AnthropicCompat.builder().betaHeaders(false).cacheTtl(false).build()).build();
    }
}
