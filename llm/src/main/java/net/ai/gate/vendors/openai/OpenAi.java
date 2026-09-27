package net.ai.gate.vendors.openai;

import java.net.URI;

import net.ai.gate.Provider;
import net.ai.gate.auth.ApiKeyAuth;
import net.ai.gate.spi.protocol.WireApi;
import net.ai.gate.vendors.openai.internal.CompletionsCodec;
import net.ai.gate.vendors.openai.internal.ModelList;
import net.ai.gate.vendors.openai.internal.ResponsesCodec;

/// OpenAI's wire APIs and the `openai` preset.
public final class OpenAi {
    /// Responses API; the default of `OpenAi.provider()`.
    public static final WireApi RESPONSES = ResponsesCodec.INSTANCE;
    /// Chat Completions; also the compatibility API of most gateways.
    public static final WireApi CHAT_COMPLETIONS = CompletionsCodec.INSTANCE;

    private OpenAi() { }

    /// `https://api.openai.com/v1`, keys from `OPENAI_API_KEY`, live `/models` listing.
    public static Provider provider() {
        return Provider.builder("openai", RESPONSES).api(CHAT_COMPLETIONS).name("OpenAI").preset("openai")
                .baseUrl("https://api.openai.com/v1").auth(ApiKeyAuth.bearer("OpenAI API key", "OPENAI_API_KEY"))
                .modelSource(ModelList.INSTANCE).apiKeyUrl(URI.create("https://platform.openai.com/api-keys")).build();
    }
}
